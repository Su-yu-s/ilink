/**
 * 师生通讯录。
 *
 * 数据来源是既有的 project_application（status = APPROVED）：
 * 「我的学生」按 teacher_id 查，「我的导师」按 user_id 查。本页只读，关系的建立
 * 仍然走导师详情页的申请流程。
 */
(function () {
    'use strict';

    var COPY = {
        mentors: {
            title: '我的导师',
            subtitle: '已建立指导关系的导师，邀请成员时可直接选中',
            emptyTitle: '还没有建立指导关系的导师',
            emptyText: '去导师招贤看看，向心仪的导师发起申请，通过后会出现在这里',
            emptyAction: { href: '/teacher-wall.html', label: '去找导师' }
        },
        students: {
            title: '我的学生',
            subtitle: '已建立指导关系的学生，邀请成员时可直接选中',
            emptyTitle: '还没有建立指导关系的学生',
            emptyText: '学生在导师招贤页向你发起申请并同意后，会出现在这里',
            emptyAction: { href: '/teacher-wall.html', label: '去导师招贤' }
        }
    };

    var listEl = document.getElementById('rosterList');
    var searchEl = document.getElementById('rosterSearchInput');
    if (!listEl) return;

    var allRows = [];
    var side = resolveInitialSide();

    function resolveInitialSide() {
        var params = new URLSearchParams(window.location.search);
        var fromUrl = (params.get('side') || '').trim();
        if (fromUrl === 'students' || fromUrl === 'mentors') return fromUrl;
        var fallback = document.body.getAttribute('data-default-side');
        return fallback === 'students' ? 'students' : 'mentors';
    }

    function escape(value) {
        return typeof escapeHtml === 'function'
            ? escapeHtml(value == null ? '' : String(value))
            : String(value == null ? '' : value);
    }

    /**
     * 只显示与当前身份相符的那个标签：学生看「我的导师」，教师看「我的学生」。
     * 两边都显示会让人以为「我的学生」是空的，其实是根本没这个身份。
     */
    function renderSwitch() {
        var ownSide = document.body.getAttribute('data-default-side') === 'students' ? 'students' : 'mentors';
        var visible = 0;
        document.querySelectorAll('.roster-switch__btn').forEach(function (btn) {
            var btnSide = btn.getAttribute('data-side');
            var applicable = btnSide === ownSide;
            btn.hidden = !applicable;
            if (!applicable) return;
            visible++;
            var active = btnSide === side;
            btn.classList.toggle('is-active', active);
            btn.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        var box = document.getElementById('rosterSwitch');
        // 只剩一个标签时它就是纯标签，没有可切换的东西，整个收起
        if (box) box.hidden = visible <= 1;
    }

    function renderCopy() {
        var copy = COPY[side];
        var titleEl = document.getElementById('rosterTitle');
        var subtitleEl = document.getElementById('rosterSubtitle');
        if (titleEl) titleEl.textContent = copy.title;
        if (subtitleEl) subtitleEl.textContent = copy.subtitle;
    }

    function avatarMarkup(row) {
        var name = escape(row.realName || '');
        var initials = name.slice(0, 2).toUpperCase() || '?';
        var src = row.avatar ? escape(row.avatar) : '';
        if (!src) {
            return '<div class="roster-card__avatar"><div class="roster-card__avatar-fallback">' + initials + '</div></div>';
        }
        return '<div class="roster-card__avatar">' +
            '<img src="' + src + '" alt="' + name + '" loading="lazy" ' +
            "onerror=\"this.style.display='none';this.parentNode.innerHTML='<div class=&quot;roster-card__avatar-fallback&quot;>" + initials + "</div>';\">" +
            '</div>';
    }

    function metaText(row) {
        var parts = side === 'mentors'
            ? [row.professionalTitle, row.major]
            : [row.major, row.grade];
        return parts.filter(Boolean).join(' · ');
    }

    function tagsMarkup(row) {
        var tags = [];
        if (side === 'mentors' && row.researchDirection) {
            // 研究方向可能是一句话，按常见分隔符拆成标签
            tags = String(row.researchDirection).split(/[，,、;；\s]+/).filter(Boolean).slice(0, 4);
        } else if (side === 'students' && row.grade) {
            tags = [String(row.grade)];
        }
        if (!tags.length) return '';
        return '<div class="roster-card__tags">' + tags.map(function (tag) {
            return '<span class="roster-card__tag">' + escape(tag) + '</span>';
        }).join('') + '</div>';
    }

    function renderEmpty() {
        var copy = COPY[side];
        listEl.innerHTML =
            '<div class="roster-empty">' +
                '<svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">' +
                    '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/>' +
                    '<path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>' +
                '</svg>' +
                '<p class="roster-empty__title">' + escape(copy.emptyTitle) + '</p>' +
                '<p class="roster-empty__text">' + escape(copy.emptyText) + '</p>' +
                '<a href="' + copy.emptyAction.href + '" class="il-btn il-btn-primary">' + escape(copy.emptyAction.label) + '</a>' +
            '</div>';
    }

    function renderList() {
        var keyword = (searchEl ? searchEl.value : '').trim().toLowerCase();
        var rows = keyword
            ? allRows.filter(function (row) {
                return String(row.realName || '').toLowerCase().indexOf(keyword) >= 0;
            })
            : allRows;

        if (!allRows.length) {
            renderEmpty();
            return;
        }
        if (!rows.length) {
            listEl.innerHTML = '<div class="roster-empty"><p class="roster-empty__text">没有匹配「' +
                escape(searchEl.value.trim()) + '」的人</p></div>';
            return;
        }

        listEl.innerHTML = rows.map(function (row) {
            var detail = metaText(row);
            return '<article class="roster-card" data-user-id="' + escape(row.userId) + '">' +
                '<div class="roster-card__head">' +
                    avatarMarkup(row) +
                    '<div class="roster-card__body">' +
                        '<h3 class="roster-card__name">' + escape(row.realName || '未命名') + '</h3>' +
                        '<div class="roster-card__meta">' + escape(detail || '资料待完善') + '</div>' +
                    '</div>' +
                    '<span class="roster-card__connected">已连接</span>' +
                '</div>' +
                tagsMarkup(row) +
                '<div class="roster-card__actions">' +
                    '<a class="il-btn il-btn-secondary" href="/user-profile.html?id=' + escape(row.userId) + '">查看主页</a>' +
                    (side === 'mentors'
                        ? '<a class="il-btn il-btn-primary" href="/teacher-detail.html?id=' + escape(row.userId) + '">进入交流空间</a>'
                        : '') +
                '</div>' +
            '</article>';
        }).join('');
    }

    function load() {
        listEl.innerHTML = '<div class="roster-empty"><p class="roster-empty__text">加载中…</p></div>';
        apiFetch('/api/user/roster?side=' + encodeURIComponent(side))
            .then(function (response) { return response.json(); })
            .then(function (data) {
                if (data.code !== 200) {
                    throw new Error(data.message || '加载失败');
                }
                allRows = Array.isArray(data.data) ? data.data : [];
                renderList();
            })
            .catch(function (error) {
                listEl.innerHTML = '<div class="roster-empty"><p class="roster-empty__text">' +
                    escape(error.message || '加载失败，请稍后重试') + '</p></div>';
            });
    }

    function switchSide(next) {
        if (next === side) return;
        side = next;
        allRows = [];
        if (searchEl) searchEl.value = '';
        // 地址栏跟着变，刷新后仍停在同一个标签
        var url = new URL(window.location.href);
        url.searchParams.set('side', side);
        window.history.replaceState(null, '', url.pathname + '?' + url.searchParams.toString());
        renderSwitch();
        renderCopy();
        load();
    }

    document.querySelectorAll('.roster-switch__btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            switchSide(btn.getAttribute('data-side'));
        });
    });
    if (searchEl) {
        searchEl.addEventListener('input', renderList);
    }

    renderSwitch();
    renderCopy();
    load();
})();
