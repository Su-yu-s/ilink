// 管理后台JavaScript
const AdminState = {
    usersById: new Map(),
    teamsById: new Map(),
    teachersById: new Map(),
    assetsById: new Map(),
    communityPostsById: new Map(),
    userEditModal: null,
    userDetailModal: null,
    recordDetailModal: null,
    allUsers: [],
    filteredUsers: [],
    allTeams: [],
    filteredTeams: [],
    allTeachers: [],
    filteredTeachers: [],
    allAssets: [],
    filteredAssets: [],
    allCommunityPosts: [],
    filteredCommunityPosts: [],
    userPage: 1
};
const ADMIN_USER_PAGE_SIZE = 10;

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', async function() {
    // 加载仪表盘数据
    await loadDashboardData();
    
    // 先加载用户，后续模块可用用户信息展示发布者/作者名称。
    await loadUsers();
    await Promise.all([
        loadTeams(),
        loadTeachers(),
        loadAssets(),
        loadCommunityPosts()
    ]);

    const modalEl = document.getElementById('adminUserEditModal');
    if (modalEl && window.bootstrap && typeof window.bootstrap.Modal === 'function') {
        AdminState.userEditModal = new window.bootstrap.Modal(modalEl);
    }

    const detailModalEl = document.getElementById('adminUserDetailModal');
    if (detailModalEl && window.bootstrap && typeof window.bootstrap.Modal === 'function') {
        AdminState.userDetailModal = new window.bootstrap.Modal(detailModalEl);
    }

    const recordDetailModalEl = document.getElementById('adminRecordDetailModal');
    if (recordDetailModalEl && window.bootstrap && typeof window.bootstrap.Modal === 'function') {
        AdminState.recordDetailModal = new window.bootstrap.Modal(recordDetailModalEl);
    }

    const saveBtn = document.getElementById('adminUserEditSaveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', saveUserEditFromModal);
    }

    bindAdminSearch('userSearchInput', 'userSearchBtn', function() {
        AdminState.userPage = 1;
        applyUserSearchAndRender();
    });
    bindAdminSearch('teamSearchInput', 'teamSearchBtn', applyTeamSearchAndRender);
    bindAdminSearch('teacherSearchInput', 'teacherSearchBtn', applyTeacherSearchAndRender);
    bindAdminSearch('assetSearchInput', 'assetSearchBtn', applyAssetSearchAndRender);
    bindAdminSearch('communitySearchInput', 'communitySearchBtn', applyCommunityPostSearchAndRender);
});

function bindAdminSearch(inputId, searchBtnId, renderFn) {
    const input = document.getElementById(inputId);
    const searchBtn = document.getElementById(searchBtnId);
    if (input) {
        input.addEventListener('input', renderFn);
        input.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                renderFn();
            }
        });
    }
    if (searchBtn) {
        searchBtn.addEventListener('click', renderFn);
    }
}

function matchesKeyword(values, keyword) {
    const kw = String(keyword || '').trim().toLowerCase();
    if (!kw) return true;
    return values
        .map(v => String(v == null ? '' : v).toLowerCase())
        .join(' ')
        .includes(kw);
}

function setTableRowCells(tr, cells) {
    tr.innerHTML = cells.map(cell => {
        const className = cell.className ? ` class="${cell.className}"` : '';
        return `<td data-label="${escapeHtml(cell.label)}"${className}>${cell.html == null ? '' : cell.html}</td>`;
    }).join('');
}

function updateAdminListHint(hintId, total) {
    const hint = document.getElementById(hintId);
    if (hint) hint.textContent = `共 ${total || 0} 条`;
}

function getUserLabelById(userId) {
    const id = userId == null || userId === '' ? '' : String(userId);
    if (!id) return '未设置';
    const user = AdminState.usersById.get(id);
    if (!user) return `用户 #${id}`;
    const name = String(user.realName || user.username || '').trim() || `用户 #${id}`;
    return `${name}（ID:${id}）`;
}

function getStatusDisplayName(status, type) {
    const value = String(status || '').trim().toUpperCase();
    if (type === 'team') {
        if (value === 'OPEN') return '招募中';
        if (value === 'TEAMING') return '已组队';
        if (value === 'CLOSED') return '已结束';
    }
    if (type === 'teacher') {
        if (value === 'PENDING') return '待审核';
        if (value === 'APPROVED') return '已批准';
        if (value === 'REJECTED') return '已拒绝';
    }
    return value || '未设置';
}

function renderStatusBadge(status, type) {
    const value = String(status || '').trim().toUpperCase();
    const label = getStatusDisplayName(status, type);
    let cls = 'pending';
    if (value === 'OPEN' || value === 'APPROVED') cls = 'approved';
    if (value === 'TEAMING' || value === 'CLOSED') cls = 'teaming';
    if (value === 'REJECTED') cls = 'closed';
    return `<span class="status-badge ${cls}">${escapeHtml(label)}</span>`;
}

function formatDetailValue(value) {
    if (value == null) return '';
    if (typeof value === 'object') {
        try {
            return JSON.stringify(value, null, 2);
        } catch (e) {
            return String(value);
        }
    }
    return String(value).trim();
}

// 加载仪表盘数据
async function loadDashboardData() {
    try {
        const response = await apiFetch('/api/admin/dashboard');
        const result = await response.json();
        
        if (result.code === 200) {
            const data = result.data;
            document.getElementById('userCount').textContent = data.userCount;
            document.getElementById('teamCount').textContent = data.teamCount;
            document.getElementById('teacherCount').textContent = data.teacherCount;
            document.getElementById('assetCount').textContent = data.assetCount;
            const postEl = document.getElementById('postCount');
            if (postEl) postEl.textContent = data.postCount != null ? data.postCount : 0;
        } else if (result.code === 403) {
            showMessage('无权限访问管理后台', 'error');
            window.location.href = '/index.html';
        } else {
            console.error('获取仪表盘数据失败:', result.message);
            showMessage('获取仪表盘数据失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('获取仪表盘数据异常:', error);
        showMessage('获取仪表盘数据异常，请稍后重试', 'error');
    }
}

// 加载用户列表
async function loadUsers() {
    try {
        const response = await apiFetch('/api/admin/users');
        const result = await response.json();
        
        if (result.code === 200) {
            renderUserList(result.data);
        } else if (result.code === 403) {
            console.error('无权限访问用户列表');
        } else {
            console.error('获取用户列表失败:', result.message);
        }
    } catch (error) {
        console.error('获取用户列表异常:', error);
    }
}

// 渲染用户列表
function renderUserList(users) {
    const tbody = document.getElementById('userTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';
    AdminState.usersById = new Map((users || []).map(u => [String(u.id), u]));
    AdminState.allUsers = Array.isArray(users) ? users.slice() : [];
    AdminState.userPage = 1;
    
    applyUserSearchAndRender();
}

function applyUserSearchAndRender() {
    const kw = (document.getElementById('userSearchInput')?.value || '').trim().toLowerCase();
    if (!kw) {
        AdminState.filteredUsers = AdminState.allUsers.slice();
    } else {
        AdminState.filteredUsers = AdminState.allUsers.filter(user => {
            const roleText = getUserRoleDisplayName(user.role || '');
            const haystack = [
                user.id,
                user.username,
                user.realName,
                user.email,
                user.phoneNumber,
                user.studentId,
                user.gender,
                user.grade,
                user.major,
                user.school,
                user.college,
                user.role,
                roleText
            ]
                .map(v => String(v == null ? '' : v).toLowerCase())
                .join(' ');
            return haystack.includes(kw);
        });
    }
    const maxPage = Math.max(1, Math.ceil(AdminState.filteredUsers.length / ADMIN_USER_PAGE_SIZE));
    if (AdminState.userPage > maxPage) AdminState.userPage = maxPage;
    renderUserPage(AdminState.userPage);
}

function renderUserPage(page) {
    const tbody = document.getElementById('userTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';

    const total = AdminState.filteredUsers.length;
    if (total === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="text-center admin-empty-cell">暂无匹配用户</td></tr>';
        renderUserPagination(0, 1);
        updateUserListHint(0, 0, 0);
        return;
    }

    const maxPage = Math.max(1, Math.ceil(total / ADMIN_USER_PAGE_SIZE));
    const safePage = Math.min(Math.max(1, page), maxPage);
    AdminState.userPage = safePage;
    const start = (safePage - 1) * ADMIN_USER_PAGE_SIZE;
    const end = Math.min(start + ADMIN_USER_PAGE_SIZE, total);
    const pageItems = AdminState.filteredUsers.slice(start, end);

    pageItems.forEach(user => {
        const accountName = user && user.username ? String(user.username).trim() : '';
        const userNameForList = accountName || '-';
        const realName = user && user.realName ? String(user.realName).trim() : '';
        const tr = document.createElement('tr');
        setTableRowCells(tr, [
            { label: '用户名', html: escapeHtml(userNameForList) },
            { label: '姓名', html: renderUserCell(realName) },
            { label: '学号', html: renderUserCell(user.studentId != null ? user.studentId : '') },
            { label: '学校', html: renderUserCell(user.school) },
            { label: '角色', html: escapeHtml(getUserRoleDisplayName(user.role)) },
            {
                label: '操作',
                className: 'admin-user-actions admin-row-actions',
                html: `
                    <button class="btn btn-outline-secondary btn-sm" data-admin-action="detail" onclick="openUserDetailModal(${user.id})">详情</button>
                    <button class="btn btn-outline-primary btn-sm" onclick="openUserEditModal(${user.id})">编辑</button>
                    <button class="btn btn-danger btn-sm" onclick="deleteUser(${user.id})">删除</button>
                `
            }
        ]);
        tbody.appendChild(tr);
    });

    renderUserPagination(total, safePage);
    updateUserListHint(total, start + 1, end);
}

function renderUserPagination(total, currentPage) {
    const pager = document.getElementById('userPagination');
    if (!pager) return;

    const maxPage = Math.max(1, Math.ceil(total / ADMIN_USER_PAGE_SIZE));
    if (total <= ADMIN_USER_PAGE_SIZE) {
        pager.innerHTML = '';
        return;
    }

    const prevDisabled = currentPage <= 1 ? ' disabled' : '';
    const nextDisabled = currentPage >= maxPage ? ' disabled' : '';
    const pages = [];

    for (let p = 1; p <= maxPage; p++) {
        if (p === 1 || p === maxPage || Math.abs(p - currentPage) <= 1) {
            pages.push(p);
        } else if (pages[pages.length - 1] !== '...') {
            pages.push('...');
        }
    }

    let html = '<ul class="pagination pagination-sm mb-0">';
    html += `<li class="page-item${prevDisabled}"><a class="page-link" href="#" data-page="${currentPage - 1}">上一页</a></li>`;
    pages.forEach(p => {
        if (p === '...') {
            html += '<li class="page-item disabled"><span class="page-link">...</span></li>';
        } else {
            const active = p === currentPage ? ' active' : '';
            html += `<li class="page-item${active}"><a class="page-link" href="#" data-page="${p}">${p}</a></li>`;
        }
    });
    html += `<li class="page-item${nextDisabled}"><a class="page-link" href="#" data-page="${currentPage + 1}">下一页</a></li>`;
    html += '</ul>';
    pager.innerHTML = html;

    pager.querySelectorAll('a.page-link[data-page]').forEach(a => {
        a.addEventListener('click', function(e) {
            e.preventDefault();
            const target = Number(this.getAttribute('data-page'));
            if (!Number.isFinite(target)) return;
            if (target < 1 || target > maxPage || target === AdminState.userPage) return;
            renderUserPage(target);
        });
    });
}

function updateUserListHint(total, start, end) {
    const hint = document.getElementById('userListHint');
    if (!hint) return;
    if (total <= 0) {
        hint.textContent = '共 0 条';
        return;
    }
    hint.textContent = `共 ${total} 条，当前显示 ${start}-${end} 条`;
}

function renderUserCell(value, compact) {
    const text = String(value == null ? '' : value).trim();
    if (!text || text === '未设置') {
        return '<span class="admin-cell-empty">未设置</span>';
    }
    const cls = compact ? 'admin-cell-text admin-cell-text--compact' : 'admin-cell-text';
    return `<span class="${cls}" title="${escapeHtml(text)}">${escapeHtml(text)}</span>`;
}

function renderUserDetailItem(label, value, wide) {
    const text = formatDetailValue(value);
    const displayText = text || '未设置';
    const emptyClass = text ? '' : ' admin-detail-value--empty';
    const wideClass = wide ? ' admin-detail-item--wide' : '';
    return `
        <div class="admin-detail-item${wideClass}">
            <span class="admin-detail-label">${escapeHtml(label)}</span>
            <span class="admin-detail-value${emptyClass}" title="${escapeHtml(displayText)}">${escapeHtml(displayText)}</span>
        </div>
    `;
}

function normalizeAdminAvatarUrl(value) {
    const raw = String(value == null ? '' : value).trim();
    if (!raw) return '';
    const uploadsIndex = raw.indexOf('/uploads/');
    if (uploadsIndex >= 0) {
        return raw.substring(uploadsIndex);
    }
    if (raw.startsWith('uploads/')) {
        return '/' + raw;
    }
    try {
        const url = new URL(raw, window.location.origin);
        if (url.origin === window.location.origin) {
            return url.pathname + url.search + url.hash;
        }
    } catch (e) {
        return raw;
    }
    return raw;
}

function renderAdminDetailAvatar(user, initial) {
    const avatarUrl = normalizeAdminAvatarUrl(user && user.avatar);
    const fallback = escapeHtml(initial || '用');
    if (!avatarUrl) {
        return `<div class="admin-detail-avatar"><span class="admin-detail-avatar-fallback">${fallback}</span></div>`;
    }
    return `
        <div class="admin-detail-avatar admin-detail-avatar--photo">
            <img class="admin-detail-avatar__img"
                 src="${escapeHtml(avatarUrl)}"
                 alt=""
                 referrerpolicy="no-referrer"
                 onerror="this.style.display='none';var fb=this.parentElement.querySelector('.admin-detail-avatar-fallback');if(fb)fb.style.display='flex';">
            <span class="admin-detail-avatar-fallback" style="display:none;">${fallback}</span>
        </div>
    `;
}

function openUserDetailModal(userId) {
    const user = AdminState.usersById.get(String(userId));
    if (!user) {
        showMessage('未找到用户信息', 'error');
        return;
    }
    const body = document.getElementById('adminUserDetailBody');
    if (!body) return;

    const accountName = user.username ? String(user.username).trim() : '';
    const realName = user.realName ? String(user.realName).trim() : '';
    const displayName = realName || accountName || `用户 #${user.id}`;
    const initial = displayName ? displayName.charAt(0).toUpperCase() : '用';

    const fields = [
        ['用户ID', user.id],
        ['用户名', accountName],
        ['姓名', realName],
        ['角色', getUserRoleDisplayName(user.role)],
        ['邮箱', user.email],
        ['手机号', user.phoneNumber],
        ['学号/工号', user.studentId],
        ['性别', getGenderDisplayName(user.gender)],
        ['年级', user.grade],
        ['专业', user.major],
        ['学校', user.school],
        ['学院', user.college],
        ['注册时间', formatTime(user.createdAt)]
    ];

    body.innerHTML = `
        <div class="admin-detail-summary">
            ${renderAdminDetailAvatar(user, initial)}
            <div>
                <div class="admin-detail-name">${escapeHtml(displayName)}</div>
                <div class="admin-detail-subtitle">${escapeHtml(getUserRoleDisplayName(user.role))} · ID ${escapeHtml(user.id)}</div>
            </div>
        </div>
        <div class="admin-detail-list">
            ${fields.map(([label, value, wide]) => renderUserDetailItem(label, value, wide)).join('')}
        </div>
    `;

    if (AdminState.userDetailModal) {
        AdminState.userDetailModal.show();
    } else {
        const modalEl = document.getElementById('adminUserDetailModal');
        if (modalEl) modalEl.classList.add('show');
    }
}

function openUserEditModal(userId) {
    const user = AdminState.usersById.get(String(userId));
    if (!user) {
        showMessage('未找到用户信息', 'error');
        return;
    }
    const idEl = document.getElementById('adminEditUserId');
    const usernameEl = document.getElementById('adminEditUsername');
    const emailEl = document.getElementById('adminEditEmail');
    const roleEl = document.getElementById('adminEditRole');
    const realNameEl = document.getElementById('adminEditRealName');
    const genderEl = document.getElementById('adminEditGender');
    const phoneEl = document.getElementById('adminEditPhoneNumber');
    const sidEl = document.getElementById('adminEditStudentId');
    const gradeEl = document.getElementById('adminEditGrade');
    const majorEl = document.getElementById('adminEditMajor');
    const schoolEl = document.getElementById('adminEditSchool');
    const collegeEl = document.getElementById('adminEditCollege');

    if (idEl) idEl.value = String(user.id || '');
    if (usernameEl) usernameEl.value = user.username || '';
    if (emailEl) emailEl.value = user.email || '';
    if (roleEl) roleEl.value = user.role || 'STUDENT';
    if (realNameEl) realNameEl.value = user.realName || '';
    if (genderEl) genderEl.value = user.gender || '';
    if (phoneEl) phoneEl.value = user.phoneNumber || '';
    if (sidEl) sidEl.value = user.studentId != null ? String(user.studentId) : '';
    if (gradeEl) gradeEl.value = user.grade || '';
    if (majorEl) majorEl.value = user.major || '';
    if (schoolEl) schoolEl.value = user.school || '';
    if (collegeEl) collegeEl.value = user.college || '';

    if (AdminState.userEditModal) {
        AdminState.userEditModal.show();
    } else {
        const modalEl = document.getElementById('adminUserEditModal');
        if (modalEl) modalEl.classList.add('show');
    }
}

async function saveUserEditFromModal() {
    const idEl = document.getElementById('adminEditUserId');
    if (!idEl || !idEl.value) {
        showMessage('缺少用户ID', 'error');
        return;
    }
    const userId = idEl.value;
    const payload = {
        username: (document.getElementById('adminEditUsername')?.value || '').trim(),
        email: (document.getElementById('adminEditEmail')?.value || '').trim(),
        role: (document.getElementById('adminEditRole')?.value || '').trim(),
        realName: (document.getElementById('adminEditRealName')?.value || '').trim(),
        gender: (document.getElementById('adminEditGender')?.value || '').trim(),
        phoneNumber: (document.getElementById('adminEditPhoneNumber')?.value || '').trim(),
        studentId: (document.getElementById('adminEditStudentId')?.value || '').trim(),
        grade: (document.getElementById('adminEditGrade')?.value || '').trim(),
        major: (document.getElementById('adminEditMajor')?.value || '').trim(),
        school: (document.getElementById('adminEditSchool')?.value || '').trim(),
        college: (document.getElementById('adminEditCollege')?.value || '').trim()
    };
    if (!payload.username) {
        showMessage('用户名不能为空', 'warning');
        return;
    }
    if (!payload.studentId) {
        payload.studentId = null;
    }
    try {
        const response = await apiFetch(`/api/admin/user/${encodeURIComponent(String(userId))}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('用户信息更新成功', 'success');
            if (AdminState.userEditModal) AdminState.userEditModal.hide();
            loadUsers();
        } else {
            showMessage(result.message || '更新失败', 'error');
        }
    } catch (error) {
        console.error('更新用户信息异常:', error);
        showMessage('更新失败，请稍后重试', 'error');
    }
}

// 获取用户角色显示名称
function getUserRoleDisplayName(role) {
    switch (role) {
        case 'STUDENT':
            return '学生';
        case 'TEACHER':
            return '教师';
        case 'ADMIN':
            return '管理员';
        default:
            return role;
    }
}

function getGenderDisplayName(gender) {
    switch (String(gender || '').toUpperCase()) {
        case 'MALE':
            return '男';
        case 'FEMALE':
            return '女';
        case 'OTHER':
            return '其他';
        default:
            return '未设置';
    }
}

// 删除用户
async function deleteUser(userId) {
    if (!confirm('确定要删除该用户吗？')) {
        return;
    }
    
    try {
        const response = await apiFetch(`/api/admin/user/${encodeURIComponent(String(userId))}`, {
            method: 'DELETE'
        });
        const result = await response.json();
        
        if (result.code === 200) {
            showMessage('删除成功', 'success');
            // 重新加载用户列表
            loadUsers();
            // 重新加载仪表盘数据
            loadDashboardData();
        } else {
            showMessage('删除失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('删除用户异常:', error);
        showMessage('删除失败，请稍后重试', 'error');
    }
}

function renderEmptyRow(tbody, colspan, text) {
    tbody.innerHTML = `<tr><td colspan="${colspan}" class="text-center admin-empty-cell">${escapeHtml(text)}</td></tr>`;
}

function openAdminRecordDetailModal(title, summaryName, subtitle, fields) {
    const titleEl = document.getElementById('adminRecordDetailTitle');
    const body = document.getElementById('adminRecordDetailBody');
    if (!body) return;

    const safeTitle = title || '详情';
    const safeName = summaryName || safeTitle;
    const initial = safeName ? String(safeName).charAt(0).toUpperCase() : '详';
    if (titleEl) titleEl.textContent = safeTitle;
    body.innerHTML = `
        <div class="admin-detail-summary">
            <div class="admin-detail-avatar"><span class="admin-detail-avatar-fallback">${escapeHtml(initial)}</span></div>
            <div>
                <div class="admin-detail-name">${escapeHtml(safeName)}</div>
                <div class="admin-detail-subtitle">${escapeHtml(subtitle || '')}</div>
            </div>
        </div>
        <div class="admin-detail-list">
            ${fields.map(([label, value, wide]) => renderUserDetailItem(label, value, wide)).join('')}
        </div>
    `;

    if (AdminState.recordDetailModal) {
        AdminState.recordDetailModal.show();
    } else {
        const modalEl = document.getElementById('adminRecordDetailModal');
        if (modalEl) modalEl.classList.add('show');
    }
}

// 加载团队列表
async function loadTeams() {
    try {
        const response = await apiFetch('/api/admin/teams');
        const result = await response.json();

        if (result.code === 200) {
            renderTeamList(result.data);
        } else if (result.code === 403) {
            console.error('无权限访问团队列表');
        } else {
            console.error('获取团队列表失败:', result.message);
        }
    } catch (error) {
        console.error('获取团队列表异常:', error);
    }
}

function renderTeamList(teams) {
    AdminState.allTeams = Array.isArray(teams) ? teams.slice() : [];
    AdminState.teamsById = new Map(AdminState.allTeams.map(team => [String(team.id), team]));
    applyTeamSearchAndRender();
}

function applyTeamSearchAndRender() {
    const keyword = document.getElementById('teamSearchInput')?.value || '';
    AdminState.filteredTeams = AdminState.allTeams.filter(team => matchesKeyword([
        team.id,
        team.title,
        team.description,
        team.requiredSkills,
        team.competitionId,
        team.status,
        getStatusDisplayName(team.status, 'team'),
        team.creatorId,
        getUserLabelById(team.creatorId),
        formatTime(team.createdAt)
    ], keyword));
    renderTeamRows(AdminState.filteredTeams);
    updateAdminListHint('teamListHint', AdminState.filteredTeams.length);
}

function renderTeamRows(teams) {
    const tbody = document.getElementById('teamTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (!teams || teams.length === 0) {
        renderEmptyRow(tbody, 5, '暂无匹配组队需求');
        return;
    }

    teams.forEach(team => {
        const tr = document.createElement('tr');
        setTableRowCells(tr, [
            { label: '标题', html: renderUserCell(team.title) },
            { label: '发布者', html: renderUserCell(getUserLabelById(team.creatorId)) },
            { label: '状态', html: renderStatusBadge(team.status, 'team') },
            { label: '发布时间', html: escapeHtml(formatTime(team.createdAt)) },
            {
                label: '操作',
                className: 'admin-row-actions',
                html: `
                    <button class="btn btn-outline-secondary btn-sm" data-admin-action="detail" onclick="openTeamDetailModal(${team.id})">详情</button>
                    <button class="btn btn-danger btn-sm" onclick="deleteTeam(${team.id})">删除</button>
                `
            }
        ]);
        tbody.appendChild(tr);
    });
}

function openTeamDetailModal(teamId) {
    const team = AdminState.teamsById.get(String(teamId));
    if (!team) {
        showMessage('未找到组队需求', 'error');
        return;
    }
    openAdminRecordDetailModal('组队需求详情', team.title || `组队需求 #${team.id}`, `${getStatusDisplayName(team.status, 'team')} · ID ${team.id}`, [
        ['需求ID', team.id],
        ['标题', team.title],
        ['发布者', getUserLabelById(team.creatorId)],
        ['发布者ID', team.creatorId],
        ['状态', getStatusDisplayName(team.status, 'team')],
        ['竞赛ID', team.competitionId],
        ['发布时间', formatTime(team.createdAt)],
        ['所需技能', team.requiredSkills, true],
        ['需求描述', team.description, true]
    ]);
}

async function deleteTeam(teamId) {
    if (!confirm('确定要删除该团队吗？')) return;

    try {
        const response = await apiFetch(`/api/admin/team/${encodeURIComponent(String(teamId))}`, {
            method: 'DELETE'
        });
        const result = await response.json();

        if (result.code === 200) {
            showMessage('删除成功', 'success');
            loadTeams();
            loadDashboardData();
        } else {
            showMessage('删除失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('删除团队异常:', error);
        showMessage('删除失败，请稍后重试', 'error');
    }
}

// 加载导师列表
async function loadTeachers() {
    try {
        const response = await apiFetch('/api/admin/teachers');
        const result = await response.json();

        if (result.code === 200) {
            renderTeacherList(result.data);
        } else if (result.code === 403) {
            console.error('无权限访问导师列表');
        } else {
            console.error('获取导师列表失败:', result.message);
        }
    } catch (error) {
        console.error('获取导师列表异常:', error);
    }
}

function renderTeacherList(teachers) {
    AdminState.allTeachers = Array.isArray(teachers) ? teachers.slice() : [];
    AdminState.teachersById = new Map(AdminState.allTeachers.map(teacher => [String(teacher.id), teacher]));
    applyTeacherSearchAndRender();
}

function applyTeacherSearchAndRender() {
    const keyword = document.getElementById('teacherSearchInput')?.value || '';
    AdminState.filteredTeachers = AdminState.allTeachers.filter(teacher => matchesKeyword([
        teacher.id,
        teacher.userId,
        getUserLabelById(teacher.userId),
        teacher.introduction,
        teacher.researchDirection,
        teacher.projects,
        teacher.status,
        getStatusDisplayName(teacher.status, 'teacher'),
        formatTime(teacher.createdAt)
    ], keyword));
    renderTeacherRows(AdminState.filteredTeachers);
    updateAdminListHint('teacherListHint', AdminState.filteredTeachers.length);
}

function renderTeacherRows(teachers) {
    const tbody = document.getElementById('teacherTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (!teachers || teachers.length === 0) {
        renderEmptyRow(tbody, 5, '暂无匹配导师申请');
        return;
    }

    teachers.forEach(teacher => {
        const tr = document.createElement('tr');
        const approveButton = String(teacher.status || '').toUpperCase() === 'PENDING'
            ? `<button class="btn btn-success btn-sm" onclick="approveTeacher(${teacher.id})">批准</button>`
            : '';
        setTableRowCells(tr, [
            { label: '申请人', html: renderUserCell(getUserLabelById(teacher.userId)) },
            { label: '研究方向', html: renderUserCell(teacher.researchDirection) },
            { label: '状态', html: renderStatusBadge(teacher.status, 'teacher') },
            { label: '申请时间', html: escapeHtml(formatTime(teacher.createdAt)) },
            {
                label: '操作',
                className: 'admin-row-actions',
                html: `
                    <button class="btn btn-outline-secondary btn-sm" data-admin-action="detail" onclick="openTeacherDetailModal(${teacher.id})">详情</button>
                    ${approveButton}
                    <button class="btn btn-danger btn-sm" onclick="deleteTeacher(${teacher.id})">删除</button>
                `
            }
        ]);
        tbody.appendChild(tr);
    });
}

function openTeacherDetailModal(teacherId) {
    const teacher = AdminState.teachersById.get(String(teacherId));
    if (!teacher) {
        showMessage('未找到导师申请', 'error');
        return;
    }
    const applicant = getUserLabelById(teacher.userId);
    openAdminRecordDetailModal('导师申请详情', applicant, `${getStatusDisplayName(teacher.status, 'teacher')} · ID ${teacher.id}`, [
        ['申请ID', teacher.id],
        ['申请人', applicant],
        ['申请人ID', teacher.userId],
        ['状态', getStatusDisplayName(teacher.status, 'teacher')],
        ['申请时间', formatTime(teacher.createdAt)],
        ['研究方向', teacher.researchDirection, true],
        ['个人简介', teacher.introduction, true],
        ['项目经历', teacher.projects, true]
    ]);
}

async function approveTeacher(teacherId) {
    try {
        const response = await apiFetch(`/api/admin/teacher/${teacherId}/approve`, {
            method: 'PUT'
        });
        const result = await response.json();

        if (result.code === 200) {
            showMessage('审批通过', 'success');
            loadTeachers();
            loadDashboardData();
        } else {
            showMessage('审批失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('审批导师异常:', error);
        showMessage('审批失败，请稍后重试', 'error');
    }
}

async function deleteTeacher(teacherId) {
    if (!confirm('确定要删除该导师申请吗？')) return;

    try {
        const response = await apiFetch(`/api/admin/teacher/${encodeURIComponent(String(teacherId))}`, {
            method: 'DELETE'
        });
        const result = await response.json();

        if (result.code === 200) {
            showMessage('删除成功', 'success');
            loadTeachers();
            loadDashboardData();
        } else {
            showMessage('删除失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('删除导师异常:', error);
        showMessage('删除失败，请稍后重试', 'error');
    }
}

// 加载成果列表
async function loadAssets() {
    try {
        const response = await apiFetch('/api/admin/assets');
        const result = await response.json();

        if (result.code === 200) {
            renderAssetList(result.data);
        } else if (result.code === 403) {
            console.error('无权限访问成果列表');
        } else {
            console.error('获取成果列表失败:', result.message);
        }
    } catch (error) {
        console.error('获取成果列表异常:', error);
    }
}

function renderAssetList(assets) {
    AdminState.allAssets = Array.isArray(assets) ? assets.slice() : [];
    AdminState.assetsById = new Map(AdminState.allAssets.map(asset => [String(asset.id), asset]));
    applyAssetSearchAndRender();
}

function applyAssetSearchAndRender() {
    const keyword = document.getElementById('assetSearchInput')?.value || '';
    AdminState.filteredAssets = AdminState.allAssets.filter(asset => matchesKeyword([
        asset.id,
        asset.title,
        asset.description,
        asset.fileUrl,
        asset.userId,
        getUserLabelById(asset.userId),
        asset.viewCount,
        formatTime(asset.createdAt)
    ], keyword));
    renderAssetRows(AdminState.filteredAssets);
    updateAdminListHint('assetListHint', AdminState.filteredAssets.length);
}

function renderAssetRows(assets) {
    const tbody = document.getElementById('assetTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (!assets || assets.length === 0) {
        renderEmptyRow(tbody, 5, '暂无匹配成果');
        return;
    }

    assets.forEach(asset => {
        const tr = document.createElement('tr');
        setTableRowCells(tr, [
            { label: '标题', html: renderUserCell(asset.title) },
            { label: '作者', html: renderUserCell(getUserLabelById(asset.userId)) },
            { label: '浏览量', html: escapeHtml(asset.viewCount != null ? asset.viewCount : 0) },
            { label: '发布时间', html: escapeHtml(formatTime(asset.createdAt)) },
            {
                label: '操作',
                className: 'admin-row-actions',
                html: `
                    <button class="btn btn-outline-secondary btn-sm" data-admin-action="detail" onclick="openAssetDetailModal(${asset.id})">详情</button>
                    <button class="btn btn-danger btn-sm" onclick="deleteAsset(${asset.id})">删除</button>
                `
            }
        ]);
        tbody.appendChild(tr);
    });
}

function openAssetDetailModal(assetId) {
    const asset = AdminState.assetsById.get(String(assetId));
    if (!asset) {
        showMessage('未找到成果信息', 'error');
        return;
    }
    openAdminRecordDetailModal('成果详情', asset.title || `成果 #${asset.id}`, `浏览 ${asset.viewCount || 0} · ID ${asset.id}`, [
        ['成果ID', asset.id],
        ['标题', asset.title],
        ['作者', getUserLabelById(asset.userId)],
        ['作者ID', asset.userId],
        ['浏览量', asset.viewCount != null ? asset.viewCount : 0],
        ['发布时间', formatTime(asset.createdAt)],
        ['文件地址', asset.fileUrl, true],
        ['成果描述', asset.description, true]
    ]);
}

async function deleteAsset(assetId) {
    if (!confirm('确定要删除该成果吗？')) return;

    try {
        const response = await apiFetch(`/api/admin/asset/${encodeURIComponent(String(assetId))}`, {
            method: 'DELETE'
        });
        const result = await response.json();

        if (result.code === 200) {
            showMessage('删除成功', 'success');
            loadAssets();
            loadDashboardData();
        } else {
            showMessage('删除失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('删除成果异常:', error);
        showMessage('删除失败，请稍后重试', 'error');
    }
}

const COMMUNITY_CATEGORY_LABELS = {
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

async function loadCommunityPosts() {
    try {
        const response = await apiFetch('/api/admin/community-posts');
        const result = await response.json();

        if (result.code === 200) {
            renderCommunityPostList(result.data);
        } else if (result.code === 403) {
            console.error('无权限访问社区帖子列表');
        } else {
            console.error('获取社区帖子失败:', result.message);
        }
    } catch (error) {
        console.error('获取社区帖子异常:', error);
    }
}

function getCommunityCategoryLabel(category) {
    return COMMUNITY_CATEGORY_LABELS[category] || category || '未设置';
}

function renderCommunityPostList(posts) {
    AdminState.allCommunityPosts = Array.isArray(posts) ? posts.slice() : [];
    AdminState.communityPostsById = new Map(AdminState.allCommunityPosts.map(post => [String(post.id), post]));
    applyCommunityPostSearchAndRender();
}

function applyCommunityPostSearchAndRender() {
    const keyword = document.getElementById('communitySearchInput')?.value || '';
    AdminState.filteredCommunityPosts = AdminState.allCommunityPosts.filter(post => matchesKeyword([
        post.id,
        post.category,
        getCommunityCategoryLabel(post.category),
        post.title,
        post.content,
        post.attachments,
        post.authorId,
        getUserLabelById(post.authorId),
        post.viewCount,
        post.likeCount,
        post.favoriteCount,
        formatTime(post.createdAt)
    ], keyword));
    renderCommunityPostRows(AdminState.filteredCommunityPosts);
    updateAdminListHint('communityListHint', AdminState.filteredCommunityPosts.length);
}

function renderCommunityPostRows(posts) {
    const tbody = document.getElementById('communityPostTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (!posts || posts.length === 0) {
        renderEmptyRow(tbody, 6, '暂无匹配帖子');
        return;
    }

    posts.forEach(post => {
        const tr = document.createElement('tr');
        setTableRowCells(tr, [
            { label: '分区', html: renderUserCell(getCommunityCategoryLabel(post.category)) },
            { label: '标题', html: renderUserCell(post.title) },
            { label: '作者', html: renderUserCell(getUserLabelById(post.authorId)) },
            { label: '阅读', html: escapeHtml(post.viewCount != null ? post.viewCount : 0) },
            { label: '发布时间', html: escapeHtml(formatTime(post.createdAt)) },
            {
                label: '操作',
                className: 'admin-row-actions',
                html: `
                    <button class="btn btn-outline-secondary btn-sm" data-admin-action="detail" onclick="openCommunityPostDetailModal(${post.id})">详情</button>
                    <button class="btn btn-danger btn-sm" onclick="deleteCommunityPost(${post.id})">删除</button>
                `
            }
        ]);
        tbody.appendChild(tr);
    });
}

function openCommunityPostDetailModal(postId) {
    const post = AdminState.communityPostsById.get(String(postId));
    if (!post) {
        showMessage('未找到帖子信息', 'error');
        return;
    }
    openAdminRecordDetailModal('社区帖子详情', post.title || `帖子 #${post.id}`, `${getCommunityCategoryLabel(post.category)} · ID ${post.id}`, [
        ['帖子ID', post.id],
        ['分区', getCommunityCategoryLabel(post.category)],
        ['作者', getUserLabelById(post.authorId)],
        ['作者ID', post.authorId],
        ['阅读数', post.viewCount != null ? post.viewCount : 0],
        ['点赞数', post.likeCount != null ? post.likeCount : 0],
        ['收藏数', post.favoriteCount != null ? post.favoriteCount : 0],
        ['发布时间', formatTime(post.createdAt)],
        ['标题', post.title, true],
        ['正文内容', post.content, true],
        ['附件', post.attachments, true]
    ]);
}

async function deleteCommunityPost(postId) {
    if (!confirm('确定要删除该帖子吗？')) return;

    try {
        const response = await apiFetch(`/api/admin/community-post/${encodeURIComponent(String(postId))}`, {
            method: 'DELETE'
        });
        const result = await response.json();

        if (result.code === 200) {
            showMessage('删除成功', 'success');
            loadCommunityPosts();
            loadDashboardData();
        } else {
            showMessage('删除失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('删除社区帖子异常:', error);
        showMessage('删除失败，请稍后重试', 'error');
    }
}
