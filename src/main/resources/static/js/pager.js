/*
 * 全站分页渲染器
 * ══════════════════════════════════════════════════════════
 * 统一各列表页的分页行为与结构，输出标准 Bootstrap 结构：
 *   <ul class="pagination"><li class="page-item"><button class="page-link">
 *
 * 页码窗口遵循主流做法：总页数 ≤ 7 时全显示，超过则用省略号收窄，
 * 形如 1 … 4 [5] 6 … 10。
 *
 * 用法：
 *   IlPager.render(容器, {
 *     page: 当前页（1 起）,
 *     totalPages: 总页数,
 *     onChange: function (page) { ... }
 *   });
 * 容器可以是 <ul>，也可以是里面含 <ul> 的 <nav>；只有一页时自动隐藏。
 */
(function (global) {
    'use strict';

    var DOTS = '…';
    var WINDOW = 1;      // 当前页左右各保留几个
    var MAX_PLAIN = 7;   // 不超过这个页数就全部平铺

    function pageModel(current, total) {
        var list = [];
        var i;

        if (total <= MAX_PLAIN) {
            for (i = 1; i <= total; i++) list.push(i);
            return list;
        }

        var start = Math.max(2, current - WINDOW);
        var end = Math.min(total - 1, current + WINDOW);

        // 贴近首尾时把窗口补满，避免只剩两三个数字
        if (current <= 3) {
            start = 2;
            end = 5;
        }
        if (current >= total - 2) {
            start = total - 4;
            end = total - 1;
        }

        list.push(1);
        if (start > 2) list.push(DOTS);
        for (i = start; i <= end; i++) list.push(i);
        if (end < total - 1) list.push(DOTS);
        list.push(total);
        return list;
    }

    function render(container, options) {
        if (!container) return;
        var opts = options || {};
        var page = Math.max(1, parseInt(opts.page, 10) || 1);
        var totalPages = Math.max(1, parseInt(opts.totalPages, 10) || 1);
        var onChange = typeof opts.onChange === 'function' ? opts.onChange : function () {};

        var ul = container.tagName === 'UL' ? container : container.querySelector('ul');
        if (!ul) {
            ul = document.createElement('ul');
            container.appendChild(ul);
        }
        ul.className = 'pagination';
        ul.innerHTML = '';

        // 单页没有可翻的内容，整块收起来
        if (totalPages <= 1) {
            container.classList.add('d-none');
            return;
        }
        container.classList.remove('d-none');
        container.classList.add('il-pager');

        function item(label, target, state) {
            var li = document.createElement('li');
            // 省略号也要带 disabled：样式表只认 .page-item.disabled，
            // 只标 is-dots 会让「…」显示成一个正常可点的白底按钮
            li.className = 'page-item' +
                (state.active ? ' active' : '') +
                (state.disabled || state.dots ? ' disabled' : '') +
                (state.dots ? ' is-dots' : '');

            var btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'page-link';
            btn.textContent = label;

            if (state.active) {
                btn.setAttribute('aria-current', 'page');
            } else if (state.dots) {
                btn.disabled = true;
                btn.setAttribute('aria-hidden', 'true');
                btn.tabIndex = -1;
            } else if (state.disabled) {
                btn.disabled = true;
            } else {
                btn.addEventListener('click', function () {
                    if (target === page) return;
                    onChange(target);
                });
            }
            if (!state.dots) btn.setAttribute('aria-label', label);
            li.appendChild(btn);
            return li;
        }

        var frag = document.createDocumentFragment();
        frag.appendChild(item('上一页', page - 1, { disabled: page <= 1 }));

        pageModel(page, totalPages).forEach(function (value) {
            if (value === DOTS) {
                frag.appendChild(item(DOTS, 0, { dots: true }));
            } else {
                frag.appendChild(item(String(value), value, { active: value === page }));
            }
        });

        frag.appendChild(item('下一页', page + 1, { disabled: page >= totalPages }));
        ul.appendChild(frag);
    }

    global.IlPager = { render: render, pageModel: pageModel };
})(window);
