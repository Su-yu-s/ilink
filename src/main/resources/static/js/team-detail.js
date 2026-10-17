const urlParams = new URLSearchParams(window.location.search);
const teamId = (urlParams.get('id') || '').trim();
const teamIdNum = /^\d+$/.test(teamId) && Number(teamId) > 0 ? Number(teamId) : null;
let currentTeamData = null;
/** 当前登录用户是否为本队队长，由 checkCreatorAccess 设定 */
let teamDetailCurrentUserId = null;
let isCurrentUserCreator = false;

async function teamApiRequest(path, options) {
    const requestOptions = { ...(options || {}) };
    const headers = { ...(requestOptions.headers || {}) };
    if (requestOptions.body && !(requestOptions.body instanceof FormData) && !headers['Content-Type']) {
        headers['Content-Type'] = 'application/json';
    }
    requestOptions.headers = headers;

    const url = path.startsWith('/api/') ? path : '/api' + path;
    const response = await apiFetch(url, requestOptions);
    let payload;
    try {
        payload = await response.json();
    } catch (error) {
        throw new Error('服务器返回了无法解析的数据，请稍后重试');
    }

    if (!response.ok || !payload || (payload.code !== 200 && payload.code !== 0)) {
        const requestError = new Error((payload && payload.message) || `请求失败（${response.status}）`);
        requestError.status = response.status;
        requestError.code = payload && payload.code;
        throw requestError;
    }
    return payload.data;
}

function setTeamDetailState(type, message) {
    const state = document.getElementById('teamDetailState');
    const content = document.getElementById('teamDetailContent');
    const title = document.getElementById('teamDetailStateTitle');
    const messageEl = document.getElementById('teamDetailStateMessage');
    const retry = document.getElementById('teamDetailRetryBtn');
    const indicator = state && state.querySelector('.detail-load-state__indicator');
    const success = type === 'success';
    const loading = type === 'loading';

    if (state) {
        state.hidden = success;
        state.classList.toggle('detail-load-state--error', type === 'error');
        state.setAttribute('aria-busy', loading ? 'true' : 'false');
    }
    if (content) content.hidden = !success;
    if (indicator) indicator.hidden = !loading;
    if (retry) retry.hidden = loading || success;
    if (title) title.textContent = loading ? '正在加载组队信息' : '组队信息暂时无法显示';
    if (messageEl) {
        messageEl.textContent = message || (loading ? '正在连接团队服务，请稍候…' : '请检查网络后重新加载。');
    }
}

async function loadTeamDetail() {
    setTeamDetailState('loading');
    try {
        const team = await teamApiRequest(`/team/${teamIdNum}`);
        if (!team || typeof team !== 'object') {
            throw new Error('组队数据为空，请稍后重试');
        }
        renderTeamDetail(team);
        setTeamDetailState('success');
        await Promise.allSettled([
            checkApplicationStatus(teamIdNum),
            checkCreatorAccess(team)
        ]);
    } catch (error) {
        console.error('获取团队详情失败:', error);
        setTeamDetailState('error', error && error.message ? error.message : '网络异常，请稍后重试。');
    }
}

function teamStatusLabel(status) {
    const map = { OPEN: '招募中', TEAMING: '已组队', CLOSED: '已结束' };
    return map[status] || status || '未知';
}

function renderTeamStatus(status) {
    const node = document.getElementById('teamStatus');
    if (!node) return;
    const normalized = String(status || '').toUpperCase();
    node.textContent = teamStatusLabel(normalized);
    node.className = 'team-detail-status ' + (normalized === 'OPEN' ? 'is-open' : 'is-neutral');
}

function normalizeDateInput(value) {
    if (!value) return '';
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return String(value).slice(0, 10);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 编辑面板的参赛竞赛控件：目录选择与自填互斥 */
const editCompetition = (function () {
    const searchInput = document.getElementById('editCompetitionSearch');
    if (!searchInput) return null;
    const menu = document.getElementById('editCompetitionMenu');
    const idInput = document.getElementById('editCompetitionId');
    const customInput = document.getElementById('editCustomCompetitionInput');
    const toggleBtn = document.getElementById('editToggleCustomCompetition');

    CompetitionPicker.create({
        input: searchInput,
        menu: menu,
        onSelect: function (comp) {
            idInput.value = comp.id;
            customInput.value = '';
        }
    });

    toggleBtn.addEventListener('click', function () {
        const toCustom = customInput.hidden;
        customInput.hidden = !toCustom;
        searchInput.style.display = toCustom ? 'none' : '';
        menu.hidden = true;
        toggleBtn.textContent = toCustom ? '从竞赛目录选择' : '目录里没有？自行填写';
        if (toCustom) {
            idInput.value = '';
        } else {
            customInput.value = '';
        }
    });

    /** 进入编辑模式时回填：目录竞赛显示名称，自填显示原文 */
    function fill(team) {
        if (team.competitionId) {
            idInput.value = team.competitionId;
            searchInput.value = team.competitionName || '';
            customInput.value = '';
            customInput.hidden = true;
            searchInput.style.display = '';
            toggleBtn.textContent = '目录里没有？自行填写';
        } else if (team.customCompetition) {
            idInput.value = '';
            searchInput.value = '';
            customInput.value = team.customCompetition;
            customInput.hidden = false;
            searchInput.style.display = 'none';
            toggleBtn.textContent = '从竞赛目录选择';
        } else {
            // 存量队伍尚未补竞赛：留在目录选择模式等队长补填
            idInput.value = '';
            searchInput.value = '';
            customInput.value = '';
            customInput.hidden = true;
            searchInput.style.display = '';
            toggleBtn.textContent = '目录里没有？自行填写';
        }
    }

    function values() {
        const id = idInput.value ? parseInt(idInput.value, 10) : null;
        return { competitionId: id, customCompetition: id ? '' : customInput.value.trim() };
    }

    return { fill: fill, values: values };
})();

function formatTime(dateStr) {
    if (!dateStr) return '';
    var d = new Date(dateStr);
    if (Number.isNaN(d.getTime())) return String(dateStr);
    var month = String(d.getMonth() + 1).padStart(2, '0');
    var day = String(d.getDate()).padStart(2, '0');
    var hour = String(d.getHours()).padStart(2, '0');
    var min = String(d.getMinutes()).padStart(2, '0');
    return d.getFullYear() + '-' + month + '-' + day + ' ' + hour + ':' + min;
}

function formatCompactDate(dateStr) {
    if (!dateStr) return '';
    var d = new Date(dateStr);
    if (Number.isNaN(d.getTime())) return String(dateStr);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

document.addEventListener('DOMContentLoaded', async function() {
    document.getElementById('teamDetailRetryBtn')?.addEventListener('click', loadTeamDetail);
    bindApplyButton();
    bindApplyModal();
    bindApproveModalActions();
    bindEditMode();
    bindTeamStatusActions();
    bindWorkspaceButton();

    if (!teamIdNum) {
        setTeamDetailState('error', '链接中缺少有效的团队 ID，请返回组队大厅重新选择。');
        return;
    }
    await loadTeamDetail();
});

async function checkCreatorAccess(teamData) {
    try {
        // 匿名访客由 loadCurrentUserOnce 直接返回 null，不再产生 401 请求（核实报告 P2-6）；
        // 登录态下多次调用共享同一次请求（本页此前会重复拉两次资料）
        const data = await loadCurrentUserOnce();
        if (!data || !teamData.creatorId) return;
        teamDetailCurrentUserId = Number(data.id);
        isCurrentUserCreator = teamDetailCurrentUserId === Number(teamData.creatorId);

        // 判断是否为队员
        const members = Array.isArray(teamData.members) ? teamData.members : [];
        const isMember = members.some(m => Number(m.userId || m.id) === teamDetailCurrentUserId);
        // 队长双保险：创建者，或成员列表中被标记为 LEADER/队长 的人（避免 creatorId 与成员角色不一致）
        const isLeaderMember = members.some(m =>
            Number(m.userId || m.id) === teamDetailCurrentUserId &&
            (String(m.role || '').toUpperCase() === 'LEADER' || m.role === '队长')
        );
        isCurrentUserCreator = isCurrentUserCreator || isLeaderMember;

        if (isCurrentUserCreator) {
            // 队长：隐藏"申请加入"，显示管理按钮（根据状态控制）
            document.getElementById('applyBtn')?.classList.add('d-none');
            document.getElementById('editTeamBtn')?.classList.toggle('d-none', teamData.status !== 'OPEN');
            document.getElementById('workspaceBtn')?.classList.toggle('d-none', teamData.status !== 'TEAMING');
            document.getElementById('markTeamingBtn')?.classList.toggle('d-none', teamData.status !== 'OPEN');
            document.getElementById('closeTeamBtn')?.classList.toggle('d-none', !(teamData.status === 'OPEN' || teamData.status === 'TEAMING'));

            // 队长且队伍 OPEN 时加载待审批列表
            if (teamData.status === 'OPEN') {
                loadPendingApplications();
            }
        } else if (isMember) {
            // 队员：只显示"返回组队大厅"，隐藏其他所有按钮
            document.getElementById('applyBtn')?.classList.add('d-none');
            document.getElementById('editTeamBtn')?.classList.add('d-none');
            document.getElementById('workspaceBtn')?.classList.add('d-none');
            document.getElementById('markTeamingBtn')?.classList.add('d-none');
            document.getElementById('closeTeamBtn')?.classList.add('d-none');
        }
        // 路人（非队长非队员）：保留默认显示（"申请加入" + "返回组队大厅"）
    } catch (e) {
        console.warn('检查创建者权限失败', e);
    }
}

// ============================================================
// 待审批申请列表
// ============================================================
async function loadPendingApplications() {
    try {
        const data = await teamApiRequest('/team/my/pending-applications');
        const apps = Array.isArray(data) ? data : [];
        // 只显示本队伍的待审批
        const teamApps = apps.filter(function(a) { return Number(a.teamId) === teamIdNum; });
        renderPendingApplications(teamApps);
    } catch (e) {
        console.error('加载待审批列表失败', e);
    }
}

function renderPendingApplications(apps) {
    const section = document.getElementById('pendingSection');
    const list = document.getElementById('pendingList');
    const countBadge = document.getElementById('pendingCount');
    if (!section || !list) return;
    section.classList.remove('d-none');
    // 修复：区分「待审批申请」（对方主动申请）与「已发出的邀请」（等待对方确认）。
    // 邀请行不能给通过/拒绝按钮——那是对方的决定权。
    var applications = apps.filter(function(a) { return !a.invitedByMe; });
    var invitations = apps.filter(function(a) { return a.invitedByMe; });
    countBadge.textContent = applications.length;
    if (apps.length === 0) {
        list.innerHTML = '<div class="pending-list__empty">暂无待审批的申请</div>';
        return;
    }
    list.innerHTML = (invitations.length
        ? '<div class="pending-group-title">已发出的邀请 · 等待对方确认（' + invitations.length + '）</div>' + invitations.map(function(app) {
        var name = escapeHtml(app.applicantName || '申请人');
        var major = app.applicantMajor ? escapeHtml(app.applicantMajor) : '';
        var grade = app.applicantGrade ? escapeHtml(app.applicantGrade) : '';
        var school = app.applicantSchool ? escapeHtml(app.applicantSchool) : '';
        var subtitle = [major, grade, school].filter(Boolean).join(' · ');
        var avatarHtml = renderAvatar(name, app.applicantAvatar);
        var dateStr = app.createdAt ? formatCompactDate(app.createdAt) : '';
        return '<div class="pending-card" data-app-id="' + app.id + '" data-user-id="' + (app.applicantUserId || '') + '">' +
            '<div class="pending-card__header">' +
                '<div class="pending-card__avatar">' + avatarHtml + '</div>' +
                '<div class="pending-card__info">' +
                    '<div class="pending-card__name">' + name + '</div>' +
                    (subtitle ? '<div class="pending-card__subtitle">' + subtitle + '</div>' : '') +
                '</div>' +
            '</div>' +
            '<div class="pending-card__footer">' +
                '<span class="pending-card__date">邀请于 ' + dateStr + ' · 等待对方确认</span>' +
                '<div class="pending-card__actions">' +
                    '<button type="button" class="il-btn il-btn--sm il-btn--ghost" onclick="openProfileOverlay(' + (app.applicantUserId || 0) + ')">查看完整资料</button>' +
                '</div>' +
            '</div>' +
        '</div>';
    }).join('') : '') +
    (applications.length
        ? (invitations.length ? '<div class="pending-group-title">待审批申请（' + applications.length + '）</div>' : '')
        + applications.map(function(app) {
        var name = escapeHtml(app.applicantName || '申请人');
        var major = app.applicantMajor ? escapeHtml(app.applicantMajor) : '';
        var grade = app.applicantGrade ? escapeHtml(app.applicantGrade) : '';
        var school = app.applicantSchool ? escapeHtml(app.applicantSchool) : '';
        var subtitle = [major, grade, school].filter(Boolean).join(' · ');
        var avatarHtml = renderAvatar(name, app.applicantAvatar);
        // 技能标签不再上卡：与"查看完整资料"弹窗重复，卡片保持轻量
        var dateStr = app.createdAt ? formatCompactDate(app.createdAt) : '';

        return '<div class="pending-card" data-app-id="' + app.id + '" data-user-id="' + (app.applicantUserId || '') + '">' +
            '<div class="pending-card__header">' +
                '<div class="pending-card__avatar">' + avatarHtml + '</div>' +
                '<div class="pending-card__info">' +
                    '<div class="pending-card__name">' + name + '</div>' +
                    (subtitle ? '<div class="pending-card__subtitle">' + subtitle + '</div>' : '') +
                '</div>' +
            '</div>' +
            (app.message ? '<div class="pending-card__message">' + escapeHtml(app.message) + '</div>' : '') +
            '<div class="pending-card__footer">' +
                '<span class="pending-card__date">申请于 ' + dateStr + '</span>' +
                '<div class="pending-card__actions">' +
                    '<button type="button" class="il-btn il-btn--sm il-btn--ghost" onclick="openProfileOverlay(' + (app.applicantUserId || 0) + ')">查看完整资料</button>' +
                    '<button type="button" class="il-btn il-btn--sm il-btn--primary" onclick="openApproveModal(' + app.id + ', \'' + name + '\')">通过</button>' +
                    '<button type="button" class="il-btn il-btn--sm il-btn--danger" onclick="openRejectModal(' + app.id + ', \'' + name + '\')">拒绝</button>' +
                '</div>' +
            '</div>' +
        '</div>';
    }).join('') : '');
}

function renderAvatar(name, avatar) {
    if (avatar && String(avatar).trim()) {
        return '<img class="pending-avatar-img" src="' + escapeHtml(avatar) + '" alt="" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\';">' +
            '<span class="pending-avatar-fallback" style="display:none">' + escapeHtml(name.charAt(0)) + '</span>';
    }
    return '<span class="pending-avatar-fallback">' + escapeHtml(name.charAt(0)) + '</span>';
}

function renderSkillTag(skill) {
    if (!skill || !skill.name) return '';
    var stars = '';
    var level = Number(skill.level) || 0;
    for (var i = 0; i < 5; i++) {
        stars += i < level ? '★' : '☆';
    }
    var category = skill.category ? '<span class="skill-cat">' + escapeHtml(skill.category) + '</span>' : '';
    return '<span class="skill-tag">' +
        '<span class="skill-name">' + escapeHtml(skill.name) + '</span>' +
        '<span class="skill-stars">' + stars + '</span>' +
        category +
    '</span>';
}

// ============================================================
// 申请加入弹窗
// ============================================================
function bindApplyButton() {
    const applyBtn = document.getElementById('applyBtn');
    applyBtn?.addEventListener('click', function() {
        if (!teamIdNum) {
            showMessage('缺少团队ID参数', 'error');
            return;
        }
        // 如果按钮是被禁用 / REJECTED 状态直提
        if (applyBtn.disabled) return;
        if (teamDetailCurrentUserId == null) {
            showMessage('请先登录后再申请加入团队', 'warning');
            redirectToLogin(700);
            return;
        }
        // 打开申请弹窗
        var teamNameEl = document.getElementById('applyTeamName');
        if (teamNameEl && currentTeamData) {
            teamNameEl.textContent = currentTeamData.title || '未知队伍';
        }
        document.getElementById('applyMessage').value = '';
        var modal = new bootstrap.Modal(document.getElementById('applyModal'));
        modal.show();
    });
}

function bindApplyModal() {
    document.getElementById('submitApplyBtn')?.addEventListener('click', async function() {
        var message = document.getElementById('applyMessage').value.trim();
        var btn = this;
        btn.disabled = true;
        btn.textContent = '提交中...';
        try {
            await teamApiRequest('/team/join', {
                method: 'POST',
                body: JSON.stringify({
                    teamId: teamIdNum,
                    message: message
                })
            });
            showMessage('申请提交成功，请等待团队创建者审核', 'success');
            var modal = bootstrap.Modal.getInstance(document.getElementById('applyModal'));
            if (modal) modal.hide();
            var applyBtn = document.getElementById('applyBtn');
            if (applyBtn) {
                applyBtn.disabled = true;
                applyBtn.textContent = '申请审核中';
            }
        } catch (e) {
            if (Number(e.status || e.code) === 401) {
                showMessage('请先登录后再提交申请', 'warning');
                redirectToLogin(700);
                return;
            }
            showMessage(e.message || '提交失败', 'error');
        } finally {
            btn.disabled = false;
            btn.textContent = '提交申请';
        }
    });
}

// ============================================================
// 审批弹窗（通过 / 拒绝）
// ============================================================
let currentApproveAppId = null;
let currentApproveIsReject = false;

function openApproveModal(appId, name) {
    currentApproveAppId = appId;
    currentApproveIsReject = false;
    document.getElementById('approveModalTitle').textContent = '通过申请';
    document.getElementById('approveModalDesc').textContent = '确认通过 ' + escapeHtml(name) + ' 的入队申请？通过后将成为正式队员。';
    document.getElementById('approveNoteLabel').textContent = '给申请人留言（选填）';
    document.getElementById('approveNote').value = '';
    document.getElementById('approveNoteHint').textContent = '';
    document.getElementById('confirmApproveBtn').textContent = '确认通过';
    document.getElementById('confirmApproveBtn').className = 'btn btn-primary';
    document.getElementById('approveNote').maxLength = 200;
    var modal = new bootstrap.Modal(document.getElementById('approveModal'));
    modal.show();
}

function openRejectModal(appId, name) {
    currentApproveAppId = appId;
    currentApproveIsReject = true;
    document.getElementById('approveModalTitle').textContent = '拒绝申请';
    document.getElementById('approveModalDesc').textContent = '确认拒绝 ' + escapeHtml(name) + ' 的入队申请？拒绝后对方可再次申请加入。';
    document.getElementById('approveNoteLabel').textContent = '拒绝理由（必填） *';
    document.getElementById('approveNote').value = '';
    document.getElementById('approveNoteHint').textContent = '至少填写 10 个字';
    document.getElementById('confirmApproveBtn').textContent = '确认拒绝';
    document.getElementById('confirmApproveBtn').className = 'btn btn-danger';
    document.getElementById('approveNote').maxLength = 200;
    // 实时校验
    document.getElementById('approveNote').oninput = function() {
        var len = this.value.trim().length;
        document.getElementById('approveNoteHint').textContent = len >= 10 ? '✓ 已满足' : '至少填写 10 个字（当前 ' + len + ' 字）';
        document.getElementById('confirmApproveBtn').disabled = len < 10;
    };
    document.getElementById('approveNote').oninput({ target: document.getElementById('approveNote') });
    var modal = new bootstrap.Modal(document.getElementById('approveModal'));
    modal.show();
}

function bindApproveModalActions() {
    document.getElementById('confirmApproveBtn')?.addEventListener('click', async function() {
        if (!currentApproveAppId) return;
        var note = document.getElementById('approveNote').value.trim();
        var action = currentApproveIsReject ? 'REJECTED' : 'APPROVED';
        var btn = this;
        btn.disabled = true;
        btn.textContent = '处理中...';
        try {
            await teamApiRequest('/team/application/' + currentApproveAppId + '/approve', {
                method: 'PUT',
                body: JSON.stringify({
                    action: action,
                    note: note || ''
                })
            });
            showMessage(currentApproveIsReject ? '已拒绝该申请' : '已通过该申请', 'success');
            var modal = bootstrap.Modal.getInstance(document.getElementById('approveModal'));
            if (modal) modal.hide();
            // 重新加载待审批列表
            await loadPendingApplications();
        } catch (e) {
            showMessage(e.message || '操作失败', 'error');
        } finally {
            btn.disabled = false;
            btn.textContent = currentApproveIsReject ? '确认拒绝' : '确认通过';
        }
    });
    // 弹窗关闭时清理 oninput 事件引用
    document.getElementById('approveModal')?.addEventListener('hidden.bs.modal', function() {
        document.getElementById('approveNote').oninput = null;
    });
}

// ============================================================
// 申请人资料浮层
// ============================================================
const profileCache = {};

async function openProfileOverlay(userId) {
    if (!userId) {
        showMessage('用户信息不存在', 'error');
        return;
    }
    var overlay = document.getElementById('profileOverlay');
    if (!overlay) return;
    var body = document.getElementById('profileOverlayBody');
    body.innerHTML = '<div class="text-center py-4"><div class="spinner-border" role="status"><span class="visually-hidden">加载中...</span></div></div>';
    // 修正：跳转到 user-profile.html（公开主页），而非 profile.html
    document.getElementById('profileFullLink').href = '/user-profile.html?id=' + userId;
    var modal = new bootstrap.Modal(overlay);
    modal.show();

    try {
        // 并行加载公开资料 + 技能
        var profilePromise = apiFetch('/api/user/public/' + userId).then(function(r) {
            var ct = r.headers.get('content-type') || '';
            if (!ct.includes('application/json')) return null;
            return r.json();
        });
        var skillsPromise = apiFetch('/api/user/skills/public/' + userId).then(function(r) {
            var ct = r.headers.get('content-type') || '';
            if (!ct.includes('application/json')) return { code: 500 };
            return r.json();
        });
        var [profileResult, skillsResult] = await Promise.all([profilePromise, skillsPromise]);

        var user = (profileResult && profileResult.code === 200) ? profileResult.data : null;
        var skills = (skillsResult && skillsResult.code === 200 && Array.isArray(skillsResult.data)) ? skillsResult.data : [];

        if (!user) {
            body.innerHTML = '<div class="text-center py-4 text-muted">用户信息不存在</div>';
            return;
        }

        var name = escapeHtml(user.displayName || user.realName || '用户');
        var major = user.major ? escapeHtml(user.major) : '';
        var grade = user.grade ? escapeHtml(user.grade) : '';
        var school = user.school ? escapeHtml(user.school) : '';
        var college = user.college ? escapeHtml(user.college) : '';
        var subtitle = [major, grade, school, college].filter(Boolean).join(' · ');

        var avatarHtml = '';
        if (user.avatar && String(user.avatar).trim()) {
            avatarHtml = '<img class="profile-overlay__avatar-img" src="' + escapeHtml(user.avatar) + '" alt="">';
        } else {
            avatarHtml = '<div class="profile-overlay__avatar-fallback">' + name.charAt(0) + '</div>';
        }

        // 技能标签
        var skillsHtml = '';
        if (skills.length > 0) {
            skillsHtml = skills.map(function(s) {
                // 五角星熟练度去掉：弹窗里无信息量，只保留技能名与方向
                var cat = s.skillCategory ? '<span class="skill-cat">' + escapeHtml(s.skillCategory) + '</span>' : '';
                var exp = s.yearsExperience ? ' · ' + s.yearsExperience + '年' : '';
                return '<div class="profile-skill-item">' +
                    '<span class="skill-name">' + escapeHtml(s.skillName) + '</span>' +
                    cat + exp +
                '</div>';
            }).join('');
        } else {
            skillsHtml = '<div class="text-muted small">暂无技能信息</div>';
        }

        // 荣誉
        var honorsHtml = '';
        if (user.honors) {
            try {
                var honorList = typeof user.honors === 'string' ? JSON.parse(user.honors) : user.honors;
                if (Array.isArray(honorList) && honorList.length > 0) {
                    honorsHtml = honorList.map(function(h) {
                        var text = typeof h === 'string' ? h : (h.name || h.title || '');
                        return '<div class="profile-honor-item">&#127942; ' + escapeHtml(text) + '</div>';
                    }).join('');
                }
            } catch (e) { /* ignore parse errors */ }
        }

        // 近期发布：序号 + 标题 + 日期右对齐，排版更整齐
        var postsHtml = '';
        if (Array.isArray(user.publishedPosts) && user.publishedPosts.length > 0) {
            postsHtml = '<div class="profile-section"><h6>近期发布</h6>' +
                user.publishedPosts.slice(0, 5).map(function(p, idx) {
                    var title = escapeHtml(p.title || '无标题');
                    var date = p.createdAt ? formatCompactDate(p.createdAt) : '';
                    var num = String(idx + 1).padStart(2, '0');
                    return '<div class="profile-post-item">' +
                        '<span class="profile-post-index">' + num + '</span>' +
                        '<a href="/community.html" class="profile-post-title text-decoration-none">' + title + '</a>' +
                        '<span class="profile-post-date">' + date + '</span>' +
                        '</div>';
                }).join('') + '</div>';
        }

        body.innerHTML =
            '<div class="profile-overlay">' +
                '<div class="profile-overlay__header">' +
                    '<div class="profile-overlay__avatar">' + avatarHtml + '</div>' +
                    '<div class="profile-overlay__info">' +
                        '<h5 class="profile-overlay__name">' + name + '</h5>' +
                        (subtitle ? '<p class="profile-overlay__subtitle">' + subtitle + '</p>' : '') +
                    '</div>' +
                '</div>' +
                (user.bio ? '<div class="profile-section"><h6>个人简介</h6><p class="profile-bio">' + escapeHtml(user.bio) + '</p></div>' : '') +
                '<div class="profile-section"><h6>技能标签</h6>' + skillsHtml + '</div>' +
                (honorsHtml ? '<div class="profile-section"><h6>荣誉与获奖</h6>' + honorsHtml + '</div>' : '') +
                postsHtml +
            '</div>';

    } catch (e) {
        console.error('加载申请人资料失败', e);
        body.innerHTML = '<div class="text-center py-4 text-muted">加载失败，请稍后重试</div>';
    }
}

// ============================================================
// 原有功能保留
// ============================================================
function bindEditMode() {
    const editBtn = document.getElementById('editTeamBtn');
    const saveBtn = document.getElementById('saveTeamBtn');
    const cancelBtn = document.getElementById('cancelEditBtn');
    const editPanel = document.getElementById('editModePanel');
    const detailMeta = document.getElementById('detailViewMeta');
    editBtn?.addEventListener('click', function() {
        fillEditForm();
        editPanel?.classList.remove('d-none');
        detailMeta?.classList.add('d-none');
    });
    cancelBtn?.addEventListener('click', function() {
        editPanel?.classList.add('d-none');
        detailMeta?.classList.remove('d-none');
    });
    saveBtn?.addEventListener('click', saveTeamEdit);
}

function fillEditForm() {
    if (!currentTeamData) return;
    document.getElementById('editTitle').value = currentTeamData.title || '';
    document.getElementById('editDescription').value = currentTeamData.description || '';
    document.getElementById('editSkills').value = currentTeamData.requiredSkills || '';
    document.getElementById('editCategory').value = currentTeamData.category || '技术开发';
    document.getElementById('editMemberCount').value = currentTeamData.requiredMemberCount || '';
    document.getElementById('editDeadline').value = normalizeDateInput(currentTeamData.deadline);
    if (editCompetition) editCompetition.fill(currentTeamData);
}

async function saveTeamEdit() {
    const title = document.getElementById('editTitle').value.trim();
    const description = document.getElementById('editDescription').value.trim();
    if (!title || !description) {
        showMessage('请填写必填字段', 'warning');
        return;
    }
    const memberValue = document.getElementById('editMemberCount').value;
    const compValues = editCompetition ? editCompetition.values() : { competitionId: null, customCompetition: '' };
    if (!compValues.competitionId && !compValues.customCompetition) {
        showMessage('请选择参赛竞赛，或点击「目录里没有？自行填写」', 'warning');
        return;
    }
    try {
        await teamApiRequest(`/team/${teamIdNum}`, {
            method: 'PUT',
            body: JSON.stringify({
                title,
                description,
                category: document.getElementById('editCategory').value,
                competitionId: compValues.competitionId,
                customCompetition: compValues.customCompetition,
                requiredSkills: document.getElementById('editSkills').value.trim(),
                requiredMemberCount: memberValue ? parseInt(memberValue, 10) : null,
                deadline: document.getElementById('editDeadline').value || null
            })
        });
        showMessage('保存成功', 'success');
        setTimeout(() => location.reload(), 600);
    } catch (error) {
        console.error('保存失败:', error);
    }
}

function bindTeamStatusActions() {
    document.getElementById('markTeamingBtn')?.addEventListener('click', function() {
        updateTeamStatus('TEAMING', '确认完成组队？');
    });
    document.getElementById('closeTeamBtn')?.addEventListener('click', function() {
        updateTeamStatus('CLOSED', '确认结束该项目？');
    });
}

function bindWorkspaceButton() {
    const workspaceBtn = document.getElementById('workspaceBtn');
    if (!workspaceBtn || workspaceBtn.dataset.bound === '1') return;
    workspaceBtn.dataset.bound = '1';
    workspaceBtn.addEventListener('click', function () {
        if (!teamIdNum) return;
        window.location.assign('/team-space.html?id=' + encodeURIComponent(String(teamIdNum)));
    });
}

async function updateTeamStatus(status, confirmText) {
    if (!uiConfirm(confirmText)) return;
    try {
        await teamApiRequest(`/team/${teamIdNum}/status`, {
            method: 'PUT',
            body: JSON.stringify({ status })
        });
        showMessage('状态已更新', 'success');
        await loadTeamDetail();
    } catch (error) {
        console.error('更新团队状态失败:', error);
        showMessage(error.message || '状态更新失败', 'error');
    }
}

function renderTeamDetail(team) {
    currentTeamData = team || {};
    document.getElementById('teamTitle').textContent = team.title || '未命名团队';
    renderPublisher(team);
    document.getElementById('teamCategory').textContent = team.category || '未分类';
    document.getElementById('teamCompetition').textContent =
        team.competitionName || team.customCompetition || '未填写';
    renderTeamStatus(team.status);
    document.getElementById('teamMemberCount').textContent = team.requiredMemberCount
        ? t('ui.teamDetail.currentMemberCount', { count: team.requiredMemberCount })
        : '待定';
    document.getElementById('teamDeadline').textContent = team.deadline ? normalizeDateInput(team.deadline) : '长期有效';
    document.getElementById('teamSkills').textContent = team.requiredSkills || '无';
    document.getElementById('teamDescription').textContent = team.description || '暂无描述';
    document.getElementById('teamCreateTime').textContent = team.createdAt ? formatTime(team.createdAt) : '未知';
    renderTeamMembers(team.members || []);
    const applyBtn = document.getElementById('applyBtn');
    if (applyBtn && team.status !== 'OPEN') {
        applyBtn.disabled = true;
        applyBtn.textContent = team.status === 'TEAMING' ? '已进入组队中' : '项目已结束';
    }
}

function renderPublisher(team) {
    const pubEl = document.getElementById('teamPublisher');
    if (!pubEl) return;
    if (typeof publisherAvatarHtml === 'function' && team.creatorPreview && team.creatorPreview.id != null) {
        const preview = team.creatorPreview;
        const name = preview.displayName || preview.realName || '用户';
        pubEl.innerHTML = `<div class="d-flex align-items-center gap-2 flex-wrap">${publisherAvatarHtml(preview)}<span class="text-body">${escapeHtml(name)}</span></div>`;
    } else {
        pubEl.textContent = team.creatorId != null ? `用户 #${team.creatorId}` : '未知';
    }
}

function renderTeamMembers(members) {
    const container = document.getElementById('teamMembers');
    if (!container) return;
    if (!Array.isArray(members) || members.length === 0) {
        container.textContent = '暂无成员';
        return;
    }
    container.innerHTML = members.map(function(member) {
        const name = escapeHtml(member.displayName || member.username || '成员');
        const role = escapeHtml(member.role || '队员');
        const isLeader = member.role === 'LEADER' || role === '队长';
        const major = member.major ? '<span class="team-member__major">' + escapeHtml(member.major) + '</span>' : '';
        var roleClass = isLeader ? 'team-member__role--leader' : 'team-member__role--member';
        var avatarHtml = '';
        if (member.avatar && String(member.avatar).trim()) {
            avatarHtml = '<img class="team-member__avatar-img" src="' + escapeHtml(member.avatar) + '" alt="" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\';">' +
                '<span class="team-member__avatar-fallback" style="display:none">' + escapeHtml(name.charAt(0)) + '</span>';
        } else {
            avatarHtml = '<span class="team-member__avatar-fallback">' + escapeHtml(name.charAt(0)) + '</span>';
        }
        // 有 userId 的成员整卡可点，进 TA 的公开主页
        var uid = member.userId != null ? Number(member.userId) : null;
        var linkAttrs = uid
            ? ' data-uid="' + uid + '" role="link" tabindex="0" title="查看 ' + name + ' 的主页"'
            : '';
        return '<div class="team-member' + (uid ? ' team-member--linkable' : '') + '"' + linkAttrs + '>' +
            '<div class="team-member__avatar">' + avatarHtml + '</div>' +
            '<div class="team-member__info">' +
                '<span class="team-member__name">' + name + '</span>' +
                major +
            '</div>' +
            '<span class="team-member__role ' + roleClass + '">' + role + '</span>' +
        '</div>';
    }).join('');
    bindMemberCardNav(container);
}

/** 成员卡跳转：事件委托挂在容器上，重渲染不丢；鼠标点、键盘 Enter/空格都能进 */
function bindMemberCardNav(container) {
    if (container.dataset.memberNavBound) return;
    container.dataset.memberNavBound = '1';
    function go(card) {
        var uid = card && card.dataset ? card.dataset.uid : null;
        if (uid) window.location.href = '/user-profile.html?id=' + encodeURIComponent(uid);
    }
    container.addEventListener('click', function (e) {
        var card = e.target.closest ? e.target.closest('.team-member[data-uid]') : null;
        if (card) go(card);
    });
    container.addEventListener('keydown', function (e) {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        var card = e.target.closest ? e.target.closest('.team-member[data-uid]') : null;
        if (card) { e.preventDefault(); go(card); }
    });
}

async function checkApplicationStatus(teamId) {
    if (currentTeamData && currentTeamData.status !== 'OPEN') return;
    try {
        const data = await teamApiRequest(`/team/application-status?teamId=${encodeURIComponent(teamId)}`);
        const status = data && data.status;
        const applyBtn = document.getElementById('applyBtn');
        if (!applyBtn) return;
        if (status === 'PENDING') {
            applyBtn.disabled = true;
            applyBtn.textContent = '申请审核中';
        } else if (status === 'APPROVED') {
            applyBtn.disabled = true;
            applyBtn.textContent = '已加入团队';
        } else if (status === 'REJECTED') {
            applyBtn.disabled = false;
            applyBtn.textContent = '申请被拒绝，重新申请';
        } else {
            applyBtn.disabled = false;
            applyBtn.textContent = '申请加入';
        }
    } catch (error) {
        if (Number(error && (error.status || error.code)) === 401) {
            const applyBtn = document.getElementById('applyBtn');
            if (applyBtn) {
                applyBtn.disabled = false;
                applyBtn.textContent = '登录后申请';
            }
            return;
        }
        console.error('检查申请状态失败:', error);
    }
}
