// 导师邀请选择器：发布组队页可选邀请一位导师进队。
// 两个数据源：/api/user/search 按姓名/学号搜索（前端过滤出导师），
// /api/user/roster?side=mentors 取「我的导师」通讯录。单选：再次选择即替换。
// 结构与 competition-picker.js 同款（input + menu + 点选回调），样式复用 tp-comp-*。
const MentorPicker = (function () {

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    /**
     * config: { input, menu, onPick, onOpenRoster }
     *   onPick(mentor) 点选某个导师时回调，mentor = {userId, realName, professionalTitle, researchDirection, major, avatar}
     *   onOpenRoster() 点击「从我的导师中选择」类入口时回调（页面自行拉通讯录并调 renderCandidates）
     */
    function create(config) {
        const input = config.input;
        const menu = config.menu;
        let debounceTimer = null;
        let seq = 0;

        function close() {
            menu.hidden = true;
            input.setAttribute('aria-expanded', 'false');
        }

        /** 渲染导师候选（搜索结果与通讯录共用）；emptyText 为空列表提示 */
        function renderCandidates(list, emptyText) {
            const mentors = (list || []).filter(function (r) { return r && r.role === 'TEACHER'; });
            if (!mentors.length) {
                menu.innerHTML = '<div class="tp-comp-empty">' + escapeHtml(emptyText || '没有匹配的导师') + '</div>';
            } else {
                menu.innerHTML = mentors.map(function (m) {
                    const title = [m.professionalTitle, m.researchDirection].filter(Boolean).join(' · ') || m.major || '';
                    return '<button type="button" class="tp-comp-item" role="option" data-user-id="' + m.userId + '"' +
                        ' data-name="' + escapeHtml(m.realName || '') + '">' +
                        '<span class="tp-comp-name">' + escapeHtml(m.realName || ('用户 #' + m.userId)) + '</span>' +
                        (title ? '<span class="tp-comp-org">' + escapeHtml(title) + '</span>' : '') +
                        '</button>';
                }).join('');
            }
            menu.hidden = false;
            input.setAttribute('aria-expanded', 'true');
        }

        function search(keyword) {
            const callSeq = ++seq;
            return apiFetch('/api/user/search?keyword=' + encodeURIComponent(keyword) + '&limit=20')
                .then(function (r) { return r.json(); })
                .then(function (res) {
                    if (callSeq === seq) renderCandidates(res && res.data ? res.data : [], '没有匹配的导师');
                })
                .catch(function () {
                    if (callSeq === seq) renderCandidates([], '搜索失败，请稍后重试');
                });
        }

        function bind() {
            if (!input || !menu) return;
            input.addEventListener('input', function () {
                const kw = input.value.trim();
                if (debounceTimer) clearTimeout(debounceTimer);
                if (!kw) { close(); return; }
                debounceTimer = setTimeout(function () { search(kw); }, 300);
            });
            input.addEventListener('focus', function () {
                // 已有输入时聚焦重新拉一次候选
                const kw = input.value.trim();
                if (kw) search(kw);
            });
            menu.addEventListener('click', function (e) {
                const item = e.target.closest('.tp-comp-item');
                if (!item) return;
                close();
                input.value = '';
                if (typeof config.onPick === 'function') {
                    config.onPick({
                        userId: Number(item.dataset.userId),
                        realName: item.dataset.name || ''
                    });
                }
            });
            document.addEventListener('click', function (e) {
                if (!menu.hidden && !menu.contains(e.target) && e.target !== input) close();
            });
        }

        bind();
        return { close: close, renderCandidates: renderCandidates };
    }

    return { create: create };
})();
