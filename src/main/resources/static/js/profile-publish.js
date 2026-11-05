/*
 * 我的发布 · 成果与文章合并列表
 * ------------------------------------------------------------------
 * 数据来自 /api/profile/publications（后端把 asset 与 community_post
 * 拉平成同一种行结构），这里只负责渲染与筛选。
 */
(function () {
    'use strict';

    var listEl = document.getElementById('publishList');
    var pagerEl = document.getElementById('publishPager');
    if (!listEl) return;

    var state = { page: 1, size: 10, type: 'all', keyword: '' };
    var searchTimer = null;

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
        var tag = isWork
            ? '<span class="pp-tag pp-tag--work">作品</span>'
            : '<span class="pp-tag pp-tag--article">文章</span>';
        var stats = '<span>阅读 ' + (row.viewCount || 0) + '</span>';
        if (!isWork) stats += '<span>点赞 ' + (row.likeCount || 0) + '</span>';

        // 文章是纯文字内容，不摆封面 —— 给它一个假图片块只会让人以为图没加载。
        // 作品保留 160×100 封面，两种行共用同一套正文排版。
        var cover = isWork
            ? '<a class="pp-cover" href="' + esc(row.detailUrl) + '">' + coverHtml(row) + '</a>'
            : '';

        return '<article class="pp-row' + (isWork ? '' : ' pp-row--article') + '">' +
            cover +
            '<div class="pp-body">' +
                '<div class="pp-head"><a class="pp-title" href="' + esc(row.detailUrl) + '">' +
                    esc(row.title || '未命名') + '</a>' + tag + '</div>' +
                (row.summary ? '<p class="pp-desc">' + esc(row.summary) + '</p>' : '') +
                '<div class="pp-meta"><span>发布 ' + fmtDate(row.createdAt) + '</span>' + stats +
                    '<span class="pp-actions">' +
                        '<a class="pp-action" href="' + esc(row.editUrl) + '">编辑</a>' +
                        '<a class="pp-action" href="' + esc(row.detailUrl) + '">查看</a>' +
                    '</span></div>' +
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

    async function load() {
        listEl.innerHTML = '<p class="pp-loading">加载中…</p>';
        var params = new URLSearchParams({ page: state.page, size: state.size, type: state.type });
        if (state.keyword) params.set('keyword', state.keyword);
        try {
            var resp = await apiFetch('/api/profile/publications?' + params.toString(), { credentials: 'same-origin' });
            var json = await resp.json();
            var rows = (json && json.data) || [];
            var total = (json && json.pagination && json.pagination.total) || rows.length;
            if (!rows.length) { listEl.innerHTML = emptyHtml(); renderPager(0); return; }
            listEl.innerHTML = rows.map(rowHtml).join('');
            renderPager(total);
        } catch (e) {
            listEl.innerHTML = '<p class="pp-loading">加载失败，请稍后重试</p>';
        }
    }

    document.querySelectorAll('[data-publish-type]').forEach(function (tab) {
        tab.addEventListener('click', function () {
            document.querySelectorAll('[data-publish-type]').forEach(function (t) {
                t.classList.toggle('active', t === tab);
            });
            state.type = tab.dataset.publishType;
            state.page = 1;
            load();
        });
    });

    var search = document.getElementById('publishSearch');
    if (search) {
        search.addEventListener('input', function () {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(function () {
                state.keyword = search.value.trim();
                state.page = 1;
                load();
            }, 250);
        });
    }

    if (pagerEl) {
        pagerEl.addEventListener('click', function (e) {
            var btn = e.target.closest('[data-page]');
            if (!btn || btn.disabled) return;
            state.page = Number(btn.dataset.page);
            load();
        });
    }

    load();
})();
