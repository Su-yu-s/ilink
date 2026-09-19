/* =====================================================================
 * profile-refresh.js  v1.0
 * 个人中心交互增强（不改业务 JS、不改接口）：
 *  1. 个人资料：个人简介为空时给出“去填写”引导
 *  2. 我的文章/收藏：卡片数据行注入线性 SVG、一体化工具栏搜索/分类/排序、空态
 *  3. 修改密码：眼睛显隐、新密码强度条、确认密码即时校验
 * ===================================================================== */
(function () {
  'use strict';

  var ICON = {
    calendar: "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><rect x='3' y='4' width='18' height='18' rx='2'/><path d='M16 2v4M8 2v4M3 10h18'/></svg>",
    eye: "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M2.04 12.32a1 1 0 0 1 0-.64C3.42 7.51 7.36 4.5 12 4.5s8.58 3.01 9.96 7.18a1 1 0 0 1 0 .64C20.58 16.49 16.64 19.5 12 19.5s-8.58-3.01-9.96-7.18z'/><circle cx='12' cy='12' r='3'/></svg>",
    thumb: "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M6.6 10.5c.8 0 1.53-.45 2.03-1.08a9 9 0 0 1 2.86-2.4c.72-.38 1.35-.95 1.65-1.71a4.5 4.5 0 0 0 .32-1.68V3a.75.75 0 0 1 .75-.75A2.25 2.25 0 0 1 16.5 4.5c0 1.15-.26 2.24-.72 3.22-.27.55.1 1.28.72 1.28h3.13c1.03 0 1.95.69 2.05 1.72 0 .42.07.85.07 1.28a11.95 11.95 0 0 1-2.65 7.52c-.39.48-.99.73-1.6.73h-2.62c-.48 0-.96-.08-1.42-.23l-3.12-1.04a4.5 4.5 0 0 0-1.42-.23H5.9M14.25 9h2.25'/></svg>",
    starOutline: "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M11.48 3.5a.56.56 0 0 1 1.04 0l2.13 5.11a.56.56 0 0 0 .47.35l5.52.44c.5.04.7.66.32.99l-4.2 3.6a.56.56 0 0 0-.18.56l1.28 5.38a.56.56 0 0 1-.84.61l-4.72-2.88a.56.56 0 0 0-.59 0l-4.72 2.88a.56.56 0 0 1-.84-.61l1.29-5.38a.56.56 0 0 0-.19-.56l-4.2-3.6a.56.56 0 0 1 .32-.99l5.52-.44a.56.56 0 0 0 .47-.35L11.48 3.5z'/></svg>",
    starSolid: "<svg viewBox='0 0 24 24' fill='currentColor' stroke='currentColor' stroke-width='1.5' stroke-linejoin='round'><path d='M11.48 3.5a.56.56 0 0 1 1.04 0l2.13 5.11a.56.56 0 0 0 .47.35l5.52.44c.5.04.7.66.32.99l-4.2 3.6a.56.56 0 0 0-.18.56l1.28 5.38a.56.56 0 0 1-.84.61l-4.72-2.88a.56.56 0 0 0-.59 0l-4.72 2.88a.56.56 0 0 1-.84-.61l1.29-5.38a.56.56 0 0 0-.19-.56l-4.2-3.6a.56.56 0 0 1 .32-.99l5.52-.44a.56.56 0 0 0 .47-.35L11.48 3.5z'/></svg>"
  };
  var EYE_OPEN = "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M2.04 12.32a1 1 0 0 1 0-.64C3.42 7.51 7.36 4.5 12 4.5s8.58 3.01 9.96 7.18a1 1 0 0 1 0 .64C20.58 16.49 16.64 19.5 12 19.5s-8.58-3.01-9.96-7.18z'/><circle cx='12' cy='12' r='3'/></svg>";
  var EYE_OFF = "<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M3.98 8.22A11.4 11.4 0 0 1 12 5.5c4.64 0 8.58 3.01 9.96 7.18a11.5 11.5 0 0 1-3.17 4.51M6.6 6.6A11.5 11.5 0 0 0 2.04 12.68a11.46 11.46 0 0 0 5.35 5.64M9.88 9.88a3 3 0 1 0 4.24 4.24'/><path d='M3 3l18 18'/></svg>";

  function debounce(fn, wait) {
    var t = null;
    return function () { var a = arguments, c = this; clearTimeout(t); t = function () { fn.apply(c, a); }; setTimeout(t, wait || 120); };
  }

  /* ---------- 1. 个人资料：简介空引导 ---------- */
  function initBioGuide() {
    var bio = document.getElementById('profileReadBio');
    if (!bio || bio.getAttribute('data-pf-init')) return;
    bio.setAttribute('data-pf-init', '1');
    var wide = bio.closest('.il-profile-read-grid__wide');
    if (!wide) return;
    var editBtn = document.getElementById('profileEditBtn');
    // 按钮放在 bio 之外（dd 的兄弟），避免其文本污染 bio.textContent 造成 observer 震荡
    var btn = document.createElement('button');
    btn.type = 'button'; btn.className = 'pf-bio-fill'; btn.textContent = '去填写';
    btn.addEventListener('click', function () { if (editBtn && !editBtn.disabled) editBtn.click(); });
    btn.style.display = 'none';
    wide.appendChild(btn);
    function decorate() {
      var txt = (bio.textContent || '').trim();
      var empty = !txt || txt === '未填写' || txt === '-';
      // 只改 bio 之外的 class / 显隐，不会反过来触发对 bio 的 observer
      wide.classList.toggle('is-bio-empty', empty);
      btn.style.display = empty ? 'inline-flex' : 'none';
    }
    decorate();
    new MutationObserver(decorate).observe(bio, { childList: true, characterData: true, subtree: true });
  }

  /* ---------- 2. 我的文章 / 收藏 ---------- */
  function initList(page) {
    var listId = page === 'posts' ? 'myPostsList' : 'myFavoritesList';
    var pagerId = page === 'posts' ? 'myPostsPager' : 'myFavoritesPager';
    var listEl = document.getElementById(listId);
    if (!listEl || listEl.getAttribute('data-pf-init')) return;
    listEl.setAttribute('data-pf-init', '1');
    var search = document.getElementById('pfSearchInput');
    var catSel = document.getElementById('pfCategorySelect');
    var sortSel = document.getElementById('pfSortSelect');
    var isFav = page === 'favorites';

    // 空态节点
    var empty = document.createElement('div');
    empty.className = 'pf-filter-empty';
    empty.id = 'pfFilterEmpty';
    empty.innerHTML = "<div class='pf-filter-empty__icon'>" + ICON.starOutline.replace('currentColor', 'currentColor') + "</div>" +
      "<p class='pf-filter-empty__title'>" + (isFav ? '未找到相关收藏' : '未找到相关文章') + "</p>" +
      "<p class='pf-filter-empty__text'>请尝试更换搜索词或选择其他分类</p>";
    listEl.parentNode.insertBefore(empty, listEl.nextSibling);

    function cards() { return listEl.querySelectorAll('.profile-post-card'); }

    // 注入数据行 svg + 补 data 属性 + 收集分类
    function decorate() {
      var cats = {};
      cards().forEach(function (card) {
        var link = card.querySelector('.profile-post-card__title-link');
        var chip = card.querySelector('.profile-post-card__chip');
        if (link) card.setAttribute('data-title', link.textContent.trim().toLowerCase());
        if (chip) { var c = chip.textContent.trim(); card.setAttribute('data-category', c); cats[c] = true; }
        var spans = card.querySelectorAll('.profile-post-card__meta > span');
        spans.forEach(function (sp) {
          if (sp.classList.contains('profile-post-card__chip') || sp.getAttribute('data-pf-icon')) return;
          var t = sp.textContent;
          var html = null, fav = false;
          if (t.indexOf('发布时间') !== -1 || t.indexOf('收藏于') !== -1 || t.indexOf('时间') !== -1) html = ICON.calendar;
          else if (t.indexOf('阅读') !== -1) html = ICON.eye;
          else if (t.indexOf('点赞') !== -1) html = ICON.thumb;
          else if (t.indexOf('收藏') !== -1) { html = isFav ? ICON.starSolid : ICON.starOutline; fav = isFav; }
          if (html) { sp.setAttribute('data-pf-icon', '1'); sp.insertAdjacentHTML('afterbegin', html); if (fav) sp.classList.add('pf-meta-fav'); }
        });
      });
      // 动态填充分类（保留当前选择）
      if (catSel) {
        var cur = catSel.value;
        var opts = ['<option value="all">全部分类</option>'];
        Object.keys(cats).sort().forEach(function (c) { opts.push('<option value="' + c + '">' + c + '</option>'); });
        catSel.innerHTML = opts.join('');
        if (cur && catSel.querySelector('option[value="' + cur + '"]')) catSel.value = cur; else catSel.value = 'all';
      }
    }

    function num(card, kw) {
      var sp = card.querySelectorAll('.profile-post-card__meta > span');
      for (var i = 0; i < sp.length; i++) {
        var t = sp[i].textContent || '';
        if (t.indexOf(kw) !== -1) { var m = t.match(/\d+/); return m ? parseInt(m[0], 10) : 0; }
      }
      return 0;
    }

    function applyFilter() {
      decorate();
      var kw = search ? search.value.trim().toLowerCase() : '';
      var cat = catSel ? catSel.value : 'all';
      var wrap = listEl.querySelector('.profile-post-list');
      var visible = 0;
      cards().forEach(function (card) {
        var title = card.getAttribute('data-title') || '';
        var cc = card.getAttribute('data-category') || '';
        var ok = (!kw || title.indexOf(kw) !== -1) && (cat === 'all' || cc === cat);
        card.style.display = ok ? '' : 'none';
        if (ok) visible++;
      });
      // 排序（仅文章页：最多阅读 / 最多点赞）
      if (sortSel && wrap && (sortSel.value === 'views' || sortSel.value === 'likes')) {
        var arr = Array.prototype.slice.call(cards());
        var key = sortSel.value === 'views' ? '阅读' : '点赞';
        arr.sort(function (a, b) { return num(b, key) - num(a, key); });
        arr.forEach(function (c) { wrap.appendChild(c); });
      }
      empty.classList.toggle('is-visible', visible === 0);
      if (wrap) wrap.style.display = visible === 0 ? 'none' : '';
      var pager = document.getElementById(pagerId);
      if (pager) { if (kw || cat !== 'all' || (sortSel && sortSel.value !== 'latest')) pager.classList.add('d-none'); else pager.classList.remove('d-none'); }
    }

    var lazy = debounce(applyFilter, 120);
    if (search) search.addEventListener('input', lazy);
    if (catSel) catSel.addEventListener('change', applyFilter);
    if (sortSel) sortSel.addEventListener('change', applyFilter);
    new MutationObserver(lazy).observe(listEl, { childList: true, subtree: true });
    applyFilter();
  }

  /* ---------- 3. 修改密码 ---------- */
  function initPassword() {
    var form = document.getElementById('passwordForm');
    if (!form || form.getAttribute('data-pf-init')) return;
    form.setAttribute('data-pf-init', '1');
    var nw = document.getElementById('newPassword');
    var cf = document.getElementById('confirmPassword');

    // 眼睛显隐
    form.querySelectorAll('.pf-pwd-toggle').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var input = form.querySelector('#' + btn.getAttribute('data-target'));
        if (!input) return;
        var show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        btn.innerHTML = show ? EYE_OFF : EYE_OPEN;
        btn.setAttribute('aria-label', show ? '隐藏密码' : '显示密码');
      });
    });

    // 强度条
    var strength = document.getElementById('pfStrength');
    var bar = document.getElementById('pfStrengthBar');
    var stext = document.getElementById('pfStrengthText');
    function calcStrength() {
      var v = nw.value || '';
      if (!v) { strength.className = 'pf-strength'; bar.style.width = '0'; stext.textContent = ''; return; }
      var score = 0;
      if (v.length >= 8) score++;
      if (/\d/.test(v)) score++;
      if (/[a-z]/.test(v)) score++;
      if (/[A-Z]/.test(v)) score++;
      if (/[^A-Za-z0-9]/.test(v)) score++;
      strength.classList.add('is-on');
      strength.classList.remove('is-weak', 'is-mid', 'is-strong');
      if (score <= 2) { strength.classList.add('is-weak'); stext.textContent = '弱'; }
      else if (score === 3) { strength.classList.add('is-mid'); stext.textContent = '中'; }
      else { strength.classList.add('is-strong'); stext.textContent = '强'; }
    }
    if (nw) nw.addEventListener('input', function () { calcStrength(); checkMatch(); });

    // 确认密码即时校验
    function checkMatch() {
      if (!cf) return;
      var field = cf.closest('.pf-pwd-field');
      if (!field) return;
      if (cf.value && nw.value && cf.value !== nw.value) field.classList.add('is-mismatch');
      else field.classList.remove('is-mismatch');
    }
    if (cf) cf.addEventListener('input', checkMatch);
  }

  function boot() {
    var page = document.body.getAttribute('data-profile-page');
    try {
      if (page === 'edit') initBioGuide();
      if (page === 'posts') initList('posts');
      if (page === 'favorites') initList('favorites');
      if (page === 'password') initPassword();
    } catch (e) { console.error('profile-refresh error:', e); }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
  // 业务数据异步返回较晚时兜底
  setTimeout(boot, 800);
  setTimeout(boot, 1800);
})();
