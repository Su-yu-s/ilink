function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

const urlParams = new URLSearchParams(window.location.search);
const teamId = urlParams.get('id');
const teamIdNum = teamId ? parseInt(teamId, 10) : null;
let currentTeamData = null;

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
    bindEditMode();
    bindTeamStatusActions();
});

async function checkCreatorAccess(teamData) {
    try {
        const currentUser = await apiFetch('/api/user/profile');
        const result = await currentUser.json();
        if (result.code !== 200 || !result.data || !teamData.creatorId) return;
        const isCreator = Number(result.data.id) === Number(teamData.creatorId);
        if (!isCreator) return;
        document.getElementById('editTeamBtn')?.classList.toggle('d-none', teamData.status !== 'OPEN');
        document.getElementById('workspaceBtn')?.classList.toggle('d-none', teamData.status !== 'TEAMING');
        document.getElementById('markTeamingBtn')?.classList.toggle('d-none', teamData.status !== 'OPEN');
        document.getElementById('closeTeamBtn')?.classList.toggle('d-none', !(teamData.status === 'OPEN' || teamData.status === 'TEAMING'));
    } catch (e) {
        console.warn('检查创建者权限失败', e);
    }
}

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
        const major = member.major ? `<span class="text-muted ms-2">${escapeHtml(member.major)}</span>` : '';
        return `<div class="d-flex align-items-center justify-content-between gap-2 border rounded px-3 py-2 bg-white">
            <div class="min-w-0"><span class="fw-semibold">${name}</span>${major}</div>
            <span class="badge bg-primary">${role}</span>
        </div>`;
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

function bindApplyButton() {
    const applyBtn = document.getElementById('applyBtn');
    applyBtn?.addEventListener('click', async function() {
        if (!teamIdNum) {
            showMessage('缺少团队ID参数', 'error');
            return;
        }
        await request('/team/join', {
            method: 'POST',
            body: JSON.stringify({ teamId: teamIdNum })
        });
        showMessage('申请提交成功，请等待团队创建者审核', 'success');
        applyBtn.disabled = true;
        applyBtn.textContent = '申请审核中';
    });
}
