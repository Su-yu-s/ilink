// 组队大厅页面JavaScript

// 当前页码
let currentPage = 1;
// 组队大厅每页 12 张卡片（与导师招贤保持一致）
const pageSize = 12;

function formatDateStr(value) {
    if (!value) return '长期有效';
    var d = new Date(value);
    if (isNaN(d.getTime())) return String(value).slice(0, 10);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', function() {
    loadTeamList();

    // 绑定搜索按钮
    var searchBtn = document.getElementById('searchBtn');
    if (searchBtn) {
        searchBtn.addEventListener('click', function() {
            currentPage = 1;
            loadTeamList();
        });
    }

    // 回车搜索
    var keywordInput = document.getElementById('keyword');
    if (keywordInput) {
        keywordInput.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                currentPage = 1;
                loadTeamList();
            }
        });
    }
});

// 加载团队列表
async function loadTeamList() {
    const keyword = document.getElementById('keyword') ? document.getElementById('keyword').value : '';
    const category = document.getElementById('category') ? document.getElementById('category').value : '';
    const status = document.getElementById('status') ? document.getElementById('status').value : '';

    try {
        const params = new URLSearchParams({
            page: currentPage,
            size: pageSize,
            keyword: keyword,
            category: category,
            status: status
        });

        const response = await apiFetch('/api/team/list?' + params, { credentials: 'same-origin' });
        const result = await response.json();

        if (result.code === 200) {
            const pagination = (result.extra && result.extra.pagination) || result.pagination;
            // 空页回退：当前页超出总页数（如数据被删、每页条数调整）时自动回退到末页重载，避免空白页
            const p = pagination || {};
            const totalPages = Math.max(1, Math.ceil((p.total || 0) / (p.size || pageSize)));
            if (currentPage > totalPages && currentPage > 1) {
                currentPage = totalPages;
                return loadTeamList();
            }
            renderTeamList(result.data);
            renderPagination(pagination);
        } else {
            console.error('获取团队列表失败:', result.message);
            showMessage('获取团队列表失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('获取团队列表异常:', error);
        showMessage('网络错误，请稍后重试', 'error');
    }
}

function renderPagination(pagination) {
    const paginationNav = document.getElementById('pagination');
    if (!paginationNav || !pagination) return;

    const { page, size, total } = pagination;
    const safePage = page || 1;
    const safeSize = size || pageSize;
    const safeTotal = total || 0;
    const totalPages = Math.max(1, Math.ceil(safeTotal / safeSize));

    if (totalPages <= 1) {
        paginationNav.classList.add('d-none');
        return;
    }

    const ul = paginationNav.querySelector('ul.pagination') || paginationNav.querySelector('ul');
    if (!ul) return;

    ul.innerHTML = '';
    paginationNav.classList.remove('d-none');

    const makeItem = (label, targetPage, isActive, isDisabled) => {
        const li = document.createElement('li');
        li.className = 'page-item' + (isActive ? ' active' : '') + (isDisabled ? ' disabled' : '');

        const a = document.createElement('a');
        a.className = 'page-link';
        a.href = '#';
        a.textContent = label;
        a.addEventListener('click', (e) => {
            e.preventDefault();
            if (isDisabled || !targetPage) return;
            currentPage = targetPage;
            loadTeamList();
        });
        li.appendChild(a);
        return li;
    };

    const prevPage = safePage - 1;
    ul.appendChild(makeItem('上一页', prevPage >= 1 ? prevPage : null, false, prevPage < 1));

    let start = Math.max(1, safePage - 2);
    let end = Math.min(totalPages, safePage + 2);
    if (end - start < 4) {
        if (start === 1) end = Math.min(totalPages, start + 4);
        if (end === totalPages) start = Math.max(1, end - 4);
    }

    if (start > 1) {
        ul.appendChild(makeItem('1', 1, safePage === 1));
        if (start > 2) {
            const ellipsisLi = document.createElement('li');
            ellipsisLi.className = 'page-item disabled';
            ellipsisLi.innerHTML = '<a class="page-link" href="#">...</a>';
            ul.appendChild(ellipsisLi);
        }
    }

    for (let p = start; p <= end; p++) {
        ul.appendChild(makeItem(String(p), p, p === safePage));
    }

    if (end < totalPages) {
        if (end < totalPages - 1) {
            const ellipsisLi = document.createElement('li');
            ellipsisLi.className = 'page-item disabled';
            ellipsisLi.innerHTML = '<a class="page-link" href="#">...</a>';
            ul.appendChild(ellipsisLi);
        }
        ul.appendChild(makeItem(String(totalPages), totalPages, safePage === totalPages));
    }

    const nextPage = safePage + 1;
    ul.appendChild(makeItem('下一页', nextPage <= totalPages ? nextPage : null, false, nextPage > totalPages));
}

// 还差几个人：席位视图、排序、按钮状态都用这一份口径
function openSlotCount(team) {
    var total = parseInt(team.requiredMemberCount, 10) || 0;
    var joined = Array.isArray(team.members)
        ? team.members.filter(function (m) { return m && m.userId != null; }).length
        : 0;
    return Math.max(0, total - joined);
}

function isRecruitingTeam(team) {
    var s = String(team.status || '').toUpperCase();
    return s === 'OPEN' || ['招募中', '招募'].includes(team.status);
}

// 渲染团队列表
function renderTeamList(teams) {
    const teamListContainer = document.getElementById('teamList');
    teamListContainer.innerHTML = '';

    if (teams && teams.length > 0) {
        // 大厅是「找队伍」的地方：能申请的排最前，人满的排后面。
        // 只按状态排不够——很多 OPEN 的队伍其实已经满员，点进去才发现申请不了。
        const rank = (t) => {
            if (!isRecruitingTeam(t)) return 2;
            return openSlotCount(t) > 0 ? 0 : 1;
        };
        teams.sort((a, b) => (rank(a) - rank(b)) || (openSlotCount(b) - openSlotCount(a)));
    }

    if (!teams || teams.length === 0) {
        teamListContainer.innerHTML = `
            <div class="empty-state">
                <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>
                    <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>
                </svg>
                <p>暂无组队需求</p>
                <span>去发布一条，成为第一个招募者</span>
            </div>
        `;
        return;
    }

    teams.forEach(team => {
        const detailUrl = '/team-detail.html?id=' + encodeURIComponent(String(team.id));

        const rawStatus = team.status || '';
        const normalizedStatus = rawStatus.toUpperCase();
        const backendStatusLabel = team.statusLabel || '';
        const statusLabel = backendStatusLabel || (normalizedStatus === 'OPEN' ? '招募中'
            : (normalizedStatus === 'TEAMING' || ['已组队', '组队中'].includes(rawStatus) ? '已组队'
            : (normalizedStatus === 'CLOSED' || ['已结束', '已完成'].includes(rawStatus) ? '已完成' : '未知')));
        const isRecruiting = normalizedStatus === 'OPEN' || ['招募中', '招募'].includes(rawStatus);

        // 发布者信息
        let authorName = '发布者';
        if (team.creatorPreview && team.creatorPreview.id != null) {
            const pv = team.creatorPreview;
            const name = (pv.displayName && String(pv.displayName).trim()) || (pv.realName && String(pv.realName).trim()) || '';
            authorName = name || '发布者';
        } else if (team.creatorId != null) {
            authorName = t('ui.market.userId', { id: team.creatorId });
        }

        // 分类：team_demand.category 列（competitionId 不再承载分类语义）
        const category = team.category || '其他';
        const deadline = team.deadline ? formatDateStr(team.deadline) : '长期有效';
        const competitionLabel = team.competitionName || team.customCompetition || '';

        // 席位：已加入成员 + 空缺。每个席位 = 头像 + 职责，空缺用虚线圆 + 需求技能。
        var memberList = Array.isArray(team.members)
            ? team.members.filter(function (m) { return m && m.userId != null; })
            : [];
        var totalNeed = parseInt(team.requiredMemberCount, 10);
        if (!totalNeed || totalNeed < memberList.length) totalNeed = memberList.length || 1;
        var missing = Math.max(0, totalNeed - memberList.length);
        // 只按分隔符切，不切空格：「Spring Boot」是一个技能，不是两个
        var skillList = String(team.requiredSkills || '').split(/[,，;；、/|]+/).map(function (s) { return s.trim(); }).filter(Boolean);

        // 席位列宽固定，最多放 4 个。超出时优先保住「空缺位」（那是这条招募真正的信息），
        // 压缩已加入成员为「+N」，避免把中列标题挤到自己身上。
        var MAX_CELLS = 4;
        var memberCells = [];
        var emptyCells = [];
        memberList.forEach(function (m, idx) {
            var nm = String(m.displayName || m.username || '成员').trim();
            var ini = String(m.avatarInitial || nm.charAt(0) || '?');
            var isLead = m.isOwner === true || idx === 0;
            // memberRole 是 MENTOR/STUDENT 这类内部枚举，不能直接当标签显示；
            // duty 是队长可编辑的职责（尚未落库时回落到身份名）。
            var duty = isLead ? '队长' : String(m.duty || '').trim() || '队员';
            var face = m.avatar
                ? '<img src="' + escapeHtml(m.avatar) + '" alt="' + escapeHtml(nm) + '" loading="lazy">'
                : escapeHtml(ini);
            memberCells.push('<span class="tm-seat"' + (isLead ? ' data-lead="1"' : '') + '>' +
                '<span class="tm-seat__face">' + face + '</span>' +
                '<span class="tm-seat__duty" title="' + escapeHtml(nm + ' · ' + duty) + '">' + escapeHtml(duty) + '</span>' +
                '</span>');
        });
        for (var i = 0; i < missing; i++) {
            var needRole = skillList[i] || '待定';
            emptyCells.push('<span class="tm-seat tm-seat--empty">' +
                '<span class="tm-seat__face">+</span>' +
                '<span class="tm-seat__duty" title="' + escapeHtml(needRole) + '">' + escapeHtml(needRole) + '</span>' +
                '</span>');
        }

        var seatsHtml;
        if (memberCells.length + emptyCells.length <= MAX_CELLS) {
            seatsHtml = memberCells.concat(emptyCells).join('');
        } else {
            // 先留空缺位，再从队长开始留成员，余下的并成一个 +N
            var keepEmpty = emptyCells.slice(0, Math.min(emptyCells.length, MAX_CELLS - 2));
            var keepMember = memberCells.slice(0, Math.max(1, MAX_CELLS - keepEmpty.length - 1));
            var hiddenCount = memberCells.length - keepMember.length;
            if (hiddenCount <= 0) {
                hiddenCount = emptyCells.length - keepEmpty.length;
            }
            seatsHtml = keepMember.concat(keepEmpty).join('') +
                '<span class="tm-seat tm-seat--more">' +
                '<span class="tm-seat__face">+' + hiddenCount + '</span>' +
                '<span class="tm-seat__duty">另有 ' + hiddenCount + ' 人</span>' +
                '</span>';
        }

        // 句子读的是「这支队伍还想要什么样的人」，列出全部需求技能，不按席位数切片
        var needText = missing > 0
            ? '<b>' + memberList.length + ' / ' + totalNeed + '</b> 人 · 还需要 ' +
                (skillList.length
                    ? '<b>' + escapeHtml(skillList.slice(0, 4).join('、')) + '</b>'
                    : missing + ' 人')
            : '<b>' + memberList.length + ' / ' + totalNeed + '</b> 人 · 已满员';

        // 只有「招募中且没满」才给申请入口；其余状态把状态名放在同一位置，避免按钮失效还像可点。
        var canApply = isRecruiting && missing > 0;
        var flatLabel = !isRecruiting ? statusLabel : '已满员';
        var actionHtml = canApply
            ? '<a class="tm-btn" href="' + detailUrl + '">申请</a>'
            : '<span class="tm-btn tm-btn--flat">' + escapeHtml(flatLabel) + '</span>';

        // 右栏只放两行：赛事名 + 「方向 · 发布人 · 截止」，再多会把行高撑得参差
        var metaRest = [competitionLabel ? category : '', authorName + ' 发布', deadline ? deadline + ' 截止' : '']
            .filter(Boolean).join(' · ');

        var row = document.createElement('article');
        row.className = 'tm-row' + (canApply ? '' : ' tm-row--closed');
        row.innerHTML =
            '<div class="tm-seats">' + seatsHtml + '</div>' +
            '<div class="tm-body">' +
                '<h3 class="tm-title">' +
                    '<a class="tm-title__link" href="' + detailUrl + '">' + escapeHtml(team.title || '未命名需求') + '</a>' +
                '</h3>' +
                '<p class="tm-need">' + needText + '</p>' +
            '</div>' +
            '<div class="tm-meta">' +
                '<span class="tm-meta__name">' + escapeHtml(competitionLabel || category) + '</span>' +
                '<span class="tm-meta__rest">' + escapeHtml(metaRest) + '</span>' +
            '</div>' +
            '<div class="tm-act">' + actionHtml + '</div>';

        teamListContainer.appendChild(row);
    });
}
