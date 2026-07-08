// Gallery Page — 成果展示 v5.5
// 依赖：common.js (escapeHtml, showMessage), asset-publish.js (AssetPublish)
(function () {
  if (window.GalleryApp) return;

  const PAGE_SIZE = 12;

  const COVERS = {
    '竞赛获奖': 'https://images.unsplash.com/photo-1546422904-90eab23c3d7e?w=600&h=400&fit=crop',
    '论文发表': 'https://images.unsplash.com/photo-1456513080510-7bf3a84b82f8?w=600&h=400&fit=crop',
    '科研项目': 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=600&h=400&fit=crop',
    '作品项目': 'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?w=600&h=400&fit=crop',
    '作品 / 项目': 'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?w=600&h=400&fit=crop',
    '荣誉称号': 'https://images.unsplash.com/photo-1523580494863-6f3031224c94?w=600&h=400&fit=crop',
    '奖学金': 'https://images.unsplash.com/photo-1523050854058-8df90110c9f1?w=600&h=400&fit=crop',
    default: 'https://images.unsplash.com/photo-1555066931-4365d14bab8c?w=600&h=400&fit=crop'
  };

  const TAG_CLASS = {
    '竞赛获奖': 'gallery-tag--competition', '论文发表': 'gallery-tag--paper',
    '科研项目': 'gallery-tag--research', '作品项目': 'gallery-tag--work',
    '作品 / 项目': 'gallery-tag--work', '荣誉称号': 'gallery-tag--honor',
    '奖学金': 'gallery-tag--scholarship', '技术创新': 'gallery-tag--tech'
  };

  // ── localStorage helpers ──
  function storedActions(key) {
    try {
      return (JSON.parse(localStorage.getItem('ilink-asset-actions') || '{}')[key])
        || { liked: false, faved: false, likeDelta: 0, favDelta: 0 };
    } catch (e) { return { liked: false, faved: false, likeDelta: 0, favDelta: 0 }; }
  }

  function toggleStorage(key, action) {
    try {
      var data = JSON.parse(localStorage.getItem('ilink-asset-actions') || '{}');
      var entry = data[key] || { liked: false, faved: false, likeDelta: 0, favDelta: 0 };
      if (action === 'like') { entry.liked = !entry.liked; entry.likeDelta = entry.liked ? 1 : 0; }
      else { entry.faved = !entry.faved; entry.favDelta = entry.faved ? 1 : 0; }
      data[key] = entry;
      localStorage.setItem('ilink-asset-actions', JSON.stringify(data));
      return entry;
    } catch (e) { return null; }
  }

  function fmtDate(d) {
    if (!d) return '';
    var dt = new Date(d);
    if (Number.isNaN(dt.getTime())) return '';
    return dt.getFullYear() + '-' + String(dt.getMonth() + 1).padStart(2, '0') + '-' + String(dt.getDate()).padStart(2, '0');
  }

  function catTag(cat) { return TAG_CLASS[cat] || 'gallery-tag--tech'; }

  function authorHtml(asset) {
    var o = asset.ownerPreview;
    if (!o || o.id == null) return '';
    var name = typeof displayUsername === 'function' ? displayUsername(o) : '匿名用户';
    var av = typeof galleryPublisherAvatarHtml === 'function' ? galleryPublisherAvatarHtml(o, 'gc-avatar') : '';
    return '<div class="gc-author">' + av + '<span class="gc-author-name">' + escapeHtml(name) + '</span></div>';
  }

  // ── App ──
  var app = {
    page: 1,

    $: function (id) { return document.getElementById(id); },

    init: function () {
      var k = this.$('keyword'); if (k) k.addEventListener('keyup', function (e) { if (e.key === 'Enter') app.load(1); });
      var c = this.$('category'); if (c) c.addEventListener('change', function () { app.load(1); });
      var s = this.$('sort'); if (s) s.addEventListener('change', function () { app.load(1); });
      var r = this.$('resetBtn'); if (r) r.addEventListener('click', function () { app.load(1); });
      var sb = this.$('searchBtn'); if (sb) sb.addEventListener('click', function () { app.load(1); });
      if (window.AssetPublish) AssetPublish.bind({ onSuccess: function () { app.load(1); } });
      this.load(1);
    },

    load: async function (page) {
      app.page = Math.max(1, page || 1);
      var params = new URLSearchParams({
        page: app.page, size: PAGE_SIZE,
        keyword: (app.$('keyword') || {}).value || '',
        category: (app.$('category') || {}).value || '',
        sort: (app.$('sort') || {}).value || 'latest'
      });
      try {
        var r = await fetch('/api/asset/list?' + params);
        var d = await r.json();
        var pg = (d.extra && d.extra.pagination) || d.pagination;
        if (d.code === 200) app.render(d.data, pg);
        else showMessage(d.message || '加载失败', 'error');
      } catch (e) { showMessage('网络异常', 'error'); }
    },

    render: function (rows, pg) {
      var c = app.$('listContainer'); if (!c) return;
      if (!rows || !rows.length) {
        c.innerHTML = '<div class="gc-empty"><div class="gc-empty-icon"><svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18"/><line x1="9" y1="9" x2="15" y2="15"/><line x1="15" y1="9" x2="9" y2="15"/></svg></div><h3>暂无成果</h3><p>还没有人发布成果，成为第一个吧。</p><button class="il-btn il-btn-primary" onclick="GalleryApp.openPublish()">发布成果</button></div>';
        app.$('pagination').innerHTML = '';
        return;
      }
      c.innerHTML = '<div class="gc-grid">' + rows.map(function (a) {
        var raw = a.description || '';
        var desc = raw.replace(/（分类：[^）]*）/g, '').trim();
        var catM = raw.match(/（分类：([^）]+)）/);
        var cat = a.category || (catM ? catM[1] : '');
        var s = storedActions('asset-' + (a.id || ''));
        var likes = (a.likeCount || 0) + (s.likeDelta || 0);
        var favs = (a.favoriteCount || 0) + (s.favDelta || 0);
        var cover = COVERS[cat] || COVERS.default;
        return '<div class="gc-card" role="link" tabindex="0" onclick="location.href=\'/asset-detail.html?id=' + (a.id || '') + '\'">'
          + '<div class="gc-cover"><img src="' + cover + '" alt="封面"><div class="gc-cover-overlay"></div>'
          + '<span class="gc-cover-badge"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg></span></div>'
          + '<div class="gc-content">' + authorHtml(a)
          + '<div class="gc-meta-row">' + (cat ? '<span class="gallery-tag ' + catTag(cat) + '">' + escapeHtml(cat) + '</span>' : '') + '<span class="gc-date">' + fmtDate(a.createdAt) + '</span></div>'
          + '<div class="gc-body"><h3 class="gc-title">' + escapeHtml(a.title || '未命名成果') + '</h3>' + (desc ? '<p class="gc-desc">' + escapeHtml(desc) + '</p>' : '') + '</div>'
          + '<div class="gc-stats">'
          + '<button class="gc-action' + (s.liked ? ' gc-action--on' : '') + '" data-action="like" data-id="' + (a.id || '') + '" onclick="event.stopPropagation();GalleryApp.doAction(this)">'
          + '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M9 10V6.8c0-1.7 1.2-3.3 2.9-3.7l.7-.2c.7-.2 1.4.3 1.4 1v4.1H18c1.1 0 2 .9 2 2 0 .2 0 .4-.1.6l-1.5 6.2c-.2.9-1 1.6-2 1.6H9" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/><rect x="4" y="10" width="4" height="9" stroke="currentColor" stroke-width="1.8"/></svg>'
          + '<span class="gc-action-num">' + likes + '</span></button>'
          + '<button class="gc-action' + (s.faved ? ' gc-action--on' : '') + '" data-action="fav" data-id="' + (a.id || '') + '" onclick="event.stopPropagation();GalleryApp.doAction(this)">'
          + '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3.8l2.6 5.3 5.9.9-4.2 4.1 1 5.8L12 17.2 6.7 19.9l1-5.8L3.5 10l5.9-.9L12 3.8z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>'
          + '<span class="gc-action-num">' + favs + '</span></button></div>'
          + '<span class="gc-arrow" aria-hidden="true"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 18l6-6-6-6"/></svg></span>'
          + '</div></div>';
      }).join('') + '</div>';
      if (typeof hideGalleryPublisherAvatarFallbacks === 'function') hideGalleryPublisherAvatarFallbacks(c);
      app.paginate(pg);
    },

    paginate: function (pg) {
      var el = app.$('pagination'); if (!el || !pg) return;
      var total = Math.ceil(pg.total / (pg.size || PAGE_SIZE));
      if (total <= 1) { el.innerHTML = ''; return; }
      el.innerHTML = '<div class="pagination-wrap">'
        + '<button class="page-btn" onclick="GalleryApp.load(' + (pg.page - 1) + ')"' + (pg.page <= 1 ? ' disabled' : '') + '>上一页</button>'
        + '<span class="page-info">第 ' + pg.page + ' / ' + total + ' 页</span>'
        + '<button class="page-btn" onclick="GalleryApp.load(' + (pg.page + 1) + ')"' + (pg.page >= total ? ' disabled' : '') + '>下一页</button>'
        + '</div>';
    },

    doAction: function (btn) {
      var id = btn.getAttribute('data-id'), act = btn.getAttribute('data-action');
      if (!id || !act) return;
      var key = 'asset-' + id;
      var old = storedActions(key);
      var wasOn = act === 'like' ? old.liked : old.faved;
      var entry = toggleStorage(key, act);
      if (!entry) return;
      var isOn = act === 'like' ? entry.liked : entry.faved;
      var num = btn.querySelector('.gc-action-num');
      btn.classList.toggle('gc-action--on', isOn);
      if (num) {
        var cur = parseInt(num.textContent, 10) || 0;
        if (!wasOn && isOn) num.textContent = cur + 1;
        else if (wasOn && !isOn) num.textContent = Math.max(0, cur - 1);
      }
    },

    openPublish: function () {
      if (window.AssetPublish) AssetPublish.openCreate(function () { app.load(1); });
    }
  };

  window.GalleryApp = app;
  document.addEventListener('DOMContentLoaded', function () { app.init(); });
})();
