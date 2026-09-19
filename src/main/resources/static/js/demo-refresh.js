/* iLink · demo-refresh 交互补丁
   1) 工具栏放大镜可点击 = 触发该输入框的回车搜索（各页已监听 Enter）
   2) 刷新按钮点击时图标旋转 1s
   纯事件委托，不改动任何业务接口与逻辑。 */
(function () {
    'use strict';

    function fireEnter(input) {
        if (!input) return;
        input.focus();
        var opts = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
        input.dispatchEvent(new KeyboardEvent('keydown', opts));
        input.dispatchEvent(new KeyboardEvent('keypress', opts));
        input.dispatchEvent(new KeyboardEvent('keyup', opts));
    }

    document.addEventListener('click', function (e) {
        var icon = e.target.closest && e.target.closest('.il-search-field__icon');
        if (icon) {
            var field = icon.closest('.il-search-field') || icon.parentElement;
            var input = field && field.querySelector('input');
            if (input) { e.preventDefault(); fireEnter(input); }
            return;
        }
        var refresh = e.target.closest && e.target.closest('#refreshBtn');
        if (refresh) {
            var svg = refresh.querySelector('svg');
            if (svg) {
                svg.style.transition = 'transform 1s ease';
                svg.style.transform = 'rotate(360deg)';
                setTimeout(function () { svg.style.transition = ''; svg.style.transform = ''; }, 1000);
            }
        }
    }, true);
})();
