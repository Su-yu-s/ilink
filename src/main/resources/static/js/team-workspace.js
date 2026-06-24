// 任务看板管理类
class TaskBoardManager {
    constructor() {
        this.teamId = null;
        this.teamInfo = null;
        this.tasks = [];
        this.currentTask = null;
        this.taskModal = null;
        this.createModal = null;
    }

    async init(teamId) {
        this.teamId = teamId;
        this.taskModal = new bootstrap.Modal(document.getElementById('taskDetailModal'));
        this.createModal = new bootstrap.Modal(document.getElementById('createTaskModal'));

        await this.loadTeamInfo();
        await this.loadTasks();
        this.renderBoard();
        this.setupDragAndDrop();
    }

    async loadTeamInfo() {
        try {
            this.teamInfo = await request(`/team/${this.teamId}`);
            document.getElementById('workspaceTitle').textContent = this.teamInfo.title || '任务看板';
            document.getElementById('workspaceSubtitle').textContent = '团队协作空间';
        } catch (error) {
            console.error('加载团队信息失败:', error);
            showMessage('加载团队信息失败', 'error');
        }
    }

    async loadTasks() {
        try {
            const data = await request(`/team/${this.teamId}/tasks`);
            this.tasks = (Array.isArray(data) ? data : []).map(task => this.adaptTaskFromApi(task));
        } catch (error) {
            console.error('加载任务失败:', error);
            this.tasks = [];
            showMessage('加载任务列表失败', 'error');
        }
    }

    adaptTaskFromApi(apiTask) {
        return {
            id: apiTask.id,
            taskTitle: apiTask.taskTitle,
            taskDescription: apiTask.taskDescription,
            taskType: apiTask.taskType,
            priority: apiTask.priority,
            status: apiTask.status,
            estimatedHours: apiTask.estimatedHours,
            actualHours: apiTask.actualHours,
            deadline: apiTask.deadline,
            assignedTo: apiTask.assignedTo ? {
                id: apiTask.assignedTo,
                username: apiTask.assigneeName || '',
                realName: apiTask.assigneeName || ''
            } : null,
            createdBy: apiTask.createdBy,
            creatorName: apiTask.creatorName,
            createdAt: apiTask.createdAt,
            completedAt: apiTask.completedAt
        };
    }

    renderBoard() {
        const columns = {
            todo: [],
            in_progress: [],
            review: [],
            completed: []
        };

        this.tasks.forEach(task => {
            const status = this.normalizeStatus(task.status);
            if (columns[status]) {
                columns[status].push(task);
            }
        });

        document.getElementById('todoCount').textContent = columns.todo.length;
        document.getElementById('inProgressCount').textContent = columns.in_progress.length;
        document.getElementById('reviewCount').textContent = columns.review.length;
        document.getElementById('completedCount').textContent = columns.completed.length;

        document.getElementById('todoList').innerHTML = this.renderTaskList(columns.todo);
        document.getElementById('inProgressList').innerHTML = this.renderTaskList(columns.in_progress);
        document.getElementById('reviewList').innerHTML = this.renderTaskList(columns.review);
        document.getElementById('completedList').innerHTML = this.renderTaskList(columns.completed);

        this.bindTaskCardEvents();
    }

    normalizeStatus(status) {
        const statusMap = {
            'pending': 'todo',
            'todo': 'todo',
            'in_progress': 'in_progress',
            'progress': 'in_progress',
            'review': 'review',
            'rework': 'review',
            'completed': 'completed',
            'done': 'completed',
            'cancelled': 'completed'
        };
        return statusMap[status] || 'todo';
    }

    renderTaskList(tasks) {
        if (!tasks || tasks.length === 0) {
            return `
                <div class="kanban-empty">
                    <div class="kanban-empty-icon">📋</div>
                    <p class="kanban-empty-text">暂无任务</p>
                </div>
            `;
        }

        return tasks.map(task => this.renderTaskCard(task)).join('');
    }

    renderTaskCard(task) {
        const priorityLabels = { 1: '低', 2: '中', 3: '高', 4: '紧急' };
        const typeLabels = {
            'development': '开发',
            'design': '设计',
            'testing': '测试',
            'documentation': '文档',
            'other': '其他'
        };

        const isOverdue = task.deadline && new Date(task.deadline) < new Date() && task.status !== 'completed';
        const deadlineText = task.deadline ? this.formatDate(task.deadline) : '无截止日期';

        let assigneeHtml = '';
        if (task.assignedTo) {
            const name = task.assignedTo.realName || task.assignedTo.username || '未知';
            const initials = name.substring(0, 2).toUpperCase();
            assigneeHtml = `
                <div class="kanban-task-assignee">
                    <div class="kanban-task-assignee-avatar">${escapeHtml(initials)}</div>
                    <span class="kanban-task-assignee-name">${escapeHtml(name)}</span>
                </div>
            `;
        }

        return `
            <div class="kanban-task-card" data-task-id="${task.id}" onclick="taskBoard.openTaskDetail(${task.id})">
                <div class="kanban-task-header">
                    <h4 class="kanban-task-title">${escapeHtml(task.taskTitle)}</h4>
                    <div class="kanban-task-priority p${task.priority}" title="优先级: ${priorityLabels[task.priority]}"></div>
                </div>
                ${task.taskDescription ? `<p class="kanban-task-description">${escapeHtml(task.taskDescription)}</p>` : ''}
                <div class="kanban-task-meta">
                    <span class="kanban-task-type">${typeLabels[task.taskType] || '其他'}</span>
                    <span class="kanban-task-deadline ${isOverdue ? 'overdue' : ''}">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2"/>
                            <path d="M12 6V12L16 14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                        </svg>
                        ${deadlineText}
                    </span>
                </div>
                ${assigneeHtml}
            </div>
        `;
    }

    formatDate(dateString) {
        if (!dateString) return '';
        const date = new Date(dateString);
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        return `${month}-${day} ${hours}:${minutes}`;
    }

    bindTaskCardEvents() {
        const cards = document.querySelectorAll('.kanban-task-card');
        cards.forEach(card => {
            card.setAttribute('draggable', 'true');
            card.addEventListener('dragstart', (e) => this.handleDragStart(e));
            card.addEventListener('dragend', (e) => this.handleDragEnd(e));
        });

        const lists = document.querySelectorAll('.kanban-task-list');
        lists.forEach(list => {
            list.addEventListener('dragover', (e) => this.handleDragOver(e));
            list.addEventListener('drop', (e) => this.handleDrop(e));
            list.addEventListener('dragleave', (e) => this.handleDragLeave(e));
        });
    }

    setupDragAndDrop() {
        const columns = document.querySelectorAll('.kanban-column');
        columns.forEach(column => {
            column.addEventListener('dragover', (e) => {
                e.preventDefault();
                column.querySelector('.kanban-task-list').classList.add('drag-over');
            });

            column.addEventListener('dragleave', (e) => {
                if (!column.contains(e.relatedTarget)) {
                    const list = column.querySelector('.kanban-task-list');
                    if (list) list.classList.remove('drag-over');
                }
            });
        });
    }

    handleDragStart(e) {
        const card = e.target;
        card.classList.add('dragging');
        e.dataTransfer.setData('text/plain', card.dataset.taskId);
        e.dataTransfer.effectAllowed = 'move';
    }

    handleDragEnd(e) {
        e.target.classList.remove('dragging');
        document.querySelectorAll('.kanban-task-list').forEach(list => {
            list.classList.remove('drag-over');
        });
    }

    handleDragOver(e) {
        e.preventDefault();
        e.dataTransfer.dropEffect = 'move';
    }

    handleDragLeave(e) {
        if (!e.currentTarget.contains(e.relatedTarget)) {
            e.currentTarget.classList.remove('drag-over');
        }
    }

    async handleDrop(e) {
        e.preventDefault();
        e.currentTarget.classList.remove('drag-over');

        const taskId = parseInt(e.dataTransfer.getData('text/plain'));
        const column = e.currentTarget.closest('.kanban-column');
        const newStatus = column.dataset.status;

        await this.updateTaskStatus(taskId, newStatus);
    }

    openTaskDetail(taskId) {
        const task = this.tasks.find(t => t.id === taskId);
        if (!task) {
            showMessage('任务不存在', 'error');
            return;
        }

        this.currentTask = task;
        this.renderTaskDetail(task);
        this.taskModal.show();
    }

    renderTaskDetail(task) {
        const priorityLabels = { 1: '低', 2: '中', 3: '高', 4: '紧急' };
        const priorityClasses = { 1: 'badge-secondary', 2: 'badge-primary', 3: 'badge-warning', 4: 'badge-danger' };
        const typeLabels = {
            'development': '开发',
            'design': '设计',
            'testing': '测试',
            'documentation': '文档',
            'other': '其他'
        };
        const statusLabels = {
            'todo': '待办',
            'in_progress': '进行中',
            'review': '待审核',
            'completed': '已完成',
            'pending': '待办',
            'in progress': '进行中'
        };
        const statusClasses = {
            'todo': 'badge-secondary',
            'pending': 'badge-secondary',
            'in_progress': 'badge-primary',
            'review': 'badge-warning',
            'completed': 'badge-success'
        };

        document.getElementById('detailTaskTitle').textContent = task.taskTitle || '任务详情';
        document.getElementById('detailTaskDescription').textContent = task.taskDescription || '暂无描述';

        const normalizedStatus = this.normalizeStatus(task.status);
        document.getElementById('detailTaskStatus').innerHTML = `
            <span class="badge ${statusClasses[normalizedStatus]}">${statusLabels[normalizedStatus] || normalizedStatus}</span>
        `;

        document.getElementById('detailCreatedAt').textContent = task.createdAt ? formatTime(task.createdAt) : '-';
        document.getElementById('detailCompletedAt').textContent = task.completedAt ? formatTime(task.completedAt) : '-';

        document.getElementById('detailPriority').innerHTML = `
            <span class="badge ${priorityClasses[task.priority] || 'badge-secondary'}">${priorityLabels[task.priority] || '未知'}</span>
        `;
        document.getElementById('detailType').textContent = typeLabels[task.taskType] || '其他';
        document.getElementById('detailDeadline').textContent = task.deadline ? this.formatDate(task.deadline) : '无截止日期';
        document.getElementById('detailHours').textContent = `${task.estimatedHours || 0}h / ${task.actualHours || 0}h`;

        let assigneeHtml = '<span class="text-tertiary">未分配</span>';
        if (task.assignedTo) {
            const name = task.assignedTo.realName || task.assignedTo.username || '未知';
            const initials = name.substring(0, 2).toUpperCase();
            assigneeHtml = `
                <div class="d-flex align-items-center gap-2">
                    <div class="publisher-avatar" style="width: 28px; height: 28px; border-radius: 50%; background: var(--primary); color: white; display: flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 600;">
                        ${escapeHtml(initials)}
                    </div>
                    <span>${escapeHtml(name)}</span>
                </div>
            `;
        }
        document.getElementById('detailAssignee').innerHTML = assigneeHtml;

        const statusSelect = this.createStatusSelect(normalizedStatus);
        document.getElementById('updateStatusBtn').onclick = () => this.changeTaskStatus(statusSelect.value);
        document.getElementById('deleteTaskBtn').onclick = () => this.deleteCurrentTask();
    }

    createStatusSelect(currentStatus) {
        const select = document.createElement('select');
        select.className = 'form-select';
        select.id = 'newStatusSelect';

        const statuses = [
            { value: 'todo', label: '待办' },
            { value: 'in_progress', label: '进行中' },
            { value: 'review', label: '待审核' },
            { value: 'completed', label: '已完成' }
        ];

        statuses.forEach(s => {
            const option = document.createElement('option');
            option.value = s.value;
            option.textContent = s.label;
            if (s.value === currentStatus) {
                option.selected = true;
            }
            select.appendChild(option);
        });

        const container = document.getElementById('detailTaskStatus');
        container.innerHTML = '';
        container.appendChild(select);

        return select;
    }

    async changeTaskStatus(newStatus) {
        if (!this.currentTask) return;

        await this.updateTaskStatus(this.currentTask.id, newStatus);
        this.taskModal.hide();
        showMessage('任务状态已更新', 'success');
    }

    async updateTaskStatus(taskId, newStatus) {
        const apiStatusMap = {
            'todo': 'pending',
            'in_progress': 'in_progress',
            'review': 'review',
            'completed': 'completed'
        };

        try {
            await request(`/tasks/${taskId}/status`, {
                method: 'PUT',
                body: JSON.stringify({
                    status: apiStatusMap[newStatus] || newStatus
                })
            });
            const task = this.tasks.find(t => t.id === taskId);
            if (task) {
                task.status = apiStatusMap[newStatus] || newStatus;
                if (newStatus === 'completed') {
                    task.completedAt = new Date().toISOString();
                }
            }
            this.renderBoard();
            showMessage('任务状态已更新', 'success');
        } catch (error) {
            console.error('更新任务状态失败:', error);
        }
    }

    openCreateModal(defaultStatus = 'todo') {
        document.getElementById('taskTitle').value = '';
        document.getElementById('taskDescription').value = '';
        document.getElementById('taskType').value = 'development';
        document.getElementById('taskPriority').value = '2';
        document.getElementById('taskDeadline').value = '';
        document.getElementById('taskEstimatedHours').value = '';

        this.createModal.show();
    }

    async createTask() {
        const title = document.getElementById('taskTitle').value.trim();
        if (!title) {
            showMessage('请输入任务标题', 'warning');
            return;
        }

        const taskData = {
            taskTitle: title,
            taskDescription: document.getElementById('taskDescription').value.trim(),
            taskType: document.getElementById('taskType').value,
            priority: parseInt(document.getElementById('taskPriority').value),
            deadline: document.getElementById('taskDeadline').value || null,
            estimatedHours: parseFloat(document.getElementById('taskEstimatedHours').value) || null,
            status: 'pending'
        };

        try {
            await request(`/team/${this.teamId}/tasks`, {
                method: 'POST',
                body: JSON.stringify(taskData)
            });
            await this.loadTasks();
            this.renderBoard();
            this.createModal.hide();
            showMessage('任务创建成功', 'success');
        } catch (error) {
            console.error('创建任务失败:', error);
        }
    }

    async deleteCurrentTask() {
        if (!this.currentTask) return;

        if (!confirm('确定要删除这个任务吗？')) return;

        const taskId = this.currentTask.id;

        try {
            await request(`/tasks/${taskId}`, { method: 'DELETE' });
            this.tasks = this.tasks.filter(t => t.id !== taskId);
            this.renderBoard();
            this.taskModal.hide();
            showMessage('任务已删除', 'success');
        } catch (error) {
            console.error('删除任务失败:', error);
        }
    }

    updateCurrentTaskStatus() {
        const select = document.getElementById('newStatusSelect');
        if (select) {
            this.changeTaskStatus(select.value);
        }
    }

    async refreshBoard() {
        showMessage('正在刷新...', 'info');
        await this.loadTasks();
        this.renderBoard();
        showMessage('看板已刷新', 'success');
    }
}

const urlParams = new URLSearchParams(window.location.search);
const teamId = urlParams.get('teamId');

let taskBoard;

document.addEventListener('DOMContentLoaded', async function() {
    if (!teamId) {
        showMessage('缺少团队ID参数', 'error');
        setTimeout(() => {
            window.location.href = '/team-market.html';
        }, 1500);
        return;
    }

    window.currentTeamId = teamId;
    taskBoard = new TaskBoardManager();
    await taskBoard.init(teamId);
});
