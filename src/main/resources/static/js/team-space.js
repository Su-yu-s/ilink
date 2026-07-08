// 团队空间：任务看板、群聊和成员列表
(function () {
    'use strict';

    var params = new URLSearchParams(window.location.search);
    var teamId = params.get('id') || params.get('teamId');
    var currentUserId = null;
    var currentUserName = '';
    var currentUserAvatar = '';
    var teamInfo = null;
    var tasks = [];
    var members = [];
    var currentPanelId = 'kanban-panel';
    var stompClient = null;
    var isConnected = false;
    var pollingTimer = null;
    var emojiPickerEl = null;

    function byId(id) {
        return document.getElementById(id);
    }


    function notify(message, type) {
        if (typeof showMessage === 'function') {
            showMessage(message, type || 'info');
        } else {
            console.log(message);
        }
    }

    async function api(path, options) {
        if (typeof request === 'function') {
            return request(path, options || {});
        }
        var response = await apiFetch('/api' + path, options || {});
        var result = await response.json();
        if (!response.ok || !result || Number(result.code) !== 200) {
            throw new Error(result && result.message ? result.message : '请求失败，请稍后重试');
        }
        return result.data;
    }

    function formatDate(value) {
        if (!value) return '';
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value).slice(0, 10);
        var month = String(date.getMonth() + 1).padStart(2, '0');
        var day = String(date.getDate()).padStart(2, '0');
        return date.getFullYear() + '-' + month + '-' + day;
    }

    function formatTime(value) {
        if (!value) return '';
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        var today = new Date();
        var yesterday = new Date();
        yesterday.setDate(today.getDate() - 1);
        var hour = String(date.getHours()).padStart(2, '0');
        var minute = String(date.getMinutes()).padStart(2, '0');
        if (date.toDateString() === today.toDateString()) return hour + ':' + minute;
        if (date.toDateString() === yesterday.toDateString()) return '昨天 ' + hour + ':' + minute;
        return formatDate(value) + ' ' + hour + ':' + minute;
    }

    function avatarUrl(raw) {
        if (!raw) return '';
        var url = String(raw).trim();
        if (!url) return '';
        if (/^(https?:)?\/\//.test(url) || url.charAt(0) === '/') return url;
        return '/uploads/' + url;
    }

    function priorityLabel(priority) {
        return { 1: '低', 2: '中', 3: '高', 4: '紧急' }[Number(priority)] || '中';
    }

    function typeLabel(type) {
        var map = {
            development: '开发',
            design: '设计',
            testing: '测试',
            documentation: '文档',
            other: '其他'
        };
        return map[String(type || 'other').toLowerCase()] || '其他';
    }

    function normalizeStatus(status) {
        var key = String(status || '').toLowerCase();
        var map = {
            pending: 'todo',
            todo: 'todo',
            in_progress: 'in_progress',
            progress: 'in_progress',
            review: 'review',
            returned: 'review',
            rework: 'review',
            completed: 'completed',
            done: 'completed'
        };
        return map[key] || 'todo';
    }

    function statusLabel(status) {
        var map = {
            todo: '待办',
            in_progress: '进行中',
            review: '待审核',
            completed: '已完成'
        };
        return map[normalizeStatus(status)] || '待办';
    }

    function teamStatusLabel(status) {
        var map = {
            OPEN: '招募中',
            TEAMING: '组队中',
            CLOSED: '已结束'
        };
        return map[String(status || '').toUpperCase()] || status || '-';
    }

    function statusBadgeClass(status) {
        return {
            todo: 'task-status-badge task-status-badge--todo',
            in_progress: 'task-status-badge task-status-badge--progress',
            review: 'task-status-badge task-status-badge--review',
            completed: 'task-status-badge task-status-badge--done'
        }[normalizeStatus(status)] || 'task-status-badge';
    }

    function isTaskOverdue(task) {
        return task && task.deadline && new Date(task.deadline) < new Date() && normalizeStatus(task.status) !== 'completed';
    }

    function renderEmptyState(text) {
        return '<div class="kanban-empty">' +
            '<div class="kanban-empty-icon" aria-hidden="true">' +
                '<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 9h8M8 13h5"/></svg>' +
            '</div>' +
            '<p>' + escapeHtml(text || '暂无内容') + '</p>' +
        '</div>';
    }

    function setText(id, value) {
        var el = byId(id);
        if (el) el.textContent = value == null || value === '' ? '-' : String(value);
    }

    async function loadCurrentUser() {
        try {
            var user = await api('/user/profile', { silent: true });
            if (user) {
                currentUserId = user.id;
                currentUserName = user.realName || user.username || '我';
                currentUserAvatar = user.avatar || '';
            }
        } catch (error) {
            console.warn('获取当前用户失败', error);
        }
    }

    async function loadTeamInfo() {
        teamInfo = await api('/team-space/' + teamId + '/info');
        var name = teamInfo.teamName || teamInfo.title || '团队空间';
        document.title = name + ' - iLink';
        setText('sidebarTeamName', name);
        setText('kanbanTitle', name);
        setText('chatTitle', name);

        var warning = byId('teamWarning');
        if (warning) warning.style.display = teamInfo.status === 'OPEN' ? 'flex' : 'none';

        var createBtn = byId('createTaskBtn');
        if (createBtn) {
            var canCreate = teamInfo.isLeader !== false && teamInfo.status !== 'CLOSED';
            createBtn.style.display = canCreate ? 'inline-flex' : 'none';
        }
        renderOverview(teamInfo);
    }

    async function loadOverview() {
        try {
            var overview = await api('/team-space/' + teamId + '/overview', { silent: true });
            renderOverview(overview || {});
            return true;
        } catch (error) {
            console.warn('加载团队概要失败', error);
            if (teamInfo) renderOverview(teamInfo);
            return false;
        }
    }

    function renderOverview(data) {
        data = data || {};
        var memberCount = data.memberCount != null ? data.memberCount : (Array.isArray(members) ? members.length : 0);
        var requiredCount = data.requiredMemberCount || data.requiredMemberNum || '-';
        setText('footerMemberCount', memberCount + '/' + requiredCount);
        setText('footerStatus', teamStatusLabel(data.status));
        setText('footerDeadline', data.deadline ? formatDate(data.deadline) : '长期有效');
    }

    async function loadMembers() {
        try {
            var data = await api('/team-space/' + teamId + '/members', { silent: true });
            members = Array.isArray(data) ? data : [];
            var countEl = byId('membersCount');
            if (countEl) countEl.textContent = '(' + members.length + ')';
            renderMembersList();
            fillAssigneeSelect();
            if (teamInfo) renderOverview(teamInfo);
            return true;
        } catch (error) {
            console.warn('加载成员列表失败', error);
            var area = byId('membersListArea');
            if (area) area.innerHTML = renderEmptyState('成员列表暂时无法加载，请稍后刷新');
            return false;
        }
    }

    function fillAssigneeSelect(selectedId) {
        var select = byId('newTaskAssignee');
        if (!select) return;
        select.innerHTML = '<option value="">选择负责人</option>';
        members.forEach(function (member) {
            var option = document.createElement('option');
            option.value = member.userId || member.id || '';
            option.textContent = (member.username || member.realName || '成员') + (member.role ? '（' + member.role + '）' : '');
            if (selectedId && Number(selectedId) === Number(option.value)) option.selected = true;
            select.appendChild(option);
        });
    }

    function renderMembersList() {
        var area = byId('membersListArea');
        if (!area) return;
        if (!members.length) {
            area.innerHTML = renderEmptyState('暂无成员');
            return;
        }

        area.innerHTML = members.map(function (member) {
            var name = member.username || member.realName || '成员';
            var userId = member.userId || member.id || '';
            var avatar = avatarUrl(member.avatar);
            var initials = escapeHtml(name).slice(0, 2).toUpperCase();
            var meta = [member.major, member.grade, member.school].filter(Boolean).map(safeText).join(' / ');
            var role = member.isLeader || member.role === 'LEADER' ? '队长' : (member.role || '队员');
            var skills = Array.isArray(member.skills) ? member.skills.slice(0, 4) : [];
            var skillHtml = skills.length ? '<div class="member-card-skills">' + skills.map(function (skill) {
                return '<span class="skill-tag">' + escapeHtml(skill) + '</span>';
            }).join('') + '</div>' : '';
            var joinTime = member.joinedAt ? '<div class="member-card-meta">加入于 ' + formatDate(member.joinedAt) + '</div>' : '';
            var avatarHtml = avatar
                ? '<img src="' + escapeHtml(avatar) + '" alt="' + escapeHtml(name) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\';"><div class="avatar-placeholder" style="display:none;">' + initials + '</div>'
                : '<div class="avatar-placeholder">' + initials + '</div>';

            return '<button type="button" class="member-card" onclick="window.location.href=\'/user-profile.html?id=' + escapeHtml(userId) + '\'">' +
                '<span class="member-card-avatar">' + avatarHtml + '<span class="member-online-dot ' + (member.online ? 'online' : 'offline') + '"></span></span>' +
                '<span class="member-card-info">' +
                    '<span class="member-card-name">' + escapeHtml(name) + '</span>' +
                    '<span class="member-card-role">' + escapeHtml(role) + '</span>' +
                    (meta ? '<span class="member-card-meta">' + meta + '</span>' : '') +
                    joinTime +
                    skillHtml +
                '</span>' +
            '</button>';
        }).join('');
    }

    async function loadTasks() {
        try {
            var data = await api('/tasks?teamId=' + encodeURIComponent(teamId), { silent: true });
            tasks = Array.isArray(data) ? data.map(adaptTask) : [];
            renderBoard();
            return true;
        } catch (error) {
            console.warn('加载任务失败', error);
            tasks = [];
            renderBoard();
            return false;
        }
    }

    function adaptTask(task) {
        task = task || {};
        return {
            id: task.id,
            taskTitle: task.taskTitle || task.title || '未命名任务',
            taskDescription: task.taskDescription || task.description || '',
            taskType: String(task.taskType || task.type || 'other').toLowerCase(),
            priority: Number(task.priority || 2),
            status: normalizeStatus(task.status),
            estimatedHours: task.estimatedHours,
            actualHours: task.actualHours,
            deadline: task.deadline,
            assignedTo: task.assignedTo || task.assigneeId || null,
            assigneeName: task.assigneeName || task.assignee || '',
            assigneeAvatar: task.assigneeAvatar || '',
            createdBy: task.createdBy,
            creatorName: task.creatorName || '',
            createdAt: task.createdAt,
            completedAt: task.completedAt
        };
    }

    function renderBoard() {
        var grouped = { todo: [], in_progress: [], review: [], completed: [] };
        tasks.forEach(function (task) {
            grouped[normalizeStatus(task.status)].push(task);
        });

        renderTaskColumn('todoList', 'todoCount', grouped.todo);
        renderTaskColumn('inProgressList', 'inProgressCount', grouped.in_progress);
        renderTaskColumn('reviewList', 'reviewCount', grouped.review);
        renderTaskColumn('completedList', 'completedCount', grouped.completed);
        bindTaskDragEvents();
    }

    function renderTaskColumn(listId, countId, list) {
        setText(countId, list.length);
        var area = byId(listId);
        if (!area) return;
        area.innerHTML = list.length ? list.map(renderTaskCard).join('') : renderEmptyState('暂无任务，点击创建任务开始协作');
    }

    function renderTaskCard(task) {
        var overdue = isTaskOverdue(task);
        var deadline = task.deadline ? formatDate(task.deadline) : '';
        var assigneeHtml = '';
        if (task.assigneeName) {
            var avatar = avatarUrl(task.assigneeAvatar);
            var initials = escapeHtml(task.assigneeName).slice(0, 2).toUpperCase();
            assigneeHtml = '<span class="task-card-assignee">' +
                (avatar
                    ? '<img src="' + escapeHtml(avatar) + '" alt="' + escapeHtml(task.assigneeName) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'inline-flex\';"><span style="display:none;">' + initials + '</span>'
                    : '<span>' + initials + '</span>') +
                '<span>' + escapeHtml(task.assigneeName) + '</span>' +
            '</span>';
        }

        return '<article class="task-card" data-task-id="' + escapeHtml(task.id) + '" draggable="true" onclick="teamSpace.openTaskDetail(' + Number(task.id) + ')">' +
            '<header class="task-card-header">' +
                '<h2 class="task-card-title">' + escapeHtml(task.taskTitle) + '</h2>' +
                '<span class="' + statusBadgeClass(task.status) + '">' + statusLabel(task.status) + '</span>' +
            '</header>' +
            (task.taskDescription ? '<p class="task-card-desc">' + escapeHtml(task.taskDescription) + '</p>' : '') +
            '<div class="task-card-tags">' +
                '<span class="task-card-tag">优先级 ' + escapeHtml(priorityLabel(task.priority)) + '</span>' +
                '<span class="task-card-tag">' + escapeHtml(typeLabel(task.taskType)) + '</span>' +
            '</div>' +
            '<footer class="task-card-footer">' +
                (deadline ? '<span class="' + (overdue ? 'task-card-overdue' : '') + '">' + escapeHtml(deadline) + (overdue ? ' 已逾期' : '') + '</span>' : '<span></span>') +
                assigneeHtml +
            '</footer>' +
        '</article>';
    }

    function bindTaskDragEvents() {
        document.querySelectorAll('.task-card').forEach(function (card) {
            card.addEventListener('dragstart', function (event) {
                card.classList.add('dragging');
                event.dataTransfer.setData('text/plain', card.dataset.taskId);
                event.dataTransfer.effectAllowed = 'move';
            });
            card.addEventListener('dragend', function () {
                card.classList.remove('dragging');
                document.querySelectorAll('.kanban-col').forEach(function (col) {
                    col.classList.remove('drag-over');
                });
            });
        });
    }

    async function updateTaskStatus(taskId, newStatus) {
        var apiStatus = {
            todo: 'PENDING',
            in_progress: 'IN_PROGRESS',
            review: 'REVIEW',
            completed: 'COMPLETED'
        }[normalizeStatus(newStatus)] || String(newStatus || '').toUpperCase();

        await api('/tasks/' + taskId + '/status', {
            method: 'PUT',
            body: JSON.stringify({ status: apiStatus })
        });
    }

    function setupSidebarNav() {
        document.querySelectorAll('.sidebar-nav-item').forEach(function (item) {
            item.addEventListener('click', function () {
                switchPanel(item.dataset.panel || 'kanban-panel');
            });
        });
    }

    function switchPanel(panelId) {
        currentPanelId = panelId || 'kanban-panel';
        document.querySelectorAll('.sidebar-nav-item').forEach(function (item) {
            item.classList.toggle('active', item.dataset.panel === currentPanelId);
        });
        document.querySelectorAll('.workspace-panel').forEach(function (panel) {
            panel.classList.toggle('active', panel.id === currentPanelId);
        });

        if (currentPanelId === 'chat-panel') {
            loadChatHistory();
            if (!isConnected && !stompClient) connectWebSocket();
        } else if (currentPanelId === 'members-panel') {
            loadMembers();
        } else {
            loadTasks();
        }
    }

    async function loadChatHistory() {
        try {
            var messages = await api('/team/' + teamId + '/messages?limit=50', { silent: true });
            messages = Array.isArray(messages) ? messages : [];
            var area = byId('chatMessagesArea');
            if (!area) return false;
            area.innerHTML = messages.length ? messages.map(renderChatMessage).join('') : renderEmptyState('暂无消息，先和队友打个招呼');
            scrollChatToBottom();
            return true;
        } catch (error) {
            console.warn('加载聊天历史失败', error);
            var chatArea = byId('chatMessagesArea');
            if (chatArea) chatArea.innerHTML = renderEmptyState('聊天暂时无法加载，请稍后刷新');
            return false;
        }
    }

    function renderChatMessage(message) {
        message = message || {};
        var sent = Number(message.senderId) === Number(currentUserId);
        var name = message.senderName || (sent ? currentUserName : '队友');
        var initials = escapeHtml(name).slice(0, 2).toUpperCase();
        var avatar = avatarUrl(sent ? currentUserAvatar : message.senderAvatar);
        var avatarHtml = avatar
            ? '<img src="' + escapeHtml(avatar) + '" alt="' + escapeHtml(name) + '" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\';"><span style="display:none;">' + initials + '</span>'
            : '<span>' + initials + '</span>';

        return '<div class="chat-msg ' + (sent ? 'sent' : 'received') + '">' +
            '<div class="chat-msg-avatar">' + avatarHtml + '</div>' +
            '<div class="chat-msg-body">' +
                (!sent ? '<div class="chat-msg-name">' + escapeHtml(name) + '</div>' : '') +
                '<div class="chat-msg-bubble">' + renderMessageContent(message) + '</div>' +
                '<div class="chat-msg-time">' + escapeHtml(formatTime(message.createdAt)) + '</div>' +
            '</div>' +
        '</div>';
    }

    function renderMessageContent(message) {
        var content = String(message.content || '');
        var type = String(message.messageType || message.type || '').toUpperCase();
        if (type === 'IMAGE') {
            return '<img class="chat-image" src="' + escapeHtml(content) + '" alt="聊天图片">';
        }
        if (type === 'FILE') {
            var parts = content.split('|');
            var url = parts[0] || '';
            var name = parts[1] || '下载附件';
            return url ? '<a class="chat-file-link" href="' + escapeHtml(url) + '" target="_blank" rel="noopener">' + escapeHtml(name) + '</a>' : escapeHtml(name);
        }
        return escapeHtml(content).replace(/\n/g, '<br>');
    }

    function scrollChatToBottom() {
        var area = byId('chatMessagesArea');
        if (area) area.scrollTop = area.scrollHeight;
    }

    function appendChatMessage(message) {
        var area = byId('chatMessagesArea');
        if (!area) return;
        if (area.querySelector('.kanban-empty')) area.innerHTML = '';
        area.insertAdjacentHTML('beforeend', renderChatMessage(message));
        scrollChatToBottom();
    }

    function connectWebSocket() {
        if (typeof STOMP === 'undefined' && typeof Stomp === 'undefined') {
            setConnectionState(false, '轮询中');
            startPolling();
            return;
        }

        try {
            var socket = typeof SockJS !== 'undefined' ? new SockJS('/ws') : new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws-native');
            var stompFactory = typeof STOMP !== 'undefined' ? STOMP : Stomp;
            stompClient = stompFactory.over(socket);
            stompClient.debug = null;
            stompClient.connect({}, function () {
                isConnected = true;
                setConnectionState(true, '已连接');
                stompClient.subscribe('/topic/team/' + teamId, function (frame) {
                    try {
                        appendChatMessage(JSON.parse(frame.body));
                    } catch (error) {
                        console.warn('解析聊天消息失败', error);
                    }
                });
            }, function () {
                isConnected = false;
                setConnectionState(false, '轮询中');
                startPolling();
            });
        } catch (error) {
            console.warn('连接 WebSocket 失败', error);
            isConnected = false;
            setConnectionState(false, '轮询中');
            startPolling();
        }
    }

    function setConnectionState(connected, text) {
        var status = byId('connStatus');
        var label = byId('connText');
        if (status) status.className = connected ? 'conn-status connected' : 'conn-status disconnected';
        if (label) label.textContent = text || (connected ? '已连接' : '未连接');
    }

    function startPolling() {
        if (pollingTimer) return;
        pollingTimer = window.setInterval(function () {
            if (currentPanelId === 'chat-panel') loadChatHistory();
        }, 10000);
    }

    async function postChatMessage(content, type) {
        await api('/team/' + teamId + '/messages', {
            method: 'POST',
            body: JSON.stringify({ content: content, type: type || 'TEXT' })
        });
    }

    function resetCreateTaskForm() {
        ['newTaskTitle', 'newTaskDesc', 'newTaskDeadline', 'newTaskHours'].forEach(function (id) {
            var el = byId(id);
            if (el) el.value = '';
        });
        var type = byId('newTaskType');
        var priority = byId('newTaskPriority');
        if (type) type.value = 'development';
        if (priority) priority.value = '2';
        fillAssigneeSelect();
    }

    function bindToolbarEvents() {
        var emojiBtn = document.querySelector('.chat-tool-btn[title="表情"]');
        var fileBtn = document.querySelector('.chat-tool-btn[title="文件"]');
        var imageBtn = document.querySelector('.chat-tool-btn[title="图片"]');

        if (emojiBtn) emojiBtn.addEventListener('click', toggleEmojiPicker);
        if (fileBtn) setupUploadButton(fileBtn, false);
        if (imageBtn) setupUploadButton(imageBtn, true);
    }

    function setupUploadButton(button, imageOnly) {
        var input = document.createElement('input');
        input.type = 'file';
        input.style.display = 'none';
        if (imageOnly) input.accept = 'image/*';
        document.body.appendChild(input);
        button.addEventListener('click', function () {
            input.click();
        });
        input.addEventListener('change', function () {
            if (input.files && input.files[0]) uploadChatFile(input.files[0], imageOnly);
            input.value = '';
        });
    }

    async function uploadChatFile(file, imageOnly) {
        var form = new FormData();
        form.append('file', file);
        notify('上传中...', 'info');
        try {
            var response = await apiFetch('/api/upload', {
                method: 'POST',
                body: form
            });
            var result = await response.json();
            if (!response.ok || Number(result.code) !== 200) {
                throw new Error(result.message || '上传失败');
            }
            var data = result.data || {};
            var url = typeof data === 'string' ? data : (data.url || data.path || '');
            if (!url) throw new Error('上传结果缺少文件地址');
            var type = imageOnly ? 'IMAGE' : 'FILE';
            var content = imageOnly ? url : url + '|' + file.name;
            appendChatMessage({
                senderId: currentUserId,
                senderName: currentUserName || '我',
                senderAvatar: currentUserAvatar,
                content: content,
                type: type,
                createdAt: new Date().toISOString()
            });
            await postChatMessage(content, type);
            notify('上传成功', 'success');
        } catch (error) {
            console.error('上传失败', error);
            notify(error.message || '上传失败', 'error');
        }
    }

    function toggleEmojiPicker(event) {
        if (event) event.stopPropagation();
        if (emojiPickerEl) {
            closeEmojiPicker();
            return;
        }
        var emojis = ['😀', '😂', '😅', '😊', '👍', '👏', '🙏', '💪', '🔥', '✅', '⭐', '📌', '📎', '💡', '🎯', '🚩'];
        emojiPickerEl = document.createElement('div');
        emojiPickerEl.className = 'emoji-picker';
        emojiPickerEl.innerHTML = '<div class="emoji-grid">' + emojis.map(function (emoji) {
            return '<button type="button" class="emoji-item" data-emoji="' + emoji + '">' + emoji + '</button>';
        }).join('') + '</div>';
        document.body.appendChild(emojiPickerEl);
        emojiPickerEl.addEventListener('click', function (e) {
            var item = e.target.closest('.emoji-item');
            if (!item) return;
            insertAtCursor(byId('chatInput'), item.dataset.emoji || '');
            closeEmojiPicker();
        });
        setTimeout(function () {
            document.addEventListener('click', closeEmojiPicker);
        }, 0);
    }

    function closeEmojiPicker() {
        if (emojiPickerEl) {
            emojiPickerEl.remove();
            emojiPickerEl = null;
        }
        document.removeEventListener('click', closeEmojiPicker);
    }

    function insertAtCursor(input, text) {
        if (!input) return;
        var start = input.selectionStart || input.value.length;
        var end = input.selectionEnd || input.value.length;
        input.value = input.value.slice(0, start) + text + input.value.slice(end);
        input.focus();
        input.selectionStart = input.selectionEnd = start + text.length;
        window.teamSpace.autoResizeChatInput(input);
    }

    window.teamSpace = {
        handleColDragOver: function (event) {
            event.preventDefault();
            event.currentTarget.classList.add('drag-over');
        },
        handleColDragLeave: function (event) {
            if (!event.currentTarget.contains(event.relatedTarget)) {
                event.currentTarget.classList.remove('drag-over');
            }
        },
        handleColDrop: async function (event) {
            event.preventDefault();
            var column = event.currentTarget;
            column.classList.remove('drag-over');
            var taskId = event.dataTransfer.getData('text/plain');
            var nextStatus = column.dataset.status;
            if (!taskId || !nextStatus) return;
            try {
                await updateTaskStatus(taskId, nextStatus);
                await loadTasks();
                notify('任务状态已更新', 'success');
            } catch (error) {
                notify(error.message || '状态更新失败', 'error');
            }
        },
        refreshCurrentPanel: async function () {
            notify('刷新中...', 'info');
            var ok = false;
            if (currentPanelId === 'chat-panel') ok = await loadChatHistory();
            else if (currentPanelId === 'members-panel') ok = await loadMembers();
            else ok = await loadTasks();
            await loadOverview();
            notify(ok ? '已刷新' : '刷新失败', ok ? 'success' : 'error');
        },
        openCreateModal: function () {
            if (teamInfo && teamInfo.status === 'CLOSED') {
                notify('项目已结束，不能创建新任务', 'warning');
                return;
            }
            resetCreateTaskForm();
            bootstrap.Modal.getOrCreateInstance(byId('createTaskModal')).show();
        },
        createTask: async function () {
            var title = byId('newTaskTitle').value.trim();
            var assignee = byId('newTaskAssignee').value;
            if (!title) {
                notify('请输入任务标题', 'warning');
                return;
            }
            if (!assignee) {
                notify('请选择负责人', 'warning');
                return;
            }

            var deadline = byId('newTaskDeadline').value;
            var payload = {
                taskTitle: title,
                taskDescription: byId('newTaskDesc').value.trim(),
                taskType: byId('newTaskType').value,
                priority: Number(byId('newTaskPriority').value || 2),
                deadline: deadline ? new Date(deadline).toISOString() : null,
                estimatedHours: byId('newTaskHours').value ? Number(byId('newTaskHours').value) : null,
                assignedTo: Number(assignee)
            };

            try {
                await api('/team/' + teamId + '/tasks', {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                bootstrap.Modal.getInstance(byId('createTaskModal'))?.hide();
                await loadTasks();
                notify('任务已创建', 'success');
            } catch (error) {
                notify(error.message || '创建失败', 'error');
            }
        },
        openTaskDetail: function (taskId) {
            var task = tasks.find(function (item) { return Number(item.id) === Number(taskId); });
            if (!task) {
                notify('任务不存在', 'error');
                return;
            }
            setText('detailTitle', task.taskTitle);
            var desc = byId('detailDesc');
            if (desc) desc.textContent = task.taskDescription || '暂无描述';
            var detailStatus = byId('detailStatus');
            if (detailStatus) detailStatus.innerHTML = '<span class="' + statusBadgeClass(task.status) + '">' + statusLabel(task.status) + '</span>';
            setText('detailPriority', priorityLabel(task.priority));
            setText('detailType', typeLabel(task.taskType));
            setText('detailAssignee', task.assigneeName || '未分配');
            setText('detailDeadline', task.deadline ? formatDate(task.deadline) + (isTaskOverdue(task) ? ' 已逾期' : '') : '无截止日期');
            setText('detailCreatedAt', task.createdAt ? formatTime(task.createdAt) : '-');

            var footer = byId('detailFooter');
            if (footer) {
                footer.innerHTML =
                    '<button type="button" class="btn btn-secondary" data-bs-dismiss="modal">关闭</button>' +
                    '<button type="button" class="btn btn-primary" onclick="teamSpace.advanceTaskStatus(' + Number(task.id) + ')">推进状态</button>' +
                    '<button type="button" class="btn btn-outline-secondary" onclick="teamSpace.deleteTask(' + Number(task.id) + ')">删除</button>';
            }
            bootstrap.Modal.getOrCreateInstance(byId('taskDetailModal')).show();
        },
        advanceTaskStatus: async function (taskId) {
            var task = tasks.find(function (item) { return Number(item.id) === Number(taskId); });
            if (!task) return;
            var map = { todo: 'in_progress', in_progress: 'review', review: 'completed' };
            var next = map[normalizeStatus(task.status)];
            if (!next) {
                notify('当前任务已经完成', 'info');
                return;
            }
            try {
                await updateTaskStatus(taskId, next);
                bootstrap.Modal.getInstance(byId('taskDetailModal'))?.hide();
                await loadTasks();
                notify('任务状态已更新', 'success');
            } catch (error) {
                notify(error.message || '状态更新失败', 'error');
            }
        },
        deleteTask: async function (taskId) {
            if (!window.confirm('确定删除这个任务吗？')) return;
            try {
                await api('/tasks/' + taskId, { method: 'DELETE' });
                bootstrap.Modal.getInstance(byId('taskDetailModal'))?.hide();
                await loadTasks();
                notify('任务已删除', 'success');
            } catch (error) {
                notify(error.message || '删除失败', 'error');
            }
        },
        updateTaskStatus: async function (taskId, newStatus) {
            await updateTaskStatus(taskId, newStatus);
            await loadTasks();
        },
        sendChatMessage: async function () {
            var input = byId('chatInput');
            var content = input ? input.value.trim() : '';
            if (!content) return;
            if (input) {
                input.value = '';
                input.style.height = 'auto';
            }
            appendChatMessage({
                senderId: currentUserId,
                senderName: currentUserName || '我',
                senderAvatar: currentUserAvatar,
                content: content,
                type: 'TEXT',
                createdAt: new Date().toISOString()
            });
            try {
                await postChatMessage(content, 'TEXT');
            } catch (error) {
                notify(error.message || '发送失败', 'error');
            }
        },
        handleChatKeyDown: function (event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                window.teamSpace.sendChatMessage();
            }
        },
        autoResizeChatInput: function (textarea) {
            textarea.style.height = 'auto';
            textarea.style.height = Math.min(textarea.scrollHeight, 120) + 'px';
        }
    };

    async function init() {
        if (!teamId) {
            notify('缺少团队ID参数', 'error');
            window.setTimeout(function () {
                window.location.href = '/team-market.html';
            }, 1200);
            return;
        }
        setupSidebarNav();
        bindToolbarEvents();
        await loadCurrentUser();
        await Promise.allSettled([loadTeamInfo(), loadOverview(), loadMembers()]);
        await loadTasks();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
