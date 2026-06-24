// 组队大厅页面JavaScript

// 当前页码
let currentPage = 1;
const pageSize = 10;

function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function getPaginationMetaFromResult(result) {
    if (result && result.extra && result.extra.pagination) return result.extra.pagination;
    if (result && result.pagination) return result.pagination;
    return null;
}

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', function() {
    // 加载团队列表
    loadTeamList();
    
    // 绑定筛选表单提交事件
    const filterForm = document.getElementById('filterForm');
    if (filterForm) {
        filterForm.addEventListener('submit', function(e) {
            e.preventDefault();
            currentPage = 1;
            loadTeamList();
        });
    }
    
    // 绑定重置按钮事件
    const resetBtn = document.querySelector('#filterForm button[type="reset"]');
    if (resetBtn) {
        resetBtn.addEventListener('click', function() {
            setTimeout(() => {
                currentPage = 1;
                loadTeamList();
            }, 100);
        });
    }
});

// 加载团队列表
async function loadTeamList() {
    // 获取筛选条件
    const keyword = document.getElementById('keyword') ? document.getElementById('keyword').value : '';
    const category = document.getElementById('category') ? document.getElementById('category').value : '';
    const status = document.getElementById('status') ? document.getElementById('status').value : '';
    
    try {
        // 构建查询参数
        const params = new URLSearchParams({
            page: currentPage,
            size: pageSize,
            keyword: keyword,
            category: category,
            status: status
        });
        
        const response = await apiFetch(`/api/team/list?${params}`, { credentials: 'same-origin' });
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

    const makeItem = (label, targetPage, isActive = false, isDisabled = false) => {
        const li = document.createElement('li');
        li.className = `page-item${isActive ? ' active' : ''}${isDisabled ? ' disabled' : ''}`;

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

    // 上一页
    const prevPage = safePage - 1;
    ul.appendChild(makeItem('上一页', prevPage >= 1 ? prevPage : null, false, prevPage < 1));

    // 页码窗口（最多展示 5 页）
    let start = Math.max(1, safePage - 2);
    let end = Math.min(totalPages, safePage + 2);
    if (end - start < 4) {
        if (start === 1) end = Math.min(totalPages, start + 4);
        if (end === totalPages) start = Math.max(1, end - 4);
    }

    // 起始省略
    if (start > 1) {
        ul.appendChild(makeItem('1', 1, safePage === 1));
        if (start > 2) {
            const ellipsisLi = document.createElement('li');
            ellipsisLi.className = 'page-item disabled';
            ellipsisLi.innerHTML = `<a class="page-link" href="#">...</a>`;
            const ellipsisA = ellipsisLi.querySelector('a');
            if (ellipsisA) ellipsisA.addEventListener('click', (e) => e.preventDefault());
            ul.appendChild(ellipsisLi);
        }
    }

    for (let p = start; p <= end; p++) {
        ul.appendChild(makeItem(String(p), p, p === safePage));
    }

    // 结束省略
    if (end < totalPages) {
        if (end < totalPages - 1) {
            const ellipsisLi = document.createElement('li');
            ellipsisLi.className = 'page-item disabled';
            ellipsisLi.innerHTML = `<a class="page-link" href="#">...</a>`;
            const ellipsisA = ellipsisLi.querySelector('a');
            if (ellipsisA) ellipsisA.addEventListener('click', (e) => e.preventDefault());
            ul.appendChild(ellipsisLi);
        }
        ul.appendChild(makeItem(String(totalPages), totalPages, safePage === totalPages));
    }

    // 下一页
    const nextPage = safePage + 1;
    ul.appendChild(makeItem('下一页', nextPage <= totalPages ? nextPage : null, false, nextPage > totalPages));
}

// 渲染团队列表
function renderTeamList(teams) {
    const teamListContainer = document.getElementById('teamList');
    teamListContainer.innerHTML = '';
    
    if (!teams || teams.length === 0) {
        teamListContainer.innerHTML = `
            <div class="il-list-empty" style="text-align: center; padding: 60px 24px; color: #6b7280;">
                <svg width="64" height="64" viewBox="0 0 24 24" fill="none" style="margin: 0 auto 16px; display: block; opacity: 0.5;">
                    <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <p style="font-size: 1rem;">暂无组队需求</p>
            </div>
        `;
        return;
    }
    
    teams.forEach(team => {
        const teamCard = document.createElement('div');
        teamCard.className = 'il-team-card-shell';
        
        const normalizedStatus = (team.status || '').toString().toUpperCase();
        const statusLabel = normalizedStatus === 'OPEN' ? '招募中'
            : (normalizedStatus === 'CLOSED' ? '已完成' : '未知');
        const isRecruiting = normalizedStatus === 'OPEN';
        
        // 处理发布者信息
        let authorName = '发布者';
        let authorAvatar = '发';
        let authorAvatarUrl = null;
        if (team.creatorPreview && team.creatorPreview.id != null) {
            const pv = team.creatorPreview;
            const name =
                (pv.username && String(pv.username).trim()) ||
                (pv.realName && String(pv.realName).trim()) ||
                '';
            authorName = name || '发布者';
            authorAvatar = authorName.charAt(0).toUpperCase();
            authorAvatarUrl = pv.avatar || null;
        } else if (team.creatorId != null) {
            authorName = '用户 #' + team.creatorId;
        }

        // 头像HTML生成函数
        function buildTeamAvatarHtml(avatarUrl, fallbackChar) {
            if (avatarUrl) {
                return `<div class="il-author-avatar">
                    <img src="${escapeHtml(avatarUrl)}" alt="${escapeHtml(authorName)}"
                         onerror="this.style.display='none'; this.parentElement.querySelector('.il-author-avatar-fallback').style.display='flex';">
                    <span class="il-author-avatar-fallback" style="display:none;">${escapeHtml(fallbackChar)}</span>
                </div>`;
            }
            return `<div class="il-author-avatar"><span class="il-author-avatar-fallback">${escapeHtml(fallbackChar)}</span></div>`;
        }
        
        // 处理技能标签
        let skillsHtml = '';
        if (team.requiredSkills) {
            const skills = String(team.requiredSkills).split(/[,，;；\s]+/).filter(s => s.trim());
            if (skills.length > 0) {
                skillsHtml = skills.slice(0, 5).map(skill => 
                    `<span class="il-skill-tag">${escapeHtml(skill.trim())}</span>`
                ).join('');
            }
        }
        
        // 处理分类
        const category = team.category || '其他';
        
        teamCard.innerHTML = `
            <div class="il-team-card" onclick="window.ILink && window.ILink.navigate ? window.ILink.navigate('/team-detail.html?id=${team.id}') : window.location.href='/team-detail.html?id=${team.id}'">
                <div class="il-team-card-header">
                    <div>
                        <h3 class="il-team-title">${escapeHtml(team.title || '未命名需求')}</h3>
                    </div>
                    <span class="il-team-category">${escapeHtml(category)}</span>
                </div>
                <p class="il-team-desc">${escapeHtml(team.description ? team.description.substring(0, 120) + (team.description.length > 120 ? '...' : '') : '暂无描述')}</p>
                
                <div class="il-team-meta">
                    <span class="il-team-meta-item">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" style="display: block;">
                            <path d="M12 8v4l3 3m6-3a9 9 0 1 1-18 0 9 9 0 0 1 18 0z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                        ${team.createdAt ? formatTime(team.createdAt) : '未知'}
                    </span>
                </div>
                
                ${skillsHtml ? `<div class="il-team-skills">${skillsHtml}</div>` : ''}
                
                <div class="il-team-footer">
                    <div class="il-team-author">
                        ${buildTeamAvatarHtml(authorAvatarUrl, authorAvatar)}
                        <span class="il-author-name">${escapeHtml(authorName)}</span>
                    </div>
                    <span class="il-team-status ${isRecruiting ? 'il-status-recruiting' : 'il-status-closed'}">${escapeHtml(statusLabel)}</span>
                </div>
            </div>
        `;
        teamListContainer.appendChild(teamCard);
    });
}
