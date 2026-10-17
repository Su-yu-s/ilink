/* ============================================================
   iLink Legal Pages — privacy.html / terms.html
   ------------------------------------------------------------
   只做两件事，无第三方依赖（不依赖 Bootstrap / jQuery / GSAP）：
   1. 目录滚动高亮（scrollspy）
   2. 返回顶部按钮的显隐与滚动
   ============================================================ */
(function () {
  'use strict';

  var reduceMotion = window.matchMedia
    ? window.matchMedia('(prefers-reduced-motion: reduce)')
    : { matches: false };

  /* ----------------------------------------------------------
     1. 目录滚动高亮
     以「已滚过」的最后一节为当前节；阈值 160px 让高亮略早于
     节标题到达视口顶部，避免读到内容时目录还停在上一条。
     ---------------------------------------------------------- */
  function initTocSpy() {
    var links = Array.prototype.slice.call(
      document.querySelectorAll('.lg-toc__list a[href^="#"]')
    );
    if (!links.length) return;

    var sections = [];
    links.forEach(function (link) {
      var id = link.getAttribute('href').slice(1);
      var el = id ? document.getElementById(id) : null;
      if (el) sections.push({ link: link, el: el });
    });
    if (!sections.length) return;

    var activeIndex = -1;
    var ticking = false;

    function sync() {
      ticking = false;
      var threshold = window.scrollY + 160;
      var current = 0;

      for (var i = 0; i < sections.length; i++) {
        if (sections[i].el.offsetTop <= threshold) current = i;
      }

      // 滚到底部时强制高亮最后一节：末节较短时 offsetTop 可能永远
      // 追不上阈值，否则目录会一直停在倒数第二节。
      var atBottom =
        window.innerHeight + window.scrollY >=
        document.documentElement.scrollHeight - 2;
      if (atBottom) current = sections.length - 1;

      if (current === activeIndex) return;
      activeIndex = current;

      sections.forEach(function (item, i) {
        item.link.classList.toggle('is-active', i === current);
      });
    }

    function onScroll() {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(sync);
    }

    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll, { passive: true });
    sync();
  }

  /* ----------------------------------------------------------
     2. 返回顶部
     滚动超过一屏后淡入。锚点为 href="#"，点击时阻止默认行为，
     避免往地址栏写入多余的 #。
     ---------------------------------------------------------- */
  function initBackToTop() {
    var btn = document.querySelector('.lg-top');
    if (!btn) return;

    var ticking = false;

    function sync() {
      ticking = false;
      var show = window.scrollY > window.innerHeight;
      btn.classList.toggle('is-visible', show);
      btn.setAttribute('aria-hidden', show ? 'false' : 'true');
      btn.setAttribute('tabindex', show ? '0' : '-1');
    }

    function onScroll() {
      if (ticking) return;
      ticking = true;
      window.requestAnimationFrame(sync);
    }

    btn.addEventListener('click', function (event) {
      event.preventDefault();
      if (reduceMotion.matches) {
        window.scrollTo(0, 0);
      } else {
        window.scrollTo({ top: 0, behavior: 'smooth' });
      }
      // 回到顶部后把焦点交还文档，避免焦点停在一个已隐藏的按钮上
      var heading = document.querySelector('.lg-h1');
      if (heading) {
        heading.setAttribute('tabindex', '-1');
        heading.focus({ preventScroll: true });
      }
    });

    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll, { passive: true });
    sync();
  }

  function init() {
    initTocSpy();
    initBackToTop();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
