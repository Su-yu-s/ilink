/*
 * iLink 视觉系统 v1.0 · 光照引擎
 * ------------------------------------------------------------------
 * 唯一的职责：把指针位置翻译成受光坐标。
 *
 * 手机系统没有指针，光只能是全局的、装饰的。桌面有指针，光可以有来源：
 *   · 亮部落在指针与表面的交点      → --hx / --hy
 *   · 暗部落在几何对侧              → --rx / --ry
 *   · 投影永远背离指针              → --sx / --sy
 *
 * 三条工程约束：
 *   1. 事件委托在 document 上，不为每个元素单独绑定（列表会无限加载）；
 *   2. pointermove 用 rAF 节流，一帧最多算一次；
 *   3. 尊重 prefers-reduced-motion，此时只保留静态受光，不做位移。
 *
 * 用法：给元素加 data-ui-lit；需要投影跟随的再加 data-ui-lit-cast="8"。
 */
(function () {
  'use strict';
  if (window.__ilinkLightingReady) return;
  window.__ilinkLightingReady = true;

  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var pending = false;
  var lastEvent = null;

  function applyLight(el, clientX, clientY) {
    var r = el.getBoundingClientRect();
    if (!r.width || !r.height) return;

    var x = (clientX - r.left) / r.width;
    var y = (clientY - r.top) / r.height;

    // 指针移出元素边界时（快速划过），夹到 0..1，避免光影跳到面板外
    x = Math.min(1, Math.max(0, x));
    y = Math.min(1, Math.max(0, y));

    el.style.setProperty('--hx', (x * 100).toFixed(1) + '%');
    el.style.setProperty('--hy', (y * 100).toFixed(1) + '%');
    el.style.setProperty('--rx', ((1 - x) * 100).toFixed(1) + '%');
    el.style.setProperty('--ry', ((1 - y) * 100).toFixed(1) + '%');

    var cast = el.hasAttribute('data-ui-lit-cast')
      ? parseFloat(el.getAttribute('data-ui-lit-cast')) || 8
      : 0;

    if (!cast || reduceMotion) {
      el.style.setProperty('--sx', '0px');
      el.style.setProperty('--sy', cast + 'px');
      return;
    }

    // 指针在左上 → 影子朝右下倒；指针在右下 → 影子朝左上倒
    el.style.setProperty('--sx', ((0.5 - x) * cast * 2).toFixed(1) + 'px');
    el.style.setProperty('--sy', ((0.5 - y) * cast * 2 + cast).toFixed(1) + 'px');
  }

  document.addEventListener('pointermove', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('[data-ui-lit]') : null;
    if (!el) return;
    lastEvent = { el: el, x: e.clientX, y: e.clientY };
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () {
      pending = false;
      if (lastEvent) applyLight(lastEvent.el, lastEvent.x, lastEvent.y);
    });
  }, { passive: true });

  // 指针离开该元素时把投影复位，不留一个歪着的影子
  document.addEventListener('pointerout', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('[data-ui-lit]') : null;
    if (!el) return;
    var to = e.relatedTarget;
    if (to && el.contains(to)) return;
    var cast = el.hasAttribute('data-ui-lit-cast')
      ? parseFloat(el.getAttribute('data-ui-lit-cast')) || 8
      : 0;
    el.style.setProperty('--sx', '0px');
    el.style.setProperty('--sy', cast + 'px');
  }, { passive: true });

  /* ------------------------------------------------------------------
     点触光波：光从落点向外扩散，而不是整块变暗。
     ------------------------------------------------------------------ */
  if (reduceMotion) return;

  document.addEventListener('pointerdown', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('[data-ui-tap], .ui-btn') : null;
    if (!el || el.disabled) return;

    var r = el.getBoundingClientRect();
    var size = Math.max(r.width, r.height) * 2.2;
    var dot = document.createElement('span');
    dot.className = 'ui-ripple';
    dot.style.width = size + 'px';
    dot.style.height = size + 'px';
    dot.style.left = (e.clientX - r.left) + 'px';
    dot.style.top = (e.clientY - r.top) + 'px';
    el.appendChild(dot);
    window.setTimeout(function () {
      if (dot.parentNode) dot.parentNode.removeChild(dot);
    }, 560);
  }, { passive: true });
})();
