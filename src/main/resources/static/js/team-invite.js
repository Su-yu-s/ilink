/**
 * 邀请成员弹窗。
 *
 * 页面用法：
 *   window.TeamInvite.open(teamId, teamTitle, function (result) { ... });
 *
 * 两个标签页共用同一份选中集合：搜索用来冷启动（按姓名/学号找人），
 * 通讯录用来高频操作（直接勾已经建立关系的人）。服务端已经在结果里带上
 * joined / invited 标记，前端据此置灰，不必为每个人单独查一次。
 */
(function () {
    'use strict';

    var state = {
        teamId: null,
        teamTitle: '',
        onDone: null,
        tab: 'search',
        selected: new Map(),
        rows: [],
        keyword: '',
        searchTimer: null,
        requestSeq: 0,
        submitting: false
    };

    var el = {};
    var modalInstance = null;

    var SKIP_REASON = {
        ALREADY_MEMBER: '已在团队',
        ALREADY_INVITED: '已邀请',
        TEAM_FULL: '队伍已满',
        USER_NOT_FOUND: '用户不存在',
        SELF: '不能邀请自己'
    };

    function byId(id) { return document.getElementById(id); }

    function escape(value) {
        return typeof escapeHtml === 'function'
            ? escapeHtml(value == null ? '' : String(value))
            : String(value == null ? '' : value);
    }

    function notifyUser(message, type) {
        if (typeof notify === 'function') { notify(message, type || 'info'); return; }
        if (typeof showToast === 'function') { showToast(message, type || 'info'); }
    }

    function inviterRole() {
        var box = document.querySelector('[data-inviter-role]');
        var role = box ? box.getAttribute('data-inviter-role') : '';
        return role || 'STUDENT';
    }

    /** 导师的通讯录是「我的学生」，学生的通讯录是「我的导师」 */
    function rosterSide() {
        return inviterRole() === 'TEACHER' ? 'students' : 'mentors';
    }

    function renderRows() {
        if (!el.list) return;
        if (!state.rows.length) {
            el.list.innerHTML = '<div class="ti-empty">' +
                (state.tab === 'search'
                    ? (state.keyword ? '没有找到匹配的人' : '输入姓名或学号开始搜索')
                    : '通讯录还是空的，先去建立指导关系') +
                '</div>';
            return;
        }
        el.list.innerHTML = state.rows.map(function (row) {
            var userId = String(row.userId);
            var disabled = row.joined || row.invited;
            var selected = state.selected.has(userId);
            var badge = row.joined ? '已加入' : (row.invited ? '已邀请' : '');
            var detail = [row.major, row.grade, row.professionalTitle].filter(Boolean).join(' · ');
            var roleLabel = row.role === 'TEACHER' ? '导师' : '学生';
            var initials = escape(row.realName || '').slice(0, 2).toUpperCase() || '?';
            var avatar = row.avatar
                ? '<img src="' + escape(row.avatar) + '" alt="" loading="lazy">'
                : '<span class="ti-row__avatar-fallback">' + initials + '</span>';
            return '<button type="button" class="ti-row' + (selected ? ' is-selected' : '') + '"' +
                ' data-ti-user="' + escape(userId) + '"' + (disabled ? ' disabled aria-disabled="true"' : '') + '>' +
                '<span class="ti-row__avatar">' + avatar + '</span>' +
                '<span class="ti-row__body">' +
                    '<span class="ti-row__name">' + escape(row.realName || '未命名') +
                        '<span class="ti-row__role">' + escape(roleLabel) + '</span>' +
                    '</span>' +
                    '<span class="ti-row__meta">' + escape(detail || '资料待完善') + '</span>' +
                '</span>' +
                (badge
                    ? '<span class="ti-row__badge">' + escape(badge) + '</span>'
                    : '<span class="ti-row__check" aria-hidden="true">' +
                        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7"/></svg>' +
                      '</span>') +
            '</button>';
        }).join('');

        el.list.querySelectorAll('[data-ti-user]').forEach(function (button) {
            button.addEventListener('click', function () {
                if (button.disabled) return;
                var userId = button.getAttribute('data-ti-user');
                if (state.selected.has(userId)) {
                    state.selected.delete(userId);
                } else {
                    state.selected.set(userId, true);
                }
                renderRows();
                renderSelectionCount();
            });
        });
    }

    function renderSelectionCount() {
        var count = state.selected.size;
        if (el.count) el.count.textContent = '已选 ' + count + ' 人';
        if (el.submit) el.submit.disabled = count === 0 || state.submitting;
    }

    function setTab(tab) {
        state.tab = tab;
        document.querySelectorAll('[data-ti-tab]').forEach(function (button) {
            var active = button.getAttribute('data-ti-tab') === tab;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        if (el.searchBox) el.searchBox.style.display = tab === 'search' ? '' : 'none';
        if (tab === 'roster') {
            loadRoster();
        } else if (state.keyword) {
            loadSearch(state.keyword);
        } else {
            state.rows = [];
            renderRows();
        }
    }

    function loadSearch(keyword) {
        var seq = ++state.requestSeq;
        apiFetch('/api/user/search?keyword=' + encodeURIComponent(keyword) +
                 '&teamId=' + encodeURIComponent(state.teamId))
            .then(function (response) { return response.json(); })
            .then(function (data) {
                if (seq !== state.requestSeq) return; // 只认最后一次输入的结果
                state.rows = data.code === 200 && Array.isArray(data.data) ? data.data : [];
                renderRows();
            })
            .catch(function () {
                if (seq !== state.requestSeq) return;
                state.rows = [];
                renderRows();
            });
    }

    function loadRoster() {
        var seq = ++state.requestSeq;
        el.list.innerHTML = '<div class="ti-empty">加载中…</div>';
        apiFetch('/api/user/roster?side=' + rosterSide() + '&teamId=' + encodeURIComponent(state.teamId))
            .then(function (response) { return response.json(); })
            .then(function (data) {
                if (seq !== state.requestSeq) return;
                state.rows = data.code === 200 && Array.isArray(data.data) ? data.data : [];
                renderRows();
            })
            .catch(function () {
                if (seq !== state.requestSeq) return;
                state.rows = [];
                renderRows();
            });
    }

    function submit() {
        if (!state.selected.size || state.submitting) return;
        state.submitting = true;
        renderSelectionCount();
        var userIds = Array.from(state.selected.keys()).map(Number);
        apiFetch('/api/team/' + encodeURIComponent(state.teamId) + '/invite', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userIds: userIds })
        })
        .then(function (response) { return response.json(); })
        .then(function (data) {
            if (data.code !== 200) {
                throw new Error(data.message || '邀请失败，请稍后重试');
            }
            var payload = data.data || {};
            var invited = Array.isArray(payload.invited) ? payload.invited : [];
            var skipped = Array.isArray(payload.skipped) ? payload.skipped : [];
            var message = invited.length ? '已发送 ' + invited.length + ' 个邀请' : '没有新增邀请';
            var reasons = skipped.map(function (item) {
                return SKIP_REASON[item.reason] || item.reason;
            }).filter(Boolean);
            if (reasons.length) {
                message += '，' + Array.from(new Set(reasons)).join('、') + '已自动跳过';
            }
            notifyUser(message, invited.length ? 'success' : 'warning');
            close();
            if (typeof state.onDone === 'function') state.onDone({ invited: invited, skipped: skipped });
        })
        .catch(function (error) {
            notifyUser(error.message || '邀请失败，请稍后重试', 'error');
        })
        .finally(function () {
            state.submitting = false;
            renderSelectionCount();
        });
    }

    function close() {
        if (!modalInstance && typeof bootstrap !== 'undefined' && el.modal) {
            modalInstance = bootstrap.Modal.getOrCreateInstance(el.modal);
        }
        if (modalInstance) modalInstance.hide();
    }

    function reset() {
        state.selected = new Map();
        state.rows = [];
        state.keyword = '';
        state.requestSeq++;
        if (el.searchInput) el.searchInput.value = '';
        renderSelectionCount();
    }

    function open(teamId, teamTitle, onDone) {
        el.modal = byId('teamInviteModal');
        if (!el.modal || typeof bootstrap === 'undefined') {
            notifyUser('邀请窗口未就绪，请刷新页面', 'error');
            return;
        }
        state.teamId = teamId;
        state.teamTitle = teamTitle || '';
        state.onDone = onDone || null;
        reset();
        if (el.teamName) el.teamName.textContent = state.teamTitle ? '邀请成员加入「' + state.teamTitle + '」' : '';
        modalInstance = bootstrap.Modal.getOrCreateInstance(el.modal);
        setTab('search');
        modalInstance.show();
        window.setTimeout(function () {
            if (el.searchInput) el.searchInput.focus();
        }, 200);
    }

    function init() {
        el.modal = byId('teamInviteModal');
        if (!el.modal) return;
        el.list = byId('tiList');
        el.searchInput = byId('tiSearchInput');
        el.searchBox = byId('tiSearchBox');
        el.count = byId('tiSelectedCount');
        el.submit = byId('tiSubmitBtn');
        el.teamName = byId('tiTeamName');

        document.querySelectorAll('[data-ti-tab]').forEach(function (button) {
            button.addEventListener('click', function () {
                setTab(button.getAttribute('data-ti-tab'));
            });
        });
        if (el.searchInput) {
            el.searchInput.addEventListener('input', function () {
                state.keyword = el.searchInput.value.trim();
                if (state.searchTimer) window.clearTimeout(state.searchTimer);
                if (!state.keyword) {
                    state.requestSeq++;
                    state.rows = [];
                    renderRows();
                    return;
                }
                state.searchTimer = window.setTimeout(function () {
                    loadSearch(state.keyword);
                }, 300);
            });
        }
        if (el.submit) el.submit.addEventListener('click', submit);
        state.selected = new Map();
        renderSelectionCount();
    }

    // 兼容脚本在弹窗片段之前加载的情况
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    window.TeamInvite = { open: open };
})();
