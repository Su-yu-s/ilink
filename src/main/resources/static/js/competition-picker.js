// 竞赛搜索选择器：发布页与团队详情编辑页共用。
// 从 /api/competitions 拉全量目录在前端过滤，选中后回调；不选就是自填模式（由页面自行处理）。
const CompetitionPicker = (function () {

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    let cache = null;
    function ensureLoaded() {
        if (cache) return Promise.resolve(cache);
        return apiFetch('/api/competitions?size=100')
            .then(function (r) { return r.json(); })
            .then(function (res) {
                cache = res && res.data ? res.data : [];
                return cache;
            })
            .catch(function () { cache = []; return cache; });
    }

    /**
     * config: { input, menu, onSelect }
     * onSelect(comp) 在点选目录项时回调；输入但未点选不会触发。
     */
    function create(config) {
        const input = config.input;
        const menu = config.menu;
        let items = [];

        function close() {
            menu.hidden = true;
            input.setAttribute('aria-expanded', 'false');
        }

        function render(list) {
            items = list.slice(0, 8);
            if (!items.length) {
                menu.innerHTML = '<div class="tp-comp-empty">目录里没有匹配的竞赛，可点击下方「自行填写」</div>';
            } else {
                menu.innerHTML = items.map(function (c) {
                    return '<button type="button" class="tp-comp-item" role="option" data-id="' + c.id + '">' +
                        '<span class="tp-comp-name">' + escapeHtml(c.name) + '</span>' +
                        (c.organizer ? '<span class="tp-comp-org">' + escapeHtml(c.organizer) + '</span>' : '') +
                        '</button>';
                }).join('');
            }
            menu.hidden = false;
            input.setAttribute('aria-expanded', 'true');
            // mousedown 抢在 input blur 之前，否则点选项时下拉先被关掉
            Array.prototype.forEach.call(menu.querySelectorAll('.tp-comp-item'), function (btn) {
                btn.addEventListener('mousedown', function (e) {
                    e.preventDefault();
                    const comp = items.filter(function (c) { return String(c.id) === btn.dataset.id; })[0];
                    if (!comp) return;
                    input.value = comp.name;
                    close();
                    if (config.onSelect) config.onSelect(comp);
                });
            });
        }

        function filter() {
            const kw = input.value.trim().toLowerCase();
            ensureLoaded().then(function (list) {
                const hits = !kw ? list : list.filter(function (c) {
                    return ((c.name || '') + ' ' + (c.organizer || '')).toLowerCase().indexOf(kw) >= 0;
                });
                render(hits);
            });
        }

        input.addEventListener('focus', filter);
        input.addEventListener('input', filter);
        input.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') close();
        });
        input.addEventListener('blur', function () { setTimeout(close, 120); });

        return { close: close };
    }

    return { create: create };
})();
