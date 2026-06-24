class ChatManager {
    constructor() {
        this.teamId = null;
        this.currentUser = null;
        this.stompClient = null;
        this.messages = [];
        this.members = [];
        this.subscription = null;
        this.isConnected = false;
        this.messageQueue = [];
        this.pollingTimer = null;
        this.reconnectTimer = null;
    }

    async init(teamId) {
        if (!(await this.loadCurrentUser())) return;

        this.teamId = teamId;
        if (!this.teamId) {
            showMessage('缺少团队ID参数', 'error');
            setTimeout(() => { window.location.href = '/team-market.html'; }, 1200);
            return;
        }

        this.loadTeamInfo();
        this.loadMembers();
        this.loadHistory();
        this.connectWebSocket();
        this.setupInputHandlers();
    }

    async loadCurrentUser() {
        try {
            const response = await apiFetch('/api/user/profile');
            const result = await response.json();
            if (result.code !== 200 || !result.data) {
                showMessage('请先登录', 'warning');
                setTimeout(() => { window.location.href = '/login.html'; }, 1200);
                return false;
            }
            const user = result.data;
            this.currentUser = {
                id: user.id || user.userId,
                displayName: user.displayName || user.realName || user.username || '用户',
                username: user.username || '',
                realName: user.realName || '',
                role: user.role || '成员'
            };
            return true;
        } catch (e) {
            console.error('获取用户信息失败:', e);
            showMessage('登录信息无效，请重新登录', 'error');
            setTimeout(() => { window.location.href = '/login.html'; }, 1200);
            return false;
        }
    }

    normalizeMember(raw) {
        return {
            id: raw.userId || raw.id,
            username: raw.username || '',
            displayName: raw.displayName || raw.username || '成员',
            role: raw.role || '成员',
            isOnline: raw.isOnline || false
        };
    }

    normalizeMessage(raw) {
        const type = (raw.messageType || raw.type || 'TEXT').toLowerCase();
        const ts = raw.createdAt || raw.timestamp;
        return {
            id: raw.id,
            teamId: raw.teamId,
            senderId: raw.senderId,
            senderName: raw.senderName || '未知用户',
            content: raw.content || '',
            timestamp: ts ? (typeof ts === 'number' ? new Date(ts).toISOString() : ts) : new Date().toISOString(),
            type: type === 'file' ? 'file' : 'text',
            fileName: raw.fileName,
            fileUrl: raw.fileUrl
        };
    }

    loadTeamInfo() {
        const teamData = localStorage.getItem('currentTeam');
        if (teamData) {
            try {
                const team = JSON.parse(teamData);
                this.applyTeamHeader(team);
            } catch (e) {
                console.error('解析团队缓存失败:', e);
            }
        } else {
            document.getElementById('chatTitle').textContent = '团队聊天室';
            document.getElementById('chatSubtitle').textContent = '团队沟通协作';
        }

        if (this.teamId) {
            this.fetchTeamInfo(this.teamId);
        }
    }

    applyTeamHeader(team) {
        const title = team.title || team.name || '团队';
        document.getElementById('chatTitle').textContent = title + ' 聊天室';
        document.getElementById('chatSubtitle').textContent = team.description || '团队沟通协作';
    }

    async fetchTeamInfo(teamId) {
        try {
            const team = await request(`/team/${teamId}`);
            this.applyTeamHeader(team);
            localStorage.setItem('currentTeam', JSON.stringify(team));
        } catch (error) {
            console.error('获取团队信息失败:', error);
        }
    }

    async loadMembers() {
        if (!this.teamId) return;

        try {
            const data = await request(`/team/${this.teamId}/members`);
            this.members = (Array.isArray(data) ? data : []).map(m => this.normalizeMember(m));
            this.renderMembers();
        } catch (error) {
            console.error('加载成员失败:', error);
            this.members = [];
            this.renderMembers();
            showMessage('加载成员列表失败', 'error');
        }
    }

    renderMembers() {
        const memberList = document.getElementById('memberList');
        const memberCount = document.getElementById('memberCount');

        memberCount.textContent = `(${this.members.length})`;

        if (!memberList) return;

        memberList.innerHTML = this.members.map(member => {
            const displayName = escapeHtml(member.displayName);
            const initials = escapeHtml(this.getInitials(member.displayName));
            const isCurrentUser = member.id === this.currentUser.id;
            const roleLabel = escapeHtml(member.role || '成员');

            return `
                <div class="member-item ${isCurrentUser ? 'bg-primary bg-opacity-10' : ''}">
                    <div class="member-avatar">
                        ${initials}
                        <span class="member-status ${member.isOnline ? 'online' : 'offline'}"></span>
                    </div>
                    <div class="member-info">
                        <div class="member-name">${displayName}${isCurrentUser ? ' (我)' : ''}</div>
                        <div class="member-role">${roleLabel}</div>
                    </div>
                </div>
            `;
        }).join('');
    }

    async loadHistory() {
        if (!this.teamId) {
            this.messages = [];
            this.renderMessages();
            return;
        }

        try {
            const data = await request(`/team/${this.teamId}/messages?limit=50`);
            const list = Array.isArray(data) ? data : (data && data.content) || [];
            this.messages = list.map(m => this.normalizeMessage(m));
            this.renderMessages();
        } catch (error) {
            console.error('加载历史消息失败:', error);
            this.messages = [];
            this.renderMessages();
            showMessage('加载聊天记录失败', 'error');
        }
    }

    renderMessages() {
        const container = document.getElementById('chatMessages');
        const emptyState = document.getElementById('chatEmpty');

        if (!container) return;

        if (this.messages.length === 0) {
            if (emptyState) {
                emptyState.style.display = 'flex';
            }
            container.innerHTML = '';
            return;
        }

        if (emptyState) {
            emptyState.style.display = 'none';
        }

        let html = '';
        let lastDate = null;

        this.messages.forEach(message => {
            const messageDate = this.formatDateHeader(new Date(message.timestamp));

            if (messageDate !== lastDate) {
                html += `
                    <div class="message-date-divider">
                        <span>${messageDate}</span>
                    </div>
                `;
                lastDate = messageDate;
            }

            html += this.renderMessage(message);
        });

        container.innerHTML = html;
        this.scrollToBottom();
    }

    renderMessage(message) {
        const isSent = message.senderId === this.currentUser.id;
        const initials = escapeHtml(this.getInitials(message.senderName));
        const senderName = escapeHtml(message.senderName);
        const time = this.formatTime(new Date(message.timestamp));
        const bubbleClass = isSent ? 'sent' : 'received';

        if (message.type === 'file') {
            const fileName = escapeHtml(message.fileName || '文件');
            const fileUrl = escapeHtml(message.fileUrl || '#');
            return `
                <div class="message ${bubbleClass}">
                    <div class="message-meta">
                        ${!isSent ? `<div class="message-avatar">${initials}</div>` : ''}
                        <span class="message-author">${senderName}</span>
                        <span class="message-time">${time}</span>
                    </div>
                    <div class="message-bubble">
                        <div class="message-file">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                                <path d="M14 2H6C5.46957 2 4.96086 2.21071 4.58579 2.58579C4.21071 2.96086 4 3.46957 4 4V20C4 20.5304 4.21071 21.0391 4.58579 21.4142C4.96086 21.7893 5.46957 22 6 22H18C18.5304 22 19.0391 21.7893 19.4142 21.4142C19.7893 21.0391 20 20.5304 20 20V8L14 2Z" stroke="currentColor" stroke-width="2"/>
                                <path d="M14 2V8H20" stroke="currentColor" stroke-width="2"/>
                            </svg>
                            <a href="${fileUrl}" target="_blank" rel="noopener">${fileName}</a>
                        </div>
                    </div>
                </div>
            `;
        }

        return `
            <div class="message ${bubbleClass}">
                ${!isSent ? `
                    <div class="message-meta">
                        <div class="message-avatar">${initials}</div>
                        <span class="message-author">${senderName}</span>
                        <span class="message-time">${time}</span>
                    </div>
                ` : `
                    <div class="message-meta">
                        <span class="message-time">${time}</span>
                    </div>
                `}
                <div class="message-bubble">${escapeHtml(message.content)}</div>
            </div>
        `;
    }

    stopPolling() {
        if (this.pollingTimer) {
            clearInterval(this.pollingTimer);
            this.pollingTimer = null;
        }
    }

    connectWebSocket() {
        if (typeof SockJS === 'undefined' || typeof Stomp === 'undefined') {
            console.warn('SockJS 或 STOMP 未加载，使用轮询模式');
            this.startPolling();
            return;
        }

        try {
            const socket = new SockJS('/ws');
            this.stompClient = Stomp.over(socket);
            this.stompClient.debug = () => {};

            this.stompClient.connect({}, () => {
                this.isConnected = true;
                this.updateConnectionStatus(true);
                this.stopPolling();
                this.subscribeToTeam();
                this.flushMessageQueue();
            }, (error) => {
                console.error('WebSocket 连接失败:', error);
                this.isConnected = false;
                this.updateConnectionStatus(false);
                this.startPolling();
                if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
                this.reconnectTimer = setTimeout(() => this.connectWebSocket(), 5000);
            });

            socket.onclose = () => {
                this.isConnected = false;
                this.updateConnectionStatus(false);
                this.startPolling();
            };

        } catch (error) {
            console.error('初始化 WebSocket 失败:', error);
            this.startPolling();
        }
    }

    subscribeToTeam() {
        if (!this.stompClient || !this.teamId) return;

        if (this.subscription) {
            this.subscription.unsubscribe();
        }

        this.subscription = this.stompClient.subscribe(
            `/topic/team/${this.teamId}`,
            (message) => {
                try {
                    const messageData = JSON.parse(message.body);
                    this.receiveMessage(this.normalizeMessage(messageData));
                } catch (error) {
                    console.error('解析消息失败:', error);
                }
            }
        );
    }

    startPolling() {
        if (this.pollingTimer) return;
        this.pollingTimer = setInterval(() => {
            if (this.teamId && !this.isConnected) {
                this.pollNewMessages();
            }
        }, 3000);
    }

    async pollNewMessages() {
        try {
            const data = await request(`/team/${this.teamId}/messages?limit=50`);
            const list = Array.isArray(data) ? data : [];
            list.map(m => this.normalizeMessage(m)).forEach(msg => {
                if (!this.messages.find(m => m.id === msg.id)) {
                    this.receiveMessage(msg, true);
                }
            });
        } catch (error) {
            console.error('轮询消息失败:', error);
        }
    }

    async sendMessage() {
        const input = document.getElementById('messageInput');
        const content = input.value.trim();

        if (!content) return;

        const tempId = Date.now();
        const optimistic = {
            id: tempId,
            teamId: this.teamId,
            senderId: this.currentUser.id,
            senderName: this.currentUser.displayName,
            content: content,
            timestamp: new Date().toISOString(),
            type: 'text'
        };

        input.value = '';
        input.style.height = 'auto';

        this.messages.push(optimistic);
        this.renderMessages();

        const payload = { content: content, type: 'TEXT' };

        if (this.isConnected && this.stompClient) {
            try {
                this.stompClient.send(
                    `/app/chat/${this.teamId}`,
                    {},
                    JSON.stringify(payload)
                );
            } catch (error) {
                console.error('发送消息失败:', error);
                this.messageQueue.push(payload);
                showMessage('消息发送失败，已加入重试队列', 'warning');
            }
        } else {
            try {
                const saved = await request(`/team/${this.teamId}/messages`, {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                const normalized = this.normalizeMessage(saved);
                const idx = this.messages.findIndex(m => m.id === tempId);
                if (idx >= 0) {
                    this.messages[idx] = normalized;
                } else {
                    this.messages.push(normalized);
                }
                this.renderMessages();
            } catch (error) {
                console.error('发送消息失败:', error);
                this.messages = this.messages.filter(m => m.id !== tempId);
                this.renderMessages();
            }
        }
    }

    receiveMessage(message, fromPoll) {
        if (!fromPoll && message.senderId === this.currentUser.id) return;

        const exists = this.messages.find(m => m.id === message.id);
        if (exists) return;

        this.messages.push(message);
        this.renderMessages();
    }

    flushMessageQueue() {
        while (this.messageQueue.length > 0) {
            const payload = this.messageQueue.shift();
            if (this.stompClient) {
                this.stompClient.send(
                    `/app/chat/${this.teamId}`,
                    {},
                    JSON.stringify(payload)
                );
            }
        }
    }

    async handleFileSelect(event) {
        const file = event.target.files[0];
        if (!file) return;

        showMessage('文件消息暂不支持，请使用文字沟通', 'info');
        event.target.value = '';
    }

    handleKeyDown(event) {
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            this.sendMessage();
        }
    }

    autoResize(textarea) {
        textarea.style.height = 'auto';
        textarea.style.height = Math.min(textarea.scrollHeight, 120) + 'px';
    }

    setupInputHandlers() {
        const input = document.getElementById('messageInput');
        const sendBtn = document.getElementById('sendBtn');

        if (input) {
            input.addEventListener('input', () => {
                if (sendBtn) {
                    sendBtn.disabled = !input.value.trim();
                }
            });

            if (sendBtn) {
                sendBtn.disabled = true;
            }
        }
    }

    updateConnectionStatus(connected) {
        const statusEl = document.getElementById('connectionStatus');
        const textEl = document.getElementById('connectionText');

        if (statusEl) {
            if (connected) {
                statusEl.classList.add('connected');
                statusEl.classList.remove('disconnected');
                if (textEl) textEl.textContent = '已连接';
            } else {
                statusEl.classList.remove('connected');
                statusEl.classList.add('disconnected');
                if (textEl) textEl.textContent = '未连接';
            }
        }
    }

    scrollToBottom() {
        const container = document.getElementById('chatMessages');
        if (container) {
            setTimeout(() => {
                container.scrollTop = container.scrollHeight;
            }, 100);
        }
    }

    formatTime(date) {
        const hours = date.getHours().toString().padStart(2, '0');
        const minutes = date.getMinutes().toString().padStart(2, '0');
        return `${hours}:${minutes}`;
    }

    formatDateHeader(date) {
        const today = new Date();
        const yesterday = new Date(today);
        yesterday.setDate(yesterday.getDate() - 1);

        if (date.toDateString() === today.toDateString()) {
            return '今天';
        } else if (date.toDateString() === yesterday.toDateString()) {
            return '昨天';
        } else {
            const month = date.getMonth() + 1;
            const day = date.getDate();
            return `${month}月${day}日`;
        }
    }

    getInitials(name) {
        if (!name) return '?';
        const parts = name.trim().split(/\s+/);
        if (parts.length >= 2) {
            return (parts[0][0] + parts[1][0]).toUpperCase();
        }
        return name.substring(0, 2).toUpperCase();
    }

    disconnect() {
        this.stopPolling();
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.subscription) {
            this.subscription.unsubscribe();
        }
        if (this.stompClient) {
            this.stompClient.disconnect();
        }
        this.isConnected = false;
    }
}

const chatManager = new ChatManager();

document.addEventListener('DOMContentLoaded', () => {
    const urlParams = new URLSearchParams(window.location.search);
    const teamId = urlParams.get('teamId');
    if (!teamId) {
        showMessage('缺少团队ID参数', 'error');
        setTimeout(() => { window.location.href = '/team-market.html'; }, 1200);
        return;
    }
    chatManager.init(parseInt(teamId, 10));
});

window.addEventListener('beforeunload', () => {
    chatManager.disconnect();
});
