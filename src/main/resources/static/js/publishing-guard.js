// 发布工作台（写文章 / 编辑成果）的「离开确认」。
//
// 为什么不用原生提示：浏览器的 beforeunload 弹窗属于浏览器自己的界面，
// 样式由浏览器控制、不允许网页自定义（防止站点伪造「是否离开」骗点击）。
// 所以这里分两条路：
//   1. 站内跳转（返回社区 / 顶部导航 / 返回成果展示）→ 拦下来，用应用内弹窗；
//   2. 关标签页 / 刷新 / 浏览器后退 → 只能交给原生弹窗兜底，样式改不了。
window.ILinkLeaveGuard = (function () {

    var config = null;
    var overlay = null;

    function build() {
        overlay = document.createElement('div');
        overlay.className = 'ws-leave-mask';
        overlay.hidden = true;
        overlay.innerHTML =
            '<div class="ws-leave" role="dialog" aria-modal="true" aria-labelledby="wsLeaveTitle">' +
                '<h2 class="ws-leave__title" id="wsLeaveTitle">要离开这一页吗？</h2>' +
                '<p class="ws-leave__text"></p>' +
                '<div class="ws-leave__foot">' +
                    '<button type="button" class="ws-leave__stay" data-act="stay">继续编辑</button>' +
                    '<button type="button" class="il-btn-primary" data-act="leave">离开</button>' +
                '</div>' +
            '</div>';
        document.body.appendChild(overlay);
        overlay.querySelector('[data-act="stay"]').addEventListener('click', close);
        overlay.querySelector('[data-act="leave"]').addEventListener('click', function () {
            var target = overlay.__target;
            close();
            if (target) window.location.href = target;
        });
        overlay.addEventListener('click', function (e) { if (e.target === overlay) close(); });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && overlay && !overlay.hidden) { e.preventDefault(); close(); }
        });
    }

    function open(target) {
        if (!overlay) build();
        overlay.__target = target || '';
        overlay.querySelector('.ws-leave__text').textContent = (config && config.message) ||
            '有未保存的修改，离开这一页会丢失。';
        overlay.hidden = false;
        overlay.querySelector('[data-act="stay"]').focus();
    }

    function close() {
        if (!overlay) return;
        overlay.hidden = true;
        overlay.__target = null;
    }

    /**
     * @param {{isDirty: function(): boolean, message?: string, ignore?: function(Element): boolean}} options
     */
    function install(options) {
        config = options || {};
        // 捕获阶段先于页面自己的链接处理，保证站内跳转一定过这道确认
        document.addEventListener('click', function (e) {
            if (e.defaultPrevented || e.button !== 0) return;
            if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;   // 新窗口打开不拦
            var link = e.target && e.target.closest ? e.target.closest('a[href]') : null;
            if (!link || link.target === '_blank' || link.hasAttribute('download')) return;
            var href = link.getAttribute('href');
            if (!href || href.charAt(0) === '#') return;
            if (config.ignore && config.ignore(link)) return;
            if (!config.isDirty || !config.isDirty()) return;
            e.preventDefault();
            open(link.href);
        }, true);
    }

    return { install: install, open: open, close: close, isOpen: function () { return !!overlay && !overlay.hidden; } };
})();
