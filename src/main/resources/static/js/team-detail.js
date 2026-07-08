const urlParams = new URLSearchParams(window.location.search);
const teamId = urlParams.get('id');
const teamIdNum = teamId ? parseInt(teamId, 10) : null;
let currentTeamData = null;
/** 当前登录用户是否为本队队长，由 checkCreatorAccess 设定 */
let teamDetailCurrentUserId = null;
let isCurrentUserCreator = false;

function teamStatusLabel(status) {
    const map = { OPEN: '招募中', TEAMING: '组队中', CLOSED: '已结束' };
    return map[status] || status || '未知';
}

function normalizeDateInput(value) {
    if (!value) return '';
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return String(value).slice(0, 10);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function categoryLabel(competitionId) {
    const map = { 1: '技术开发', 2: '创意设计', 3: '市场营销', 4: '学术研究' };
    return map[competitionId] || '未分类';
}

function categoryId(value) {
    const map = { 技术开发: 1, 创意设计: 2, 市场营销: 3, 学术研究: 4, 竞赛协作: null };
    return map[value] || null;
}

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
    if (!teamIdNum) {
        showMessage('缺少团队ID参数', 'error');
        setTimeout(() => window.location.href = '/team-market.html', 1000);
        return;
    }
    try {
        const team = await request(`/team/${teamIdNum}`);
        renderTeamDetail(team);
        await checkApplicationStatus(teamIdNum);
        await checkCreatorAccess(team);
    } catch (error) {
        console.error('获取团队详情失败:', error);
        setTimeout(() => window.location.href = '/team-market.html', 1000);
    }
    document.getElementById('backBtn')?.addEventListener('click', function() {
        window.location.href = '/team-market.html';
    });
    bindApplyButton();
    bindApplyModal();
    bindApproveModalActions();
    bindEditMode();
    bindTeamStatusActions();
});

async function checkCreatorAccess(teamData) {
    try {
        const currentUser = await apiFetch('/api/user/profile');
        const result = await currentUser.json();
        if (result.code !== 200 || !result.data || !teamData.creatorId) return;
        teamDetailCurrentUserId = Number(result.data.id);
        isCurrentUserCreator = teamDetailCurrentUserId === Number(teamData.creatorId);
        if (!isCurrentUserCreator) return;

        // 队长：隐藏"申请加入"按钮
        document.getElementById('applyBtn')?.classList.add('d-none');

        // 队长：显示管理按钮（根据状态控制）
        document.getElementById('editTeamBtn')?.classList.toggle('d-none', teamData.status !== 'OPEN');
        document.getElementById('workspaceBtn')?.classList.toggle('d-none', teamData.status !== 'TEAMING');
        document.getElementById('markTeamingBtn')?.classList.toggle('d-none', teamData.status !== 'OPEN');
        document.getElementById('closeTeamBtn')?.classList.toggle('d-none', !(teamData.status === 'OPEN' || teamData.status === 'TEAMING'));

        // 队长且队伍 OPEN 时加载待审批列表
        if (teamData.status === 'OPEN') {
            loadPendingApplications();
        }
    } catch (e) {
        console.warn('检查创建者权限失败', e);
    }
}

// ============================================================
// 待审批申请列表
// ============================================================
async function loadPendingApplications() {
    try {
        const data = await request('/team/my/pending-applications');
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
    countBadge.textContent = apps.length;
    if (apps.length === 0) {
        list.innerHTML = '<div class="pending-list__empty">暂无待审批的申请</div>';
        return;
    }
    list.innerHTML = apps.map(function(app) {
        var name = escapeHtml(app.applicantName || '申请人');
        var major = app.applicantMajor ? escapeHtml(app.applicantMajor) : '';
        var grade = app.applicantGrade ? escapeHtml(app.applicantGrade) : '';
        var school = app.applicantSchool ? escapeHtml(app.applicantSchool) : '';
        var subtitle = [major, grade, school].filter(Boolean).join(' · ');
        var avatarHtml = renderAvatar(name, app.applicantAvatar);
        var skillsHtml = '';
        if (Array.isArray(app.skills) && app.skills.length > 0) {
            skillsHtml = '<div class="pending-skill-list">' +
                app.skills.map(function(s) { return renderSkillTag(s); }).join('') +
                '</div>';
        }
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
            skillsHtml +
            '<div class="pending-card__footer">' +
                '<span class="pending-card__date">申请于 ' + dateStr + '</span>' +
                '<div class="pending-card__actions">' +
                    '<button type="button" class="il-btn il-btn--sm il-btn--ghost" onclick="openProfileOverlay(' + (app.applicantUserId || 0) + ')">查看完整资料</button>' +
                    '<button type="button" class="il-btn il-btn--sm il-btn--primary" onclick="openApproveModal(' + app.id + ', \'' + name + '\')">✓ 通过</button>' +
                    '<button type="button" class="il-btn il-btn--sm il-btn--danger" onclick="openRejectModal(' + app.id + ', \'' + name + '\')">✗ 拒绝</button>' +
                '</div>' +
            '</div>' +
        '</div>';
    }).join('');
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
            await request('/team/join', {
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
            await request('/team/application/' + currentApproveAppId + '/approve', {
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

        var name = escapeHtml(user.username || '用户');
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
                var stars = '';
                var level = Number(s.skillLevel) || 0;
                for (var i = 0; i < 5; i++) stars += i < level ? '★' : '☆';
                var cat = s.skillCategory ? '<span class="skill-cat">' + escapeHtml(s.skillCategory) + '</span>' : '';
                var exp = s.yearsExperience ? ' · ' + s.yearsExperience + '年' : '';
                return '<div class="profile-skill-item">' +
                    '<span class="skill-name">' + escapeHtml(s.skillName) + '</span>' +
                    '<span class="skill-stars">' + stars + '</span>' +
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

        // 近期发布
        var postsHtml = '';
        if (Array.isArray(user.publishedPosts) && user.publishedPosts.length > 0) {
            postsHtml = '<div class="profile-section"><h6>近期发布</h6>' +
                user.publishedPosts.slice(0, 5).map(function(p) {
                    var title = escapeHtml(p.title || '无标题');
                    var date = p.createdAt ? formatCompactDate(p.createdAt) : '';
                    return '<div class="profile-post-item"><a href="/community.html" class="text-decoration-none">' + title + '</a><span class="text-muted small ms-2">' + date + '</span></div>';
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
    document.getElementById('editCategory').value = categoryLabel(currentTeamData.competitionId);
    document.getElementById('editMemberCount').value = currentTeamData.requiredMemberCount || '';
    document.getElementById('editDeadline').value = normalizeDateInput(currentTeamData.deadline);
}

async function saveTeamEdit() {
    const title = document.getElementById('editTitle').value.trim();
    const description = document.getElementById('editDescription').value.trim();
    if (!title || !description) {
        showMessage('请填写必填字段', 'warning');
        return;
    }
    const memberValue = document.getElementById('editMemberCount').value;
    try {
        await request(`/team/${teamIdNum}`, {
            method: 'PUT',
            body: JSON.stringify({
                title,
                description,
                competitionId: categoryId(document.getElementById('editCategory').value),
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
        updateTeamStatus('TEAMING', '确认将该组队标记为组队中？');
    });
    document.getElementById('closeTeamBtn')?.addEventListener('click', function() {
        updateTeamStatus('CLOSED', '确认结束该项目？');
    });
}

async function updateTeamStatus(status, confirmText) {
    if (!confirm(confirmText)) return;
    await request(`/team/${teamIdNum}/status`, {
        method: 'PUT',
        body: JSON.stringify({ status })
    });
    showMessage('状态已更新', 'success');
    setTimeout(() => location.reload(), 600);
}

function renderTeamDetail(team) {
    currentTeamData = team || {};
    document.getElementById('teamTitle').textContent = team.title || '未命名团队';
    renderPublisher(team);
    document.getElementById('teamCategory').textContent = categoryLabel(team.competitionId);
    document.getElementById('teamStatus').textContent = teamStatusLabel(team.status);
    document.getElementById('teamMemberCount').textContent = team.requiredMemberCount ? `${team.requiredMemberCount}人` : '待定';
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
        const name = preview.username || preview.realName || '用户';
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
        const name = escapeHtml(member.username || '成员');
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
        return '<div class="team-member">' +
            '<div class="team-member__avatar">' + avatarHtml + '</div>' +
            '<div class="team-member__info">' +
                '<span class="team-member__name">' + name + '</span>' +
                major +
            '</div>' +
            '<span class="team-member__role ' + roleClass + '">' + role + '</span>' +
        '</div>';
    }).join('');
}

async function checkApplicationStatus(teamId) {
    if (currentTeamData && currentTeamData.status !== 'OPEN') return;
    try {
        const data = await request(`/team/application-status?teamId=${teamId}`);
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
        console.error('检查申请状态失败:', error);
    }
}
