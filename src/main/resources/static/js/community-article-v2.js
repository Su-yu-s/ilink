/* ============================================================
   community-article-v2.js — 文章详情三栏阅读流增强
   1) 左栏目录：扫描正文 h2/h3 生成锚点，滚动时高亮当前章节
   2) 右栏信息：把标题区异步写入的作者/分类/时间/阅读数同步过去
   不改变任何业务逻辑，正文仍由 community-article.js 渲染。
   ============================================================ */
(function () {
    'use strict';

    var body = document.getElementById('articleBody');
    var tocAside = document.getElementById('articleTocAside');
    var tocList = document.getElementById('articleToc');
    var tocObserver = null;

    function esc(text) {
        return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function setActive(id) {
        if (!tocList) return;
        tocList.querySelectorAll('.ca-toc__link').forEach(function (a) {
            a.classList.toggle('is-active', a.getAttribute('data-target') === id);
        });
    }

    function bindTocLinks() {
        if (!tocList) return;
        tocList.querySelectorAll('.ca-toc__link').forEach(function (a) {
            a.addEventListener('click', function (e) {
                e.preventDefault();
                var target = document.getElementById(a.getAttribute('data-target'));
                if (target) {
                    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
                    setActive(target.id);
                }
            });
        });
    }

    function observeHeadings(headings) {
        if (tocObserver) tocObserver.disconnect();
        if (!('IntersectionObserver' in window)) return;
        tocObserver = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (entry.isIntersecting) setActive(entry.target.id);
            });
        }, { rootMargin: '-96px 0px -70% 0px', threshold: 0 });
        headings.forEach(function (h) { tocObserver.observe(h); });
    }

    function buildToc() {
        if (!body || !tocList || !tocAside) return;
        var headings = body.querySelectorAll('h2, h3');
        // 少于 2 个标题时目录没有意义，整栏收起
        if (headings.length < 2) {
            tocAside.hidden = true;
            tocList.innerHTML = '';
            return;
        }
        var html = '';
        headings.forEach(function (h, index) {
            if (!h.id) h.id = 'article-sec-' + (index + 1);
            var sub = h.tagName === 'H3' ? ' ca-toc__link--sub' : '';
            html += '<a class="ca-toc__link' + sub + '" href="#' + esc(h.id) + '" data-target="' + esc(h.id) + '">' +
                esc(h.textContent.trim()) + '</a>';
        });
        tocList.innerHTML = html;
        tocAside.hidden = false;
        bindTocLinks();
        observeHeadings(Array.prototype.slice.call(headings));
    }

    /* —— 日期与阅读数搬到右栏作者名下 —— */
    function mountMeta() {
        var meta = document.getElementById('articleMetaStats');
        var sideSlot = document.getElementById('articleSideMeta');
        var header = document.querySelector('.ca-header');
        if (!meta || !sideSlot || !header) return;
        var isDesktop = window.matchMedia('(min-width: 1024px)').matches;
        if (isDesktop) {
            if (meta.parentElement !== sideSlot) sideSlot.appendChild(meta);
        } else if (meta.parentElement !== header) {
            // 必须插回标题之后：直接 append 会跑到 <h1> 前面
            var title = document.getElementById('articleTitle');
            if (title && title.nextSibling) header.insertBefore(meta, title.nextSibling);
            else header.appendChild(meta);
        }
    }

    /* —— 点赞/收藏搬到右栏 —— */
    function mountEngage() {
        var engage = document.getElementById('articleEngage');
        var sideSlot = document.getElementById('articleSideEngage');
        var headerActions = document.getElementById('articleHeaderActions');
        var mobileActions = document.getElementById('articleMobileActions');
        var deleteBtn = document.getElementById('deleteArticleBtn');
        if (!engage || !sideSlot || !headerActions) return;
        var isDesktop = window.matchMedia('(min-width: 1024px)').matches;
        if (isDesktop) {
            if (engage.parentElement !== sideSlot) sideSlot.appendChild(engage);
        } else if (engage.parentElement !== headerActions) {
            headerActions.insertBefore(engage, headerActions.firstChild);
        }
        // 侧栏在窄屏是隐藏的，删除入口要跟到正文下方，否则作者无从入口
        if (deleteBtn) {
            var target = isDesktop ? document.querySelector('.ca-side') : mobileActions;
            if (target && deleteBtn.parentElement !== target) target.appendChild(deleteBtn);
        }
    }

    /* —— 右栏：同步作者与文章事实 —— */
    /* 作者头像/姓名已经直接渲染在右栏（沿用同一批 id），
       这里不再需要镜像同步。 */

    /* galleryPublisherAvatarHtml 把 40px 六件尺寸写成了行内样式。
       行内声明优先级高于任何样式表规则（!important 除外），而本页刻意
       不用 !important，于是 40px 的小图会缩在 72px 的圆槽里露出一圈灰底。
       直接把尺寸相关的行内声明摘掉，交给 .ca-side__avatar 那组 CSS 决定。 */
    function adoptAvatarSizing(slot) {
        if (!slot) return;
        // style.css 有一组全局兜底：.il-publisher-avatar、
        // .il-publisher-avatar .il-avatar-wrap、.il-publisher-avatar__img
        // 全部写了 40px !important（列表页小头像用）。摘掉锚点上的这个类，
        // 三条 !important 同时失效，右栏的 72px 才有机会生效。
        var anchor = slot.querySelector('a.il-publisher-avatar');
        if (anchor) anchor.classList.remove('il-publisher-avatar');
        Array.prototype.forEach.call(
            slot.querySelectorAll('a, .il-avatar-wrap, img, .il-avatar-fallback'),
            function (node) {
                // 只清掉写死的 px，保留 100% 这类相对尺寸
                ['width', 'height', 'min-width', 'min-height', 'max-width', 'max-height', 'flex-shrink']
                    .forEach(function (prop) {
                        var value = node.style.getPropertyValue(prop);
                        if (value && /px$/.test(value.trim())) node.style.removeProperty(prop);
                    });
                node.removeAttribute('width');
                node.removeAttribute('height');
            }
        );
    }

    function syncSide() {
        var avSlot = document.getElementById('articleMetaAvatar');
        if (!avSlot) return;
        adoptAvatarSizing(avSlot);
        if (typeof window.fixCommunityAvatars === 'function') {
            window.fixCommunityAvatars(avSlot);
        }
    }

    function init() {
        buildToc();
        syncSide();
        mountMeta();
        mountEngage();
        // 断点切换时在「标题下」与「右栏」之间搬动互动按钮
        var mq = window.matchMedia('(min-width: 1024px)');
        var onMq = function () { mountMeta(); mountEngage(); };
        if (mq.addEventListener) mq.addEventListener('change', onMq);
        else if (mq.addListener) mq.addListener(onMq);

        // 正文与标题区都是异步渲染的，监听变化后重建/同步
        if (body) {
            new MutationObserver(buildToc).observe(body, { childList: true });
        }
        var metaBox = document.getElementById('articleMetaStats');
        if (metaBox) {
            new MutationObserver(syncSide).observe(metaBox, { childList: true, subtree: true, characterData: true });
        }
        var title = document.getElementById('articleTitle');
        if (title) {
            new MutationObserver(syncSide).observe(title, { childList: true, subtree: true, characterData: true });
        }
        // 头像由文章接口异步写入，落位后再摘一次行内尺寸
        var avSlot = document.getElementById('articleMetaAvatar');
        if (avSlot) {
            new MutationObserver(syncSide).observe(avSlot, { childList: true, subtree: true });
        }
        // 评论输入框左侧的小头像同理
        var composerAv = document.getElementById('commentComposerAvatar');
        if (composerAv) {
            var syncComposerAv = function () { adoptAvatarSizing(composerAv); };
            new MutationObserver(syncComposerAv).observe(composerAv, { childList: true, subtree: true });
            syncComposerAv();
            setTimeout(syncComposerAv, 600);
        }
        // 业务数据较晚返回时兜底
        setTimeout(syncSide, 600);
        setTimeout(syncSide, 1500);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
