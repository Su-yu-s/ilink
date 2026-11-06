/*
 * 我的发布 · 成果与文章合并列表
 * ══════════════════════════════════════════════════════════
 * 数据来自 /api/profile/publications（后端把 asset 与 community_post
 * 拉平成同一种行结构），这里负责渲染、筛选，以及「管理 → 勾选 →
 * 二次确认 → 删除」这条流转。
 *
 * 删除走各自的既有接口，前端只按行类型分流：
 *   作品 asset          → DELETE /api/asset/{id}
 *   文章 community_post → DELETE /api/community/posts/{id}
 * 两者都只允许作者本人删除，服务端已有鉴权，这里不做二次判断。
 */
(function () {
    'use strict';

    var listEl = document.getElementById('publishList');
    var pagerEl = document.getElementById('publishPager');
    if (!listEl) return;

    var toolbarEl = document.getElementById('publishToolbar');
    var actionsEl = document.getElementById('publishActions');
    var manageBtn = document.getElementById('publishManageBtn');
    var batchBtn = document.getElementById('publishBatchDelete');
    var countEl = document.getElementById('publishSelectionCount');
    var searchEl = document.getElementById('publishSearch');
    var modalEl = document.getElementById('publishConfirm');
    var modalTitleEl = document.getElementById('publishConfirmTitle');
    var modalOkBtn = document.getElementById('publishConfirmOk');

    var state = { page: 1, size: 10, type: 'all', keyword: '', managing: false };
    var searchTimer = null;
    // key 形如 "article:13"，同时保存 type/id，删除时无需再回查行数据
    var selected = new Map();
    var pendingDelete = [];
    var lastFocus = null;
    var loadToken = 0;

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function fmtDate(v) {
        if (!v) return '';
        var d = new Date(v);
        if (isNaN(d.getTime())) return '';
        var p = function (n) { return String(n).padStart(2, '0'); };
        return d.getFullYear() + '/' + p(d.getMonth() + 1) + '/' + p(d.getDate());
    }

    function keyOf(row) {
        return row.type + ':' + row.id;
    }

    function targetsOf(keys) {
        return keys.map(function (key) {
            var parts = String(key).split(':');
            return { key: key, type: parts[0], id: parts[1] };
        });
    }

    // 作品是视觉内容，用封面；作品缺图时退回首字占位。
    function coverHtml(row) {
        if (row.coverUrl) {
            return '<img src="' + esc(row.coverUrl) + '" alt="" loading="lazy">';
        }
        var initial = (row.title || '作').trim().charAt(0);
        return '<span class="pp-cover__letter" aria-hidden="true">' + esc(initial) + '</span>';
    }

    function rowHtml(row) {
        var isWork = row.type === 'work';
        var key = keyOf(row);
        var checked = selected.has(key);
        var tag = isWork
            ? '<span class="pp-tag pp-tag--work">作品</span>'
            : '<span class="pp-tag pp-tag--article">文章</span>';
        var stats = '<span>阅读 ' + (row.viewCount || 0) + '</span>';
        if (!isWork) stats += '<span>点赞 ' + (row.likeCount || 0) + '</span>';

        // 文章是纯文字内容，不摆封面 —— 给它一个假图片块只会让人以为图没加载。
        var cover = isWork
            ? '<a class="pp-cover" href="' + esc(row.detailUrl) + '">' + coverHtml(row) + '</a>'
            : '';

        // 复选框常驻 DOM，靠 .pp-list.is-managing 控制显隐，
        // 这样进出管理模式不用重绘整个列表。
        var check = '<button type="button" class="pp-check' + (checked ? ' is-checked' : '') + '"' +
            ' data-check="' + esc(key) + '" role="checkbox" aria-checked="' + (checked ? 'true' : 'false') + '"' +
            ' aria-label="选择 ' + esc(row.title || '未命名') + '"></button>';

        // 上下分层：标题独占第一行（所有行左边缘绝对对齐），
        // 第二行才排「封面 + 摘要/元数据 + 右侧操作」。
        return '<article class="pp-row' + (isWork ? '' : ' pp-row--article') + '"' +
                ' data-key="' + esc(key) + '" data-type="' + esc(row.type) + '" data-id="' + esc(row.id) + '">' +
            '<div class="pp-row__head">' +
                '<a class="pp-title" href="' + esc(row.detailUrl) + '">' + esc(row.title || '未命名') + '</a>' + tag +
            '</div>' +
            '<div class="pp-row__main">' +
                cover +
                '<div class="pp-body">' +
                    (row.summary ? '<p class="pp-desc">' + esc(row.summary) + '</p>' : '') +
                    '<div class="pp-meta"><span>发布 ' + fmtDate(row.createdAt) + '</span>' + stats + '</div>' +
                '</div>' +
                '<span class="pp-actions">' +
                    '<a class="pp-action" href="' + esc(row.editUrl) + '">编辑</a>' +
                    '<a class="pp-action" href="' + esc(row.detailUrl) + '">查看</a>' +
                    '<button type="button" class="pp-action pp-action--danger" data-delete="' + esc(key) + '">删除</button>' +
                '</span>' +
                check +
            '</div>' +
        '</article>';
    }

    function emptyHtml() {
        return '<div class="pp-empty">' +
            '<div class="pp-empty__icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">' +
            '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/>' +
            '</svg></div>' +
            '<p class="pp-empty__title">还没有发布内容</p>' +
            '<p class="pp-empty__desc">发布作品或写一篇文章，它们都会出现在这里</p>' +
            '<a class="il-btn il-btn-primary il-btn-sm" href="/profile-asset-edit.html">发布成果</a>' +
            '</div>';
    }

    function renderPager(total) {
        if (!pagerEl) return;
        var pages = Math.max(1, Math.ceil(total / state.size));
        if (pages <= 1) { pagerEl.innerHTML = ''; return; }
        pagerEl.innerHTML = '<button type="button" class="pp-page" data-page="' + (state.page - 1) + '"' +
            (state.page <= 1 ? ' disabled' : '') + '>上一页</button>' +
            '<span class="pp-page__info">' + state.page + ' / ' + pages + '</span>' +
            '<button type="button" class="pp-page" data-page="' + (state.page + 1) + '"' +
            (state.page >= pages ? ' disabled' : '') + '>下一页</button>';
    }

    /* ── 选择与管理态 ───────────────────────────────────────── */

    function syncSelectionUi() {
        var count = selected.size;
        if (countEl) countEl.textContent = String(count);
        if (batchBtn) batchBtn.disabled = count === 0;
        listEl.querySelectorAll('[data-check]').forEach(function (btn) {
            var on = selected.has(btn.getAttribute('data-check'));
            btn.classList.toggle('is-checked', on);
            btn.setAttribute('aria-checked', on ? 'true' : 'false');
        });
    }

    function setManaging(on) {
        state.managing = !!on;
        listEl.classList.toggle('is-managing', state.managing);
        if (toolbarEl) toolbarEl.classList.toggle('is-managing', state.managing);
        if (actionsEl) actionsEl.classList.toggle('is-managing', state.managing);
        if (pagerEl) pagerEl.classList.toggle('is-managing', state.managing);
        if (manageBtn) {
            manageBtn.textContent = state.managing ? '完成' : '管理';
            manageBtn.setAttribute('aria-pressed', state.managing ? 'true' : 'false');
        }
        if (searchEl) searchEl.disabled = state.managing;
        if (!state.managing) selected.clear();
        syncSelectionUi();
    }

    function toggleSelect(key) {
        if (!state.managing) return;
        if (selected.has(key)) selected.delete(key);
        else selected.set(key, true);
        syncSelectionUi();
    }

    /* ── 删除确认 ───────────────────────────────────────────── */

    function openConfirm(targets) {
        if (!targets || !targets.length || !modalEl) return;
        pendingDelete = targets;
        if (modalTitleEl) {
            modalTitleEl.textContent = '确认删除 ' + targets.length + ' 项内容吗？';
        }
        lastFocus = document.activeElement;
        modalEl.hidden = false;
        var cancel = document.getElementById('publishConfirmCancel');
        if (cancel) cancel.focus();
    }

    function closeConfirm() {
        if (!modalEl || modalEl.hidden) return;
        modalEl.hidden = true;
        pendingDelete = [];
        if (lastFocus && typeof lastFocus.focus === 'function') lastFocus.focus();
    }

    function deleteUrl(target) {
        return target.type === 'work'
            ? '/api/asset/' + encodeURIComponent(target.id)
            : '/api/community/posts/' + encodeURIComponent(target.id);
    }

    async function performDelete() {
        if (!pendingDelete.length) return;
        var targets = pendingDelete.slice();
        if (modalOkBtn) modalOkBtn.disabled = true;

        var removed = [];
        var failed = 0;
        for (var i = 0; i < targets.length; i++) {
            var target = targets[i];
            try {
                var resp = await apiFetch(deleteUrl(target), { method: 'DELETE', credentials: 'same-origin' });
                var json = await resp.json().catch(function () { return {}; });
                var okCode = json && (json.code === 200 || json.code === 0);
                if (resp.ok && okCode) removed.push(target);
                else failed += 1;
            } catch (e) {
                failed += 1;
            }
        }

        if (modalOkBtn) modalOkBtn.disabled = false;
        closeConfirm();

        // 命中确认后立刻从 DOM 移除，不等重新请求
        removed.forEach(function (target) {
            var node = listEl.querySelector('[data-key="' + target.key + '"]');
            if (node) node.remove();
            selected.delete(target.key);
        });
        syncSelectionUi();

        if (typeof showMessage === 'function') {
            if (removed.length) showMessage('已删除 ' + removed.length + ' 项', 'success');
            if (failed) showMessage(failed + ' 项删除失败，请稍后重试', 'error');
        }

        // 列表被清空就自动退出管理态
        if (!listEl.querySelector('.pp-row')) setManaging(false);
        state.page = 1;
        await load();
    }

    /* ── 数据加载 ───────────────────────────────────────────── */

    async function load() {
        var token = ++loadToken;
        // 换页/换筛选后旧勾选已不可见，统一清空，避免"删掉看不见的行"
        selected.clear();
        syncSelectionUi();
        listEl.innerHTML = '<p class="pp-loading">加载中…</p>';
        var params = new URLSearchParams({ page: state.page, size: state.size, type: state.type });
        if (state.keyword) params.set('keyword', state.keyword);
        try {
            var resp = await apiFetch('/api/profile/publications?' + params.toString(), { credentials: 'same-origin' });
            var json = await resp.json();
            if (token !== loadToken) return;   // 已有更新的请求，丢弃本次结果
            if (json && json.code === 401) {
                listEl.innerHTML = '<p class="pp-loading">登录已过期，请重新登录后查看</p>';
                renderPager(0);
                return;
            }
            var rows = (json && json.data) || [];
            // 分页信息在 extra.pagination 下（Result.withPagination 的约定，
            // 全站其它列表页也都这么读）。读顶层 json.pagination 会恒为
            // undefined，导致超过一页时永远翻不到第 2 页。
            var pageInfo = (json && json.extra && json.extra.pagination) || {};
            var total = typeof pageInfo.total === 'number' ? pageInfo.total : rows.length;
            if (!rows.length) {
                listEl.innerHTML = emptyHtml();
                renderPager(0);
                if (state.managing) setManaging(false);
                return;
            }
            listEl.innerHTML = rows.map(rowHtml).join('');
            syncSelectionUi();
            renderPager(total);
        } catch (e) {
            if (token !== loadToken) return;
            listEl.innerHTML = '<p class="pp-loading">加载失败，请稍后重试</p>';
        }
    }

    /* ── 事件绑定 ───────────────────────────────────────────── */

    // 列表用事件委托：行是动态渲染的，逐行绑定会在每次翻页后泄漏
    listEl.addEventListener('click', function (e) {
        var delBtn = e.target.closest('[data-delete]');
        if (delBtn) {
            e.preventDefault();
            openConfirm(targetsOf([delBtn.getAttribute('data-delete')]));
            return;
        }
        var checkBtn = e.target.closest('[data-check]');
        if (checkBtn) {
            e.preventDefault();
            toggleSelect(checkBtn.getAttribute('data-check'));
        }
    });

    if (manageBtn) {
        manageBtn.addEventListener('click', function () {
            setManaging(!state.managing);
        });
    }

    if (batchBtn) {
        batchBtn.addEventListener('click', function () {
            if (batchBtn.disabled) return;
            openConfirm(targetsOf(Array.from(selected.keys())));
        });
    }

    if (modalEl) {
        modalEl.addEventListener('click', function (e) {
            if (e.target.closest('[data-confirm-cancel]')) closeConfirm();
        });
        if (modalOkBtn) modalOkBtn.addEventListener('click', performDelete);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && !modalEl.hidden) closeConfirm();
        });
    }

    document.querySelectorAll('[data-publish-type]').forEach(function (tab) {
        tab.addEventListener('click', function () {
            if (state.managing) return;
            document.querySelectorAll('[data-publish-type]').forEach(function (t) {
                t.classList.toggle('active', t === tab);
            });
            state.type = tab.dataset.publishType;
            state.page = 1;
            load();
        });
    });

    if (searchEl) {
        searchEl.addEventListener('input', function () {
            if (state.managing) return;
            clearTimeout(searchTimer);
            searchTimer = setTimeout(function () {
                state.keyword = searchEl.value.trim();
                state.page = 1;
                load();
            }, 250);
        });
    }

    if (pagerEl) {
        pagerEl.addEventListener('click', function (e) {
            if (state.managing) return;
            var btn = e.target.closest('[data-page]');
            if (!btn || btn.disabled) return;
            state.page = Number(btn.dataset.page);
            load();
        });
    }

    load();
})();
