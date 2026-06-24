class NotificationManager {
    constructor(userId) {
        this.userId = userId;
        this.pollingInterval = null;
        this.pollingDelay = 30000;
    }

    init() {
        this.whenApiReady(() => {
            this.pollUnreadCount();
            this.startPolling();
        });
    }

    whenApiReady(callback, attempt = 0) {
        if (typeof apiFetch === 'function') {
            callback();
            return;
        }
        if (attempt >= 50) {
            console.warn('通知模块等待 apiFetch 超时');
            return;
        }
        setTimeout(() => this.whenApiReady(callback, attempt + 1), 100);
    }

    request(url, options) {
        if (typeof apiFetch !== 'function') {
            return Promise.reject(new Error('apiFetch 未就绪'));
        }
        return apiFetch(url, options);
    }

    loadNotifications() {
        const listContainer = document.getElementById('notificationList');
        if (!listContainer) return;

        const url = `/api/notifications?userId=${this.userId}&limit=10`;

        this.request(url, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'include'
        })
        .then(response => response.json())
        .then(data => {
            if (data.code === 200 && data.data) {
                this.renderNotifications(data.data);
            }
        })
        .catch(error => {
            console.error('加载通知失败:', error);
        });
    }

    renderNotifications(notifications) {
        const listContainer = document.getElementById('notificationList');
        if (!listContainer) return;

        if (!notifications || notifications.length === 0) {
            listContainer.innerHTML = '<div class="notification-dropdown__empty">暂无通知</div>';
            return;
        }

        let html = '';
        notifications.forEach(notification => {
            const unreadClass = notification.isRead ? '' : 'notification-item--unread';
            const iconSvg = this.getNotificationIcon(notification.type);
            // C-16: 对 relatedType 做 HTML 转义，防止 XSS
            const safeRelatedType = this.escapeHtml(notification.relatedType || '');
            const safeRelatedId = Number(notification.relatedId) || 0;

            html += `
                <div class="notification-item ${unreadClass}" data-id="${notification.id}" data-related-type="${safeRelatedType}" data-related-id="${safeRelatedId}" onclick="handleNotificationClick(${notification.id}, this.dataset.relatedType, this.dataset.relatedId)">
                    <div class="notification-item__icon">
                        ${iconSvg}
                    </div>
                    <div class="notification-item__content">
                        <div class="notification-item__title">${this.escapeHtml(notification.title)}</div>
                        <div class="notification-item__text">${this.escapeHtml(notification.content)}</div>
                        <div class="notification-item__time">${notification.timeAgo}</div>
                    </div>
                    ${!notification.isRead ? '<div class="notification-item__dot"></div>' : ''}
                </div>
            `;
        });

        listContainer.innerHTML = html;
    }

    getNotificationIcon(type) {
        const icons = {
            'TEAM_INVITE': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
            'TASK_ASSIGNED': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>',
            'TASK_COMPLETED': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>',
            'MILESTONE_UPDATE': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>',
            'RECOMMENDATION': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>',
            'SYSTEM': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>'
        };
        return icons[type] || icons['SYSTEM'];
    }

    markAsRead(id) {
        const url = `/api/notifications/${id}/read`;

        this.request(url, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'include'
        })
        .then(response => response.json())
        .then(data => {
            if (data.code === 200) {
                const item = document.querySelector(`.notification-item[data-id="${id}"]`);
                if (item) {
                    item.classList.remove('notification-item--unread');
                    const dot = item.querySelector('.notification-item__dot');
                    if (dot) dot.remove();
                }
                this.pollUnreadCount();
            }
        })
        .catch(error => {
            console.error('标记已读失败:', error);
        });
    }

    markAllAsRead() {
        const url = `/api/notifications/read-all?userId=${this.userId}`;

        this.request(url, {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'include'
        })
        .then(response => response.json())
        .then(data => {
            if (data.code === 200) {
                const items = document.querySelectorAll('.notification-item--unread');
                items.forEach(item => {
                    item.classList.remove('notification-item--unread');
                    const dot = item.querySelector('.notification-item__dot');
                    if (dot) dot.remove();
                });
                this.pollUnreadCount();
            }
        })
        .catch(error => {
            console.error('标记全部已读失败:', error);
        });
    }

    pollUnreadCount() {
        const url = `/api/notifications/unread-count?userId=${this.userId}`;

        this.request(url, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'include'
        })
        .then(response => response.json())
        .then(data => {
            if (data.code === 200 && data.data) {
                this.updateUnreadBadge(data.data.count);
            }
        })
        .catch(error => {
            console.error('获取未读数失败:', error);
        });
    }

    updateUnreadBadge(count) {
        const badge = document.getElementById('unreadCount');
        if (!badge) return;

        if (count > 0) {
            badge.textContent = count > 99 ? '99+' : count;
            badge.classList.remove('notification-bell--hidden');
            badge.classList.add('notification-bell--visible');
        } else {
            badge.classList.remove('notification-bell--visible');
            badge.classList.add('notification-bell--hidden');
        }
    }

    startPolling() {
        if (this.pollingInterval) {
            clearInterval(this.pollingInterval);
        }
        this.pollingInterval = setInterval(() => {
            this.pollUnreadCount();
        }, this.pollingDelay);
    }

    stopPolling() {
        if (this.pollingInterval) {
            clearInterval(this.pollingInterval);
            this.pollingInterval = null;
        }
    }

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

function handleNotificationClick(id, relatedType, relatedId) {
    if (window.notificationManager) {
        window.notificationManager.markAsRead(id);
    }

    if (relatedType && relatedId) {
        const routes = {
            'TEAM': `/team-detail.html?id=${relatedId}`,
            'TASK': `/team-workspace.html?taskId=${relatedId}`,
            'PROJECT': `/team-detail.html?id=${relatedId}`,
            'USER': `/profile.html?id=${relatedId}`
        };

        const route = routes[relatedType];
        if (route) {
            window.location.href = route;
        }
    }
}
