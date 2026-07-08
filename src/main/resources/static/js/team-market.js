// 组队大厅页面JavaScript

// 当前页码
let currentPage = 1;
const pageSize = 10;

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

    // 绑定重置按钮
    var resetBtn = document.getElementById('resetBtn');
    if (resetBtn) {
        resetBtn.addEventListener('click', function() {
            document.getElementById('keyword').value = '';
            document.getElementById('category').value = '';
            document.getElementById('status').value = '';
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
            renderTeamList(result.data);
            const pagination = (result.extra && result.extra.pagination) || result.pagination;
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

// 渲染团队列表
function renderTeamList(teams) {
    const teamListContainer = document.getElementById('teamList');
    teamListContainer.innerHTML = '';

    if (teams && teams.length > 0) {
        teams.sort((a, b) => {
            const sA = String(a.status || '').toUpperCase();
            const sB = String(b.status || '').toUpperCase();
            const isRecruitingA = sA === 'OPEN' || ['招募中', '招募'].includes(a.status);
            const isRecruitingB = sB === 'OPEN' || ['招募中', '招募'].includes(b.status);
            return (isRecruitingA ? 0 : 1) - (isRecruitingB ? 0 : 1);
        });
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
        const cardShell = document.createElement('div');
        cardShell.className = 'il-team-card-shell';

        const rawStatus = team.status || '';
        const normalizedStatus = rawStatus.toUpperCase();
        const backendStatusLabel = team.statusLabel || '';
        const statusLabel = backendStatusLabel || (normalizedStatus === 'OPEN' ? '招募中'
            : (normalizedStatus === 'TEAMING' || ['已组队', '组队中'].includes(rawStatus) ? '已组队'
            : (normalizedStatus === 'CLOSED' || ['已结束', '已完成'].includes(rawStatus) ? '已完成' : '未知')));
        const isRecruiting = normalizedStatus === 'OPEN' || ['招募中', '招募'].includes(rawStatus);

        // 发布者信息
        let authorName = '发布者';
        let authorAvatar = '发';
        let authorAvatarUrl = null;
        if (team.creatorPreview && team.creatorPreview.id != null) {
            const pv = team.creatorPreview;
            const name = (pv.username && String(pv.username).trim()) || (pv.realName && String(pv.realName).trim()) || '';
            authorName = name || '发布者';
            authorAvatar = authorName.charAt(0).toUpperCase();
            authorAvatarUrl = pv.avatar || null;
        } else if (team.creatorId != null) {
            authorName = '用户 #' + team.creatorId;
        }

        function buildAvatarHtml(avatarUrl, fallbackChar) {
            if (avatarUrl) {
                return '<div class="il-author-avatar">' +
                    '<img src="' + escapeHtml(avatarUrl) + '" alt="' + escapeHtml(authorName) + '"' +
                    ' onerror="this.style.display=\'none\'; this.parentElement.querySelector(\'.il-author-avatar-fallback\').style.display=\'flex\';">' +
                    '<span class="il-author-avatar-fallback" style="display:none;">' + escapeHtml(fallbackChar) + '</span>' +
                    '</div>';
            }
            return '<div class="il-author-avatar"><span class="il-author-avatar-fallback">' + escapeHtml(fallbackChar) + '</span></div>';
        }

        // 技能标签
        let skillsHtml = '';
        if (team.requiredSkills) {
            const skills = String(team.requiredSkills).split(/[,，;；\s]+/).filter(s => s.trim());
            if (skills.length > 0) {
                skillsHtml = '<div class="il-team-skills">' +
                    skills.slice(0, 5).map(function(skill) {
                        return '<span class="il-skill-tag">' + escapeHtml(skill.trim()) + '</span>';
                    }).join('') + '</div>';
            }
        }

        // 分类
        const catMap = { 1: '技术开发', 2: '创意设计', 3: '市场营销', 4: '学术研究' };
        const category = team.category || catMap[team.competitionId] || '其他';
        const catClassMap = {
            '技术开发': 'il-team-category--dev',
            '创意设计': 'il-team-category--design',
            '市场营销': 'il-team-category--market',
            '学术研究': 'il-team-category--research'
        };
        const catClass = catClassMap[category] || '';

        const memberCount = team.requiredMemberCount ? team.requiredMemberCount + '人' : '待定';
        const deadline = team.deadline ? formatDateStr(team.deadline) : '长期有效';
        const postedDate = team.createdAt ? formatTime(team.createdAt) : '';
        const desc = team.description ? (team.description.length > 120 ? team.description.substring(0, 120) + '...' : team.description) : '暂无描述';

        const statusClass = isRecruiting ? 'il-status-recruiting' : 'il-status-closed';

        cardShell.innerHTML = '<div class="il-team-card">' +
            '<div class="il-team-card-header">' +
                '<h3 class="il-team-title">' + escapeHtml(team.title || '未命名需求') + '</h3>' +
                '<span class="il-team-category ' + catClass + '">' + escapeHtml(category) + '</span>' +
            '</div>' +
            '<div class="il-team-meta">' +
                '<span class="il-team-meta-item">' +
                    '<svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>' +
                    '<span>' + memberCount + '</span>' +
                '</span>' +
                '<span class="il-team-meta-item">' +
                    '<svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>' +
                    '<span>' + deadline + '</span>' +
                '</span>' +
                (postedDate ? '<span class="il-team-meta-item">' +
                    '<svg width="14" height="14" viewBox="0 0 24 24" fill="none"><path d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>' +
                    '<span>' + postedDate + '</span>' +
                '</span>' : '') +
            '</div>' +
            (skillsHtml ? skillsHtml : '') +
            '<p class="il-team-desc">' + escapeHtml(desc) + '</p>' +
            '<div class="il-team-footer">' +
                '<div class="il-team-author">' +
                    buildAvatarHtml(authorAvatarUrl, authorAvatar) +
                    '<span class="il-author-name">' + escapeHtml(authorName) + '</span>' +
                '</div>' +
                '<span class="il-team-status ' + statusClass + '">' + escapeHtml(statusLabel) + '</span>' +
            '</div>' +
        '</div>';

        // 点击跳转
        cardShell.querySelector('.il-team-card').addEventListener('click', function() {
            window.ILink && window.ILink.navigate ? window.ILink.navigate('/team-detail.html?id=' + team.id) : (window.location.href = '/team-detail.html?id=' + team.id);
        });

        teamListContainer.appendChild(cardShell);
    });
}
