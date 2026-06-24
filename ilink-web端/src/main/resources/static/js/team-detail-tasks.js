let currentUserId = null;
let currentTeamMembers = [];
let currentTaskIdForSubmit = null;
let currentEditingTaskId = null;

function taskStatusLabel(status) {
    const map = {
        PENDING: '待完成',
        IN_PROGRESS: '进行中',
        REVIEW: '待审核',
        COMPLETED: '已完成',
        CANCELLED: '已取消'
    };
    return map[status] || status || '未知';
}

function taskStatusColor(status) {
    const map = {
        PENDING: '#6b7280',
        IN_PROGRESS: '#2D6FF7',
        REVIEW: '#FF8F1F',
        COMPLETED: '#00B578',
        CANCELLED: '#999999'
    };
    return map[status] || '#6b7280';
}

function isTeamLeaderForTasks() {
    return currentTeamData && currentUserId && Number(currentTeamData.creatorId) === Number(currentUserId);
}

async function loadCurrentUserForTasks() {
    const userResp = await apiFetch('/api/user/profile');
    const userResult = await userResp.json();
    if (userResult.code === 200 && userResult.data) {
        currentUserId = userResult.data.id;
    }
}

async function loadTeamMembers() {
    try {
        currentTeamMembers = await request('/team/' + teamIdNum + '/members');
    } catch (e) {
        currentTeamMembers = [];
        console.warn('加载团队成员失败', e);
    }
}

function fillMemberSelect(selectedId) {
    const select = document.getElementById('assignMemberSelect');
    if (!select) return;
    select.innerHTML = '<option value="">选择队员</option>';
    currentTeamMembers.forEach(function(member) {
        const opt = document.createElement('option');
        opt.value = member.userId;
        opt.textContent = `${member.username || '队员'}${member.role ? '（' + member.role + '）' : ''}`;
        if (selectedId && Number(selectedId) === Number(member.userId)) {
            opt.selected = true;
        }
        select.appendChild(opt);
    });
}

async function loadTasks() {
    const container = document.getElementById('taskListContainer');
    if (!container) return;
    try {
        const tasks = await request('/tasks?teamId=' + teamIdNum);
        if (!Array.isArray(tasks) || tasks.length === 0) {
            container.innerHTML = '<div class="il-empty-state"><p>暂无任务</p></div>';
            return;
        }
        container.innerHTML = tasks.map(renderTaskCard).join('');
    } catch (e) {
        console.warn('加载任务失败', e);
        container.innerHTML = '<div class="alert alert-warning">任务加载失败</div>';
    }
}

function renderTaskCard(task) {
    const statusColor = taskStatusColor(task.status);
    const statusText = taskStatusLabel(task.status);
    const assignee = escapeHtml(task.assigneeName || '未分配');
    const deadline = task.deadline ? new Date(task.deadline).toLocaleDateString('zh-CN') : '无';
    const isLeader = isTeamLeaderForTasks();
    const isAssignee = Number(task.assignedTo) === Number(currentUserId);
    const actions = [`<button class="btn btn-sm btn-outline-primary" onclick="viewTaskDetail(${task.id})">查看</button>`];
    if (isLeader) {
        actions.push(`<button class="btn btn-sm btn-outline-secondary" onclick="openTaskForm(${task.id})">编辑</button>`);
        actions.push(`<button class="btn btn-sm btn-outline-danger" onclick="deleteTask(${task.id})">删除</button>`);
        if (task.status === 'REVIEW') {
            actions.push(`<button class="btn btn-sm btn-success" onclick="reviewTask(${task.id}, 'COMPLETED')">确认完成</button>`);
            actions.push(`<button class="btn btn-sm btn-warning" onclick="reviewTask(${task.id}, 'RETURNED')">退回修改</button>`);
        }
    } else if (isAssignee && task.status !== 'COMPLETED' && task.status !== 'CANCELLED') {
        if (task.status === 'REVIEW') {
            actions.push('<span class="badge bg-warning text-dark">待队长审核</span>');
        } else {
            actions.push(`<button class="btn btn-sm btn-primary" onclick="openSubmitModal(${task.id})">提交材料</button>`);
        }
    }
    return `<div class="task-card mb-3 p-3 border rounded bg-white" data-task-id="${task.id}">
        <div class="d-flex justify-content-between align-items-start gap-2 mb-2">
            <h6 class="mb-0 fw-semibold">${escapeHtml(task.taskTitle || '未命名任务')}</h6>
            <span class="badge" style="background:${statusColor};color:#fff;">${statusText}</span>
        </div>
        <p class="small text-muted mb-2">${escapeHtml((task.taskDescription || '').slice(0, 120))}</p>
        <div class="d-flex flex-wrap gap-3 small text-muted mb-2">
            <span>负责人：${assignee}</span>
            <span>截止：${escapeHtml(deadline)}</span>
        </div>
        <div class="d-flex flex-wrap gap-2">${actions.join('')}</div>
    </div>`;
}

async function openTaskForm(taskId) {
    currentEditingTaskId = taskId || null;
    fillMemberSelect(null);
    document.getElementById('assignTaskName').value = '';
    document.getElementById('assignTaskDesc').value = '';
    document.getElementById('assignTaskDeadline').value = '';
    const titleEl = document.querySelector('#assignTaskModal .modal-title');
    if (titleEl) titleEl.textContent = currentEditingTaskId ? '编辑任务' : '分配任务';
    if (currentEditingTaskId) {
        const task = await request('/tasks/' + currentEditingTaskId);
        document.getElementById('assignTaskName').value = task.taskTitle || '';
        document.getElementById('assignTaskDesc').value = task.taskDescription || '';
        document.getElementById('assignTaskDeadline').value = task.deadline ? new Date(task.deadline).toISOString().slice(0, 10) : '';
        fillMemberSelect(task.assignedTo);
    }
    bootstrap.Modal.getOrCreateInstance(document.getElementById('assignTaskModal')).show();
}

async function submitTaskForm() {
    const memberId = document.getElementById('assignMemberSelect').value;
    const taskName = document.getElementById('assignTaskName').value.trim();
    const taskDesc = document.getElementById('assignTaskDesc').value.trim();
    const deadline = document.getElementById('assignTaskDeadline').value;
    if (!memberId) {
        showMessage('请选择队员', 'warning');
        return;
    }
    if (!taskName) {
        showMessage('请填写任务名称', 'warning');
        return;
    }
    const payload = {
        taskTitle: taskName,
        taskDescription: taskDesc,
        deadline: deadline || null,
        assignedTo: parseInt(memberId, 10)
    };
    if (currentEditingTaskId) {
        await request('/tasks/' + currentEditingTaskId, {
            method: 'PUT',
            body: JSON.stringify(payload)
        });
        showMessage('任务已更新', 'success');
    } else {
        await request('/team/' + teamIdNum + '/tasks', {
            method: 'POST',
            body: JSON.stringify(payload)
        });
        showMessage('任务已创建', 'success');
    }
    bootstrap.Modal.getInstance(document.getElementById('assignTaskModal'))?.hide();
    await loadTasks();
}

async function deleteTask(taskId) {
    if (!confirm('确认删除该任务？')) return;
    await request('/tasks/' + taskId, { method: 'DELETE' });
    showMessage('任务已删除', 'success');
    await loadTasks();
}

async function viewTaskDetail(taskId) {
    const task = await request('/tasks/' + taskId);
    document.getElementById('taskDetailTitle').textContent = task.taskTitle || '任务详情';
    document.getElementById('taskDetailDesc').textContent = task.taskDescription || '无描述';
    document.getElementById('taskDetailStatus').innerHTML = `<span class="badge" style="background:${taskStatusColor(task.status)};color:#fff;">${taskStatusLabel(task.status)}</span>`;
    document.getElementById('taskDetailAssignee').textContent = task.assigneeName || '未分配';
    document.getElementById('taskDetailDeadline').textContent = task.deadline ? new Date(task.deadline).toLocaleDateString('zh-CN') : '无';
    document.getElementById('taskDetailCreated').textContent = task.createdAt ? formatTime(task.createdAt) : '-';
    await loadSubmissions(taskId);
    bootstrap.Modal.getOrCreateInstance(document.getElementById('taskDetailModal')).show();
}

async function loadSubmissions(taskId) {
    const container = document.getElementById('taskSubmissions');
    if (!container) return;
    try {
        const submissions = await request('/tasks/' + taskId + '/submissions');
        if (!Array.isArray(submissions) || submissions.length === 0) {
            container.innerHTML = '<p class="text-muted small mb-0">暂无提交记录</p>';
            return;
        }
        container.innerHTML = submissions.map(renderSubmission).join('');
    } catch (e) {
        container.innerHTML = '<p class="text-warning small mb-0">提交记录加载失败</p>';
    }
}

function renderSubmission(item) {
    let attachments = [];
    try {
        attachments = item.attachments ? JSON.parse(item.attachments) : [];
    } catch (e) {
        attachments = [];
    }
    const attachmentHtml = Array.isArray(attachments) && attachments.length
        ? `<div class="mt-2 d-flex flex-wrap gap-2">${attachments.map((file) => {
            const name = escapeHtml(file.name || '附件');
            const url = escapeHtml(file.url || '#');
            return `<a class="btn btn-sm btn-outline-secondary" href="${url}" target="_blank" rel="noopener">${name}</a>`;
        }).join('')}</div>`
        : '';
    return `<div class="border rounded p-3 mb-2">
        <div class="d-flex justify-content-between gap-2 small text-muted mb-2">
            <span>${escapeHtml(item.submitterName || '提交人')}</span>
            <span>${item.createdAt ? formatTime(item.createdAt) : ''}</span>
        </div>
        <div style="white-space:pre-wrap;">${escapeHtml(item.content || '无说明')}</div>
        ${attachmentHtml}
    </div>`;
}

function openSubmitModal(taskId) {
    currentTaskIdForSubmit = taskId;
    document.getElementById('submitRemark').value = '';
    document.getElementById('submitFile').value = '';
    bootstrap.Modal.getOrCreateInstance(document.getElementById('submitMaterialModal')).show();
}

async function uploadTaskAttachment(file) {
    if (!file) return null;
    const formData = new FormData();
    formData.append('file', file);
    const response = await apiFetch('/api/upload/attachment?kind=task', {
        method: 'POST',
        body: formData
    });
    const result = await response.json();
    if (Number(result.code) !== 200 || !result.data || !result.data.url) {
        throw new Error(result.message || '附件上传失败');
    }
    return { name: file.name, url: result.data.url };
}

async function submitTaskMaterial() {
    if (!currentTaskIdForSubmit) return;
    const submitBtn = document.getElementById('submitMaterialBtn');
    const fileInput = document.getElementById('submitFile');
    const file = fileInput && fileInput.files ? fileInput.files[0] : null;
    submitBtn.disabled = true;
    try {
        const attachments = [];
        if (file) {
            attachments.push(await uploadTaskAttachment(file));
        }
        await request('/tasks/' + currentTaskIdForSubmit + '/submit', {
            method: 'POST',
            body: JSON.stringify({
                remark: document.getElementById('submitRemark').value.trim(),
                attachments: attachments
            })
        });
        showMessage('提交成功，等待队长审核', 'success');
        bootstrap.Modal.getInstance(document.getElementById('submitMaterialModal'))?.hide();
        await loadTasks();
    } catch (e) {
        showMessage(e.message || '提交失败', 'error');
    } finally {
        submitBtn.disabled = false;
    }
}

async function reviewTask(taskId, action) {
    const confirmText = action === 'COMPLETED' ? '确认该任务已完成？' : '退回给队员继续修改？';
    if (!confirm(confirmText)) return;
    await request('/tasks/' + taskId + '/review', {
        method: 'PUT',
        body: JSON.stringify({ action: action })
    });
    showMessage('操作成功', 'success');
    await loadTasks();
}

async function loadTaskSection() {
    if (!teamIdNum) return;
    if (!currentTeamData || !currentTeamData.id) {
        currentTeamData = await request('/team/' + teamIdNum);
    }
    if (currentTeamData.status !== 'TEAMING') {
        return;
    }
    await loadCurrentUserForTasks();
    await loadTeamMembers();
    const isMember = currentTeamMembers.some(function(member) {
        return Number(member.userId) === Number(currentUserId);
    });
    if (!isTeamLeaderForTasks() && !isMember) {
        return;
    }
    const section = document.getElementById('taskSection');
    if (section) {
        section.classList.remove('d-none');
    }
    const assignBtn = document.getElementById('assignTaskBtn');
    if (assignBtn && isTeamLeaderForTasks()) {
        assignBtn.classList.remove('d-none');
    }
    await loadTasks();
}

document.addEventListener('DOMContentLoaded', function() {
    document.getElementById('submitMaterialBtn')?.addEventListener('click', submitTaskMaterial);
    document.getElementById('submitAssignBtn')?.addEventListener('click', function() {
        submitTaskForm().catch(function(e) {
            console.error('保存任务失败', e);
            showMessage(e.message || '保存任务失败', 'error');
        });
    });
    document.getElementById('assignTaskBtn')?.addEventListener('click', function() {
        openTaskForm(null).catch(function(e) {
            console.error('打开任务表单失败', e);
        });
    });
});

if (typeof checkCreatorAccess === 'function') {
    const originalCheckCreatorAccess = checkCreatorAccess;
    checkCreatorAccess = async function(teamData) {
        await originalCheckCreatorAccess(teamData);
        await loadTaskSection();
    };
}
