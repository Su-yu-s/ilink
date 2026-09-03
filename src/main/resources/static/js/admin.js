// 管理后台JavaScript
const AdminState = {
    usersById: new Map(),
    teamsById: new Map(),
    teachersById: new Map(),
    assetsById: new Map(),
    communityPostsById: new Map(),
    competitionsById: new Map(),
    userEditModal: null,
    userDetailModal: null,
    recordDetailModal: null,
    teamEditModal: null,
    teacherEditModal: null,
    assetEditModal: null,
    communityPostEditModal: null,
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
    allCompetitions: [],
    filteredCompetitions: [],
    competitionEditModal: null,
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
        loadCommunityPosts(),
        loadCompetitionsAdmin()
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

    AdminState.teamEditModal = bindBootstrapModal('adminTeamEditModal');
    AdminState.teacherEditModal = bindBootstrapModal('adminTeacherEditModal');
    AdminState.assetEditModal = bindBootstrapModal('adminAssetEditModal');
    AdminState.communityPostEditModal = bindBootstrapModal('adminCommunityPostEditModal');

    const competitionModalEl = document.getElementById('adminCompetitionEditModal');
    if (competitionModalEl && window.bootstrap && typeof window.bootstrap.Modal === 'function') {
        AdminState.competitionEditModal = new window.bootstrap.Modal(competitionModalEl);
    }

    const saveBtn = document.getElementById('adminUserEditSaveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', saveUserEditFromModal);
    }
    document.getElementById('adminTeamSaveBtn')?.addEventListener('click', saveTeamEditFromModal);
    document.getElementById('adminTeacherSaveBtn')?.addEventListener('click', saveTeacherEditFromModal);
    document.getElementById('adminAssetSaveBtn')?.addEventListener('click', saveAssetEditFromModal);
    document.getElementById('adminCommunityPostSaveBtn')?.addEventListener('click', saveCommunityPostEditFromModal);
    document.getElementById('adminCompetitionSaveBtn')?.addEventListener('click', saveCompetitionFromModal);
    ['adminEditTeacherTitle', 'adminEditTeacherDirection', 'adminEditTeacherIntro', 'adminEditTeacherProjects', 'adminEditTeacherStatus']
        .forEach(function(id) {
            document.getElementById(id)?.addEventListener('input', displayTeacherEditHint);
        });

    bindAdminSearch('userSearchInput', 'userSearchBtn', function() {
        AdminState.userPage = 1;
        applyUserSearchAndRender();
    });
    bindAdminSearch('teamSearchInput', 'teamSearchBtn', applyTeamSearchAndRender);
    bindAdminSearch('teacherSearchInput', 'teacherSearchBtn', applyTeacherSearchAndRender);
    bindAdminSearch('assetSearchInput', 'assetSearchBtn', applyAssetSearchAndRender);
    bindAdminSearch('communitySearchInput', 'communitySearchBtn', applyCommunityPostSearchAndRender);
    bindAdminSearch('competitionSearchInput', 'competitionSearchBtn', applyCompetitionSearchAndRender);
});

function bindBootstrapModal(modalId) {
    const el = document.getElementById(modalId);
    if (el && window.bootstrap && typeof window.bootstrap.Modal === 'function') {
        return new window.bootstrap.Modal(el);
    }
    return null;
}

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
    let text;
    if (typeof value === 'object') {
        try {
            text = JSON.stringify(value, null, 2);
        } catch (e) {
            text = String(value);
        }
    } else {
        text = String(value).trim();
    }
    // JSON 字符串美化：详情里的附件/扩展字段以 JSON 原文展示会像“乱码”
    const trimmed = text.trim();
    if ((trimmed.startsWith('[') && trimmed.endsWith(']'))
        || (trimmed.startsWith('{') && trimmed.endsWith('}'))) {
        try {
            const parsed = JSON.parse(trimmed);
            if (parsed !== null && typeof parsed === 'object') {
                return JSON.stringify(parsed, null, 2);
            }
        } catch (e) {
            // 不是合法 JSON，原样展示
        }
    }
    return text;
}

// 用于 title 提示，压制换行/连续空白/控制字符，避免 tooltip 显示异常
function cleanTooltipText(text) {
    return String(text == null ? '' : text).replace(/[\r\n\t]+/g, ' ').replace(/\s{2,}/g, ' ').trim();
}

// ============ 富文本内容可读化（成果描述 / 帖子正文） ============
// 平台存储格式：'<!--md:base64(markdown源码)-->' + 渲染后 HTML；
// 成果为两段式：base64(简介)|base64(心得)。管理后台详情/编辑需要解码后展示。
function decodeBase64Utf8(b64) {
    const clean = String(b64 || '').replace(/[^A-Za-z0-9+/=]/g, '');
    if (!clean) return '';
    try {
        const binary = atob(clean);
        const bytes = Uint8Array.from(binary, function (ch) { return ch.charCodeAt(0); });
        // fatal:false，存量脏字节（如残缺的破折号）替换为 U+FFFD 而不是整体解码失败
        return new TextDecoder('utf-8').decode(bytes);
    } catch (e) {
        try {
            return decodeURIComponent(escape(atob(clean)));
        } catch (e2) {
            return '';
        }
    }
}

function encodeUtf8Base64(text) {
    const str = String(text == null ? '' : text);
    try {
        const bytes = new TextEncoder().encode(str);
        let binary = '';
        for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
        return btoa(binary);
    } catch (e) {
        return btoa(unescape(encodeURIComponent(str)));
    }
}

// 提取 <!--md:...--> 中嵌入的 markdown 源码（成果为 lead|insight 两段，合并返回）
function extractWrappedMarkdown(raw) {
    const text = String(raw || '');
    const match = text.match(/^<!--md:([A-Za-z0-9+/=|]+)-->/)
        || text.match(/<!--md:([A-Za-z0-9+/=|]+)-->/);
    if (!match) return '';
    return String(match[1]).split('|')
        .map(decodeBase64Utf8)
        .filter(Boolean)
        .join('\n\n')
        .trim();
}

// HTML 正文转纯文本（旧数据没有 markdown 源码时的兜底）
function htmlToPlainText(html) {
    return String(html || '')
        .replace(/<!--[\s\S]*?-->/g, '')
        .replace(/<\s*br\s*\/?\s*>/gi, '\n')
        .replace(/<\/(p|div|h[1-6]|li|tr|ul|ol|blockquote|pre|section|article)>/gi, '\n')
        .replace(/<li[^>]*>/gi, '• ')
        .replace(/<hr[^>]*\/?>/gi, '\n——————————\n')
        .replace(/<[^>]+>/g, '')
        .replace(/&nbsp;/gi, ' ')
        .replace(/&hellip;/gi, '…')
        .replace(/&mdash;/gi, '—')
        .replace(/&ldquo;|&rdquo;/gi, '“')
        .replace(/&lsquo;|&rsquo;/gi, '’')
        .replace(/&lt;/gi, '<')
        .replace(/&gt;/gi, '>')
        .replace(/&quot;/gi, '"')
        .replace(/&#39;|&apos;/gi, "'")
        .replace(/&amp;/gi, '&')
        .replace(/\uFFFD/g, '')
        .replace(/[ \t]+\n/g, '\n')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
}

// markdown 源码轻量净化，仅用于管理员阅读：去掉标记符号、保留文字与换行
function markdownToReadable(md) {
    return String(md || '')
        .replace(/^#{1,6}[ \t]+/gm, '')
        .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
        .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
        .replace(/\*\*([^*]+)\*\*/g, '$1')
        .replace(/__([^_]+)__/g, '$1')
        .replace(/\*([^*\n]+)\*/g, '$1')
        .replace(/`([^`]+)`/g, '$1')
        .replace(/^\s*>[ \t]?/gm, '')
        .replace(/^\s*[-*+][ \t]+/gm, '• ')
        .replace(/<\/?[^>]+>/g, '')
        .replace(/\uFFFD/g, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
}

// 详情弹窗用：把存储的富文本转成管理员可读的纯文本
function readableRichText(raw) {
    const text = String(raw == null ? '' : raw);
    if (!text.trim()) return '';
    const md = extractWrappedMarkdown(text);
    let out = md
        ? markdownToReadable(md)
        : htmlToPlainText(text.replace(/^<!--md:[\s\S]*?-->/, ''));
    // 成果旧格式尾部的（分类：xxx）标记，分类字段已单独展示
    return out.replace(/（分类：[^）]*）\s*$/g, '').trim();
}

// 编辑弹窗用：还原为可编辑文本（优先 markdown 源码，其次 HTML 转纯文本）
function editableRichText(raw) {
    const text = String(raw == null ? '' : raw);
    if (!text.trim()) return '';
    const md = extractWrappedMarkdown(text);
    if (md) return md;
    return htmlToPlainText(text.replace(/^<!--md:[\s\S]*?-->/, ''));
}

// 编辑保存用：重新包装为平台统一的 <!--md:base64--> 存储格式，保证公开页正常渲染
function wrapRichTextForSave(text, kind) {
    const value = String(text == null ? '' : text).trim();
    if (!value) return '';
    if (kind === 'asset') {
        // 成果格式为 lead|insight 两段；管理后台单框编辑时整体作为 lead
        return '<!--md:' + encodeUtf8Base64(value) + '|-->';
    }
    return '<!--md:' + encodeUtf8Base64(value) + '-->';
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

function renderUserDetailItem(label, value, wide, type) {
    const text = type === 'richtext' ? readableRichText(value) : formatDetailValue(value);
    const displayText = text || '未设置';
    const emptyClass = text ? '' : ' admin-detail-value--empty';
    const wideClass = wide ? ' admin-detail-item--wide' : '';
    const tooltip = cleanTooltipText(displayText);
    return `
        <div class="admin-detail-item${wideClass}">
            <span class="admin-detail-label">${escapeHtml(label)}</span>
            <span class="admin-detail-value${emptyClass}" title="${escapeHtml(tooltip)}">${escapeHtml(displayText)}</span>
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
            ${fields.map(([label, value, wide, type]) => renderUserDetailItem(label, value, wide, type)).join('')}
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
    if (titleEl) titleEl.textContent = safeTitle;
    body.innerHTML = `
        <div class="admin-detail-summary">
            <div>
                <div class="admin-detail-name">${escapeHtml(safeName)}</div>
                <div class="admin-detail-subtitle">${escapeHtml(subtitle || '')}</div>
            </div>
        </div>
        <div class="admin-detail-list">
            ${fields.map(([label, value, wide, type]) => renderUserDetailItem(label, value, wide, type)).join('')}
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
                    <button class="btn btn-outline-primary btn-sm" onclick="openTeamEditModal(${team.id})">编辑</button>
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
        ['所需人数', team.requiredMemberCount],
        ['截止日期', team.deadline ? formatTime(team.deadline) : ''],
        ['发布时间', formatTime(team.createdAt)],
        ['所需技能', team.requiredSkills, true],
        ['需求描述', team.description, true]
    ]);
}

function formatDateInput(value) {
    if (value == null || value === '') return '';
    const d = new Date(value);
    if (isNaN(d.getTime())) return '';
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
}

function openTeamEditModal(teamId) {
    const team = AdminState.teamsById.get(String(teamId));
    if (!team) {
        showMessage('未找到组队需求', 'error');
        return;
    }
    document.getElementById('adminEditTeamId').value = String(team.id || '');
    document.getElementById('adminEditTeamTitle').value = team.title || '';
    document.getElementById('adminEditTeamStatus').value = team.status || 'OPEN';
    document.getElementById('adminEditTeamSkills').value = team.requiredSkills || '';
    const memberCountEl = document.getElementById('adminEditTeamMemberCount');
    if (memberCountEl) memberCountEl.value = team.requiredMemberCount != null ? String(team.requiredMemberCount) : '';
    const compIdEl = document.getElementById('adminEditTeamCompetitionId');
    if (compIdEl) compIdEl.value = team.competitionId != null ? String(team.competitionId) : '';
    document.getElementById('adminEditTeamDeadline').value = formatDateInput(team.deadline);
    document.getElementById('adminEditTeamDescription').value = team.description || '';
    AdminState.teamEditModal?.show();
}

async function saveTeamEditFromModal() {
    const idEl = document.getElementById('adminEditTeamId');
    if (!idEl || !idEl.value) {
        showMessage('缺少组队ID', 'error');
        return;
    }
    const teamId = idEl.value;
    const payload = {
        title: (document.getElementById('adminEditTeamTitle')?.value || '').trim(),
        status: (document.getElementById('adminEditTeamStatus')?.value || '').trim(),
        requiredSkills: (document.getElementById('adminEditTeamSkills')?.value || '').trim(),
        description: (document.getElementById('adminEditTeamDescription')?.value || '').trim(),
        requiredMemberCount: (document.getElementById('adminEditTeamMemberCount')?.value || '').trim(),
        competitionId: (document.getElementById('adminEditTeamCompetitionId')?.value || '').trim(),
        deadline: (document.getElementById('adminEditTeamDeadline')?.value || '').trim()
    };
    if (!payload.title) {
        showMessage('标题不能为空', 'warning');
        return;
    }
    try {
        const response = await apiFetch(`/api/admin/team/${encodeURIComponent(String(teamId))}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('组队需求更新成功', 'success');
            AdminState.teamEditModal?.hide();
            loadTeams();
        } else {
            showMessage(result.message || '更新失败', 'error');
        }
    } catch (error) {
        console.error('更新组队需求异常:', error);
        showMessage('更新失败，请稍后重试', 'error');
    }
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
                    <button class="btn btn-outline-primary btn-sm" onclick="openTeacherEditModal(${teacher.id})">编辑</button>
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
        ['职称', teacher.professionalTitle, true],
        ['研究方向', teacher.researchDirection, true],
        ['个人简介', teacher.introduction, true],
        ['项目经历', teacher.projects, true]
    ]);
}

function openTeacherEditModal(teacherId) {
    const teacher = AdminState.teachersById.get(String(teacherId));
    if (!teacher) {
        showMessage('未找到导师申请', 'error');
        return;
    }
    document.getElementById('adminEditTeacherId').value = String(teacher.id || '');
    document.getElementById('adminEditTeacherTitle').value = teacher.professionalTitle || '';
    document.getElementById('adminEditTeacherDirection').value = teacher.researchDirection || '';
    document.getElementById('adminEditTeacherIntro').value = teacher.introduction || '';
    document.getElementById('adminEditTeacherProjects').value = teacher.projects || '';
    document.getElementById('adminEditTeacherStatus').value = teacher.status || 'PENDING';
    displayTeacherEditHint();
    AdminState.teacherEditModal?.show();
}

function displayTeacherEditHint() {
    // 从已加载的用户数据中取教师用户的姓名/学校/专业，提示还缺哪些字段
    const teacherId = document.getElementById('adminEditTeacherId')?.value;
    const teacher = teacherId ? AdminState.teachersById.get(String(teacherId)) : null;
    const user = teacher ? AdminState.usersById.get(String(teacher.userId)) : null;
    const missing = [];
    const teacherTitle = (document.getElementById('adminEditTeacherTitle')?.value || '').trim();
    const direction = (document.getElementById('adminEditTeacherDirection')?.value || '').trim();
    const intro = (document.getElementById('adminEditTeacherIntro')?.value || '').trim();
    if (!(user && (user.realName || '')).trim()) missing.push('姓名');
    if (!(user && (user.school || '')).trim()) missing.push('学校');
    if (!(user && (user.major || '')).trim()) missing.push('专业');
    if (!teacherTitle) missing.push('职称');
    if (!direction) missing.push('研究方向');
    if (!intro) missing.push('简介');
    const hint = document.getElementById('adminTeacherEditRequiredHint');
    if (hint) {
        hint.textContent = missing.length
            ? '设为「已批准」前仍需补全：' + missing.join('、') + '（姓名/学校/专业请在"用户管理"中编辑）'
            : '资料已完整，可设为「已批准」。';
    }
}

async function saveTeacherEditFromModal() {
    const idEl = document.getElementById('adminEditTeacherId');
    if (!idEl || !idEl.value) {
        showMessage('缺少导师ID', 'error');
        return;
    }
    const teacherId = idEl.value;
    const payload = {
        professionalTitle: (document.getElementById('adminEditTeacherTitle')?.value || '').trim(),
        researchDirection: (document.getElementById('adminEditTeacherDirection')?.value || '').trim(),
        introduction: (document.getElementById('adminEditTeacherIntro')?.value || '').trim(),
        projects: (document.getElementById('adminEditTeacherProjects')?.value || '').trim(),
        status: (document.getElementById('adminEditTeacherStatus')?.value || '').trim()
    };
    try {
        const response = await apiFetch(`/api/admin/teacher/${encodeURIComponent(String(teacherId))}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('导师资料更新成功', 'success');
            AdminState.teacherEditModal?.hide();
            loadTeachers();
            loadDashboardData();
        } else {
            showMessage(result.message || '更新失败', 'error');
        }
    } catch (error) {
        console.error('更新导师资料异常:', error);
        showMessage('更新失败，请稍后重试', 'error');
    }
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
                    <button class="btn btn-outline-primary btn-sm" onclick="openAssetEditModal(${asset.id})">编辑</button>
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
        ['分类', asset.category],
        ['浏览量', asset.viewCount != null ? asset.viewCount : 0],
        ['发布时间', formatTime(asset.createdAt)],
        ['文件地址', asset.fileUrl, true],
        ['成果描述', asset.description, true, 'richtext']
    ]);
}

function openAssetEditModal(assetId) {
    const asset = AdminState.assetsById.get(String(assetId));
    if (!asset) {
        showMessage('未找到成果信息', 'error');
        return;
    }
    document.getElementById('adminEditAssetId').value = String(asset.id || '');
    document.getElementById('adminEditAssetTitle').value = asset.title || '';
    document.getElementById('adminEditAssetCategory').value = asset.category || '';
    // 解码 <!--md:base64--> 存储格式，回填可读的 markdown/纯文本，避免编辑框出现 base64 乱码
    document.getElementById('adminEditAssetDescription').value = editableRichText(asset.description);
    AdminState.assetEditModal?.show();
}

async function saveAssetEditFromModal() {
    const idEl = document.getElementById('adminEditAssetId');
    if (!idEl || !idEl.value) {
        showMessage('缺少成果ID', 'error');
        return;
    }
    const assetId = idEl.value;
    const payload = {
        title: (document.getElementById('adminEditAssetTitle')?.value || '').trim(),
        category: (document.getElementById('adminEditAssetCategory')?.value || '').trim(),
        // 重新包装为平台统一的 <!--md:base64(lead)|--> 格式，保证公开成果页正常渲染
        description: wrapRichTextForSave(document.getElementById('adminEditAssetDescription')?.value || '', 'asset')
    };
    if (!payload.title) {
        showMessage('标题不能为空', 'warning');
        return;
    }
    try {
        const response = await apiFetch(`/api/admin/asset/${encodeURIComponent(String(assetId))}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('成果更新成功', 'success');
            AdminState.assetEditModal?.hide();
            loadAssets();
        } else {
            showMessage(result.message || '更新失败', 'error');
        }
    } catch (error) {
        console.error('更新成果异常:', error);
        showMessage('更新失败，请稍后重试', 'error');
    }
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
                    <button class="btn btn-outline-primary btn-sm" onclick="openCommunityPostEditModal(${post.id})">编辑</button>
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
        ['正文内容', post.content, true, 'richtext'],
        ['附件', formatPostAttachments(post.attachments), true]
    ]);
}

// 附件 JSON 可读化，避免详情页显示一长串 JSON 原文
function formatPostAttachments(attachments) {
    if (attachments == null || String(attachments).trim() === '') return '';
    const raw = String(attachments).trim();
    try {
        const list = JSON.parse(raw);
        if (Array.isArray(list) && list.length) {
            return list.map(function(item) {
                const name = item && item.name ? String(item.name) : '附件';
                const url = item && item.url ? String(item.url) : '';
                return url ? `${name}（${url}）` : name;
            }).join('\n');
        }
    } catch (e) {
        // 不是标准 JSON，原样展示
    }
    return raw;
}

function openCommunityPostEditModal(postId) {
    const post = AdminState.communityPostsById.get(String(postId));
    if (!post) {
        showMessage('未找到帖子信息', 'error');
        return;
    }
    document.getElementById('adminEditPostId').value = String(post.id || '');
    document.getElementById('adminEditPostTitle').value = post.title || '';
    document.getElementById('adminEditPostCategory').value = post.category || 'general';
    document.getElementById('adminEditPostContent').value = post.content || '';
    AdminState.communityPostEditModal?.show();
}

async function saveCommunityPostEditFromModal() {
    const idEl = document.getElementById('adminEditPostId');
    if (!idEl || !idEl.value) {
        showMessage('缺少帖子ID', 'error');
        return;
    }
    const postId = idEl.value;
    const payload = {
        title: (document.getElementById('adminEditPostTitle')?.value || '').trim(),
        category: (document.getElementById('adminEditPostCategory')?.value || '').trim(),
        // 重新包装为平台统一的 <!--md:base64--> 格式，保证公开帖子页正常渲染
        content: wrapRichTextForSave(document.getElementById('adminEditPostContent')?.value || '', 'post')
    };
    if (!payload.title) {
        showMessage('标题不能为空', 'warning');
        return;
    }
    try {
        const response = await apiFetch(`/api/admin/community-post/${encodeURIComponent(String(postId))}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify(payload)
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('帖子更新成功', 'success');
            AdminState.communityPostEditModal?.hide();
            loadCommunityPosts();
        } else {
            showMessage(result.message || '更新失败', 'error');
        }
    } catch (error) {
        console.error('更新帖子异常:', error);
        showMessage('更新失败，请稍后重试', 'error');
    }
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

const ADMIN_COMPETITION_TRACKS = {
    cs: '计算机软件',
    ee: '电子信息',
    innovation: '创新创业',
    stem: '数理建模',
    robot: '机器人 / 智能车',
    general: '综合 / 语言'
};

async function loadCompetitionsAdmin() {
    try {
        const response = await apiFetch('/api/admin/competitions');
        const result = await response.json();
        if (result.code !== 200) throw new Error(result.message || '获取竞赛目录失败');
        AdminState.allCompetitions = Array.isArray(result.data) ? result.data.slice() : [];
        AdminState.competitionsById = new Map(
            AdminState.allCompetitions.map(item => [String(item.id), item])
        );
        applyCompetitionSearchAndRender();
    } catch (error) {
        console.error('获取竞赛目录异常:', error);
        const tbody = document.getElementById('competitionTableBody');
        if (tbody) renderEmptyRow(tbody, 5, '竞赛目录加载失败');
    }
}

function applyCompetitionSearchAndRender() {
    const keyword = document.getElementById('competitionSearchInput')?.value || '';
    AdminState.filteredCompetitions = AdminState.allCompetitions.filter(item => matchesKeyword([
        item.name,
        item.organizer,
        item.track,
        ADMIN_COMPETITION_TRACKS[item.track],
        item.levelClass,
        item.scope,
        item.status === 'ACTIVE' ? '已发布' : '已停用',
        ...(Array.isArray(item.tags) ? item.tags : [])
    ], keyword));
    renderCompetitionAdminRows(AdminState.filteredCompetitions);
    updateAdminListHint('competitionListHint', AdminState.filteredCompetitions.length);
}

function renderCompetitionAdminRows(items) {
    const tbody = document.getElementById('competitionTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';
    if (!items.length) {
        renderEmptyRow(tbody, 5, '暂无匹配竞赛');
        return;
    }
    items.forEach(item => {
        const tr = document.createElement('tr');
        const status = item.status === 'ACTIVE'
            ? '<span class="status-badge approved">已发布</span>'
            : '<span class="status-badge teaming">已停用</span>';
        setTableRowCells(tr, [
            { label: '竞赛名称', html: renderUserCell(item.name) },
            { label: '赛道', html: escapeHtml(ADMIN_COMPETITION_TRACKS[item.track] || item.track || '') },
            { label: '类别/级别', html: escapeHtml((item.levelClass || '') + ' · ' + (item.scope || '')) },
            { label: '状态', html: status },
            {
                label: '操作',
                className: 'admin-row-actions',
                html: '<button class="btn btn-outline-secondary btn-sm" onclick="openCompetitionEditModal(' +
                    item.id + ')">编辑</button> ' +
                    '<button class="btn btn-danger btn-sm" onclick="deleteCompetitionAdmin(' +
                    item.id + ')">删除</button>'
            }
        ]);
        tbody.appendChild(tr);
    });
}

function setCompetitionField(id, value) {
    const element = document.getElementById(id);
    if (element) element.value = value == null ? '' : String(value);
}

function openCompetitionEditModal(competitionId) {
    const item = competitionId == null
        ? null
        : AdminState.competitionsById.get(String(competitionId));
    if (competitionId != null && !item) {
        showMessage('未找到竞赛信息', 'error');
        return;
    }
    setCompetitionField('competitionEditId', item?.id || '');
    setCompetitionField('competitionEditName', item?.name || '');
    setCompetitionField('competitionEditTrack', item?.track || 'cs');
    setCompetitionField('competitionEditOrganizer', item?.organizer || '');
    setCompetitionField('competitionEditLevel', item?.levelClass || '一类B');
    setCompetitionField('competitionEditScope', item?.scope || '国赛');
    setCompetitionField('competitionEditSeason', item?.season || '');
    setCompetitionField('competitionEditDeadline', item?.registrationDeadline || '');
    setCompetitionField('competitionEditUrl', item?.officialUrl || '');
    setCompetitionField('competitionEditStatus', item?.status || 'ACTIVE');
    setCompetitionField('competitionEditTags', Array.isArray(item?.tags) ? item.tags.join('，') : '');
    setCompetitionField('competitionEditDescription', item?.description || '');
    const title = document.getElementById('adminCompetitionEditTitle');
    if (title) title.textContent = item ? '编辑竞赛' : '新增竞赛';
    AdminState.competitionEditModal?.show();
}

async function saveCompetitionFromModal() {
    const id = document.getElementById('competitionEditId')?.value || '';
    const payload = {
        name: document.getElementById('competitionEditName')?.value.trim() || '',
        track: document.getElementById('competitionEditTrack')?.value || '',
        organizer: document.getElementById('competitionEditOrganizer')?.value.trim() || '',
        levelClass: document.getElementById('competitionEditLevel')?.value || '',
        scope: document.getElementById('competitionEditScope')?.value || '',
        season: document.getElementById('competitionEditSeason')?.value.trim() || '',
        registrationDeadline: document.getElementById('competitionEditDeadline')?.value || null,
        officialUrl: document.getElementById('competitionEditUrl')?.value.trim() || '',
        status: document.getElementById('competitionEditStatus')?.value || 'ACTIVE',
        tags: (document.getElementById('competitionEditTags')?.value || '')
            .split(/[，,]/).map(tag => tag.trim()).filter(Boolean),
        description: document.getElementById('competitionEditDescription')?.value.trim() || ''
    };
    if (!payload.name || !payload.organizer) {
        showMessage('请填写竞赛名称和主办单位', 'warning');
        return;
    }
    try {
        const response = await apiFetch(
            id ? '/api/admin/competitions/' + encodeURIComponent(id) : '/api/admin/competitions',
            {
                method: id ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }
        );
        const result = await response.json();
        if (!response.ok || result.code !== 200) throw new Error(result.message || '保存失败');
        showMessage(id ? '竞赛更新成功' : '竞赛创建成功', 'success');
        AdminState.competitionEditModal?.hide();
        await loadCompetitionsAdmin();
    } catch (error) {
        showMessage(error.message || '保存失败，请稍后重试', 'error');
    }
}

async function deleteCompetitionAdmin(competitionId) {
    if (!confirm('确定删除这条竞赛目录记录吗？')) return;
    try {
        const response = await apiFetch('/api/admin/competitions/' + encodeURIComponent(String(competitionId)), {
            method: 'DELETE'
        });
        const result = await response.json();
        if (!response.ok || result.code !== 200) throw new Error(result.message || '删除失败');
        showMessage('竞赛删除成功', 'success');
        await loadCompetitionsAdmin();
    } catch (error) {
        showMessage(error.message || '删除失败，请稍后重试', 'error');
    }
}
