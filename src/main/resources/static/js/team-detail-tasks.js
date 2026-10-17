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

function isMentorForTasks() {
    return currentTeamMembers.some(function (m) {
        const id = m.userId || m.id;
        return Number(id) === Number(currentTaskUserId) &&
            (m.memberRole === 'MENTOR' || m.role === '导师' || m.role === 'MENTOR');
    });
}

function canReviewTasks() {
    return isTeamLeaderForTasks() || isMentorForTasks();
}

async function loadCurrentUserForTasks() {
    // 匿名访客返回 null，不发请求（核实报告 P2-6）；登录态复用页面级缓存，避免重复拉取资料
    const user = await loadCurrentUserOnce();
    if (user && user.id) currentTaskUserId = user.id;
}

async function loadTeamMembers() {
    // 成员列表接口要求登录，匿名访客请求必然 401（控制台红色报错）。
    // 任务区本来就只对成员/队长开放，这里直接跳过请求（核实报告 P2-6 同类）。
    if (typeof isAnonymousVisitor === 'function' && isAnonymousVisitor()) {
        currentTeamMembers = [];
        return;
    }
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
    select.innerHTML = '<option value="" hidden>选择队员</option>';   // hidden：占位项不进可选列表
    currentTeamMembers.forEach(function(member) {
        const option = document.createElement('option');
        option.value = member.userId || member.id || '';
        option.textContent = `${member.displayName || member.username || member.realName || '队员'}${member.role ? '（' + member.role + '）' : ''}`;
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

    const normalizedStatus = String(task.status || '').toUpperCase();

    if (isLeader) {
        actions.push(`<button type="button" class="btn btn-sm btn-ilink-ghost" onclick="openTaskForm(${Number(task.id)})">编辑</button>`);
        actions.push(`<button type="button" class="btn btn-sm btn-ilink-danger-ghost" onclick="deleteTask(${Number(task.id)})">删除</button>`);
        if (normalizedStatus === 'REVIEW' && canReviewTasks()) {
            actions.push(`<button type="button" class="btn btn-sm btn-ilink-primary" onclick="openReviewWorkbench(${Number(task.id)})">批阅</button>`);
        }
    }
    if (isAssignee && normalizedStatus === 'PENDING') {
        actions.push(`<button type="button" class="btn btn-sm btn-ilink-primary" onclick="startTask(${Number(task.id)})">开始任务</button>`);
    } else if (isAssignee && normalizedStatus === 'IN_PROGRESS') {
        if (task.requiresReview === false) {
            actions.push(`<button type="button" class="btn btn-sm btn-ilink-success" onclick="completeTaskDirectly(${Number(task.id)})">标记完成</button>`);
        } else {
            actions.push(`<button type="button" class="btn btn-sm btn-ilink-primary" onclick="openSubmitModal(${Number(task.id)})">提交审阅</button>`);
        }
    } else if (isAssignee && normalizedStatus === 'REVIEW' && !canReviewTasks()) {
        actions.push('<span class="badge-ilink badge-ilink--line">待队长/导师批阅</span>');
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

async function startTask(taskId) {
    await request('/tasks/' + taskId + '/status', {
        method: 'PUT',
        body: JSON.stringify({ status: 'IN_PROGRESS' })
    });
    showMessage('任务已开始', 'success');
    await loadTasks();
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
        assignedTo: parseInt(memberId, 10),
        requiresReview: document.getElementById('assignTaskRequiresReview')?.checked !== false
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
    if (!uiConfirm('确定删除这个任务吗？')) return;
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
    await loadReviewTimeline(taskId);
    bootstrap.Modal.getOrCreateInstance(document.getElementById('taskDetailModal')).show();
}

/** 批阅记录时间线（挂在提交记录下方） */
async function loadReviewTimeline(taskId) {
    const container = document.getElementById('taskReviews');
    if (!container) return;
    try {
        const reviews = await request('/tasks/' + taskId + '/reviews', { silent: true });
        if (!Array.isArray(reviews) || reviews.length === 0) {
            container.innerHTML = '';
            return;
        }
        container.innerHTML = '<div class="il-review-timeline__title">批阅记录</div>' + reviews.map(function (r) {
            const cls = r.verdict === 'PASS' ? 'approve' : (r.verdict === 'REJECT' ? 'reject' : 'comment');
            const label = r.verdict === 'PASS' ? '通过' : (r.verdict === 'REJECT' ? '驳回' : '补充点评');
            let fileComments = [];
            try { fileComments = r.fileComments ? JSON.parse(r.fileComments) : []; } catch (e) { fileComments = []; }
            const fcHtml = Array.isArray(fileComments) ? fileComments.map(function (fc) {
                return `<div class="il-review-file-comment"><strong>${window.ILinkPreview.escapeHtml(fc.fileName || '文件')}</strong>${window.ILinkPreview.escapeHtml(fc.comment || '')}</div>`;
            }).join('') : '';
            return `<div class="il-review-round ${cls}">
                <div class="il-review-round__head">
                    <span class="il-review-round__name">${window.ILinkPreview.escapeHtml(r.reviewerName || '批阅者')}</span>
                    <span class="il-review-round__action ${cls}">${label}</span>
                    <span class="il-review-round__time">${r.createdAt ? formatTime(r.createdAt) : ''}</span>
                </div>
                ${r.overall ? `<div class="il-review-round__text">${window.ILinkPreview.escapeHtml(r.overall)}</div>` : ''}
                ${fcHtml}
            </div>`;
        }).join('');
    } catch (error) {
        container.innerHTML = '';
    }
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

const libraryPickerSelection = [];

function openSubmitModal(taskId) {
    currentTaskIdForSubmit = taskId;
    document.getElementById('submitRemark').value = '';
    document.getElementById('submitFile').value = '';
    libraryPickerSelection.length = 0;
    renderTaskSelectedFile(null);
    bootstrap.Modal.getOrCreateInstance(document.getElementById('submitMaterialModal')).show();
    loadLibraryPicker();
}

/** 完成弹窗：从资料库选择交付材料（层级平铺多选） */
async function loadLibraryPicker() {
    const box = document.getElementById('libraryPickerList');
    if (!box) return;
    try {
        const tree = await request('/team/' + teamIdNum + '/library', { silent: true });
        const rows = [];
        (function walk(nodes, depth) {
            (nodes || []).forEach(function (node) {
                if (node.isFolder) {
                    rows.push({ folder: true, name: node.name, depth: depth });
                    walk(node.children, depth + 1);
                } else {
                    rows.push({ folder: false, node: node, depth: depth });
                }
            });
        })(Array.isArray(tree) ? tree : [], 0);
        if (!rows.filter(r => !r.folder).length) {
            box.innerHTML = '<div class="text-muted small">资料库还没有文件，可直接上传附件提交。</div>';
            return;
        }
        box.innerHTML = rows.map(function (row) {
            if (row.folder) {
                return `<div class="library-picker-folder" style="padding-left:${row.depth * 14 + 8}px">${window.ILinkPreview.escapeHtml(row.name)}</div>`;
            }
            const picked = libraryPickerSelection.some(item => Number(item.libraryFileId) === Number(row.node.id));
            return `<label class="library-picker-item${picked ? ' picked' : ''}" style="padding-left:${row.depth * 14 + 8}px">
                <input type="checkbox" data-id="${Number(row.node.id)}" ${picked ? 'checked' : ''}>
                <span class="library-picker-icon">${window.ILinkPreview.fileIcon(row.node.fileType, row.node.name)}</span>
                <span class="library-picker-name">${window.ILinkPreview.escapeHtml(row.node.name)}</span>
                <span class="library-picker-meta">${window.ILinkPreview.escapeHtml(window.ILinkPreview.formatSize(row.node.fileSize) || '')}</span>
            </label>`;
        }).join('');
        box.querySelectorAll('input[type="checkbox"]').forEach(function (input) {
            input.addEventListener('change', function () {
                const id = Number(input.dataset.id);
                const row = rows.find(r => !r.folder && Number(r.node.id) === id);
                if (!row) return;
                const at = libraryPickerSelection.findIndex(item => Number(item.libraryFileId) === id);
                if (input.checked && at < 0) {
                    libraryPickerSelection.push({ libraryFileId: id, url: row.node.fileUrl, name: row.node.name });
                } else if (!input.checked && at >= 0) {
                    libraryPickerSelection.splice(at, 1);
                }
                input.closest('.library-picker-item').classList.toggle('picked', input.checked);
            });
        });
    } catch (error) {
        box.innerHTML = '<div class="text-muted small">资料库加载失败，可直接上传附件提交。</div>';
    }
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
    const uploaded = window.ILinkFiles.normalizeUploadResult(result.data, file);
    return { name: uploaded.name, url: uploaded.url };
}

function renderTaskSelectedFile(file) {
    const container = document.getElementById('submitFilePreview');
    if (!container) return;
    if (!file) {
        container.innerHTML = '';
        container.hidden = true;
        return;
    }
    container.hidden = false;
    container.innerHTML = window.ILinkFiles.iconMarkup(file.name) +
        `<span class="task-submit-file__body"><strong title="${escapeHtml(file.name)}">${escapeHtml(file.name)}</strong>` +
        `<small>${escapeHtml(window.ILinkFiles.formatSize(file.size) || '等待上传')}</small></span>` +
        '<button type="button" class="task-submit-file__remove" aria-label="移除所选文件">移除</button>';
    container.querySelector('.task-submit-file__remove')?.addEventListener('click', function () {
        const input = document.getElementById('submitFile');
        if (input) input.value = '';
        renderTaskSelectedFile(null);
    });
}

async function submitTaskMaterial() {
    if (!currentTaskIdForSubmit) return;
    const submitBtn = document.getElementById('submitMaterialBtn');
    const fileInput = document.getElementById('submitFile');
    const file = fileInput && fileInput.files ? fileInput.files[0] : null;
    submitBtn.disabled = true;
    try {
        const attachments = [];
        libraryPickerSelection.forEach(function (item) { attachments.push(item); });
        if (file) attachments.push(await uploadTaskAttachment(file));
        if (!attachments.length) {
            showMessage('请至少选择一份交付材料或上传附件', 'warning');
            submitBtn.disabled = false;
            return;
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
    } catch (error) {
        showMessage(error.message || '提交失败', 'error');
    } finally {
        submitBtn.disabled = false;
    }
}

// 直接完成通道（requires_review=0 的任务）
async function completeTaskDirectly(taskId) {
    if (!uiConfirm('确认该任务已经完成？')) return;
    try {
        await request('/tasks/' + taskId + '/complete', { method: 'POST' });
        showMessage('任务已完成', 'success');
        await loadTasks();
    } catch (error) {
        showMessage(error.message || '操作失败', 'error');
    }
}

// ---------- 批阅工作台 ----------
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
    document.getElementById('submitFile')?.addEventListener('change', function () {
        renderTaskSelectedFile(this.files && this.files[0]);
    });
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

/** 工作台（公共脚本）关闭后刷新本页的任务列表 */
window.onReviewWorkbenchClosed = function () {
    loadTasks();
};

if (typeof checkCreatorAccess === 'function') {    const originalCheckCreatorAccess = checkCreatorAccess;
    checkCreatorAccess = async function(teamData) {
        await originalCheckCreatorAccess(teamData);
        await loadTaskSection();
        await openReviewWorkbenchFromUrl();
    };
}

/** 批阅工作台是从团队空间的任务看板跳进来的吗（URL 带 reviewTaskId） */
