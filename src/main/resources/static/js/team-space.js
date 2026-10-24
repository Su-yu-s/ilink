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
    var pendingReviewTaskId = null;
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
        // 侧栏收窄后队名可能被省略号截断，补一个 hover 提示
        var sidebarName = byId('sidebarTeamName');
        if (sidebarName) sidebarName.title = name;
        setText('mobileProjectName', name);

        var warning = byId('teamWarning');
        if (warning) warning.style.display = 'none';

        renderOverview(teamInfo);
        // 批阅入口的显隐依赖 canReviewTasks（来自 /info）；任务列表可能先于这里加载完，
        // 这里再同步一次，避免按钮因为竞态一直藏着
        syncReviewBoardEntry();
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
        // hidden：占位项只在未选中时作为提示显示，不进可选列表（base-select 下用属性而非 CSS，
        // Chrome 对 picker 里的 option 忽略作者样式的 display:none）
        select.innerHTML = '<option value="" hidden>选择负责人</option>';
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
        // Task APIs are available only after the team has reached TEAMING.
        // Opening a recruiting team must remain a clean read-only workspace,
        // not trigger an avoidable 400 on every page load.
        if (!teamInfo || String(teamInfo.status || '').toUpperCase() !== 'TEAMING') {
            tasks = [];
            renderBoard();
            syncReviewBoardEntry();
            return true;
        }
        try {
            var data = await api('/tasks?teamId=' + encodeURIComponent(teamId), { silent: true });
            tasks = Array.isArray(data) ? data.map(adaptTask) : [];
            renderBoard();
            syncReviewBoardEntry();
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
            // 白名单映射漏字段会让详情页走错交付分支（需批阅的任务被当成免批阅直接完成）
            requiresReview: task.requiresReview === true || Number(task.requiresReview) === 1,
            parentTaskId: task.parentTaskId != null ? Number(task.parentTaskId) : null,
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
            stopLibraryIndexPolling();
            loadChatHistory();
            if (!isConnected && !stompClient) connectWebSocket();
        } else if (currentPanelId === 'members-panel') {
            stopLibraryIndexPolling();
            loadMembers();
        } else if (currentPanelId === 'library-panel') {
            libraryIndexPollAttempts = 0;
            loadLibraryTree(libraryTrashView);
        } else if (currentPanelId === 'ai-panel') {
            stopLibraryIndexPolling();
            loadAiPanel();
        } else if (currentPanelId === 'collab-panel') {
            stopLibraryIndexPolling();
            if (!isConnected && !stompClient) connectWebSocket();
            if (window.teamCollab && typeof window.teamCollab.reload === 'function') {
                window.teamCollab.reload();
            }
        } else {
            stopLibraryIndexPolling();
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
                stompClient.subscribe('/topic/team/' + teamId + '/collaboration', function (frame) {
                    try {
                        var event = JSON.parse(frame.body);
                        if (window.teamCollab
                            && typeof window.teamCollab.remoteChanged === 'function') {
                            window.teamCollab.remoteChanged(event);
                        }
                    } catch (error) {
                        console.warn('解析协作资产事件失败', error);
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
                var name = item.displayName || item.realName || '候选成员';
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

    // 批阅权限：与后端 /tasks/{id}/review 的判定一致（队长或已批准导师），由 /info 下发
    function canReviewTasks() {
        return !!(teamInfo && teamInfo.canReviewTasks);
    }

    /** 待批阅任务的条数，用于工作台入口的角标 */
    function pendingReviewTasks() {
        return tasks.filter(function (task) { return normalizeStatus(task.status) === 'review'; });
    }

    /** 批阅工作台是独立页面（team-review.html），带 taskId 指定先看哪一条 */
    function reviewWorkbenchUrl(taskId) {
        return '/team-review.html?id=' + encodeURIComponent(teamId) + '&taskId=' + Number(taskId);
    }

    /** 批阅工作台入口只对队长/导师显示，并带上待批阅条数 */
    /** 批阅工作台入口只对队长/导师显示，并带上待批阅条数；没得批就收起来 */
    function syncReviewBoardEntry() {
        var btn = byId('reviewBoardBtn');
        if (!btn) return;
        var pending = pendingReviewTasks().length;
        btn.hidden = !canReviewTasks() || pending === 0;
        setText('reviewBoardBtnText', pending ? '批阅工作台 · ' + pending : '批阅工作台');
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
        // 加载态不自动关闭：上传结束（成功或失败）时由 closeLoading() 收回
        var closeLoading = (typeof Toast !== 'undefined' && Toast.loading)
            ? Toast.loading('上传中…', imageOnly ? '正在上传图片' : '正在上传文件')
            : null;
        if (!closeLoading) notify('上传中...', 'info');
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
            if (closeLoading) closeLoading();
            notify('上传成功', 'success');
        } catch (error) {
            console.error('上传失败', error);
            if (closeLoading) closeLoading();
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
    var weeklyReportData = null;

    async function loadAiPanel() {
        if (window.teamAgentWorkbench
            && typeof window.teamAgentWorkbench.activate === 'function') {
            return window.teamAgentWorkbench.activate();
        }
        return false;
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
                var statusKey = normalizeStatus(task.status);
                var advanceButton = statusKey === 'todo'
                    ? '<button type="button" class="btn btn-primary" onclick="teamSpace.advanceTaskStatus(' + Number(task.id) + ')">开始任务</button>'
                    : '';
                // 进行中的任务要给被指派人一个出口：需批阅的先交材料，免批阅的直接标记完成
                var deliverButton = '';
                if (statusKey === 'in_progress' && Number(task.assignedTo) === Number(currentUserId)) {
                    deliverButton = task.requiresReview
                        ? '<button type="button" class="btn btn-primary" onclick="teamSpace.openSubmitReview(' + Number(task.id) + ')">提交审阅</button>'
                        : '<button type="button" class="btn btn-primary" onclick="teamSpace.markTaskComplete(' + Number(task.id) + ')">标记完成</button>';
                }
                // 待审核的任务只对接队长/导师：批阅走团队详情页的完整工作台（材料预览 + 逐份点评）
                var reviewButton = statusKey === 'review' && canReviewTasks()
                    ? '<a class="btn btn-primary" href="/team-detail.html?id=' + encodeURIComponent(teamId) +
                      '&reviewTaskId=' + Number(task.id) + '">批阅</a>'
                    : '';
                footer.innerHTML =
                    '<button type="button" class="btn btn-secondary" data-bs-dismiss="modal">关闭</button>' +
                    advanceButton +
                    deliverButton +
                    reviewButton +
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
        openSubmitReview: function (taskId) {
            pendingReviewTaskId = Number(taskId);
            var remark = byId('submitReviewRemark');
            if (remark) remark.value = '';
            var file = byId('submitReviewFile');
            if (file) {
                file.value = '';
                // 用赋值而非 addEventListener：每次打开模态都重绑会累积监听器
                file.onchange = function () {
                    setText('submitReviewFileName', file.files && file.files[0] ? file.files[0].name : '');
                };
            }
            setText('submitReviewFileName', '');
            bootstrap.Modal.getOrCreateInstance(byId('submitReviewModal')).show();
        },
        submitReviewMaterial: async function () {
            if (!pendingReviewTaskId) return;
            var remark = byId('submitReviewRemark') ? byId('submitReviewRemark').value.trim() : '';
            var fileInput = byId('submitReviewFile');
            var file = fileInput && fileInput.files ? fileInput.files[0] : null;
            if (!remark && !file) {
                notify('请填写交付说明或上传附件', 'warning');
                return;
            }
            var btn = byId('submitReviewBtn');
            if (btn) btn.disabled = true;
            try {
                var attachments = [];
                if (file) {
                    var form = new FormData();
                    form.append('file', file);
                    var response = await apiFetch('/api/upload/attachment?kind=task', { method: 'POST', body: form });
                    var result = await response.json();
                    if (!response.ok || Number(result.code) !== 200) {
                        throw new Error(result.message || '附件上传失败');
                    }
                    var uploaded = window.ILinkFiles.normalizeUploadResult(result.data || {}, file);
                    attachments.push({ name: uploaded.name, url: uploaded.url });
                }
                await api('/tasks/' + pendingReviewTaskId + '/submit', {
                    method: 'POST',
                    body: JSON.stringify({ remark: remark, attachments: attachments })
                });
                bootstrap.Modal.getInstance(byId('submitReviewModal'))?.hide();
                bootstrap.Modal.getInstance(byId('taskDetailModal'))?.hide();
                await loadTasks();
                notify('已提交，等待审核', 'success');
            } catch (error) {
                notify(error.message || '提交失败', 'error');
            } finally {
                if (btn) btn.disabled = false;
            }
        },
        markTaskComplete: async function (taskId) {
            if (!uiConfirm('确认该任务已完成？')) return;
            try {
                await api('/tasks/' + taskId + '/complete', { method: 'POST' });
                bootstrap.Modal.getInstance(byId('taskDetailModal'))?.hide();
                await loadTasks();
                notify('任务已完成', 'success');
            } catch (error) {
                notify(error.message || '操作失败', 'error');
            }
        },
        openReviewBoard: function () {
            var pending = pendingReviewTasks();
            if (!pending.length) {
                notify('当前没有待批阅的任务', 'warning');
                return;
            }
            // 工作台是独立页面，不再借用组队详情页
            window.location.href = reviewWorkbenchUrl(pending[0].id);
        },
        openReviewTask: function (taskId) {
            window.location.href = reviewWorkbenchUrl(taskId);
        },
        deleteTask: async function (taskId) {
            if (!uiConfirm('确定删除这个任务吗？')) return;
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
        },
        // ============ 团队资料库 ============
        loadLibrary: function () {
            libraryIndexPollAttempts = 0;
            return loadLibraryTree(libraryTrashView);
        },
        toggleLibraryTrash: function () {
            libraryTrashView = !libraryTrashView;
            libraryIndexPollAttempts = 0;
            loadLibraryTree(libraryTrashView);
        },
        libraryPickFiles: function () {
            byId('libraryFileInput')?.click();
        },
        /** parentId 传入时按该目录创建（右键菜单用），否则用当前选中的文件夹 */
        libraryNewFolder: async function (parentId) {
            var targetId = parentId === undefined ? librarySelectedFolder : parentId;
            var target = targetId == null ? null : libraryNodeById(libraryTreeData, targetId);
            await libraryDialog({
                title: '新建文件夹',
                message: '创建在：' + escapeHtml(target ? target.name : '资料库（根目录）'),
                placeholder: '文件夹名称',
                okText: '创建',
                onSubmit: async function (name) {
                    try {
                        await api('/team/' + teamId + '/library/folders', {
                            method: 'POST',
                            body: JSON.stringify({ parentId: targetId, name: name })
                        });
                    } catch (error) {
                        return error.message || '创建失败';   // 留在弹窗里，改名即可重试
                    }
                    notify('文件夹「' + name + '」已创建', 'success');
                    loadLibraryTree(libraryTrashView);
                    return null;
                }
            });
        },
        /** parentId=null 表示移到根目录 */
        libraryMove: async function (fileId, parentId) {
            try {
                await api('/team/' + teamId + '/library/files/' + Number(fileId) + '/move', {
                    method: 'PUT',
                    body: JSON.stringify({ parentId: parentId })
                });
                notify('已移动', 'success');
                loadLibraryTree(libraryTrashView);
            } catch (error) {
                notify(error.message || '移动失败', 'error');
            }
        },
        libraryDelete: async function (fileId, purge) {
            if (purge) {
                var confirmed = await libraryDialog({
                    mode: 'confirm', title: '彻底删除',
                    message: '彻底删除后不可恢复，文件将无法再下载或预览。确定继续？',
                    okText: '彻底删除', danger: true
                });
                if (confirmed === null) return;
            }
            try {
                var resp = await api('/team/' + teamId + '/library/files/' + Number(fileId) + (purge ? '?purge=true' : ''), {
                    method: 'DELETE'
                });
                notify(resp && resp.message ? resp.message : '已删除', 'success');
                if (librarySelectedFileId === Number(fileId)) clearLibraryPreview();
                loadLibraryTree(libraryTrashView);
            } catch (error) {
                notify(error.message || '删除失败', 'error');
            }
        },
        libraryRestore: async function (fileId) {
            try {
                await api('/team/' + teamId + '/library/files/' + Number(fileId) + '/restore', { method: 'PUT' });
                notify('已恢复', 'success');
                loadLibraryTree(libraryTrashView);
            } catch (error) {
                notify(error.message || '恢复失败', 'error');
            }
        },
        /** 重新建立检索索引：索引失败/失效/跳过时可重试，重试后自动轮询结果 */
        libraryReindex: async function (fileId) {
            try {
                var resp = await api('/team/' + teamId + '/library/files/' + Number(fileId) + '/reindex', {
                    method: 'POST'
                });
                notify(resp && resp.message ? resp.message : '已重新提交检索索引', 'success');
                libraryIndexPollAttempts = 0;
                loadLibraryTree(libraryTrashView);
            } catch (error) {
                notify(error.message || '重新索引请求失败', 'error');
            }
        }
    };

    // ============ 团队资料库实现 ============
    var libraryTreeData = [];
    var libraryTrashView = false;
    var librarySelectedFileId = null;
    var librarySelectedFolder = null;
    var libraryIndexPollTimer = null;
    var libraryIndexPollAttempts = 0;
    var LIBRARY_INDEX_POLL_MS = 1500;
    var LIBRARY_INDEX_POLL_MAX = 40;
    var LIBRARY_CONFLICT_PREF_KEY = 'ilink_library_conflict_strategy';
    var LIBRARY_PROGRESS_THRESHOLD = 20 * 1024 * 1024; // 大于 20MB 显示上传进度条（后端单文件上限 50MB）

    function libraryNodeById(nodes, id) {
        for (var i = 0; i < nodes.length; i++) {
            if (Number(nodes[i].id) === Number(id)) return nodes[i];
            var hit = nodes[i].children ? libraryNodeById(nodes[i].children, id) : null;
            if (hit) return hit;
        }
        return null;
    }

    function libraryCanManage(node) {
        return currentUserIsLeader() || Number(node.uploaderId) === Number(currentUserId);
    }

    function loadLibraryTree(trash) {
        var box = byId('libraryTree');
        if (!box) return Promise.resolve(false);
        stopLibraryIndexPolling();
        box.innerHTML = '<div class="panel-loading">加载中...</div>';
        var trashBtn = byId('libraryTrashBtn');
        if (trashBtn) {
            trashBtn.hidden = !currentUserIsLeader();
            trashBtn.textContent = trash ? '返回资料库' : '回收站';
            trashBtn.classList.toggle('btn-warning', trash);
        }
        if (byId('libraryUploadBtn')) byId('libraryUploadBtn').hidden = trash;
        if (byId('libraryNewFolderBtn')) byId('libraryNewFolderBtn').hidden = trash;
        return api('/team/' + teamId + '/library' + (trash ? '?view=trash' : ''), { silent: true })
            .then(function (list) {
                libraryTreeData = Array.isArray(list) ? list : [];
                librarySelectedFolder = null;
                renderLibraryTree();
                if (librarySelectedFileId) {
                    var node = libraryNodeById(libraryTreeData, librarySelectedFileId);
                    if (node && !node.isFolder) renderLibraryPreview(node);
                    else clearLibraryPreview();
                } else {
                    clearLibraryPreview();
                }
                scheduleLibraryIndexPolling();
                return true;
            })
            .catch(function (error) {
                box.innerHTML = '<div class="library-empty">' + escapeHtml(error.message || '资料库加载失败') + '</div>';
                return false;
            });
    }

    function hasPendingLibraryIndex(nodes) {
        return (nodes || []).some(function (node) {
            if (node.isFolder) return hasPendingLibraryIndex(node.children);
            return node.indexStatus === 'PENDING' || node.indexStatus === 'INDEXING';
        });
    }

    function stopLibraryIndexPolling() {
        if (libraryIndexPollTimer) {
            window.clearTimeout(libraryIndexPollTimer);
            libraryIndexPollTimer = null;
        }
        if (currentPanelId !== 'library-panel' || libraryTrashView) {
            libraryIndexPollAttempts = 0;
        }
    }

    function scheduleLibraryIndexPolling() {
        if (libraryTrashView || currentPanelId !== 'library-panel') {
            return;
        }
        if (!hasPendingLibraryIndex(libraryTreeData)) {
            libraryIndexPollAttempts = 0;
            return;
        }
        if (libraryIndexPollAttempts >= LIBRARY_INDEX_POLL_MAX) return;
        libraryIndexPollTimer = window.setTimeout(function () {
            libraryIndexPollTimer = null;
            libraryIndexPollAttempts += 1;
            loadLibraryTree(libraryTrashView);
        }, LIBRARY_INDEX_POLL_MS);
    }

    function clearLibraryPreview() {
        librarySelectedFileId = null;
        if (byId('libraryPvHead')) byId('libraryPvHead').hidden = true;
        if (byId('libraryPvCanvasWrap')) byId('libraryPvCanvasWrap').hidden = true;
        if (byId('libraryPvEmpty')) byId('libraryPvEmpty').hidden = false;
    }

    function renderLibraryPreview(node) {
        var head = byId('libraryPvHead');
        if (!head) return;
        librarySelectedFileId = Number(node.id);
        var downloadName = libraryDownloadName(node);
        var sub = window.ILinkPreview.typeLabel(node.fileType, node.name) +
            (node.fileSize ? ' · ' + window.ILinkPreview.formatSize(node.fileSize) : '') +
            ' · ' + (node.uploaderName || '') + (node.isMentor ? '（导师）' : '');
        var actions = '<a class="library-pv-action" href="' + escapeHtml(node.fileUrl || '#') + '" download="' +
            escapeHtml(downloadName) + '">下载</a>';
        if (libraryCanManage(node)) {
            actions += '<button type="button" class="library-pv-action" onclick="teamSpace.libraryRenamePrompt(' + Number(node.id) + ')">重命名</button>' +
                (LIBRARY_INDEX_RETRYABLE.indexOf(node.indexStatus) >= 0
                    ? '<button type="button" class="library-pv-action" onclick="teamSpace.libraryReindex(' + Number(node.id) + ')">重新建立检索索引</button>'
                    : '') +
                '<button type="button" class="library-pv-action library-pv-action--danger" onclick="libraryDeleteWithReferences(' + Number(node.id) + ')">删除</button>';
        }
        head.innerHTML =
            '<div class="library-pv-icon">' + window.ILinkPreview.fileIcon(node.fileType, node.name) + '</div>' +
            '<div class="library-pv-meta"><span class="library-pv-name" title="' + escapeHtml(node.name) + '">' + escapeHtml(node.name) + '</span>' +
            '<span class="library-pv-sub">' + escapeHtml(sub) + '</span></div>' +
            '<div class="library-pv-actions">' + actions + '</div>';
        head.hidden = false;
        byId('libraryPvEmpty').hidden = true;
        var wrap = byId('libraryPvCanvasWrap');
        wrap.hidden = false;
        window.ILinkPreview.renderInto(byId('libraryPvCanvas'), node);
    }

    teamSpace.libraryRenamePrompt = function (fileId) {
        var node = libraryNodeById(libraryTreeData, fileId);
        if (!node) return;
        libraryDialog({
            title: '重命名',
            message: node.isFolder ? '文件夹' : '文件',
            value: node.name,
            okText: '保存',
            onSubmit: function (name) {
                if (name === node.name) return null;   // 没改动，直接关掉
                return api('/team/' + teamId + '/library/files/' + Number(fileId), {
                    method: 'PUT',
                    body: JSON.stringify({ name: name })
                }).then(function () {
                    notify('重命名成功', 'success');
                    loadLibraryTree(libraryTrashView);
                    return null;
                }).catch(function (error) {
                    return error.message || '重命名失败';
                });
            }
        });
    };

    /** 移动到…：列出全部文件夹供选择（被移动的文件夹自身及其子树不出现，避免移进自己里面） */
    teamSpace.libraryMovePrompt = function (fileId) {
        var node = libraryNodeById(libraryTreeData, fileId);
        if (!node) return;
        var overlay = document.createElement('div');
        overlay.className = 'library-conflict-mask';
        overlay.innerHTML =
            '<div class="library-conflict">' +
                '<div class="library-conflict__title">移动「' + escapeHtml(node.name) + '」</div>' +
                '<div class="library-conflict__existing">选择目标目录</div>' +
                '<div class="library-move-list">' + libraryTargetFolders(fileId).map(function (opt) {
                    var isCurrent = opt.id === null ? node.parentId == null : Number(node.parentId) === opt.id;
                    return '<button type="button" class="library-move-item' + (isCurrent ? ' is-current' : '') + '"' +
                        ' data-id="' + (opt.id === null ? '' : opt.id) + '"' + (isCurrent ? ' disabled' : '') +
                        ' style="padding-left:' + (10 + opt.depth * 14) + 'px">' +
                        (opt.id === null ? '' : window.ILinkPreview.icons.folder) +
                        '<span>' + escapeHtml(opt.name) + '</span>' +
                        (isCurrent ? '<span class="library-move-item__tag">当前目录</span>' : '') +
                        '</button>';
                }).join('') + '</div>' +
                '<div class="library-conflict__foot">' +
                    '<button type="button" class="btn btn-sm btn-outline-secondary" data-act="cancel">取消</button>' +
                    '<button type="button" class="btn btn-sm btn-primary" data-act="ok" disabled>移动到此处</button>' +
                '</div>' +
            '</div>';
        document.body.appendChild(overlay);
        var selected = null;
        function close() { overlay.remove(); }
        overlay.querySelectorAll('.library-move-item').forEach(function (btn) {
            btn.addEventListener('click', function () {
                overlay.querySelectorAll('.library-move-item').forEach(function (b) { b.classList.remove('is-selected'); });
                btn.classList.add('is-selected');
                selected = btn.getAttribute('data-id');
                overlay.querySelector('[data-act="ok"]').disabled = false;
            });
        });
        overlay.querySelector('[data-act="cancel"]').addEventListener('click', close);
        overlay.addEventListener('click', function (e) { if (e.target === overlay) close(); });
        overlay.querySelector('[data-act="ok"]').addEventListener('click', function () {
            if (selected === null) return;
            close();
            teamSpace.libraryMove(fileId, selected === '' ? null : Number(selected));
        });
    };

    /** 可选目标目录：根目录 + 全部文件夹；跳过被移动节点本身（children 是嵌套结构，跳过即跳过整棵子树） */
    function libraryTargetFolders(excludeId) {
        var out = [{ id: null, name: '资料库（根目录）', depth: 0 }];
        (function walk(nodes, depth) {
            (nodes || []).forEach(function (n) {
                if (!n.isFolder || Number(n.id) === Number(excludeId)) return;
                out.push({ id: Number(n.id), name: n.name, depth: depth });
                walk(n.children, depth + 1);
            });
        })(libraryTreeData, 1);
        return out;
    }

    /**
     * 资料库通用弹窗，替代 window.prompt / window.confirm：
     * mode='input'（新建文件夹、重命名）或 mode='confirm'（删除确认）。
     * onSubmit 返回错误文案时弹窗不关闭，直接显示错误让用户改；返回 Promise，取消得到 null。
     */
    function libraryDialog(opts) {
        return new Promise(function (resolve) {
            var isInput = opts.mode !== 'confirm';
            var overlay = document.createElement('div');
            overlay.className = 'library-conflict-mask';
            overlay.innerHTML =
                '<div class="library-conflict">' +
                    '<div class="library-conflict__title">' + escapeHtml(opts.title) + '</div>' +
                    (opts.message ? '<div class="library-conflict__existing library-dialog__message">' + opts.message + '</div>' : '') +
                    (isInput ? '<input type="text" class="library-dialog__input" maxlength="100" autocomplete="off" ' +
                        'placeholder="' + escapeHtml(opts.placeholder || '') + '" value="' + escapeHtml(opts.value || '') + '">' : '') +
                    '<div class="library-dialog__error" hidden></div>' +
                    '<div class="library-conflict__foot">' +
                        '<button type="button" class="btn btn-sm btn-outline-secondary" data-act="cancel">取消</button>' +
                        '<button type="button" class="btn btn-sm btn-primary' + (opts.danger ? ' library-dialog__danger' : '') +
                            '" data-act="ok">' + escapeHtml(opts.okText || '确定') + '</button>' +
                    '</div>' +
                '</div>';
            document.body.appendChild(overlay);
            var input = overlay.querySelector('.library-dialog__input');
            var errorBox = overlay.querySelector('.library-dialog__error');
            var okBtn = overlay.querySelector('[data-act="ok"]');
            var busy = false;

            function close(value) {
                document.removeEventListener('keydown', onKey, true);
                overlay.remove();
                resolve(value);
            }
            function showError(message) {
                errorBox.textContent = message;
                errorBox.hidden = false;
                busy = false;
                okBtn.disabled = false;
            }
            function submit() {
                if (busy) return;
                var value = isInput ? input.value.trim() : true;
                if (isInput && !value) { showError('名称不能为空'); input.focus(); return; }
                errorBox.hidden = true;
                busy = true;
                okBtn.disabled = true;
                Promise.resolve(opts.onSubmit ? opts.onSubmit(value) : value)
                    .then(function (errorMessage) {
                        // 只有字符串才算「校验/接口错误」；确认模式下 resolve 的是 true，不能当错误
                        if (typeof errorMessage === 'string' && errorMessage) { showError(errorMessage); return; }
                        close(value);
                    })
                    .catch(function (error) { showError((error && error.message) || '操作失败'); });
            }
            function onKey(event) {
                if (event.key === 'Escape') { event.preventDefault(); close(null); }
                else if (event.key === 'Enter' && isInput) { event.preventDefault(); submit(); }
            }
            overlay.querySelector('[data-act="cancel"]').addEventListener('click', function () { close(null); });
            okBtn.addEventListener('click', submit);
            overlay.addEventListener('click', function (e) { if (e.target === overlay) close(null); });
            document.addEventListener('keydown', onKey, true);
            if (input) {
                input.addEventListener('keydown', function (e) {
                    if (e.key === 'Enter') {
                        e.preventDefault();
                        submit();
                    }
                });
            }
            setTimeout(function () { if (input) { input.focus(); input.select(); } else { okBtn.focus(); } }, 0);
        });
    }

    /** 删除前查引用：被任务引用时列出影响范围再确认 */
    function libraryDeleteWithReferences(fileId) {
        var node = libraryNodeById(libraryTreeData, fileId);
        if (!node) return;
        function confirmDelete(message) {
            return libraryDialog({
                mode: 'confirm', title: '删除确认', message: message,
                okText: '删除', danger: true
            }).then(function (ok) { if (ok !== null) teamSpace.libraryDelete(fileId, false); });
        }
        api('/team/' + teamId + '/library/files/' + Number(fileId) + '/references', { silent: true })
            .then(function (refs) {
                if (Array.isArray(refs) && refs.length) {
                    var lines = refs.map(function (r) {
                        return '<div class="library-dialog__ref">· ' + escapeHtml(r.taskTitle || '任务') + '</div>';
                    }).join('');
                    confirmDelete('「' + escapeHtml(node.name) + '」已被 ' + refs.length + ' 个任务引用：' + lines +
                        '<div class="library-dialog__note">删除后，这些任务的交付材料会显示为「文件已删除」。</div>');
                } else {
                    confirmDelete('「' + escapeHtml(node.name) + '」将进入回收站，30 天内可恢复。');
                }
            })
            .catch(function () {
                confirmDelete('确定删除「' + escapeHtml(node.name) + '」吗？');
            });
    }
    window.libraryDeleteWithReferences = libraryDeleteWithReferences;

    function renderLibraryTree() {
        var container = byId('libraryTree');
        if (!container) return;
        var keyword = (byId('librarySearch')?.value || '').trim().toLowerCase();
        container.innerHTML = '';
        if (!libraryTreeData.length) {
            container.innerHTML = '<div class="library-empty">' +
                (libraryTrashView ? '回收站是空的' : (keyword ? '未找到匹配的文件' : '还没有文件，点击右上角上传')) + '</div>';
            return;
        }
        (function renderNodes(nodes, parent, depth) {
            nodes.forEach(function (node) {
                var row = document.createElement('div');
                row.className = 'library-row';
                row.setAttribute('data-id', node.id);
                if (node.isFolder) {
                    var childCount = (node.children || []).length;
                    var matchesChild = keyword && librarySubtreeHasMatch(node, keyword);
                    var open = !!node.open || !!keyword;
                    if (keyword && !matchesChild && !node.name.toLowerCase().includes(keyword)) return;
                    row.innerHTML =
                        '<span class="library-row__arrow' + (open ? ' open' : '') + '">' + window.ILinkPreview.icons.chevronRight + '</span>' +
                        '<span class="library-row__icon library-row__icon--folder">' + (open ? window.ILinkPreview.icons.folderOpen : window.ILinkPreview.icons.folder) + '</span>' +
                        '<span class="library-row__name" title="' + escapeHtml(node.name) + '">' + escapeHtml(node.name) + '</span>' +
                        '<span class="library-row__badge">' + childCount + '</span>';
                    row.addEventListener('click', function (e) {
                        e.stopPropagation();
                        if (keyword) return; // 搜索中禁用折叠
                        node.open = !node.open;
                        renderLibraryTree();
                    });
                    row.addEventListener('contextmenu', function (e) {
                        e.preventDefault();
                        e.stopPropagation();
                        showLibraryContextMenu(e, node);
                    });
                    bindLibraryDrag(row, node);
                    bindLibraryRowDrop(row, node);
                    parent.appendChild(row);
                    if (node.children && (node.open || keyword)) {
                        var wrap = document.createElement('div');
                        wrap.className = 'library-children';
                        parent.appendChild(wrap);
                        renderNodes(node.children, wrap, depth + 1);
                    }
                } else {
                    if (keyword && !node.name.toLowerCase().includes(keyword)) return;
                    row.innerHTML =
                        '<span class="library-row__arrow" style="visibility:hidden;"></span>' +
                        '<span class="library-row__icon">' + window.ILinkPreview.fileIcon(node.fileType, node.name) + '</span>' +
                        '<span class="library-row__name" title="' + escapeHtml(node.name) + '">' + escapeHtml(node.name) + '</span>' +
                        (node.isMentor ? '<span class="library-row__mentor">导</span>' : '') +
                        libraryIndexBadge(node) +
                        '<span class="library-row__size">' + escapeHtml(window.ILinkPreview.formatSize(node.fileSize) || '') + '</span>';
                    if (Number(node.id) === Number(librarySelectedFileId)) row.classList.add('active');
                    row.addEventListener('click', function (e) {
                        e.stopPropagation();
                        renderLibraryPreview(node);
                        renderLibraryTree();
                    });
                    row.addEventListener('contextmenu', function (e) {
                        e.preventDefault();
                        e.stopPropagation();
                        showLibraryContextMenu(e, node);
                    });
                    bindLibraryDrag(row, node);
                    parent.appendChild(row);
                }
            });
        })(libraryTreeData, container, 0);
    }

    function librarySubtreeHasMatch(node, keyword) {
        return (node.children || []).some(function (c) {
            return c.name.toLowerCase().includes(keyword) || (c.isFolder && librarySubtreeHasMatch(c, keyword));
        });
    }

    var LIBRARY_INDEX_LABELS = {
        PENDING: '等待检索',
        INDEXING: '正在建立索引',
        READY: '可被 AI 检索',
        FAILED: '索引失败',
        STALE: '索引已失效',
        SKIPPED: '不参与检索'
    };

    /** 只有已经结束或失效的状态才提供重试入口；排队/进行中的状态点了也没用 */
    var LIBRARY_INDEX_RETRYABLE = ['FAILED', 'STALE', 'SKIPPED'];

    /** 仅显示当前有效文件的可检索状态；回收站中的文件不暗示仍可检索。 */
    function libraryIndexBadge(node) {
        if (libraryTrashView || !node.indexStatus) return '';
        var label = LIBRARY_INDEX_LABELS[node.indexStatus] || '索引状态未知';
        var title = node.indexError && (node.indexStatus === 'FAILED' || node.indexStatus === 'SKIPPED')
            ? label + '：' + node.indexError
            : label;
        return '<span class="library-row__index library-row__index--' +
            escapeHtml(String(node.indexStatus).toLowerCase()) + '" title="' +
            escapeHtml(title) + '">' + escapeHtml(label) + '</span>';
    }

    // ============ 拖拽移动（文件夹行 = 放进该目录，文件树空白处 = 移到根目录） ============
    var libraryDraggingId = null;

    function clearLibraryDropTargets() {
        document.querySelectorAll('.library-row.is-drop-target, .library-tree-scroll.is-drop-target')
            .forEach(function (el) { el.classList.remove('is-drop-target'); });
    }

    /** 能否拖：回收站内的不能动；需要上传者或队长权限（与服务端一致） */
    function libraryDraggable(node) {
        return !libraryTrashView && libraryCanManage(node);
    }

    /** 目标目录能否作为落点：不能是自己、不能是当前所在目录、不能是自己的子孙（会成环） */
    function libraryCanDropInto(targetId) {
        if (libraryDraggingId === null) return false;
        var dragged = libraryNodeById(libraryTreeData, libraryDraggingId);
        if (!dragged) return false;
        if (targetId === null) return dragged.parentId != null;
        if (Number(targetId) === Number(dragged.id)) return false;
        if (Number(targetId) === Number(dragged.parentId)) return false;
        return !libraryIsDescendantOf(dragged.id, targetId);
    }

    function libraryIsDescendantOf(ancestorId, candidateId) {
        var ancestor = libraryNodeById(libraryTreeData, ancestorId);
        if (!ancestor) return false;
        var hit = false;
        (function walk(nodes) {
            (nodes || []).forEach(function (n) {
                if (!n.isFolder) return;
                if (Number(n.id) === Number(candidateId)) hit = true;
                walk(n.children);
            });
        })(ancestor.children || []);
        return hit;
    }

    function bindLibraryDrag(row, node) {
        if (!libraryDraggable(node)) return;
        row.draggable = true;
        row.addEventListener('dragstart', function (e) {
            libraryDraggingId = Number(node.id);
            row.classList.add('is-dragging');
            if (e.dataTransfer) {
                e.dataTransfer.effectAllowed = 'move';
                e.dataTransfer.setData('text/plain', String(node.id));   // Firefox 不 setData 不会触发拖拽
            }
            hideLibraryContextMenu();
        });
        row.addEventListener('dragend', function () {
            libraryDraggingId = null;
            row.classList.remove('is-dragging');
            clearLibraryDropTargets();
        });
    }

    function bindLibraryRowDrop(row, node) {
        if (!node.isFolder) return;
        row.addEventListener('dragover', function (e) {
            if (!libraryCanDropInto(node.id)) return;
            e.preventDefault();
            e.stopPropagation();
            if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
            clearLibraryDropTargets();
            row.classList.add('is-drop-target');
        });
        row.addEventListener('drop', function (e) {
            if (!libraryCanDropInto(node.id)) return;
            e.preventDefault();
            e.stopPropagation();
            var movingId = libraryDraggingId;
            libraryDraggingId = null;
            clearLibraryDropTargets();
            teamSpace.libraryMove(movingId, Number(node.id));
        });
    }

    // 树容器空白处：拖到根目录（只挂一次，容器元素在多次重渲染间保持不变）
    (function bindLibraryRootDrop() {
        var tree = byId('libraryTree');
        if (!tree) return;
        tree.addEventListener('dragover', function (e) {
            if (e.target.closest && e.target.closest('.library-row')) return;   // 行上有行自己的落点
            if (!libraryCanDropInto(null)) return;
            e.preventDefault();
            if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
            tree.classList.add('is-drop-target');
        });
        tree.addEventListener('dragleave', function (e) {
            if (e.target === tree) tree.classList.remove('is-drop-target');
        });
        tree.addEventListener('drop', function (e) {
            if (e.target.closest && e.target.closest('.library-row')) return;
            if (!libraryCanDropInto(null)) return;
            e.preventDefault();
            var movingId = libraryDraggingId;
            libraryDraggingId = null;
            tree.classList.remove('is-drop-target');
            teamSpace.libraryMove(movingId, null);
        });
    })();

    // 右键菜单：按权限与视图动态生成
    function showLibraryContextMenu(event, node) {
        hideLibraryContextMenu();
        var menu = document.createElement('div');
        menu.className = 'library-context-menu';
        var items = [];
        if (libraryTrashView) {
            if (currentUserIsLeader()) {
                items.push({ label: '恢复', fn: function () { teamSpace.libraryRestore(node.id); } });
                items.push({ label: '彻底删除', danger: true, fn: function () { teamSpace.libraryDelete(node.id, true); } });
            }
        } else if (node.isFolder) {
            items.push({ label: '新建文件夹', fn: function () { teamSpace.libraryNewFolder(node.id); } });
            if (libraryCanManage(node)) {
                items.push({ label: '重命名', fn: function () { teamSpace.libraryRenamePrompt(node.id); } });
                items.push({ label: '移动到…', fn: function () { teamSpace.libraryMovePrompt(node.id); } });
                items.push({ label: '删除文件夹', danger: true, fn: function () { libraryDeleteWithReferences(node.id); } });
            }
        } else {
            items.push({ label: '预览', fn: function () { renderLibraryPreview(node); } });
            items.push({ label: '下载', fn: function () { downloadLibraryFile(node); } });
            if (libraryCanManage(node)) {
                items.push({ label: '重命名', fn: function () { teamSpace.libraryRenamePrompt(node.id); } });
                items.push({ label: '移动到…', fn: function () { teamSpace.libraryMovePrompt(node.id); } });
                if (LIBRARY_INDEX_RETRYABLE.indexOf(node.indexStatus) >= 0) {
                    items.push({ label: '重新建立检索索引', fn: function () { teamSpace.libraryReindex(node.id); } });
                }
                items.push({ label: '删除', danger: true, fn: function () { libraryDeleteWithReferences(node.id); } });
            }
        }
        if (!items.length) return;
        menu.innerHTML = items.map(function (item, i) {
            return '<button type="button" data-index="' + i + '"' + (item.danger ? ' class="danger"' : '') + '>' + escapeHtml(item.label) + '</button>';
        }).join('');
        items.forEach(function (item, i) {
            menu.querySelector('[data-index="' + i + '"]').addEventListener('click', function () {
                hideLibraryContextMenu();
                item.fn();
            });
        });
        document.body.appendChild(menu);
        var x = Math.min(event.clientX, window.innerWidth - menu.offsetWidth - 8);
        var y = Math.min(event.clientY, window.innerHeight - menu.offsetHeight - 8);
        menu.style.left = x + 'px';
        menu.style.top = y + 'px';
        setTimeout(function () {
            document.addEventListener('click', hideLibraryContextMenu, { once: true });
        }, 0);
    }

    function hideLibraryContextMenu() {
        document.querySelectorAll('.library-context-menu').forEach(function (m) { m.remove(); });
    }

    function downloadLibraryFile(node) {
        if (!node.fileUrl) return;
        var a = document.createElement('a');
        a.href = node.fileUrl;
        a.download = libraryDownloadName(node);
        document.body.appendChild(a);
        a.click();
        a.remove();
    }

    /** 下载时恢复上传时的文件名；保留两者产生的 "(1)" 只用于列表区分，不写回原文件名。 */
    function libraryDownloadName(node) {
        return node.originalFileName || node.name;
    }

    // 上传：kind=library 上传 → 登记（同名冲突处理）。>20MB 展示进度条。
    var libraryFileInput = byId('libraryFileInput');
    if (libraryFileInput) {
        libraryFileInput.addEventListener('change', function () {
            var files = libraryFileInput.files ? Array.from(libraryFileInput.files) : [];
            libraryFileInput.value = '';
            files.forEach(function (file) { uploadLibraryFile(file); });
        });
    }

    function uploadLibraryFile(file) {
        var parentId = librarySelectedFolder;
        showLibraryProgress(file, 0);
        uploadLibraryXhr(file, function (progress) {
            showLibraryProgress(file, progress);
        }, function (err, result) {
            if (err) {
                hideLibraryProgress();
                notify(err.message || '「' + file.name + '」上传失败', 'error');
                return;
            }
            registerLibraryFile(parentId, file, result, null);
        });
    }

    function uploadLibraryXhr(file, onProgress, done) {
        var xhr = new XMLHttpRequest();
        var form = new FormData();
        form.append('file', file);
        form.append('kind', 'library');
        xhr.open('POST', '/api/upload/attachment');
        var token = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
        if (token) xhr.setRequestHeader('X-XSRF-TOKEN', decodeURIComponent(token[1]));
        xhr.upload.addEventListener('progress', function (e) {
            if (e.lengthComputable) onProgress(Math.round(e.loaded / e.total * 100));
        });
        xhr.addEventListener('load', function () {
            try {
                var res = JSON.parse(xhr.responseText);
                if (res && res.code === 200) {
                    onProgress(100);
                    done(null, res.data);
                } else {
                    done(new Error(res && res.message ? res.message : '上传失败'));
                }
            } catch (e) {
                done(new Error('上传失败'));
            }
        });
        xhr.addEventListener('error', function () { done(new Error('上传失败，请检查网络')); });
        xhr.send(form);
    }

    function registerLibraryFile(parentId, file, uploaded, strategy) {
        api('/team/' + teamId + '/library/files', {
            method: 'POST',
            body: JSON.stringify({
                parentId: parentId,
                name: file.name,
                url: uploaded.url,
                size: file.size,
                conflictStrategy: strategy
            })
        }).then(function (resp) {
            hideLibraryProgress();
            // api() 已解包 Result.data；冲突对象就在这里，不能再读一层 resp.data。
            if (resp && resp.conflict) {
                resolveLibraryConflict(parentId, file, uploaded, resp.existing);
                return;
            }
            notify('「' + file.name + '」已上传', 'success');
            libraryIndexPollAttempts = 0;
            loadLibraryTree(libraryTrashView);
        }).catch(function (error) {
            hideLibraryProgress();
            notify(error.message || '「' + file.name + '」登记失败', 'error');
        });
    }

    /** 同名冲突：记住的选择直接应用；否则弹轻量弹窗（替换/保留两者/取消） */
    function resolveLibraryConflict(parentId, file, uploaded, existing) {
        var saved = null;
        try { saved = window.localStorage.getItem(LIBRARY_CONFLICT_PREF_KEY); } catch (e) { /* ignore */ }
        if (saved === 'REPLACE' || saved === 'KEEP_BOTH') {
            registerLibraryFile(parentId, file, uploaded, saved);
            return;
        }
        var overlay = document.createElement('div');
        overlay.className = 'library-conflict-mask';
        overlay.innerHTML =
            '<div class="library-conflict">' +
                '<div class="library-conflict__title">已存在同名文件</div>' +
                '<div class="library-conflict__existing">「' + escapeHtml(existing.name) + '」· ' +
                    escapeHtml(window.ILinkPreview.formatSize(existing.fileSize) || '') + ' · ' +
                    escapeHtml(existing.uploaderName || '') + '</div>' +
                '<div class="library-conflict__choice"><label><input type="radio" name="libConflict" value="KEEP_BOTH" checked> 保留两者（新文件命名为 "' +
                    escapeHtml(uniqueCandidateName(existing.name)) + '"）</label></div>' +
                '<div class="library-conflict__choice"><label><input type="radio" name="libConflict" value="REPLACE"> 替换现有文件（旧版本进入回收站）</label></div>' +
                '<div class="library-conflict__choice"><label><input type="radio" name="libConflict" value="CANCEL"> 取消上传</label></div>' +
                '<label class="library-conflict__remember"><input type="checkbox" id="libConflictRemember"> 记住我的选择，以后同名时自动应用</label>' +
                '<div class="library-conflict__foot">' +
                    '<button type="button" class="btn btn-sm btn-secondary" data-act="cancel">取消</button>' +
                    '<button type="button" class="btn btn-sm btn-primary" data-act="ok">确定</button>' +
                '</div>' +
            '</div>';
        document.body.appendChild(overlay);
        overlay.querySelector('[data-act="cancel"]').addEventListener('click', function () {
            hideLibraryProgress();
            overlay.remove();
        });
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) { hideLibraryProgress(); overlay.remove(); }
        });
        overlay.querySelector('[data-act="ok"]').addEventListener('click', function () {
            var choice = overlay.querySelector('input[name="libConflict"]:checked').value;
            var remember = overlay.querySelector('#libConflictRemember').checked;
            overlay.remove();
            if (choice === 'CANCEL') { hideLibraryProgress(); return; }
            if (remember) {
                try { window.localStorage.setItem(LIBRARY_CONFLICT_PREF_KEY, choice); } catch (e) { /* ignore */ }
            }
            registerLibraryFile(parentId, file, uploaded, choice);
        });
    }

    function uniqueCandidateName(name) {
        var dot = name.lastIndexOf('.');
        var base = dot > 0 ? name.substring(0, dot) : name;
        var ext = dot > 0 ? name.substring(dot) : '';
        return base + ' (1)' + ext;
    }

    function showLibraryProgress(file, percent) {
        var bar = byId('libraryProgressBar');
        if (!bar) {
            var tree = byId('libraryTree');
            if (!tree || !tree.parentElement) return;
            bar = document.createElement('div');
            bar.id = 'libraryProgressBar';
            bar.className = 'library-progress';
            bar.innerHTML = '<div class="library-progress__label"></div><div class="library-progress__track"><div class="library-progress__fill"></div></div>';
            tree.parentElement.insertBefore(bar, tree);
        }
        bar.querySelector('.library-progress__label').textContent = '上传中 ' + file.name + ' · ' + percent + '%';
        bar.querySelector('.library-progress__fill').style.width = percent + '%';
        if (percent >= 100) bar.querySelector('.library-progress__label').textContent = '「' + file.name + '」上传完成，正在登记…';
    }

    function hideLibraryProgress() {
        var bar = byId('libraryProgressBar');
        if (bar) setTimeout(function () { bar.remove(); }, 400);
    }

    // 搜索：输入即过滤（渲染时 kw 有值强制展开且禁折叠，清空即恢复）
    (function bindLibrarySearch() {
        var search = byId('librarySearch');
        if (!search) return;
        search.addEventListener('input', function () { renderLibraryTree(); });
        search.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') {
                search.value = '';
                renderLibraryTree();
                search.blur();
            }
        });
    })();

    // 选中文件夹作为上传目标（点击文件夹行时记录）
    document.addEventListener('click', function (e) {
        var row = e.target && e.target.closest ? e.target.closest('.library-row') : null;
        if (!row) return;
        var id = Number(row.getAttribute('data-id'));
        var node = libraryNodeById(libraryTreeData, id);
        if (node && node.isFolder) librarySelectedFolder = id;
    });

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
        // 任务接口依赖团队状态；其余请求仍并行，task 请求在 /info 完成后立即发出。
        await Promise.allSettled([
            loadCurrentUser(),
            loadTeamInfo().then(function () { return loadTasks(); }),
            loadMembers()
        ]);
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
