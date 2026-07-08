let currentTaskUserId = null;
let currentTeamMembers = [];
let currentTaskIdForSubmit = null;
let currentEditingTaskId = null;


function taskStatusLabel(status) {
    const map = {
        PENDING: '待完成',
        IN_PROGRESS: '进行中',
        REVIEW: '待审核',
        RETURNED: '需修改',
        COMPLETED: '已完成',
        CANCELLED: '已取消'
    };
    return map[String(status || '').toUpperCase()] || status || '未知';
}

function taskStatusClass(status) {
    const key = String(status || '').toUpperCase();
    return 'badge-ilink badge-ilink--' + ({
        PENDING: 'muted',
        IN_PROGRESS: 'dark',
        REVIEW: 'line',
        RETURNED: 'line',
        COMPLETED: 'dark',
        CANCELLED: 'muted'
    }[key] || 'muted');
}

function isTeamLeaderForTasks() {
    return currentTeamData && currentTaskUserId && Number(currentTeamData.creatorId) === Number(currentTaskUserId);
}

async function loadCurrentUserForTasks() {
    const user = await request('/user/profile', { silent: true });
    if (user && user.id) currentTaskUserId = user.id;
}

async function loadTeamMembers() {
    try {
        const data = await request('/team/' + teamIdNum + '/members', { silent: true });
        currentTeamMembers = Array.isArray(data) ? data : [];
    } catch (error) {
        currentTeamMembers = [];
        console.warn('加载团队成员失败', error);
    }
}

function fillMemberSelect(selectedId) {
    const select = document.getElementById('assignMemberSelect');
    if (!select) return;
    select.innerHTML = '<option value="">选择队员</option>';
    currentTeamMembers.forEach(function(member) {
        const option = document.createElement('option');
        option.value = member.userId || member.id || '';
        option.textContent = `${member.username || member.realName || '队员'}${member.role ? '（' + member.role + '）' : ''}`;
        if (selectedId && Number(selectedId) === Number(option.value)) option.selected = true;
        select.appendChild(option);
    });
}

async function loadTasks() {
    const container = document.getElementById('taskListContainer');
    if (!container) return;
    try {
        const tasks = await request('/tasks?teamId=' + teamIdNum, { silent: true });
        if (!Array.isArray(tasks) || tasks.length === 0) {
            container.innerHTML = '<div class="il-empty-state"><p>暂无任务。队长分配任务后，这里会出现协作进度。</p></div>';
            return;
        }
        container.innerHTML = tasks.map(renderTaskCard).join('');
    } catch (error) {
        console.warn('加载任务失败', error);
        container.innerHTML = '<div class="il-empty-state"><p>任务加载失败，请稍后刷新。</p></div>';
    }
}

function renderTaskCard(task) {
    const statusText = taskStatusLabel(task.status);
    const assignee = escapeHtml(task.assigneeName || '未分配');
    const deadline = task.deadline ? new Date(task.deadline).toLocaleDateString('zh-CN') : '无截止日期';
    const isLeader = isTeamLeaderForTasks();
    const isAssignee = Number(task.assignedTo) === Number(currentTaskUserId);
    const actions = [`<button type="button" class="btn btn-sm btn-ilink-outline" onclick="viewTaskDetail(${Number(task.id)})">查看</button>`];

    if (isLeader) {
        actions.push(`<button type="button" class="btn btn-sm btn-ilink-ghost" onclick="openTaskForm(${Number(task.id)})">编辑</button>`);
        actions.push(`<button type="button" class="btn btn-sm btn-ilink-danger-ghost" onclick="deleteTask(${Number(task.id)})">删除</button>`);
        if (String(task.status).toUpperCase() === 'REVIEW') {
            actions.push(`<button type="button" class="btn btn-sm btn-ilink-success" onclick="reviewTask(${Number(task.id)}, 'COMPLETED')">确认完成</button>`);
            actions.push(`<button type="button" class="btn btn-sm btn-ilink-warning" onclick="reviewTask(${Number(task.id)}, 'RETURNED')">退回修改</button>`);
        }
    } else if (isAssignee && !['COMPLETED', 'CANCELLED'].includes(String(task.status).toUpperCase())) {
        if (String(task.status).toUpperCase() === 'REVIEW') {
            actions.push('<span class="badge-ilink badge-ilink--line">待队长审核</span>');
        } else {
            actions.push(`<button type="button" class="btn btn-sm btn-ilink-primary" onclick="openSubmitModal(${Number(task.id)})">提交材料</button>`);
        }
    }

    return `<article class="il-task-card" data-task-id="${Number(task.id)}">
        <div class="il-task-card__header">
            <div class="il-task-card__title-row">
                <h3 class="il-task-card__title">${escapeHtml(task.taskTitle || '未命名任务')}</h3>
                <span class="${taskStatusClass(task.status)}">${statusText}</span>
            </div>
            <p class="il-task-card__desc">${escapeHtml((task.taskDescription || '').slice(0, 120))}</p>
        </div>
        <div class="il-task-card__meta">
            <span class="il-task-card__meta-item">${assignee}</span>
            <span class="il-task-card__meta-item">${escapeHtml(deadline)}</span>
        </div>
        <div class="il-task-card__footer">${actions.join('')}</div>
    </article>`;
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
        const task = await request('/tasks/' + currentEditingTaskId, { silent: true });
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
    if (!confirm('确定删除这个任务吗？')) return;
    await request('/tasks/' + taskId, { method: 'DELETE' });
    showMessage('任务已删除', 'success');
    await loadTasks();
}

async function viewTaskDetail(taskId) {
    const task = await request('/tasks/' + taskId, { silent: true });
    document.getElementById('taskDetailTitle').textContent = task.taskTitle || '任务详情';
    document.getElementById('taskDetailDesc').textContent = task.taskDescription || '暂无描述';
    document.getElementById('taskDetailStatus').innerHTML = `<span class="${taskStatusClass(task.status)}">${taskStatusLabel(task.status)}</span>`;
    document.getElementById('taskDetailAssignee').textContent = task.assigneeName || '未分配';
    document.getElementById('taskDetailDeadline').textContent = task.deadline ? new Date(task.deadline).toLocaleDateString('zh-CN') : '无截止日期';
    document.getElementById('taskDetailCreated').textContent = task.createdAt ? formatTime(task.createdAt) : '-';
    await loadSubmissions(taskId);
    bootstrap.Modal.getOrCreateInstance(document.getElementById('taskDetailModal')).show();
}

async function loadSubmissions(taskId) {
    const container = document.getElementById('taskSubmissions');
    if (!container) return;
    try {
        const submissions = await request('/tasks/' + taskId + '/submissions', { silent: true });
        if (!Array.isArray(submissions) || submissions.length === 0) {
            container.innerHTML = '<p class="text-muted small mb-0">暂无提交记录</p>';
            return;
        }
        container.innerHTML = submissions.map(renderSubmission).join('');
    } catch (error) {
        container.innerHTML = '<p class="text-muted small mb-0">提交记录加载失败</p>';
    }
}

function renderSubmission(item) {
    let attachments = [];
    try {
        attachments = item.attachments ? JSON.parse(item.attachments) : [];
    } catch (error) {
        attachments = [];
    }

    const attachmentHtml = Array.isArray(attachments) && attachments.length
        ? `<div class="mt-2 d-flex flex-wrap gap-2">${attachments.map((file) => {
            const name = escapeHtml(file.name || '附件');
            const url = escapeHtml(file.url || '');
            return url ? `<a class="btn btn-sm btn-outline-secondary" href="${url}" target="_blank" rel="noopener">${name}</a>` : '';
        }).join('')}</div>`
        : '';

    return `<div class="border p-3 mb-2">
        <div class="d-flex justify-content-between gap-2 small text-muted mb-2">
            <span>${escapeHtml(item.submitterName || '提交人')}</span>
            <span>${item.createdAt ? formatTime(item.createdAt) : ''}</span>
        </div>
        <div style="white-space:pre-wrap;">${escapeHtml(item.content || item.remark || '无说明')}</div>
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
        if (file) attachments.push(await uploadTaskAttachment(file));
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
    } catch (error) {
        showMessage(error.message || '提交失败', 'error');
    } finally {
        submitBtn.disabled = false;
    }
}

async function reviewTask(taskId, action) {
    const confirmText = action === 'COMPLETED' ? '确认这个任务已经完成？' : '退回给队员继续修改？';
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
        currentTeamData = await request('/team/' + teamIdNum, { silent: true });
    }
    if (currentTeamData.status !== 'TEAMING') return;

    await loadCurrentUserForTasks();
    await loadTeamMembers();

    const isMember = currentTeamMembers.some(function(member) {
        return Number(member.userId || member.id) === Number(currentTaskUserId);
    });
    if (!isTeamLeaderForTasks() && !isMember) return;

    document.getElementById('taskSection')?.classList.remove('d-none');
    const assignBtn = document.getElementById('assignTaskBtn');
    if (assignBtn && isTeamLeaderForTasks()) assignBtn.classList.remove('d-none');
    await loadTasks();
}

document.addEventListener('DOMContentLoaded', function() {
    document.getElementById('submitMaterialBtn')?.addEventListener('click', submitTaskMaterial);
    document.getElementById('submitAssignBtn')?.addEventListener('click', function() {
        submitTaskForm().catch(function(error) {
            console.error('保存任务失败', error);
            showMessage(error.message || '保存任务失败', 'error');
        });
    });
    document.getElementById('assignTaskBtn')?.addEventListener('click', function() {
        openTaskForm(null).catch(function(error) {
            console.error('打开任务表单失败', error);
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
