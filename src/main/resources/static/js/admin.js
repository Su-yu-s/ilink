// 管理后台JavaScript
const AdminState = {
    usersById: new Map(),
    teamsById: new Map(),
    teachersById: new Map(),
    assetsById: new Map(),
    communityPostsById: new Map(),
    competitionsById: new Map(),
    competitionPage: 1,
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
    userPage: 1,
    userTotal: 0,
    teamPage: 1,
    teamTotal: 0,
    teacherPage: 1,
    teacherTotal: 0,
    assetPage: 1,
    assetTotal: 0,
    communityPostPage: 1,
    communityPostTotal: 0,
    auditPage: 1,
    auditTotal: 0
};
const ADMIN_USER_PAGE_SIZE = 10;
// 组队 / 导师 / 成果 / 审计日志等服务端分页页签的每页条数
const ADMIN_PAGE_SIZE = 20;
// 社区帖子单独用 10 条：帖子列表最常翻，一页少一点更好扫
const ADMIN_COMMUNITY_PAGE_SIZE = 10;

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
        loadCompetitionsAdmin(),
        loadAuditLogs()
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
    document.getElementById('adminEditStatus')?.addEventListener('change', function () {
        const group = document.getElementById('adminEditDisabledReasonGroup');
        if (group) group.hidden = this.value !== 'DISABLED';
    });
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
        loadUsers(1);
    });
    document.getElementById('userRoleFilter')?.addEventListener('change', function() { loadUsers(1); });
    bindAdminSearch('teamSearchInput', 'teamSearchBtn', function() { loadTeams(1); });
    document.getElementById('teamStatusFilter')?.addEventListener('change', function() { loadTeams(1); });
    bindAdminSearch('teacherSearchInput', 'teacherSearchBtn', function() { loadTeachers(1); });
    bindAdminSearch('assetSearchInput', 'assetSearchBtn', function() { loadAssets(1); });
    bindAdminSearch('communitySearchInput', 'communitySearchBtn', function() { loadCommunityPosts(1); });
    bindAdminSearch('competitionSearchInput', 'competitionSearchBtn', function() {
        AdminState.competitionPage = 1;
        loadCompetitionsAdmin();
    });
    bindAdminSearch('auditSearchInput', 'auditSearchBtn', function() { loadAuditLogs(1); });
    document.getElementById('auditActionFilter')?.addEventListener('change', function() { loadAuditLogs(1); });
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
    const debounced = adminDebounce(renderFn, 350);
    if (input) {
        input.addEventListener('input', debounced);
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

/** 输入防抖：管理端搜索改为服务端过滤后，避免每个按键都打一次接口 */
function adminDebounce(fn, wait) {
    let timer = null;
    return function() {
        if (timer) clearTimeout(timer);
        timer = setTimeout(fn, wait);
    };
}

/** 组装服务端分页参数 */
function adminSearchParams(page, size) {
    const params = new URLSearchParams();
    params.set('page', String(page || 1));
    params.set('size', String(size));
    return params;
}

/** 把搜索框的关键词并入查询参数 */
function adminKeywordParams(params, inputId) {
    const kw = document.getElementById(inputId)?.value.trim();
    if (kw) params.set('keyword', kw);
    return params;
}

function adminSelectValue(id) {
    const el = document.getElementById(id);
    return el && el.value ? el.value : '';
}

/** 服务端分页器：所有列表页签共用，翻页回调由调用方传入 */
function renderAdminServerPager(pagerId, page, size, total, onPage) {
    const pager = document.getElementById(pagerId);
    if (!pager) return;
    const maxPage = Math.max(1, Math.ceil(total / size));
    if (total <= size) {
        pager.innerHTML = '';
        return;
    }
    const prevDisabled = page <= 1 ? ' disabled' : '';
    const nextDisabled = page >= maxPage ? ' disabled' : '';
    const pages = [];
    for (let p = 1; p <= maxPage; p++) {
        if (p === 1 || p === maxPage || Math.abs(p - page) <= 1) {
            pages.push(p);
        } else if (pages[pages.length - 1] !== '...') {
            pages.push('...');
        }
    }
    let html = '<ul class="pagination pagination-sm mb-0">';
    html += `<li class="page-item${prevDisabled}"><a class="page-link" href="#" data-page="${page - 1}">上一页</a></li>`;
    pages.forEach(p => {
        if (p === '...') {
            html += '<li class="page-item disabled"><span class="page-link">...</span></li>';
        } else {
            const active = p === page ? ' active' : '';
            html += `<li class="page-item${active}"><a class="page-link" href="#" data-page="${p}">${p}</a></li>`;
        }
    });
    html += `<li class="page-item${nextDisabled}"><a class="page-link" href="#" data-page="${page + 1}">下一页</a></li>`;
    html += '</ul>';
    pager.innerHTML = html;
    pager.querySelectorAll('a.page-link[data-page]').forEach(a => {
        a.addEventListener('click', function(e) {
            e.preventDefault();
            const target = Number(this.getAttribute('data-page'));
            if (!Number.isFinite(target) || target < 1 || target > maxPage || target === page) return;
            onPage(target);
        });
    });
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
    const name = String(user.displayName || user.realName || '').trim() || `用户 #${id}`;
    return `${name}（ID:${id}）`;
}

function getStatusDisplayName(status, type) {
    const value = String(status || '').trim().toUpperCase();
    if (type === 'team') {
        if (value === 'OPEN') return '招募中';
        if (value === 'TEAMING') return '已组队';
        if (value === 'CLOSED') return '已结束';
        if (value === 'DISSOLVED') return '已解散';
    }
    if (type === 'teacher') {
        if (value === 'PENDING') return '待审核';
        if (value === 'APPROVED') return '已批准';
        if (value === 'REJECTED') return '已拒绝';
    }
    return value || '未设置';
}

/**
 * 账号状态：正常 / 已暂停。
 * 后端把 status 为 null 视为 ACTIVE（兼容迁移前数据），这里保持同一判据。
 */
function isUserDisabled(user) {
    const status = user && user.status ? String(user.status).trim().toUpperCase() : '';
    return status === 'DISABLED';
}

function renderUserStatusCell(user) {
    if (!user) return '-';
    if (isUserDisabled(user)) {
        const reason = user.disabledReason ? String(user.disabledReason).trim() : '';
        const title = reason ? ` title="${escapeHtml(reason)}"` : '';
        return `<span class="admin-status-badge admin-status-badge--danger"${title}>已暂停</span>`;
    }
    return '<span class="admin-status-badge admin-status-badge--muted">正常</span>';
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
// 平台存储格式：不可见源码标记 + 渲染后的安全 HTML；
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

// 提取统一源码标记中的 Markdown（兼容历史 <!--md:...--> 数据）
function extractWrappedMarkdown(raw) {
    const payload = markdownSourcePayload(raw);
    if (!payload) return '';
    return String(payload).split('|')
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
        : htmlToPlainText(stripMarkdownSourceMarker(text));
    // 成果旧格式尾部的（分类：xxx）标记，分类字段已单独展示
    return out.replace(/（分类：[^）]*）\s*$/g, '').trim();
}

// 编辑弹窗用：还原为可编辑文本（优先 markdown 源码，其次 HTML 转纯文本）
function editableRichText(raw) {
    const text = String(raw == null ? '' : raw);
    if (!text.trim()) return '';
    const md = extractWrappedMarkdown(text);
    if (md) return md;
    return htmlToPlainText(stripMarkdownSourceMarker(text));
}

// 编辑保存用：同时保存可恢复的 Markdown 源码与安全 HTML。
function wrapRichTextForSave(text, kind) {
    const value = String(text == null ? '' : text).trim();
    if (!value) return '';
    const html = markdownToSafeHtml(value);
    if (kind === 'asset') {
        // 成果格式为 lead|insight 两段；管理后台单框编辑时整体作为 lead
        return markdownSourceMarker(encodeMarkdownSource(value) + '|') + html;
    }
    return markdownSourceMarker(encodeMarkdownSource(value)) + html;
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

// 加载用户列表（服务端分页 + 关键词/角色筛选）
async function loadUsers(page) {
    page = page || 1;
    try {
        const params = adminSearchParams(page, ADMIN_USER_PAGE_SIZE);
        adminKeywordParams(params, 'userSearchInput');
        const role = adminSelectValue('userRoleFilter');
        if (role) params.set('role', role);
        const response = await apiFetch('/api/admin/users?' + params.toString());
        const result = await response.json();

        if (result.code === 200) {
            renderUserList(result.data, (result.extra && result.extra.pagination) || null);
        } else if (result.code === 403) {
            console.error('无权限访问用户列表');
        } else {
            console.error('获取用户列表失败:', result.message);
        }
    } catch (error) {
        console.error('获取用户列表异常:', error);
    }
}

// 渲染用户列表（rows 已是服务端当前页）
function renderUserList(users, pagination) {
    const tbody = document.getElementById('userTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';
    const rows = Array.isArray(users) ? users : [];
    // 合并而非覆盖：其它模块（组队/成果等）要用用户 id 反查姓名
    rows.forEach(u => AdminState.usersById.set(String(u.id), u));
    AdminState.allUsers = rows.slice();
    AdminState.filteredUsers = rows.slice();
    AdminState.userPage = pagination ? (pagination.page || 1) : 1;
    AdminState.userTotal = pagination ? (pagination.total || rows.length) : rows.length;

    renderUserPage(AdminState.userPage);
}

function renderUserPage(page) {
    const tbody = document.getElementById('userTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';

    const rows = AdminState.filteredUsers; // 服务端已分页，这里就是当前页
    const total = AdminState.userTotal || rows.length;
    if (total === 0) {
        tbody.innerHTML = '<tr><td colspan="7" class="text-center admin-empty-cell">暂无匹配用户</td></tr>';
        renderUserPagination(0, 1);
        updateUserListHint(0, 0, 0);
        return;
    }

    const size = ADMIN_USER_PAGE_SIZE;
    const maxPage = Math.max(1, Math.ceil(total / size));
    const safePage = Math.min(Math.max(1, page), maxPage);
    AdminState.userPage = safePage;
    const start = (safePage - 1) * size;

    rows.forEach(user => {
        const accountName = user && user.username ? String(user.username).trim() : '';
        const userNameForList = accountName || '-';
        const realName = user && user.realName ? String(user.realName).trim() : '';
        const tr = document.createElement('tr');
        setTableRowCells(tr, [
            { label: '账号标识', html: escapeHtml(userNameForList) },
            { label: '姓名', html: renderUserCell(realName) },
            { label: '学号', html: renderUserCell(user.studentId != null ? user.studentId : '') },
            { label: '学校', html: renderUserCell(user.school) },
            { label: '角色', html: escapeHtml(getUserRoleDisplayName(user.role)) },
            { label: '状态', html: renderUserStatusCell(user) },
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
    updateUserListHint(total, start + 1, start + rows.length);
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
            loadUsers(target);
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
    const statusEl = document.getElementById('adminEditStatus');
    const reasonEl = document.getElementById('adminEditDisabledReason');
    const reasonGroup = document.getElementById('adminEditDisabledReasonGroup');

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
    if (statusEl) statusEl.value = isUserDisabled(user) ? 'DISABLED' : 'ACTIVE';
    if (reasonEl) reasonEl.value = user.disabledReason || '';
    if (reasonGroup) reasonGroup.hidden = !isUserDisabled(user);
    const currentUserId = document.body?.dataset.userId || '';
    const isSelf = currentUserId && String(currentUserId) === String(user.id);
    if (statusEl) statusEl.disabled = !!isSelf;
    if (reasonEl) reasonEl.disabled = !!isSelf;
    if (statusEl) statusEl.title = isSelf ? '不能修改当前管理员自己的账号状态' : '';

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
    const user = AdminState.usersById.get(String(userId));
    const statusEl = document.getElementById('adminEditStatus');
    const reasonEl = document.getElementById('adminEditDisabledReason');
    const requestedStatus = statusEl ? statusEl.value : (isUserDisabled(user) ? 'DISABLED' : 'ACTIVE');
    const reason = reasonEl ? reasonEl.value.trim() : '';
    if (requestedStatus === 'DISABLED' && !reason) {
        showMessage('暂停账号时必须填写原因', 'warning');
        reasonEl?.focus();
        return;
    }
    // username 是系统内部标识，服务端不再接受修改，这里也不提交
    const payload = {
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
            const currentStatus = isUserDisabled(user) ? 'DISABLED' : 'ACTIVE';
            if (requestedStatus !== currentStatus || (requestedStatus === 'DISABLED' && reason !== String(user?.disabledReason || '').trim())) {
                const statusResponse = await apiFetch(`/api/admin/user/${encodeURIComponent(String(userId))}/status`, {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'same-origin',
                    body: JSON.stringify({ status: requestedStatus, reason: reason })
                });
                const statusResult = await statusResponse.json();
                if (statusResult.code !== 200) {
                    showMessage((statusResult.message || '账号资料已保存，但状态更新失败') + '；请重新打开编辑窗口确认', 'error');
                    loadUsers();
                    return;
                }
            }
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
    if (!uiConfirm('确定要删除该用户吗？')) {
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
async function loadTeams(page) {
    page = page || 1;
    try {
        const params = adminSearchParams(page, ADMIN_PAGE_SIZE);
        adminKeywordParams(params, 'teamSearchInput');
        const status = adminSelectValue('teamStatusFilter');
        if (status) params.set('status', status);
        const response = await apiFetch('/api/admin/teams?' + params.toString());
        const result = await response.json();

        if (result.code === 200) {
            renderTeamList(result.data, (result.extra && result.extra.pagination) || null);
        } else if (result.code === 403) {
            console.error('无权限访问团队列表');
        } else {
            console.error('获取团队列表失败:', result.message);
        }
    } catch (error) {
        console.error('获取团队列表异常:', error);
    }
}

function renderTeamList(teams, pagination) {
    const rows = Array.isArray(teams) ? teams : [];
    rows.forEach(team => AdminState.teamsById.set(String(team.id), team));
    AdminState.allTeams = rows.slice();
    AdminState.filteredTeams = rows.slice();
    AdminState.teamPage = pagination ? (pagination.page || 1) : 1;
    AdminState.teamTotal = pagination ? (pagination.total || rows.length) : rows.length;
    applyTeamSearchAndRender();
}

function applyTeamSearchAndRender() {
    // 筛选已由服务端完成（关键词 + 状态），这里只负责渲染当前页
    renderTeamRows(AdminState.filteredTeams);
    renderAdminServerPager('teamPager', AdminState.teamPage, ADMIN_PAGE_SIZE, AdminState.teamTotal, loadTeams);
    updateAdminListHint('teamListHint', AdminState.teamTotal);
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


// ============================================================
// 通用删除原因弹窗：删除不可恢复，原因必填；确认后回调执行真正的删除请求，
// 服务端会把删除事实与原因通知内容所有者。
// ============================================================
let adminDeleteReasonCallback = null;

function openDeleteReasonModal(title, onConfirm) {
    const modalEl = document.getElementById('adminDeleteReasonModal');
    if (!modalEl) return;
    document.getElementById('adminDeleteReasonTitle').textContent = title;
    const input = document.getElementById('adminDeleteReasonInput');
    const errorEl = document.getElementById('adminDeleteReasonError');
    if (input) input.value = '';
    if (errorEl) errorEl.classList.add('d-none');
    adminDeleteReasonCallback = onConfirm;
    const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
    modal.show();
    if (input) input.focus();
}

document.addEventListener('DOMContentLoaded', function () {
    const confirmBtn = document.getElementById('adminDeleteReasonConfirmBtn');
    const input = document.getElementById('adminDeleteReasonInput');
    const errorEl = document.getElementById('adminDeleteReasonError');
    if (!confirmBtn || !input) return;
    confirmBtn.addEventListener('click', function () {
        const reason = (input.value || '').trim();
        if (!reason) {
            if (errorEl) errorEl.classList.remove('d-none');
            return;
        }
        if (reason.length > 200) {
            if (errorEl) { errorEl.textContent = '删除原因不能超过 200 字'; errorEl.classList.remove('d-none'); }
            return;
        }
        if (errorEl) errorEl.classList.add('d-none');
        const modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('adminDeleteReasonModal'));
        modal.hide();
        const cb = adminDeleteReasonCallback;
        adminDeleteReasonCallback = null;
        if (typeof cb === 'function') cb(reason);
    });
});

async function deleteTeam(teamId) {
    openDeleteReasonModal('删除组队需求', async function (reason) {
        try {
            const response = await apiFetch(`/api/admin/team/${encodeURIComponent(String(teamId))}?reason=` + encodeURIComponent(reason), {
                method: 'DELETE'
            });
            const result = await response.json();

            if (result.code === 200) {
                showMessage('删除成功，已通知创建者', 'success');
                loadTeams();
                loadDashboardData();
            } else {
                showMessage('删除失败: ' + result.message, 'error');
            }
        } catch (error) {
            console.error('删除团队异常:', error);
            showMessage('删除失败，请稍后重试', 'error');
        }
    });
}

// 加载导师列表
async function loadTeachers(page) {
    page = page || 1;
    try {
        const params = adminSearchParams(page, ADMIN_PAGE_SIZE);
        adminKeywordParams(params, 'teacherSearchInput');
        const status = adminSelectValue('teacherStatusFilter');
        if (status) params.set('status', status);
        const response = await apiFetch('/api/admin/teachers?' + params.toString());
        const result = await response.json();

        if (result.code === 200) {
            renderTeacherList(result.data, (result.extra && result.extra.pagination) || null);
        } else if (result.code === 403) {
            console.error('无权限访问导师列表');
        } else {
            console.error('获取导师列表失败:', result.message);
        }
    } catch (error) {
        console.error('获取导师列表异常:', error);
    }
}

function renderTeacherList(teachers, pagination) {
    const rows = Array.isArray(teachers) ? teachers : [];
    rows.forEach(teacher => AdminState.teachersById.set(String(teacher.id), teacher));
    AdminState.allTeachers = rows.slice();
    AdminState.filteredTeachers = rows.slice();
    AdminState.teacherPage = pagination ? (pagination.page || 1) : 1;
    AdminState.teacherTotal = pagination ? (pagination.total || rows.length) : rows.length;
    applyTeacherSearchAndRender();
}

function applyTeacherSearchAndRender() {
    renderTeacherRows(AdminState.filteredTeachers);
    renderAdminServerPager('teacherPager', AdminState.teacherPage, ADMIN_PAGE_SIZE, AdminState.teacherTotal, loadTeachers);
    updateAdminListHint('teacherListHint', AdminState.teacherTotal);
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
    if (!uiConfirm('确定要删除该导师申请吗？')) return;

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
async function loadAssets(page) {
    page = page || 1;
    try {
        const params = adminSearchParams(page, ADMIN_PAGE_SIZE);
        adminKeywordParams(params, 'assetSearchInput');
        const response = await apiFetch('/api/admin/assets?' + params.toString());
        const result = await response.json();

        if (result.code === 200) {
            renderAssetList(result.data, (result.extra && result.extra.pagination) || null);
        } else if (result.code === 403) {
            console.error('无权限访问成果列表');
        } else {
            console.error('获取成果列表失败:', result.message);
        }
    } catch (error) {
        console.error('获取成果列表异常:', error);
    }
}

function renderAssetList(assets, pagination) {
    const rows = Array.isArray(assets) ? assets : [];
    rows.forEach(asset => AdminState.assetsById.set(String(asset.id), asset));
    AdminState.allAssets = rows.slice();
    AdminState.filteredAssets = rows.slice();
    AdminState.assetPage = pagination ? (pagination.page || 1) : 1;
    AdminState.assetTotal = pagination ? (pagination.total || rows.length) : rows.length;
    applyAssetSearchAndRender();
}

function applyAssetSearchAndRender() {
    renderAssetRows(AdminState.filteredAssets);
    renderAdminServerPager('assetPager', AdminState.assetPage, ADMIN_PAGE_SIZE, AdminState.assetTotal, loadAssets);
    updateAdminListHint('assetListHint', AdminState.assetTotal);
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
                    <button class="btn ${asset.isPinned == 1 ? 'btn-secondary' : 'btn-outline-secondary'} btn-sm" title="${asset.isPinned == 1 ? '已置顶，点击取消' : '点击置顶，列表所有排序下排最前'}" onclick="toggleAssetPinned(${asset.id}, ${asset.isPinned == 1 ? 'false' : 'true'})">置顶</button>
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
    // 解码统一源码标记，回填可读的 Markdown/纯文本。
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
        // 重新包装为统一源码标记 + 安全 HTML，保证公开成果页与预览一致。
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
    openDeleteReasonModal('删除成果', async function (reason) {
        try {
            const response = await apiFetch(`/api/admin/asset/${encodeURIComponent(String(assetId))}?reason=` + encodeURIComponent(reason), {
                method: 'DELETE'
            });
            const result = await response.json();

            if (result.code === 200) {
                showMessage('删除成功，已通知所有者', 'success');
                loadAssets();
                loadDashboardData();
            } else {
                showMessage('删除失败: ' + result.message, 'error');
            }
        } catch (error) {
            console.error('删除成果异常:', error);
            showMessage('删除失败，请稍后重试', 'error');
        }
    });
}

const COMMUNITY_CATEGORY_LABELS = {
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

async function loadCommunityPosts(page) {
    page = page || 1;
    try {
        const params = adminSearchParams(page, ADMIN_COMMUNITY_PAGE_SIZE);
        adminKeywordParams(params, 'communitySearchInput');
        const response = await apiFetch('/api/admin/community-posts?' + params.toString());
        const result = await response.json();

        if (result.code === 200) {
            renderCommunityPostList(result.data, (result.extra && result.extra.pagination) || null);
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

function renderCommunityPostList(posts, pagination) {
    const rows = Array.isArray(posts) ? posts : [];
    rows.forEach(post => AdminState.communityPostsById.set(String(post.id), post));
    AdminState.allCommunityPosts = rows.slice();
    AdminState.filteredCommunityPosts = rows.slice();
    AdminState.communityPostPage = pagination ? (pagination.page || 1) : 1;
    AdminState.communityPostTotal = pagination ? (pagination.total || rows.length) : rows.length;
    applyCommunityPostSearchAndRender();
}

function applyCommunityPostSearchAndRender() {
    renderCommunityPostRows(AdminState.filteredCommunityPosts);
    renderAdminServerPager('communityPager', AdminState.communityPostPage, ADMIN_COMMUNITY_PAGE_SIZE, AdminState.communityPostTotal, loadCommunityPosts);
    updateAdminListHint('communityListHint', AdminState.communityPostTotal);
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
                    <button class="btn ${post.isPinned == 1 ? 'btn-secondary' : 'btn-outline-secondary'} btn-sm" title="${post.isPinned == 1 ? '已置顶，点击取消' : '点击置顶，列表所有排序下排最前'}" onclick="toggleCommunityPostPinned(${post.id}, ${post.isPinned == 1 ? 'false' : 'true'})">置顶</button>
                    <button class="btn btn-danger btn-sm" onclick="deleteCommunityPost(${post.id})">删除</button>
                `
            }
        ]);
        tbody.appendChild(tr);
    });
}

async function toggleCommunityPostPinned(postId, pinned) {
    if (!uiConfirm(pinned ? '确认置顶该帖子？置顶帖在社区列表所有排序下都排在最前。' : '确认取消置顶该帖子？')) {
        return;
    }
    try {
        const response = await apiFetch(`/api/admin/community-post/${encodeURIComponent(String(postId))}/pinned`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinned: pinned })
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage(pinned ? '已置顶' : '已取消置顶', 'success');
            loadCommunityPosts();
        } else {
            showMessage(result.message || '操作失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('操作失败，请稍后重试', 'error');
    }
}

async function toggleAssetPinned(assetId, pinned) {
    if (!uiConfirm(pinned ? '确认置顶该成果？置顶成果在成果列表所有排序下都排在最前。' : '确认取消置顶该成果？')) {
        return;
    }
    try {
        const response = await apiFetch(`/api/admin/asset/${encodeURIComponent(String(assetId))}/pinned`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinned: pinned })
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage(pinned ? '已置顶' : '已取消置顶', 'success');
            loadAssets();
        } else {
            showMessage(result.message || '操作失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('操作失败，请稍后重试', 'error');
    }
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
    document.getElementById('adminEditPostContent').value = editableRichText(post.content);
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
        // 重新包装为统一源码标记 + 安全 HTML，保证公开帖子页与预览一致。
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
    openDeleteReasonModal('删除社区帖子', async function (reason) {
        try {
            const response = await apiFetch(`/api/admin/community-post/${encodeURIComponent(String(postId))}?reason=` + encodeURIComponent(reason), {
                method: 'DELETE'
            });
            const result = await response.json();

            if (result.code === 200) {
                showMessage('删除成功，已通知作者', 'success');
                loadCommunityPosts();
                loadDashboardData();
            } else {
                showMessage('删除失败: ' + result.message, 'error');
            }
        } catch (error) {
            console.error('删除社区帖子异常:', error);
            showMessage('删除失败，请稍后重试', 'error');
        }
    });
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
        const params = new URLSearchParams();
        const kw = document.getElementById('competitionSearchInput')?.value.trim();
        if (kw) params.set('keyword', kw);
        const status = adminSelectValue('competitionStatusFilter');
        if (status) params.set('status', status);
        const track = adminSelectValue('competitionTrackFilter');
        if (track) params.set('track', track);
        const qs = params.toString();
        const response = await apiFetch('/api/admin/competitions' + (qs ? '?' + qs : ''));
        const result = await response.json();
        if (result.code !== 200) throw new Error(result.message || '获取竞赛目录失败');
        AdminState.allCompetitions = Array.isArray(result.data) ? result.data.slice() : [];
        AdminState.allCompetitions.forEach(item =>
            AdminState.competitionsById.set(String(item.id), item));
        applyCompetitionSearchAndRender();
    } catch (error) {
        console.error('获取竞赛目录异常:', error);
        const tbody = document.getElementById('competitionTableBody');
        if (tbody) renderEmptyRow(tbody, 5, '竞赛目录加载失败');
    }
}

const COMPETITION_PAGE_SIZE = 10;

// ==================== 审计日志（A6：此前只写不读） ====================

const ADMIN_AUDIT_ACTION_LABELS = {
    CREATE: '新增', UPDATE: '更新', DELETE: '删除', APPROVE: '审批',
    PIN: '置顶', UNPIN: '取消置顶'
};

const ADMIN_AUDIT_TARGET_LABELS = {
    USER: '用户', TEAM: '队伍', TEACHER_PROFILE: '导师', ASSET: '成果',
    COMMUNITY_POST: '帖子', COMPETITION: '竞赛', IDENTITY: '身份'
};

async function loadAuditLogs(page) {
    page = page || 1;
    try {
        const params = adminSearchParams(page, ADMIN_PAGE_SIZE);
        adminKeywordParams(params, 'auditSearchInput');
        const action = adminSelectValue('auditActionFilter');
        if (action) params.set('action', action);
        const response = await apiFetch('/api/admin/audit-logs?' + params.toString());
        const result = await response.json();
        if (result.code !== 200) {
            if (result.code === 403) {
                console.error('无权限访问审计日志');
            } else {
                console.error('获取审计日志失败:', result.message);
            }
            return;
        }
        const rows = Array.isArray(result.data) ? result.data : [];
        const pagination = (result.extra && result.extra.pagination) || {};
        renderAuditRows(rows);
        AdminState.auditPage = pagination.page || 1;
        AdminState.auditTotal = pagination.total || rows.length;
        renderAdminServerPager('auditPager', AdminState.auditPage, ADMIN_PAGE_SIZE, AdminState.auditTotal, loadAuditLogs);
        updateAdminListHint('auditListHint', AdminState.auditTotal);
    } catch (error) {
        console.error('获取审计日志异常:', error);
    }
}

function renderAuditRows(rows) {
    const tbody = document.getElementById('auditTableBody');
    if (!tbody) return;
    tbody.innerHTML = '';
    if (!rows.length) {
        renderEmptyRow(tbody, 6, '暂无审计记录');
        return;
    }
    rows.forEach(row => {
        const tr = document.createElement('tr');
        const actionLabel = ADMIN_AUDIT_ACTION_LABELS[String(row.action || '').toUpperCase()] || row.action || '';
        const targetLabel = ADMIN_AUDIT_TARGET_LABELS[String(row.targetType || '').toUpperCase()] || row.targetType || '';
        const target = targetLabel + (row.targetId ? ' #' + row.targetId : '');
        setTableRowCells(tr, [
            { label: '时间', html: escapeHtml(formatTime(row.createdAt)) },
            { label: '操作人', html: escapeHtml(row.adminName || ('用户#' + row.adminUserId)) },
            { label: '动作', html: escapeHtml(actionLabel) },
            { label: '对象', html: escapeHtml(target) },
            { label: '详情', html: renderUserCell(row.details) },
            { label: 'IP', html: escapeHtml(row.ipAddress || '') }
        ]);
        tbody.appendChild(tr);
    });
}

function applyCompetitionSearchAndRender() {
    // 关键词 / 状态 / 赛道筛选已由服务端完成，这里只做本地分页展示
    AdminState.filteredCompetitions = AdminState.allCompetitions.slice();
    const total = AdminState.filteredCompetitions.length;
    const totalPages = Math.max(1, Math.ceil(total / COMPETITION_PAGE_SIZE));
    if (AdminState.competitionPage > totalPages) AdminState.competitionPage = totalPages;
    const start = (AdminState.competitionPage - 1) * COMPETITION_PAGE_SIZE;
    renderCompetitionAdminRows(AdminState.filteredCompetitions.slice(start, start + COMPETITION_PAGE_SIZE));
    renderCompetitionPager(total, totalPages);
    const hint = document.getElementById('competitionListHint');
    if (hint) hint.textContent = '共 ' + total + ' 条 · 第 ' + AdminState.competitionPage + '/' + totalPages + ' 页';
}

function renderCompetitionPager(total, totalPages) {
    const pager = document.getElementById('competitionPager');
    const inner = document.getElementById('competitionPagerInner');
    if (!pager || !inner) return;
    if (totalPages <= 1) {
        pager.classList.add('d-none');
        return;
    }
    pager.classList.remove('d-none');
    inner.innerHTML = '';
    const mk = (label, page, disabled, active) => {
        const li = document.createElement('li');
        li.className = 'page-item' + (disabled ? ' disabled' : '') + (active ? ' active' : '');
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'page-link';
        btn.textContent = label;
        if (!disabled && !active) btn.addEventListener('click', () => gotoCompetitionPage(page));
        li.appendChild(btn);
        return li;
    };
    inner.appendChild(mk('上一页', AdminState.competitionPage - 1, AdminState.competitionPage <= 1, false));
    for (let i = 1; i <= totalPages; i++) inner.appendChild(mk(String(i), i, false, i === AdminState.competitionPage));
    inner.appendChild(mk('下一页', AdminState.competitionPage + 1, AdminState.competitionPage >= totalPages, false));
}

function gotoCompetitionPage(page) {
    AdminState.competitionPage = Math.max(1, page);
    applyCompetitionSearchAndRender();
    document.getElementById('competitionTableBody')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
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
    if (!uiConfirm('确定删除这条竞赛目录记录吗？')) return;
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
