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
    var editingTaskId = null;
    var mobileActionTaskId = null;
    var activeMobileStatus = 'todo';
    var suppressTaskClick = false;
    var mobileMedia = window.matchMedia('(max-width: 767.98px)');
    var projectMenuReturnFocus = null;
    var taskActionsReturnFocus = null;
    var focusableSelector = [
        'a[href]:not([tabindex="-1"])',
        'button:not([disabled]):not([hidden]):not([tabindex="-1"])',
        'input:not([disabled]):not([type="hidden"]):not([tabindex="-1"])',
        'select:not([disabled]):not([tabindex="-1"])',
        'textarea:not([disabled]):not([tabindex="-1"])',
        '[tabindex]:not([tabindex="-1"])'
    ].join(',');

    function byId(id) {
        return document.getElementById(id);
    }

    function isMobileLayout() {
        return mobileMedia.matches;
    }

    function getFocusableElements(container) {
        if (!container) return [];
        return Array.from(container.querySelectorAll(focusableSelector)).filter(function (element) {
            return !element.hidden && element.getAttribute('aria-hidden') !== 'true' && element.getClientRects().length > 0;
        });
    }

    function focusElement(element) {
        if (!element || !element.isConnected || typeof element.focus !== 'function') return;
        window.setTimeout(function () {
            if (element.isConnected) element.focus({ preventScroll: true });
        }, 0);
    }

    function trapFocus(container, event) {
        if (event.key !== 'Tab') return;
        var focusable = getFocusableElements(container);
        if (!focusable.length) {
            event.preventDefault();
            return;
        }
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && (document.activeElement === first || !container.contains(document.activeElement))) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && (document.activeElement === last || !container.contains(document.activeElement))) {
            event.preventDefault();
            first.focus();
        }
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
            var error = new Error(result && result.message ? result.message : '请求失败，请稍后重试');
            error.status = response.status;
            error.code = result && result.code;
            throw error;
        }
        return result.data;
    }

    function isLoginExpired(error) {
        return Number(error && error.status) === 401 || Number(error && error.code) === 401;
    }

    function renderLoginExpired() {
        var redirect = encodeURIComponent(window.location.pathname + window.location.search);
        return '<div class="panel-loading team-space-login-expired">登录状态已失效，请<a href="/login.html?redirect=' + escapeHtml(redirect) + '">重新登录</a></div>';
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
            TEAMING: '已组队',
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

    function renderEmptyState(text, clickable) {
        var html = '<div class="kanban-empty"' + (clickable ? ' onclick="teamSpace.openCreateModal()" role="button" tabindex="0"' : '') + '>' +
            '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 9h8M8 13h5"/></svg>' +
            '<p>' + (clickable ? '暂无任务 · <span class="kanban-empty-action">点击创建</span>' : escapeHtml(text || '暂无内容')) + '</p>' +
        '</div>';
        return html;
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
        setText('mobileProjectName', name);
        setText('kanbanTeamName', name);
        setText('chatTeamName', name);
        setText('membersTeamName', name);

        var warning = byId('teamWarning');
        if (warning) warning.style.display = 'none';

        renderOverview(teamInfo);
    }

    /** 仅在确认当前用户为队长时才显示创建任务按钮 */
    function updateCreateTaskBtn() {
        var createBtn = byId('createTaskBtn');
        var createFab = byId('createTaskFab');
        var canCreate = false;
        if (teamInfo && teamInfo.status === 'CLOSED') {
            if (createBtn) createBtn.hidden = true;
            if (createFab) createFab.hidden = true;
            return;
        }
        for (var i = 0; i < members.length; i++) {
            if (Number(members[i].userId) === Number(currentUserId)) {
                canCreate = members[i].isLeader === true || members[i].role === '队长' || members[i].role === 'LEADER';
                break;
            }
        }
        if (createBtn) createBtn.hidden = !canCreate;
        if (createFab) createFab.hidden = !canCreate;
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
        setText('smcStatus', teamStatusLabel(data.status));
        setText('smcDeadlineText', data.deadline ? formatDate(data.deadline) : '长期有效');

        // 头像堆叠 — 只有 img 加载失败才显示文字 fallback
        var avatarsEl = document.getElementById('smcAvatars');
        if (avatarsEl) {
            var avatarsHtml = '';
            var displayMembers = Array.isArray(members) ? members : [];
            displayMembers.forEach(function (m, i) {
                if (i >= 4) return;
                var name = m.realName || m.username || '';
                var initials = name ? name.charAt(0).toUpperCase() : '?';
                var av = m.avatar || '';
                var z = 4 - i;
                var ml = i === 0 ? 0 : -8;
                if (av) {
                    // 有头像：img 显示，fb 默认隐藏，onerror 时隐藏 img、显示 fb
                    avatarsHtml += '<img class="smc-avatar" src="' + escapeHtml(avatarUrl(av)) + '" alt="' + escapeHtml(name) + '" loading="lazy" style="z-index:' + z + ';margin-left:' + ml + 'px;display:block;" onerror="var fb=this.nextElementSibling;this.style.display=\'none\';if(fb){fb.style.display=\'inline-flex\';fb.style.zIndex=' + z + ';fb.style.marginLeft=\'' + ml + 'px\';}">';
                    avatarsHtml += '<span class="smc-avatar smc-avatar-fb" style="z-index:' + z + ';margin-left:' + ml + 'px;display:none;">' + initials + '</span>';
                } else {
                    // 无头像：直接显示文字
                    avatarsHtml += '<span class="smc-avatar smc-avatar-fb" style="z-index:' + z + ';margin-left:' + ml + 'px;display:inline-flex;">' + initials + '</span>';
                }
            });
            var extra = members.length - 4;
            if (extra > 0) {
                avatarsHtml += '<span class="smc-avatar smc-avatar-extra">+' + extra + '</span>';
            }
            avatarsEl.innerHTML = avatarsHtml;
        }
    }

    async function loadMembers() {
        try {
            var data = await api('/team-space/' + teamId + '/members', { silent: true });
            members = Array.isArray(data) ? data : [];
            renderMembersList();
            renderMembersActions();
            fillAssigneeSelect();
            if (teamInfo) renderOverview(teamInfo);
            updateCreateTaskBtn();
            return true;
        } catch (error) {
            console.error('加载成员列表失败:', error);
            var area = byId('membersListArea');
            if (area) {
                area.innerHTML = isLoginExpired(error)
                    ? renderLoginExpired()
                    : '<div class="panel-loading">成员列表加载失败，请检查网络连接后刷新</div>';
            }
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

    /**
     * 成员面板右上角的操作。
     * 「邀请成员」给能管理成员的人；「转让 / 解散」只给创建者——这两条是团队所有权的
     * 转移与终结，不随成员角色放宽。
     */
    function renderMembersActions() {
        var box = byId('membersActions');
        if (!box) return;
        var buttons = [];
        if (canManageMembers()) {
            buttons.push('<button type="button" class="btn btn-sm btn-primary" id="membersInviteBtn">邀请成员</button>');
        }
        if (isTeamOwner()) {
            buttons.push('<button type="button" class="btn btn-sm btn-outline-secondary" id="membersTransferBtn">转让创建者</button>');
            buttons.push('<button type="button" class="btn btn-sm btn-outline-secondary" id="membersDissolveBtn">解散团队</button>');
        }
        box.innerHTML = buttons.join('');
        var inviteBtn = byId('membersInviteBtn');
        if (inviteBtn) inviteBtn.addEventListener('click', openInviteModal);
        var transferBtn = byId('membersTransferBtn');
        if (transferBtn) transferBtn.addEventListener('click', transferOwner);
        var dissolveBtn = byId('membersDissolveBtn');
        if (dissolveBtn) dissolveBtn.addEventListener('click', dissolveTeam);
    }

    /** 当前用户在该团队里的角色信息，从成员列表里回捞，用于决定渲染哪些操作按钮。 */
    function currentMembership() {
        var me = Number(document.body.getAttribute('data-user-id')) || 0;
        for (var i = 0; i < members.length; i++) {
            if (Number(members[i].userId) === me) return members[i];
        }
        return null;
    }

    function isTeamOwner() {
        var mine = currentMembership();
        return !!(mine && mine.isOwner);
    }

    /** 能邀请/审批：创建者或在队导师；与后端 canManageMembers 同一套判定 */
    function canManageMembers() {
        var mine = currentMembership();
        if (!mine) return false;
        return !!mine.isOwner || mine.memberRole === 'MENTOR';
    }

    function canRemoveMember(member) {
        var me = Number(document.body.getAttribute('data-user-id')) || 0;
        if (Number(member.userId) === me) return false;
        if (isTeamOwner()) return true;
        var mine = currentMembership();
        return !!(mine && mine.memberRole === 'MENTOR') && member.memberRole !== 'MENTOR';
    }

    function renderMembersList() {
        var area = byId('membersListArea');
        if (!area) return;
        if (!members.length) {
            area.innerHTML = renderEmptyState('暂无成员');
            return;
        }
        var me = Number(document.body.getAttribute('data-user-id')) || 0;

        area.innerHTML = members.map(function (member) {
            var name = member.username || member.realName || '成员';
            var userId = member.userId || member.id || '';
            var avatar = avatarUrl(member.avatar);
            var initials = escapeHtml(name).slice(0, 2).toUpperCase();
            var meta = [member.major, member.grade, member.school]
                .filter(Boolean)
                .map(function (value) { return escapeHtml(String(value)); })
                .join(' / ');
            var isOwnerRow = !!member.isOwner || member.isLeader === true;
            var pending = member.status === 'PENDING';
            var roleLabel = isOwnerRow ? '队长' : (pending ? '待确认' : (member.memberRole === 'MENTOR' ? '导师' : '学生'));
            var roleChip = '<span class="member-role-chip' +
                (isOwnerRow || member.memberRole === 'MENTOR' ? ' member-role-chip--mentor' : '') +
                (pending ? ' member-role-chip--pending' : '') + '">' + escapeHtml(roleLabel) + '</span>';
            var skills = Array.isArray(member.skills) ? member.skills.slice(0, 4) : [];
            var skillHtml = skills.length ? '<div class="member-card-skills">' + skills.map(function (skill) {
                return '<span class="skill-tag">' + escapeHtml(skill) + '</span>';
            }).join('') + '</div>' : '';
            var joinTime = member.joinedAt ? '<div class="member-card-meta">加入于 ' + formatDate(member.joinedAt) + '</div>' : '';
            var avatarHtml = avatar
                ? '<img src="' + escapeHtml(avatar) + '" alt="' + escapeHtml(name) + '" loading="lazy" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\';"><div class="avatar-placeholder" style="display:none;">' + initials + '</div>'
                : '<div class="avatar-placeholder">' + initials + '</div>';

            var actions = '';
            if (!pending && canRemoveMember(member)) {
                actions = '<button type="button" class="member-card-action" data-remove-member="' + escapeHtml(userId) +
                    '" data-member-name="' + escapeHtml(name) + '">移除</button>';
            } else if (!pending && Number(userId) === me && !isOwnerRow) {
                actions = '<button type="button" class="member-card-action" data-leave-team="1">退出</button>';
            }

            return '<div class="member-card' + (pending ? ' member-card--pending' : '') + '" data-user-id="' + escapeHtml(userId) + '">' +
                '<span class="member-card-avatar">' + avatarHtml + '</span>' +
                '<span class="member-card-info">' +
                    '<span class="member-card-name">' + escapeHtml(name) + roleChip + '</span>' +
                    (meta ? '<span class="member-card-meta">' + meta + '</span>' : '') +
                    joinTime +
                    skillHtml +
                '</span>' +
                '<span class="member-card-actions">' +
                    '<a class="member-card-action" href="/user-profile.html?id=' + escapeHtml(userId) + '">主页</a>' +
                    actions +
                '</span>' +
            '</div>';
        }).join('');

        area.querySelectorAll('[data-remove-member]').forEach(function (button) {
            button.addEventListener('click', function () {
                var userId = button.getAttribute('data-remove-member');
                var name = button.getAttribute('data-member-name');
                window.teamSpace.confirmRemoveMember(userId, name);
            });
        });
        var leaveBtn = area.querySelector('[data-leave-team]');
        if (leaveBtn) {
            leaveBtn.addEventListener('click', function () {
                window.teamSpace.confirmLeaveTeam();
            });
        }
    }

    /**
     * 通用二次确认弹窗。
     * 团队解散这类不可逆操作由调用方通过 requireText 要求手输团队名，避免连点误触。
     */
    function confirmDialog(options) {
        var opts = options || {};
        return new Promise(function (resolve) {
            var wrapper = document.createElement('div');
            wrapper.className = 'modal fade';
            wrapper.tabIndex = -1;
            wrapper.innerHTML =
                '<div class="modal-dialog modal-dialog-centered">' +
                    '<div class="modal-content ts-confirm">' +
                        '<div class="modal-header ts-confirm__header">' +
                            '<h5 class="ts-confirm__title"></h5>' +
                        '</div>' +
                        '<div class="ts-confirm__body">' +
                            '<p class="ts-confirm__text"></p>' +
                            (opts.requireText
                                ? '<input type="text" class="ts-confirm__input" placeholder="' +
                                  escapeHtml(opts.requireText) + '">'
                                : '') +
                            (opts.selectOptions
                                ? '<select class="ts-confirm__select"></select>'
                                : '') +
                        '</div>' +
                        '<div class="modal-footer ts-confirm__footer">' +
                            '<button type="button" class="btn btn-secondary" data-ts-cancel>取消</button>' +
                            '<button type="button" class="btn btn-primary" data-ts-ok disabled>确认</button>' +
                        '</div>' +
                    '</div>' +
                '</div>';
            document.body.appendChild(wrapper);

            wrapper.querySelector('.ts-confirm__title').textContent = opts.title || '请确认';
            wrapper.querySelector('.ts-confirm__text').textContent = opts.text || '';

            var okBtn = wrapper.querySelector('[data-ts-ok]');
            var input = wrapper.querySelector('.ts-confirm__input');
            var select = wrapper.querySelector('.ts-confirm__select');
            if (select && Array.isArray(opts.selectOptions)) {
                opts.selectOptions.forEach(function (item) {
                    var option = document.createElement('option');
                    option.value = item.value;
                    option.textContent = item.label;
                    select.appendChild(option);
                });
                okBtn.disabled = false;
            }
            if (input) {
                input.addEventListener('input', function () {
                    okBtn.disabled = input.value.trim() !== (opts.requireText || '');
                });
            }

            var modal = window.bootstrap ? window.bootstrap.Modal.getOrCreateInstance(wrapper) : null;
            var settled = false;
            function finish(result) {
                if (settled) return;
                settled = true;
                resolve(result);
            }
            okBtn.addEventListener('click', function () { finish(true); if (modal) modal.hide(); });
            wrapper.querySelector('[data-ts-cancel]').addEventListener('click', function () { finish(false); if (modal) modal.hide(); });
            wrapper.addEventListener('hidden.bs.modal', function () {
                finish(false);
                wrapper.remove();
            });
            if (modal) modal.show();
            else { wrapper.style.display = 'block'; wrapper.classList.add('show'); }
        });
    }

    async function removeMember(userId, name) {
        var agreed = await confirmDialog({
            title: '移除成员',
            text: '确定把「' + name + '」移出团队吗？他名下未完成的任务会被清空指派。',
            confirmText: '移除'
        });
        if (!agreed) return;
        try {
            await api('/team/' + teamId + '/members/' + encodeURIComponent(userId), { method: 'DELETE' });
            notify('已移除该成员', 'success');
            await loadMembers();
            await loadOverview();
        } catch (error) {
            notify(error.message || '移除失败', 'error');
        }
    }

    async function leaveTeam() {
        var me = Number(document.body.getAttribute('data-user-id')) || 0;
        var agreed = await confirmDialog({
            title: '退出团队',
            text: '退出后将无法再进入该团队的协作空间，确定退出吗？',
            confirmText: '退出'
        });
        if (!agreed) return;
        try {
            await api('/team/' + teamId + '/members/' + me, { method: 'DELETE' });
            notify('已退出团队', 'success');
            window.setTimeout(function () { window.location.href = '/profile.html#teams'; }, 800);
        } catch (error) {
            notify(error.message || '退出失败', 'error');
        }
    }

    async function transferOwner() {
        var candidates = members.filter(function (member) {
            return !member.isOwner && member.status !== 'PENDING';
        });
        if (!candidates.length) {
            notify('还没有其他成员可以接手，先邀请成员吧', 'warning');
            return;
        }
        var agreed = await confirmDialog({
            title: '转让创建者',
            text: '转让后你将变为普通成员，无法再删除或解散该团队。',
            confirmText: '确认转让',
            selectOptions: candidates.map(function (member) {
                return {
                    value: member.userId,
                    label: (member.username || '成员') + '（' + (member.memberRole === 'MENTOR' ? '导师' : '学生') + '）'
                };
            })
        });
        if (!agreed) return;
        var select = document.querySelector('.ts-confirm__select');
        var newOwnerId = select ? select.value : '';
        if (!newOwnerId) return;
        try {
            await api('/team/' + teamId + '/transfer', {
                method: 'POST',
                body: JSON.stringify({ newOwnerId: Number(newOwnerId) })
            });
            notify('已转让创建者', 'success');
            await loadMembers();
            await loadOverview();
        } catch (error) {
            notify(error.message || '转让失败', 'error');
        }
    }

    async function dissolveTeam() {
        var title = teamTitle();
        var agreed = await confirmDialog({
            title: '解散团队',
            text: '解散不可撤销：未完成的任务会被取消，成员将收到通知。群聊与协作记录会保留。请输入团队名称确认。',
            confirmText: title,
            requireText: title
        });
        if (!agreed) return;
        try {
            await api('/team/' + teamId + '/dissolve', { method: 'POST' });
            notify('团队已解散', 'success');
            window.setTimeout(function () { window.location.href = '/profile.html#teams'; }, 800);
        } catch (error) {
            notify(error.message || '解散失败', 'error');
        }
    }

    /** 团队名：/api/team-space/{id}/info 用的字段是 teamName，别按 title 取 */
    function teamTitle() {
        if (!teamInfo) return '';
        return teamInfo.teamName || teamInfo.title || teamInfo.name || '';
    }

    function openInviteModal() {
        if (!window.TeamInvite) {
            notify('邀请窗口未就绪，请刷新页面', 'error');
            return;
        }
        window.TeamInvite.open(teamId, teamTitle(), function () {
            loadMembers();
            loadOverview();
        });
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
        bindTaskCardEvents();
        applyMobileKanbanStatus(activeMobileStatus);
    }

    function renderTaskColumn(listId, countId, list) {
        setText(countId, list.length);
        var area = byId(listId);
        if (!area) return;
        area.innerHTML = list.length ? list.map(renderTaskCard).join('') : renderEmptyState('暂无任务', true);
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
                    ? '<img src="' + escapeHtml(avatar) + '" alt="' + escapeHtml(task.assigneeName) + '" loading="lazy" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'inline-flex\';"><span style="display:none;">' + initials + '</span>'
                    : '<span>' + initials + '</span>') +
                '<span>' + escapeHtml(task.assigneeName) + '</span>' +
            '</span>';
        }

        return '<article class="task-card" data-task-id="' + escapeHtml(task.id) + '" role="button" tabindex="0" aria-label="查看任务：' + escapeHtml(task.taskTitle) + '">' +
            '<header class="task-card-header">' +
                '<h2 class="task-card-title">' + escapeHtml(task.taskTitle) + '</h2>' +
                '<span class="' + statusBadgeClass(task.status) + '">' + statusLabel(task.status) + '</span>' +
            '</header>' +
            (task.taskDescription ? '<p class="task-card-desc">' + escapeHtml(task.taskDescription) + '</p>' : '') +
            '<div class="task-card-tags">' +
                '<span class="task-card-tag task-priority-tag" data-priority="' + Number(task.priority || 2) + '">' + escapeHtml(priorityLabel(task.priority)) + '</span>' +
                '<span class="task-card-tag">' + escapeHtml(typeLabel(task.taskType)) + '</span>' +
            '</div>' +
            '<footer class="task-card-footer">' +
                (deadline ? '<span class="' + (overdue ? 'task-card-overdue' : '') + '">' + escapeHtml(deadline) + (overdue ? ' 已逾期' : '') + '</span>' : '<span></span>') +
                assigneeHtml +
            '</footer>' +
        '</article>';
    }

    function bindTaskCardEvents() {
        document.querySelectorAll('.task-card').forEach(function (card) {
            var pressTimer = null;
            var pressX = 0;
            var pressY = 0;
            var task = tasks.find(function (item) { return Number(item.id) === Number(card.dataset.taskId); });
            var canStartByDrag = task && normalizeStatus(task.status) === 'todo';
            card.draggable = !isMobileLayout() && canStartByDrag;
            card.addEventListener('click', function (event) {
                if (suppressTaskClick) {
                    suppressTaskClick = false;
                    event.preventDefault();
                    return;
                }
                window.teamSpace.openTaskDetail(Number(card.dataset.taskId));
            });
            card.addEventListener('keydown', function (event) {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    window.teamSpace.openTaskDetail(Number(card.dataset.taskId));
                }
            });
            card.addEventListener('contextmenu', function (event) {
                if (!isMobileLayout()) return;
                event.preventDefault();
                suppressTaskClick = true;
                openMobileTaskActions(Number(card.dataset.taskId), card);
            });
            card.addEventListener('dragstart', function (event) {
                if (isMobileLayout() || !canStartByDrag) {
                    event.preventDefault();
                    return;
                }
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

            card.addEventListener('pointerdown', function (event) {
                if (!isMobileLayout() || event.pointerType === 'mouse') return;
                pressX = event.clientX;
                pressY = event.clientY;
                pressTimer = window.setTimeout(function () {
                    pressTimer = null;
                    suppressTaskClick = true;
                    if (navigator.vibrate) navigator.vibrate(18);
                    openMobileTaskActions(Number(card.dataset.taskId), card);
                }, 520);
            });
            card.addEventListener('pointermove', function (event) {
                if (!pressTimer) return;
                if (Math.abs(event.clientX - pressX) > 10 || Math.abs(event.clientY - pressY) > 10) {
                    window.clearTimeout(pressTimer);
                    pressTimer = null;
                }
            });
            ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (eventName) {
                card.addEventListener(eventName, function () {
                    if (pressTimer) window.clearTimeout(pressTimer);
                    pressTimer = null;
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

    function applyMobileKanbanStatus(status) {
        activeMobileStatus = normalizeStatus(status);
        document.querySelectorAll('.kanban-status-tab').forEach(function (tab) {
            var active = tab.dataset.status === activeMobileStatus;
            tab.classList.toggle('active', active);
            tab.setAttribute('aria-selected', String(active));
        });
        document.querySelectorAll('.kanban-col').forEach(function (column) {
            column.classList.toggle('mobile-active', column.dataset.status === activeMobileStatus);
        });
    }

    function setupMobileKanbanTabs() {
        document.querySelectorAll('.kanban-status-tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                applyMobileKanbanStatus(tab.dataset.status || 'todo');
                var board = document.querySelector('.kanban-board');
                if (board && isMobileLayout()) board.scrollIntoView({ behavior: 'smooth', block: 'start' });
            });
        });
    }

    function setProjectMenu(open, options) {
        var sidebar = byId('sidebar');
        var backdrop = byId('projectMenuBackdrop');
        var trigger = byId('projectMenuTrigger');
        if (!sidebar || !backdrop || !trigger) return;
        options = options || {};
        var wasOpen = sidebar.classList.contains('is-open');
        if (open && !wasOpen) projectMenuReturnFocus = document.activeElement;
        sidebar.classList.toggle('is-open', open);
        backdrop.classList.toggle('is-open', open);
        trigger.setAttribute('aria-expanded', String(open));
        document.body.classList.toggle('project-menu-open', open);
        if (open) {
            sidebar.removeAttribute('inert');
            sidebar.setAttribute('role', 'dialog');
            sidebar.setAttribute('aria-modal', 'true');
            sidebar.setAttribute('aria-hidden', 'false');
            var closeButton = byId('projectMenuClose');
            focusElement(closeButton || getFocusableElements(sidebar)[0]);
        } else {
            var returnTarget = projectMenuReturnFocus && projectMenuReturnFocus.isConnected
                ? projectMenuReturnFocus
                : trigger;
            if (wasOpen && options.restoreFocus !== false) focusElement(returnTarget);
            projectMenuReturnFocus = null;
            if (isMobileLayout()) {
                sidebar.removeAttribute('aria-modal');
                sidebar.setAttribute('aria-hidden', 'true');
                sidebar.setAttribute('inert', '');
            } else {
                sidebar.removeAttribute('role');
                sidebar.removeAttribute('aria-modal');
                sidebar.removeAttribute('aria-hidden');
                sidebar.removeAttribute('inert');
            }
        }
    }

    function setupProjectMenu() {
        var trigger = byId('projectMenuTrigger');
        var close = byId('projectMenuClose');
        var backdrop = byId('projectMenuBackdrop');
        if (trigger) trigger.addEventListener('click', function () { setProjectMenu(true); });
        if (close) close.addEventListener('click', function () { setProjectMenu(false); });
        if (backdrop) backdrop.addEventListener('click', function () { setProjectMenu(false); });
        var sidebar = byId('sidebar');
        if (sidebar) sidebar.addEventListener('keydown', function (event) {
            if (isMobileLayout() && sidebar.classList.contains('is-open')) trapFocus(sidebar, event);
        });
    }

    function closeMobileTaskActions(options) {
        var dialog = byId('mobileTaskActions');
        if (!dialog) return;
        options = options || {};
        var wasOpen = dialog.classList.contains('is-open');
        dialog.classList.remove('is-open');
        dialog.setAttribute('aria-hidden', 'true');
        dialog.setAttribute('inert', '');
        document.body.classList.remove('mobile-task-actions-open');
        mobileActionTaskId = null;
        suppressTaskClick = false;
        if (wasOpen && options.restoreFocus !== false) focusElement(taskActionsReturnFocus);
        taskActionsReturnFocus = null;
    }

    function openMobileTaskActions(taskId, returnTarget) {
        if (!isMobileLayout()) return;
        var task = tasks.find(function (item) { return Number(item.id) === Number(taskId); });
        var dialog = byId('mobileTaskActions');
        if (!task || !dialog) return;
        taskActionsReturnFocus = returnTarget || document.activeElement;
        mobileActionTaskId = Number(taskId);
        setText('mobileTaskActionsTitle', task.taskTitle || '任务操作');
        dialog.querySelectorAll('[data-mobile-status]').forEach(function (button) {
            var canStart = normalizeStatus(task.status) === 'todo' && button.dataset.mobileStatus === 'in_progress';
            button.hidden = !canStart;
            button.disabled = !canStart;
        });
        var canManage = currentUserIsLeader();
        var manageActions = byId('mobileTaskManageActions');
        var editButton = byId('mobileTaskEditBtn');
        var deleteButton = byId('mobileTaskDeleteBtn');
        if (manageActions) manageActions.hidden = !canManage;
        if (editButton) {
            editButton.hidden = !canManage;
            editButton.disabled = !canManage;
        }
        if (deleteButton) {
            deleteButton.hidden = !canManage;
            deleteButton.disabled = !canManage;
        }
        dialog.classList.add('is-open');
        dialog.setAttribute('aria-hidden', 'false');
        dialog.removeAttribute('inert');
        document.body.classList.add('mobile-task-actions-open');
        var firstAction = getFocusableElements(dialog).find(function (element) {
            return element.hasAttribute('data-mobile-status') && !element.disabled;
        });
        focusElement(firstAction || getFocusableElements(dialog)[0]);
    }

    function setupMobileTaskActions() {
        var dialog = byId('mobileTaskActions');
        if (!dialog) return;
        dialog.addEventListener('keydown', function (event) {
            if (dialog.classList.contains('is-open')) trapFocus(dialog, event);
        });
        dialog.querySelectorAll('[data-close-mobile-actions]').forEach(function (button) {
            button.addEventListener('click', closeMobileTaskActions);
        });
        dialog.querySelectorAll('[data-mobile-status]').forEach(function (button) {
            button.addEventListener('click', async function () {
                if (!mobileActionTaskId) return;
                var taskId = mobileActionTaskId;
                var nextStatus = button.dataset.mobileStatus;
                closeMobileTaskActions();
                try {
                    await updateTaskStatus(taskId, nextStatus);
                    applyMobileKanbanStatus(nextStatus);
                    await loadTasks();
                    notify('任务状态已更新', 'success');
                } catch (error) {
                    notify(error.message || '状态更新失败', 'error');
                }
            });
        });
        var editButton = byId('mobileTaskEditBtn');
        var deleteButton = byId('mobileTaskDeleteBtn');
        if (editButton) editButton.addEventListener('click', function () {
            var taskId = mobileActionTaskId;
            closeMobileTaskActions({ restoreFocus: false });
            if (taskId) window.teamSpace.openEditModal(taskId);
        });
        if (deleteButton) deleteButton.addEventListener('click', function () {
            var taskId = mobileActionTaskId;
            closeMobileTaskActions();
            if (taskId) window.teamSpace.deleteTask(taskId);
        });
    }

    function applyResponsiveState() {
        if (!isMobileLayout()) {
            setProjectMenu(false, { restoreFocus: false });
            closeMobileTaskActions({ restoreFocus: false });
        } else {
            var sidebar = byId('sidebar');
            if (sidebar && !sidebar.classList.contains('is-open')) {
                sidebar.setAttribute('role', 'dialog');
                sidebar.removeAttribute('aria-modal');
                sidebar.setAttribute('aria-hidden', 'true');
                sidebar.setAttribute('inert', '');
            }
        }
        document.querySelectorAll('.task-card').forEach(function (card) {
            var task = tasks.find(function (item) { return Number(item.id) === Number(card.dataset.taskId); });
            card.draggable = !isMobileLayout() && task && normalizeStatus(task.status) === 'todo';
        });
        applyMobileKanbanStatus(activeMobileStatus);
    }

    function setupSidebarNav() {
        document.querySelectorAll('.sidebar-nav-item').forEach(function (item) {
            item.addEventListener('click', function () {
                switchPanel(item.dataset.panel || 'kanban-panel');
                if (isMobileLayout()) setProjectMenu(false);
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
        } else if (currentPanelId === 'ai-panel') {
            loadAiPanel();
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
        if (!name) name = sent ? '我' : '队友';
        var initials = escapeHtml(name).slice(0, 2).toUpperCase();
        var avatar = avatarUrl(sent ? currentUserAvatar : message.senderAvatar);
        var avatarHtml = avatar
            ? '<img src="' + escapeHtml(avatar) + '" alt="' + escapeHtml(name) + '" loading="lazy" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\';"><span style="display:none;">' + initials + '</span>'
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
            return '<img class="chat-image" src="' + escapeHtml(content) + '" alt="聊天图片" loading="lazy">';
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
                stompClient.subscribe('/topic/team/' + teamId, function (frame) {
                    try {
                        appendChatMessage(JSON.parse(frame.body));
                    } catch (error) {
                        console.warn('解析聊天消息失败', error);
                    }
                });
                loadMembers();
            }, function () {
                isConnected = false;
                startPolling();
            });
        } catch (error) {
            console.warn('连接 WebSocket 失败', error);
            isConnected = false;
            startPolling();
        }
    }

    async function loadRecommendedMembers() {
        var section = byId('candidateRecommendations');
        var list = byId('candidateRecommendationList');
        if (!section || !list || !currentUserIsLeader()) {
            if (section) section.hidden = true;
            return;
        }
        try {
            var recommendations = await api('/recommendations/users?teamId=' + encodeURIComponent(teamId) + '&limit=3', { silent: true });
            if (!Array.isArray(recommendations) || !recommendations.length) {
                section.hidden = true;
                return;
            }
            list.innerHTML = recommendations.map(function (item) {
                var name = item.realName || item.username || '候选成员';
                var reasons = Array.isArray(item.matchReasons) ? item.matchReasons.slice(0, 2).join(' · ') : '';
                var skills = Array.isArray(item.skills) ? item.skills.slice(0, 3) : [];
                var initials = escapeHtml(name).slice(0, 2).toUpperCase();
                var avatar = avatarUrl(item.avatar);
                var avatarHtml = avatar
                    ? '<img src="' + escapeHtml(avatar) + '" alt="' + escapeHtml(name) + '" loading="lazy" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'flex\';"><span class="candidate-card__avatar-fb" style="display:none;">' + initials + '</span>'
                    : '<span class="candidate-card__avatar-fb">' + initials + '</span>';
                var skillHtml = skills.length ? '<span class="candidate-card__skills">' + skills.map(function (skill) {
                    return '<i>' + escapeHtml(skill) + '</i>';
                }).join('') + '</span>' : '';
                return '<a class="candidate-card" href="/user-profile.html?id=' + Number(item.userId) + '" data-log-id="' + Number(item.logId || 0) + '">' +
                    '<span class="candidate-card__score">' + Math.round(Number(item.matchScore || 0)) + '%</span>' +
                    '<span class="candidate-card__avatar">' + avatarHtml + '</span>' +
                    '<strong>' + escapeHtml(name) + '</strong>' +
                    '<span>' + escapeHtml(item.school || '未设置学校') + '</span>' +
                    skillHtml +
                    (reasons ? '<small>' + escapeHtml(reasons) + '</small>' : '') +
                '</a>';
            }).join('');
            list.querySelectorAll('[data-log-id]').forEach(function (card) {
                card.addEventListener('click', function () {
                    var logId = Number(card.dataset.logId || 0);
                    if (logId) api('/recommendations/feedback/' + logId + '?action=VIEWED', { method: 'POST', silent: true }).catch(function () {});
                });
            });
            section.hidden = false;
        } catch (error) {
            section.hidden = true;
        }
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
        editingTaskId = null;
        ['newTaskTitle', 'newTaskDesc', 'newTaskDeadline', 'newTaskHours'].forEach(function (id) {
            var el = byId(id);
            if (el) el.value = '';
        });
        var type = byId('newTaskType');
        var priority = byId('newTaskPriority');
        if (type) type.value = 'development';
        if (priority) priority.value = '2';
        fillAssigneeSelect();
        setText('createTaskTitle', '创建新任务');
        setText('saveTaskBtn', '创建');
    }

    function toDateTimeLocalValue(value) {
        if (!value) return '';
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) return '';
        var offset = date.getTimezoneOffset() * 60000;
        return new Date(date.getTime() - offset).toISOString().slice(0, 16);
    }

    function currentUserIsLeader() {
        return members.some(function (member) {
            return Number(member.userId) === Number(currentUserId) &&
                (member.isLeader === true || member.role === '队长' || member.role === 'LEADER');
        });
    }

    function bindToolbarEvents() {
        var emojiBtn = document.querySelector('.chat-tool-btn[title="表情"]');
        var fileBtn = document.querySelector('.chat-tool-btn[title="文件"]');
        var imageBtn = document.querySelector('.chat-tool-btn[title="图片"]');

        if (emojiBtn) emojiBtn.addEventListener('click', toggleEmojiPicker);
        if (fileBtn) setupUploadButton(fileBtn, false);
        if (imageBtn) setupUploadButton(imageBtn, true);

        var picker = byId('emojiPicker');
        if (picker) {
            picker.addEventListener('click', function (e) {
                var item = e.target.closest('.emoji-item');
                if (!item) return;
                e.stopPropagation();
                insertAtCursor(byId('chatInput'), item.dataset.emoji || '');
                closeEmojiPicker();
            });
        }
    }

    var CHAT_ATTACHMENT_ACCEPT = '.jpg,.jpeg,.png,.gif,.webp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.csv,.zip,.rar,.7z,.exe,.msi,.apk,.ipa';

    function setupUploadButton(button, imageOnly) {
        var input = document.createElement('input');
        input.type = 'file';
        input.style.display = 'none';
        if (imageOnly) input.accept = 'image/*';
        else input.accept = CHAT_ATTACHMENT_ACCEPT;
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
            var response = await apiFetch('/api/upload/attachment?kind=chat', {
                method: 'POST',
                body: form
            });
            var result = await response.json();
            if (!response.ok || Number(result.code) !== 200) {
                throw new Error(result.message || '上传失败');
            }
            var data = result.data || {};
            var uploaded = window.ILinkFiles.normalizeUploadResult(data, file);
            var url = uploaded.url;
            if (!url) throw new Error('上传结果缺少文件地址');
            var type = imageOnly ? 'IMAGE' : 'FILE';
            var content = imageOnly ? url : url + '|' + uploaded.name;
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
        var picker = byId('emojiPicker');
        if (!picker) return;
        if (emojiPickerEl) {
            closeEmojiPicker();
            return;
        }
        var emojis = ['😀', '😂', '😅', '😊', '👍', '👏', '🙏', '💪', '🔥', '✅', '⭐', '📌', '📎', '💡', '🎯', '🚩'];
        picker.innerHTML = '<div class="emoji-grid">' + emojis.map(function (emoji) {
            return '<button type="button" class="emoji-item" data-emoji="' + emoji + '">' + emoji + '</button>';
        }).join('') + '</div>';
        picker.classList.add('open');
        picker.setAttribute('aria-hidden', 'false');
        emojiPickerEl = picker;
        document.addEventListener('keydown', onEmojiPickerKeydown);
        setTimeout(function () {
            document.addEventListener('click', closeEmojiPicker);
        }, 0);
    }

    function onEmojiPickerKeydown(event) {
        if (event.key === 'Escape') closeEmojiPicker();
    }

    function closeEmojiPicker() {
        if (emojiPickerEl) {
            emojiPickerEl.classList.remove('open');
            emojiPickerEl.setAttribute('aria-hidden', 'true');
            emojiPickerEl.innerHTML = '';
            emojiPickerEl = null;
        }
        document.removeEventListener('click', closeEmojiPicker);
        document.removeEventListener('keydown', onEmojiPickerKeydown);
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

    // ============ AI 助手 / 周报 辅助 ============
    var aiBreakdownTaskId = null;
    var aiSubtasks = [];
    var weeklyReportData = null;

    // ============ AI 协作面板（竞赛答疑 + 任务拆解） ============
    var aiCompetitions = [];
    var aiQaBusy = false;

    async function loadAiPanel() {
        setText('aiTeamName', teamInfo ? (teamInfo.teamName || '团队空间') : '团队空间');
        renderAiTaskPicker();
        if (!aiCompetitions.length) {
            try {
                aiCompetitions = await api('/competitions?size=100');
                aiCompetitions = Array.isArray(aiCompetitions) ? aiCompetitions : [];
            } catch (error) {
                aiCompetitions = [];
            }
            renderAiCompetitionSelect();
        }
        return true;
    }

    function renderAiCompetitionSelect() {
        var select = byId('aiCompetitionSelect');
        if (!select) return;
        // 首项为「通用提问」：不绑定竞赛，靠联网搜索 + 模型知识作答
        var options = '<option value="">通用提问（不绑定竞赛）</option>';
        options += aiCompetitions.map(function (c) {
            var name = c.name || c.title || ('竞赛 ' + (c.id || ''));
            return '<option value="' + Number(c.id) + '">' + escapeHtml(name) + '</option>';
        }).join('');
        select.innerHTML = options;
    }

    function renderAiTaskPicker() {
        var box = byId('aiTaskPicker');
        if (!box) return;
        var badge = byId('aiLeaderOnlyBadge');
        var isLeader = !!(teamInfo && teamInfo.isLeader);
        if (badge) badge.hidden = !isLeader;
        var candidates = tasks.filter(function (t) {
            var s = normalizeStatus(t.status);
            return s === 'todo' || s === 'in_progress';
        });
        if (!candidates.length) {
            box.innerHTML = '<div class="ai-task-empty">暂无待拆解的任务，先在任务看板创建任务</div>';
            return;
        }
        box.innerHTML = candidates.map(function (t) {
            return '<div class="ai-task-row">' +
                '<div class="ai-task-row__meta">' +
                    '<span class="ai-task-row__title">' + escapeHtml(t.taskTitle || '（无标题）') + '</span>' +
                    '<span class="ai-task-row__status">' + escapeHtml(statusLabel(t.status)) + '</span>' +
                '</div>' +
                (isLeader
                    ? '<button type="button" class="btn btn-sm btn-outline-primary" onclick="teamSpace.openAiBreakdown(' + Number(t.id) + ')">AI 拆解</button>'
                    : '') +
                '</div>';
        }).join('');
    }

    // 正在排队滚动的容器。每来一个 token 就读一次 scrollHeight 会强制同步回流，
    // 是流式输出卡顿的主要来源；同一帧内只滚一次，读到的是当帧最终高度。
    var pendingScrollTargets = typeof WeakSet !== 'undefined' ? new WeakSet() : null;

    function scrollAiQaToBottom(container) {
        if (!container) return;
        if (!pendingScrollTargets) {
            container.scrollTop = container.scrollHeight;
            return;
        }
        if (pendingScrollTargets.has(container)) return;
        pendingScrollTargets.add(container);
        window.requestAnimationFrame(function () {
            pendingScrollTargets.delete(container);
            container.scrollTop = container.scrollHeight;
        });
    }

    // 轻量安全的 Markdown 渲染，先 HTML 转义（common.js 的 escapeHtml）防 XSS，再解析常见语法。
    // 支持的语法：标题（#/##/###）、粗体、*斜体*、行内代码、代码块（```）、表格（|）链接（仅 http/https）、无序/有序列表、分隔线、换行。
    function renderMarkdown(text) {
        var raw = String(text == null ? '' : text).replace(/\r\n/g, '\n');
        // 兜底：若后端清洗遗漏了 prompt 专用标签，前端再剥离一次后转义渲染
        raw = raw.replace(/<\/?(?:question|competition_info|web_search_results)\s*>/gi, ' ');
        var encoded = escapeHtml(raw);
        var lines = encoded.split('\n');
        var html = [];
        var ul = null;
        var ol = null;
        var inCode = false;
        var codeLines = [];

        function closeLists() {
            if (ul) { html.push('</ul>'); ul = null; }
            if (ol) { html.push('</ol>'); ol = null; }
        }

        function closeCode() {
            if (inCode) {
                html.push('<pre><code>' + codeLines.join('\n') + '</code></pre>');
                codeLines = [];
                inCode = false;
            }
        }

        function isTableRow(line) {
            return /^\s*\|.*\|\s*$/.test(line);
        }

        function renderTable(startIndex) {
            var rows = [];
            var i = startIndex;
            while (i < lines.length && isTableRow(lines[i])) {
                rows.push(lines[i].trim().replace(/^\||\|$/g, ''));
                i++;
            }
            if (rows.length < 2) return { html: null, end: startIndex };
            // 跳过分隔行：| --- | :---: | 等
            var header = rows[0];
            var bodyStart = 1;
            if (/^[\s:|-]+$/.test(rows[1])) {
                bodyStart = 2;
            }
            if (bodyStart >= rows.length) return { html: null, end: startIndex };
            var cellsOf = function (r) {
                return r.split('|').map(function (c) { return renderInline(c.trim()); });
            };
            var headCells = cellsOf(header);
            var out = '<table><thead><tr>';
            for (var h = 0; h < headCells.length; h++) out += '<th>' + headCells[h] + '</th>';
            out += '</tr></thead><tbody>';
            for (var r = bodyStart; r < rows.length; r++) {
                var cells = cellsOf(rows[r]);
                out += '<tr>';
                for (var c = 0; c < cells.length; c++) out += '<td>' + cells[c] + '</td>';
                out += '</tr>';
            }
            out += '</tbody></table>';
            return { html: out, end: i };
        }

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            var trimmed = line.replace(/^\s+|\s+$/g, '');
            if (/^`{3,}/.test(trimmed)) {
                if (inCode) {
                    closeCode();
                } else {
                    closeLists();
                    inCode = true;
                    codeLines = [];
                }
                continue;
            }
            if (inCode) {
                codeLines.push(line);
                continue;
            }
            if (trimmed === '') { closeLists(); continue; }
            if (isTableRow(trimmed)) {
                var table = renderTable(i);
                if (table.html) {
                    closeLists();
                    html.push(table.html);
                    i = table.end - 1;
                    continue;
                }
            }
            var h = trimmed.match(/^(#{1,3})\s+(.*)$/);
            if (h && h[2]) {
                closeLists();
                var level = h[1].length;
                html.push('<h' + (level + 2) + '>' + renderInline(h[2]) + '</h' + (level + 2) + '>');
                continue;
            }
            if (/^\s*---+$/.test(trimmed)) {
                closeLists();
                html.push('<hr>');
                continue;
            }
            var ulMatch = trimmed.match(/^[-*]\s+(.*)$/);
            if (ulMatch && ulMatch[1]) {
                if (!ul) { closeLists(); html.push('<ul>'); ul = true; }
                html.push('<li>' + renderInline(ulMatch[1]) + '</li>');
                continue;
            }
            var olMatch = trimmed.match(/^\d+[.)]\s+(.*)$/);
            if (olMatch && olMatch[1]) {
                if (!ol) { closeLists(); html.push('<ol>'); ol = true; }
                html.push('<li>' + renderInline(olMatch[1]) + '</li>');
                continue;
            }
            closeLists();
            html.push('<p>' + renderInline(trimmed) + '</p>');
        }
        closeCode();
        closeLists();
        return html.join('');
    }

    // 行内语法：代码、粗体、斜体、链接（在已转义的文本上进行安全替换）
    function renderInline(text) {
        var out = '';
        var tokenRe = /(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*|\[[^\]]+\]\([^)]+\))/g;
        var match;
        var last = 0;
        while ((match = tokenRe.exec(text)) !== null) {
            out += text.slice(last, match.index);
            var token = match[0];
            if (token.charAt(0) === '`') {
                out += '<code>' + token.slice(1, -1) + '</code>';
            } else if (token.charAt(0) === '[') {
                // 链接 [文本](url)
                var linkMatch = token.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
                if (linkMatch) {
                    var label = linkMatch[1];
                    var href = linkMatch[2].replace(/&amp;/g, '&');
                    if (/^(https?:\/\/|mailto:)/i.test(href)) {
                        out += '<a href="' + href.replace(/"/g, '&quot;') + '" target="_blank" rel="noopener noreferrer">' + label + '</a>';
                    } else {
                        out += label + '（不安全链接已省略）';
                    }
                } else {
                    out += token;
                }
            } else if (token.indexOf('**') === 0) {
                out += '<strong>' + token.slice(2, -2) + '</strong>';
            } else if (token.charAt(0) === '*') {
                out += '<em>' + token.slice(1, -1) + '</em>';
            } else {
                out += token;
            }
            last = tokenRe.lastIndex;
        }
        out += text.slice(last);
        return out;
    }

    // 剥离 emoji/表情符号（AI 输出除非偶尔带表情，前端兜底清洗）
    function stripEmoji(text) {
        return String(text == null ? '' : text)
            .replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{2190}-\u{21FF}\u{FE0F}\u{200D}]/gu, '')
            .replace(/[\u{1F1E6}-\u{1F1FF}]/gu, '')
            .replace(/[ \t]{2,}/g, ' ');
    }

    function formatAiAnswer(text) {
        if (text == null || text === '') return '';
        return renderMarkdown(stripEmoji(text));
    }

    /**
     * 流式输出气泡：把 AI 的完整回答按块逐个追加到页面，
     * 配合淡入上移动效与末尾光标，模拟"正在生成中"的交互体验。
     */
    var AI_THINK_SPARK =
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
        '<path d="M12 3l1.9 5.8a2 2 0 001.3 1.3L21 12l-5.8 1.9a2 2 0 00-1.3 1.3L12 21l-1.9-5.8a2 2 0 00-1.3-1.3L3 12l5.8-1.9a2 2 0 001.3-1.3L12 3z"/></svg>';

    /**
     * 「已思考」面板外壳。返回各部件供调用方填充：
     * 非流式一次性填满，流式则先插壳再把思考增量追进 reason。
     */
    function createThinkPanel() {
        var panel = document.createElement('div');
        panel.className = 'ai-qa-think';

        var head = document.createElement('button');
        head.type = 'button';
        head.className = 'ai-qa-think__head';
        head.setAttribute('aria-expanded', 'false');
        head.innerHTML = '<span class="ai-qa-think__spark">' + AI_THINK_SPARK + '</span>' +
            '<span class="ai-qa-think__label"></span>' +
            '<svg class="ai-qa-think__chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>';

        var body = document.createElement('div');
        body.className = 'ai-qa-think__body';
        body.hidden = true;

        // 模型自述按纯文本展示，不解析 Markdown（避免其中的标记被当成排版指令）
        var reason = document.createElement('p');
        reason.className = 'ai-qa-think__reason';
        body.appendChild(reason);

        var shell = {
            panel: panel,
            head: head,
            label: head.querySelector('.ai-qa-think__label'),
            body: body,
            reason: reason
        };
        head.addEventListener('click', function () {
            setThinkOpen(shell, body.hidden);
        });

        panel.appendChild(head);
        panel.appendChild(body);
        return shell;
    }

    function setThinkOpen(shell, open) {
        shell.body.hidden = !open;
        shell.head.setAttribute('aria-expanded', open ? 'true' : 'false');
        shell.panel.classList.toggle('is-open', open);
    }

    /** 往面板正文末尾追加检索来源列表（流式与非流式共用）。 */
    function appendThinkSources(body, sources) {
        var links = [];
        (Array.isArray(sources) ? sources : []).forEach(function (item) {
            var url = String((item && item.url) || '').trim();
            // 只放行 http(s)，挡掉 javascript: 之类的伪协议
            if (!/^https?:\/\//i.test(url)) return;
            links.push({ url: url, title: String((item && item.title) || url) });
        });
        if (!links.length) return;

        var meta = document.createElement('div');
        meta.className = 'ai-qa-think__meta';
        meta.textContent = '搜索到 ' + links.length + ' 个网页';
        body.appendChild(meta);

        var ul = document.createElement('ul');
        ul.className = 'ai-qa-think__sources';
        links.forEach(function (link) {
            var li = document.createElement('li');
            var a = document.createElement('a');
            a.href = link.url;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            a.textContent = link.title;
            li.appendChild(a);
            ul.appendChild(li);
        });
        body.appendChild(ul);
    }

    function appendAiQaMessage(container, text, role) {
        var bubble = document.createElement('div');
        bubble.className = 'ai-qa-bubble ai-qa-bubble--' + role;
        if (role === 'user') {
            bubble.textContent = text;
        } else {
            bubble.innerHTML = formatAiAnswer(text);
        }
        container.appendChild(bubble);
        scrollAiQaToBottom(container);
        return bubble;
    }

    async function sendAiQuestion() {
        var input = byId('aiQaInput');
        var select = byId('aiCompetitionSelect');
        var messages = byId('aiQaMessages');
        if (!input || !messages || aiQaBusy) return;
        var question = input.value.trim();
        if (!question) {
            notify('请输入问题', 'warning');
            return;
        }
        var competitionId = select ? select.value : '';
        aiQaBusy = true;
        var sendBtn = byId('aiQaSendBtn');
        if (sendBtn) sendBtn.disabled = true;
        appendAiQaMessage(messages, question, 'user');
        input.value = '';
        if (window.teamSpace && typeof window.teamSpace.autoResizeChatInput === 'function') {
            window.teamSpace.autoResizeChatInput(input);
        }
        var answerBubble = appendAiQaMessage(messages, '', 'assistant');
        answerBubble.classList.add('ai-qa-bubble--loading');
        answerBubble.innerHTML =
            '<span class="ai-typing-dots" aria-hidden="true"><i></i><i></i><i></i></span>' +
            '<span class="ai-qa-stage">正在理解你的问题</span>';
        var stopStages = startAiQaStages(answerBubble.querySelector('.ai-qa-stage'));
        var renderer = createAiQaStreamRenderer(answerBubble, messages);
        try {
            var payload = { question: question };
            // 选了竞赛才带上，留空走通用提问（后端会按问题本身联网检索）
            if (competitionId) payload.competitionId = Number(competitionId);
            var failure = null;
            await readAiQaStream(payload, function (name, data) {
                if (name === 'reasoning') {
                    renderer.reasoning(asStreamText(data));
                } else if (name === 'answer') {
                    renderer.answer(asStreamText(data));
                } else if (name === 'done') {
                    renderer.finish(data);
                } else if (name === 'error') {
                    failure = new Error((data && data.message) || '回答失败，请稍后重试');
                }
            });
            if (failure) throw failure;
            renderer.finish(null);
        } catch (error) {
            renderer.fail(error.message || '回答失败，请稍后重试');
            scrollAiQaToBottom(messages);
        } finally {
            stopStages();
            aiQaBusy = false;
            if (sendBtn) sendBtn.disabled = false;
        }
    }

    /**
     * 流式答疑渲染器：思考过程逐字追加，正文按累计文本重新渲染 Markdown。
     * 真流式下不能再等全文到齐再假打字——那时用户等待期间气泡是空的。
     */
    function createAiQaStreamRenderer(bubble, messages) {
        var shell = null;       // 「已思考」面板
        var answerEl = null;    // 正文容器
        var caret = null;
        var raw = '';
        var lastRendered = null;
        var renderPending = false;
        var lastRenderAt = 0;
        var finished = false;
        var startedAt = Date.now();

        /**
         * 正文重渲染间隔随篇幅放宽。
         * 短回答每个 delta 都按帧画（最跟手）；长回答全量重解析 Markdown 变贵，
         * 放宽到几十毫秒一次——肉眼看不出台阶，主线程也不会被压满。
         */
        function renderIntervalFor(textLength) {
            if (textLength > 2500) return 120;
            if (textLength > 800) return 60;
            return 0;
        }

        function clearLoading() {
            if (!bubble.classList.contains('ai-qa-bubble--loading')) return;
            bubble.classList.remove('ai-qa-bubble--loading');
            // loading 动效与阶段文案是 innerHTML 塞进去的，remove class 不会清掉；
            // 不清就会在答案气泡顶部永久残留一行「内容较多，仍在生成中」
            bubble.innerHTML = '';
        }

        function ensureShell() {
            if (shell) return shell;
            clearLoading();
            shell = createThinkPanel();
            // 面板恒在正文之上；正文可能已经建好了
            bubble.insertBefore(shell.panel, answerEl);
            return shell;
        }

        function ensureAnswer() {
            if (answerEl) return answerEl;
            clearLoading();
            answerEl = document.createElement('div');
            answerEl.className = 'ai-qa-answer';
            bubble.appendChild(answerEl);
            caret = document.createElement('span');
            caret.className = 'ai-typing-caret';
            caret.setAttribute('aria-hidden', 'true');
            bubble.appendChild(caret);
            return answerEl;
        }

        function renderAnswer() {
            if (!answerEl) return;
            // 收尾、补画、排队的帧可能撞在一起；文本没变就不必重排整段 Markdown
            if (raw === lastRendered) return;
            lastRendered = raw;
            lastRenderAt = Date.now();
            answerEl.innerHTML = formatAiAnswer(stripAiPromptTags(raw));
            scrollAiQaToBottom(messages);
        }

        // 一个 token 一次 innerHTML 重排会把主线程压满，这里合并到「帧」上；
        // 帧内多次请求只保留最后一次。收尾时由 finish 直接补画，不依赖这个回调。
        function scheduleRender() {
            if (renderPending) return;
            renderPending = true;
            var wait = Math.max(0, renderIntervalFor(raw.length) - (Date.now() - lastRenderAt));
            window.setTimeout(function () {
                // 先解锁再排帧：标签页被节流时 rAF 可能迟迟不触发，
                // 若把解锁放在 rAF 里，后续所有增量都会被这一个卡住的帧挡掉
                renderPending = false;
                window.requestAnimationFrame(renderAnswer);
            }, wait);
        }

        return {
            reasoning: function (delta) {
                if (finished || !delta) return;
                var panel = ensureShell();
                if (!panel.labelPlaced) {
                    // 标题只需写一次；每个 token 都写 DOM 是白白的强制重排
                    panel.label.textContent = '正在思考…';
                    panel.labelPlaced = true;
                    setThinkOpen(panel, true);
                }
                // appendChild 是 O(1)；textContent += 每次都要重建整段文本节点
                panel.reason.appendChild(document.createTextNode(delta));
                scrollAiQaToBottom(messages);
            },

            answer: function (delta) {
                if (finished || !delta) return;
                if (shell && shell.panel.classList.contains('is-open')) {
                    // 开始作答说明思考结束了，收起过程让答案出现在视线内
                    shell.label.textContent = '已思考';
                    setThinkOpen(shell, false);
                }
                ensureAnswer();
                raw += delta;
                scheduleRender();
            },

            finish: function (data) {
                if (finished) return;
                finished = true;
                clearLoading();
                if (data) {
                    if (data.reasoning && !shell) {
                        ensureShell().reason.textContent = String(data.reasoning);
                    }
                    if (shell) {
                        appendThinkSources(shell.body, data.sources);
                    }
                }
                if (shell) {
                    // 后端没给耗时（流被截断）时用本地计时兜底，免得标题停在「已思考」
                    var elapsed = data && Number(data.elapsedMs) > 0 ? Number(data.elapsedMs) : Date.now() - startedAt;
                    shell.label.textContent = '已思考（用时 ' + Math.max(1, Math.round(elapsed / 1000)) + ' 秒）';
                    setThinkOpen(shell, false);
                }
                if (caret) {
                    caret.remove();
                    caret = null;
                }
                if (answerEl) {
                    renderAnswer();
                } else if (!shell) {
                    // 既无思考也无正文：留一句兜底，避免出现空气泡
                    answerEl = ensureAnswer();
                    raw = '（本次没有返回内容，请换个问法再试）';
                    renderAnswer();
                }
                scrollAiQaToBottom(messages);
            },

            fail: function (message) {
                finished = true;
                if (caret) {
                    caret.remove();
                    caret = null;
                }
                bubble.classList.remove('ai-qa-bubble--loading');
                if (shell || answerEl) {
                    // 已经吐出过内容（思考或部分答案），不要把已有内容抹掉，只补一条错误提示
                    // 渲染节流可能还剩最后一批增量没落盘，先补画一次再挂提示
                    renderAnswer();
                    if (shell) {
                        shell.label.textContent = '已思考（回答被中断）';
                    }
                    var notice = document.createElement('div');
                    notice.className = 'ai-qa-stream-error';
                    notice.textContent = message;
                    bubble.appendChild(notice);
                    scrollAiQaToBottom(messages);
                    return;
                }
                bubble.classList.add('ai-qa-bubble--error');
                bubble.innerHTML = formatAiAnswer(message);
            }
        };
    }

    /**
     * 以 SSE 读取答疑流，逐个事件回调。校验失败等仍走 JSON 通道，这里统一转成异常抛出。
     */
    async function readAiQaStream(payload, onEvent) {
        var response = await apiFetch('/api/ai/competition-qa/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
            // 答疑是分钟级的：检索本身可能就要几十秒，再叠上模型生成。
            // 这里给得比服务端 SSE 超时（180s）宽，让服务端的超时先说话。
            timeoutMs: 240000
        });
        var contentType = response.headers.get('content-type') || '';
        if (contentType.indexOf('text/event-stream') < 0) {
            var text = await response.text();
            var body = null;
            try { body = JSON.parse(text); } catch (e) { body = null; }
            throw new Error((body && body.message) || '回答失败，请稍后重试');
        }
        var reader = response.body.getReader();
        var decoder = new TextDecoder();
        var buffer = '';
        while (true) {
            var chunk = await reader.read();
            if (chunk.done) break;
            buffer += decoder.decode(chunk.value, { stream: true });
            var index;
            while ((index = buffer.indexOf('\n\n')) >= 0) {
                var frame = buffer.slice(0, index);
                buffer = buffer.slice(index + 2);
                var parsed = parseSseFrame(frame);
                if (parsed) onEvent(parsed.name, parsed.data);
            }
        }
    }

    /** 增量载荷必须是字符串；协议异常时宁可丢这一块，也不要往气泡里写 [object Object]。 */
    function asStreamText(value) {
        return typeof value === 'string' ? value : '';
    }

    /** 解析一个 SSE 帧（事件名 + 可能多行的 data；data 为本项目 JSON 序列化的载荷）。 */
    function parseSseFrame(frame) {
        var name = 'message';
        var dataLines = [];
        frame.split('\n').forEach(function (line) {
            if (line.indexOf('event:') === 0) {
                name = line.slice(6).trim();
            } else if (line.indexOf('data:') === 0) {
                dataLines.push(line.slice(5).replace(/^ /, ''));
            }
        });
        if (!dataLines.length) return null;
        var raw = dataLines.join('\n');
        var data = raw;
        try { data = JSON.parse(raw); } catch (e) { /* 非 JSON 就按原文透传 */ }
        return { name: name, data: data };
    }

    /**
     * 剥离模型可能复读的 prompt 专用标签。
     * 流式下只能对「累计文本」整体清洗——标签可能跨分片，逐块清洗会漏。
     */
    function stripAiPromptTags(text) {
        if (!text) return '';
        return text
            .replace(/<\/?competition_info\s*>/gi, ' ')
            .replace(/<\/?web_search_results\s*>/gi, ' ')
            .replace(/<search(_results)?\s*>[\s\S]*?<\/search(_results)?\s*>/gi, ' ')
            .replace(/<\/?search(_results)?\s*>/gi, ' ')
            .replace(/<\/?question\s*>/gi, ' ');
    }

    // 等待期约 20~60 秒，一直停在同一句话会让人以为卡死。
    // 这里按经验时间点推进文案（各句长度接近，避免气泡宽度来回跳）。
    var AI_QA_STAGES = [
        [2500, '正在联网检索资料'],
        [8000, '正在阅读资料并整理答案'],
        [22000, '内容较多，仍在生成中'],
        [45000, '快好了，请再稍等片刻'],
        // 检索慢的时候整体能到一分半以上，最后一句停太久又会被当成卡死
        [70000, '网络有点慢，仍在等待模型返回']
    ];

    /** 返回一个停止函数，务必在请求结束时调用，避免定时器写到已被替换的节点上。 */
    function startAiQaStages(label) {
        if (!label) return function () {};
        var timers = AI_QA_STAGES.map(function (stage) {
            return setTimeout(function () { label.textContent = stage[1]; }, stage[0]);
        });
        return function () {
            timers.forEach(function (id) { clearTimeout(id); });
        };
    }

    function showAiBreakdownPreview() {
        byId('aiBreakdownPreview').hidden = false;
        byId('aiBreakdownResult').hidden = true;
        var confirmBtn = byId('aiBreakdownConfirmBtn');
        confirmBtn.hidden = false;
        confirmBtn.disabled = false;
        confirmBtn.textContent = '确认拆解';
        byId('aiBreakdownRegenBtn').hidden = true;
        byId('aiCreateSubtasksBtn').hidden = true;
    }

    function reportTaskLine(item) {
        var parts = [item.title || '任务'];
        if (item.deadline) parts.push('截止 ' + item.deadline);
        if (item.assigneeName) parts.push(item.assigneeName);
        return parts.join(' · ');
    }

    function renderWeeklyReportHtml(data) {
        var counts = data.counts || {};
        var sectionHtml = function (title, list, danger) {
            if (!list || !list.length) {
                return '<div class="wr-section"><h3>' + escapeHtml(title) + '</h3><p class="wr-empty">无</p></div>';
            }
            return '<div class="wr-section' + (danger ? ' wr-section--danger' : '') + '"><h3>' + escapeHtml(title) + '（' + list.length + '）</h3><ul>' +
                list.map(function (item) { return '<li>' + escapeHtml(reportTaskLine(item)) + '</li>'; }).join('') +
                '</ul></div>';
        };
        return '' +
            '<div class="wr-meta">' +
                '<span>生成于 ' + escapeHtml(data.generatedAt || '') + '</span>' +
                (data.competitionName ? '<span>' + escapeHtml(data.competitionName) + '</span>' : '') +
            '</div>' +
            '<div class="wr-counts">' +
                '<span>共 ' + Number(counts.total || 0) + '</span>' +
                '<span>待办 ' + Number(counts.pending || 0) + '</span>' +
                '<span>进行中 ' + Number(counts.inProgress || 0) + '</span>' +
                '<span>审核中 ' + Number(counts.review || 0) + '</span>' +
                '<span>已完成 ' + Number(counts.completed || 0) + '</span>' +
            '</div>' +
            sectionHtml('逾期任务', data.overdue, true) +
            sectionHtml('本周完成', data.completedThisWeek) +
            sectionHtml('7 天内到期', data.upcomingDeadlines, true);
    }

    function buildWeeklyReportText(data) {
        var counts = data.counts || {};
        var lines = [];
        lines.push('【团队周报】' + (data.teamTitle || '') + ' · ' + (data.generatedAt || ''));
        if (data.competitionName) lines.push('竞赛：' + data.competitionName);
        lines.push('任务总览：共 ' + Number(counts.total || 0) + '（待办 ' + Number(counts.pending || 0)
            + ' · 进行中 ' + Number(counts.inProgress || 0)
            + ' · 审核中 ' + Number(counts.review || 0)
            + ' · 已完成 ' + Number(counts.completed || 0) + '）');
        var sectionText = function (title, list) {
            if (list && list.length) {
                lines.push(title + '（' + list.length + '）：');
                list.forEach(function (item) { lines.push('  - ' + reportTaskLine(item)); });
            }
        };
        sectionText('逾期任务', data.overdue);
        sectionText('本周完成', data.completedThisWeek);
        sectionText('7 天内到期', data.upcomingDeadlines);
        return lines.join('\n');
    }

    window.teamSpace = {
        openInviteModal: openInviteModal,
        transferOwner: transferOwner,
        dissolveTeam: dissolveTeam,
        confirmRemoveMember: removeMember,
        confirmLeaveTeam: leaveTeam,
        handleColDragOver: function (event) {
            if (isMobileLayout()) return;
            event.preventDefault();
            event.currentTarget.classList.add('drag-over');
        },
        handleColDragLeave: function (event) {
            if (isMobileLayout()) return;
            if (!event.currentTarget.contains(event.relatedTarget)) {
                event.currentTarget.classList.remove('drag-over');
            }
        },
        handleColDrop: async function (event) {
            if (isMobileLayout()) return;
            event.preventDefault();
            var column = event.currentTarget;
            column.classList.remove('drag-over');
            var taskId = event.dataTransfer.getData('text/plain');
            var nextStatus = column.dataset.status;
            if (!taskId || !nextStatus) return;
            var task = tasks.find(function (item) { return Number(item.id) === Number(taskId); });
            if (!task || normalizeStatus(task.status) !== 'todo' || normalizeStatus(nextStatus) !== 'in_progress') {
                notify('任务需通过开始、提交和审核依次推进', 'warning');
                return;
            }
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
            else if (currentPanelId === 'ai-panel') ok = await loadAiPanel();
            else ok = await loadTasks();
            await loadOverview();
            notify(ok ? '已刷新' : '刷新失败', ok ? 'success' : 'error');
        },
        openCreateModal: function () {
            if (teamInfo && teamInfo.status === 'CLOSED') {
                notify('项目已结束，不能创建新任务', 'warning');
                return;
            }
            // 非队长不能创建任务
            if (!currentUserIsLeader()) {
                notify('只有队长才能创建任务', 'warning');
                return;
            }
            resetCreateTaskForm();
            bootstrap.Modal.getOrCreateInstance(byId('createTaskModal')).show();
        },
        openEditModal: function (taskId) {
            var task = tasks.find(function (item) { return Number(item.id) === Number(taskId); });
            if (!task) {
                notify('任务不存在', 'error');
                return;
            }
            if (!currentUserIsLeader()) {
                notify('只有队长才能编辑任务', 'warning');
                return;
            }
            editingTaskId = Number(taskId);
            byId('newTaskTitle').value = task.taskTitle || '';
            byId('newTaskDesc').value = task.taskDescription || '';
            byId('newTaskType').value = task.taskType || 'other';
            byId('newTaskPriority').value = String(task.priority || 2);
            byId('newTaskDeadline').value = toDateTimeLocalValue(task.deadline);
            byId('newTaskHours').value = task.estimatedHours == null ? '' : task.estimatedHours;
            fillAssigneeSelect(task.assignedTo);
            setText('createTaskTitle', '编辑任务');
            setText('saveTaskBtn', '保存');
            bootstrap.Modal.getOrCreateInstance(byId('createTaskModal')).show();
        },
        saveTask: async function () {
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
            var hours = byId('newTaskHours').value;
            var editingTask = editingTaskId
                ? tasks.find(function (item) { return Number(item.id) === Number(editingTaskId); })
                : null;
            var payload = {
                taskTitle: title,
                taskDescription: byId('newTaskDesc').value.trim(),
                taskType: byId('newTaskType').value,
                priority: Number(byId('newTaskPriority').value || 2),
                deadline: deadline
                    ? new Date(deadline).toISOString()
                    : (editingTask && editingTask.deadline ? new Date(editingTask.deadline).toISOString() : null),
                estimatedHours: hours
                    ? Number(hours)
                    : (editingTask ? editingTask.estimatedHours : null),
                assignedTo: Number(assignee)
            };

            try {
                var editing = editingTaskId;
                await api(editing ? '/tasks/' + editing : '/team/' + teamId + '/tasks', {
                    method: editing ? 'PUT' : 'POST',
                    body: JSON.stringify(payload)
                });
                bootstrap.Modal.getInstance(byId('createTaskModal'))?.hide();
                editingTaskId = null;
                await loadTasks();
                notify(editing ? '任务已更新' : '任务已创建', 'success');
            } catch (error) {
                notify(error.message || (editingTaskId ? '更新失败' : '创建失败'), 'error');
            }
        },
        createTask: function () {
            return window.teamSpace.saveTask();
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
                var advanceButton = normalizeStatus(task.status) === 'todo'
                    ? '<button type="button" class="btn btn-primary" onclick="teamSpace.advanceTaskStatus(' + Number(task.id) + ')">开始任务</button>'
                    : '';
                var aiButton = currentUserIsLeader()
                    ? '<button type="button" class="btn btn-outline-primary" onclick="teamSpace.openAiBreakdown(' + Number(task.id) + ')">AI 拆解</button>'
                    : '';
                footer.innerHTML =
                    '<button type="button" class="btn btn-secondary" data-bs-dismiss="modal">关闭</button>' +
                    aiButton +
                    advanceButton +
                    '<button type="button" class="btn btn-outline-secondary" onclick="teamSpace.deleteTask(' + Number(task.id) + ')">删除</button>';
            }
            bootstrap.Modal.getOrCreateInstance(byId('taskDetailModal')).show();
        },
        advanceTaskStatus: async function (taskId) {
            var task = tasks.find(function (item) { return Number(item.id) === Number(taskId); });
            if (!task) return;
            var map = { todo: 'in_progress' };
            var next = map[normalizeStatus(task.status)];
            if (!next) {
                notify('后续状态请通过提交材料与队长审核推进', 'info');
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
        },
        // ============ AI 任务拆解 ============
        openAiBreakdown: function (taskId) {
            var task = tasks.find(function (item) { return Number(item.id) === Number(taskId); });
            if (!task) {
                notify('任务不存在', 'error');
                return;
            }
            if (!currentUserIsLeader()) {
                notify('只有队长可以使用 AI 拆解', 'warning');
                return;
            }
            aiBreakdownTaskId = Number(taskId);
            setText('aiPreviewTitle', task.taskTitle || '（无标题）');
            setText('aiPreviewDesc', task.taskDescription || '（无描述）');
            setText('aiPreviewDeadline', task.deadline ? formatDate(task.deadline) : '未设置');
            showAiBreakdownPreview();
            bootstrap.Modal.getOrCreateInstance(byId('aiBreakdownModal')).show();
        },
        resetAiBreakdownPreview: function () {
            showAiBreakdownPreview();
        },
        confirmAiBreakdown: async function () {
            var btn = byId('aiBreakdownConfirmBtn');
            if (!btn || btn.disabled) return;
            btn.disabled = true;
            btn.textContent = 'AI 拆解中，约需 10~30 秒…';
            try {
                var list = await api('/team/' + teamId + '/ai/task-breakdown', {
                    method: 'POST',
                    body: JSON.stringify({ taskId: aiBreakdownTaskId })
                });
                aiSubtasks = Array.isArray(list) ? list : [];
                var box = byId('aiSubtaskList');
                if (box) {
                    box.innerHTML = aiSubtasks.map(function (item, index) {
                        return '<label class="ai-subtask-item">' +
                            '<input type="checkbox" class="ai-subtask-check" data-index="' + index + '" checked>' +
                            '<span class="ai-subtask-body">' +
                                '<strong>' + escapeHtml(stripEmoji(item.title || '子任务')) + '</strong>' +
                                (item.description ? '<small>' + escapeHtml(stripEmoji(item.description)) + '</small>' : '') +
                                '<em>' + escapeHtml(typeLabel(item.taskType)) + ' · ' + escapeHtml(priorityLabel(item.priority)) + ' · ' + Number(item.estimatedHours || 0) + ' 小时</em>' +
                            '</span>' +
                        '</label>';
                    }).join('');
                }
                byId('aiBreakdownPreview').hidden = true;
                byId('aiBreakdownResult').hidden = false;
                byId('aiBreakdownConfirmBtn').hidden = true;
                byId('aiBreakdownRegenBtn').hidden = false;
                byId('aiCreateSubtasksBtn').hidden = false;
            } catch (error) {
                notify(error.message || 'AI 拆解失败，请稍后重试', 'error');
                btn.disabled = false;
                btn.textContent = '确认拆解';
            }
        },
        createSelectedSubtasks: async function () {
            var checks = Array.prototype.slice.call(document.querySelectorAll('.ai-subtask-check'));
            var selected = checks.filter(function (check) { return check.checked; });
            if (!selected.length) {
                notify('请至少勾选一个子任务', 'warning');
                return;
            }
            var btn = byId('aiCreateSubtasksBtn');
            if (btn) btn.disabled = true;
            var parentTask = tasks.find(function (item) { return Number(item.id) === Number(aiBreakdownTaskId); });
            var created = 0;
            try {
                for (var i = 0; i < selected.length; i++) {
                    var item = aiSubtasks[Number(selected[i].dataset.index)];
                    if (!item) continue;
                    await api('/team/' + teamId + '/tasks', {
                        method: 'POST',
                        body: JSON.stringify({
                            taskTitle: String(stripEmoji(item.title) || '').slice(0, 100),
                            taskDescription: String(stripEmoji(item.description) || '').slice(0, 500),
                            taskType: item.taskType || 'other',
                            priority: Number(item.priority || 2),
                            estimatedHours: Number(item.estimatedHours || 2),
                            deadline: parentTask && parentTask.deadline ? new Date(parentTask.deadline).toISOString() : null,
                            assignedTo: currentUserId
                        })
                    });
                    created++;
                }
                bootstrap.Modal.getInstance(byId('aiBreakdownModal'))?.hide();
                await loadTasks();
                notify('已创建 ' + created + ' 个子任务', 'success');
            } catch (error) {
                notify((error.message || '创建失败') + (created > 0 ? '（已成功创建 ' + created + ' 个）' : ''), 'error');
            } finally {
                if (btn) btn.disabled = false;
            }
        },
        // ============ AI 协作面板 ============
        handleAiQaKeyDown: function (event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendAiQuestion();
            }
        },
        sendAiQuestion: sendAiQuestion,
        // ============ 团队周报（本地聚合） ============
        generateWeeklyReport: async function () {
            var body = byId('weeklyReportBody');
            if (body) body.innerHTML = '<div class="panel-loading">生成中...</div>';
            var sendBtn = byId('weeklyReportSendBtn');
            if (sendBtn) sendBtn.disabled = true;
            bootstrap.Modal.getOrCreateInstance(byId('weeklyReportModal')).show();
            try {
                weeklyReportData = await api('/team/' + teamId + '/weekly-report');
                if (body) body.innerHTML = renderWeeklyReportHtml(weeklyReportData);
                if (sendBtn) sendBtn.disabled = false;
            } catch (error) {
                if (body) body.innerHTML = '<div class="panel-loading">' + escapeHtml(error.message || '生成失败') + '</div>';
            }
        },
        sendWeeklyReportToChat: async function () {
            if (!weeklyReportData) return;
            var text = buildWeeklyReportText(weeklyReportData);
            if (!text) return;
            var sendBtn = byId('weeklyReportSendBtn');
            if (sendBtn) sendBtn.disabled = true;
            try {
                await postChatMessage(text, 'TEXT');
                bootstrap.Modal.getInstance(byId('weeklyReportModal'))?.hide();
                notify('周报已发送到群聊', 'success');
            } catch (error) {
                notify(error.message || '发送失败', 'error');
            } finally {
                if (sendBtn) sendBtn.disabled = false;
            }
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
        setupProjectMenu();
        setupMobileKanbanTabs();
        setupMobileTaskActions();
        bindToolbarEvents();
        if (mobileMedia.addEventListener) mobileMedia.addEventListener('change', applyResponsiveState);
        else if (mobileMedia.addListener) mobileMedia.addListener(applyResponsiveState);
        document.addEventListener('keydown', function (event) {
            if (event.key !== 'Escape') return;
            setProjectMenu(false);
            closeMobileTaskActions();
        });
        var taskModal = byId('createTaskModal');
        if (taskModal) taskModal.addEventListener('hidden.bs.modal', function () { editingTaskId = null; });
        // 所有 API 请求并行发出，消除串行等待
        await Promise.allSettled([loadCurrentUser(), loadTeamInfo(), loadMembers(), loadTasks()]);
        // 并行完成后统一刷新依赖多数据源的 UI
        if (teamInfo) renderOverview(teamInfo);
        updateCreateTaskBtn();
        await loadRecommendedMembers();
        applyResponsiveState();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
