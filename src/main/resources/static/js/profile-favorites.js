// 个人中心 · 我的收藏

const PROFILE_FAVORITE_CATEGORY_LABELS = {
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

const PAGE_SIZE = 10;
let currentPage = 1;

document.addEventListener('DOMContentLoaded', function() {
    loadMyFavorites(1);
});

function articlePublicUrl(id) {
    return `/community/article/${encodeURIComponent(String(id))}`;
}

// ── 卡片“更多”菜单管理（portal 模式：菜单挂载到 body 层级，脱离卡片文档流）──
let _pfOpenMenu = null;

function _pfCloseMenu() {
    if (!_pfOpenMenu) return;
    _pfOpenMenu.btn.setAttribute('aria-expanded', 'false');
    _pfOpenMenu.btn.classList.remove('is-open');
    if (_pfOpenMenu.menu && _pfOpenMenu.menu.parentNode) {
        _pfOpenMenu.menu.parentNode.removeChild(_pfOpenMenu.menu);
    }
    _pfOpenMenu = null;
}

function _pfEnsureGlobalListeners() {
    if (window._pfMenuBound) return;
    window._pfMenuBound = true;
    document.addEventListener('click', _pfCloseMenu);
    window.addEventListener('scroll', _pfCloseMenu, { capture: true, passive: true });
    window.addEventListener('resize', _pfCloseMenu, { passive: true });
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') _pfCloseMenu();
    });
}

function _pfBuildMenu(btn) {
    const rect = btn.getBoundingClientRect();
    const id = btn.getAttribute('data-id');
    const menu = document.createElement('div');
    menu.className = 'profile-post-menu profile-post-menu--portal';
    menu.innerHTML =
        '<button type="button" class="profile-post-menu__item profile-post-menu__item--delete profile-fav-remove" data-id="' + id + '">' +
            '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/><line x1="5" y1="2" x2="19" y2="2"/></svg>' +
            '<span>取消收藏</span>' +
        '</button>';
    menu.style.position = 'fixed';
    menu.style.top = (rect.bottom + 4) + 'px';
    menu.style.right = (window.innerWidth - rect.right) + 'px';
    menu.style.zIndex = '10000';
    menu.addEventListener('click', function(e) { e.stopPropagation(); });
    menu.querySelector('.profile-fav-remove').addEventListener('click', function(e) {
        e.stopPropagation();
        _pfCloseMenu();
        if (id) unfavorite(id);
    });
    return menu;
}

function bindFavCardMenus(wrap) {
    _pfEnsureGlobalListeners();
    wrap.querySelectorAll('.profile-post-more-btn').forEach(function(btn) {
        btn.addEventListener('click', function(e) {
            e.stopPropagation();
            const willOpen = !_pfOpenMenu || _pfOpenMenu.btn !== btn;
            _pfCloseMenu();
            if (willOpen) {
                const menu = _pfBuildMenu(btn);
                document.body.appendChild(menu);
                btn.setAttribute('aria-expanded', 'true');
                btn.classList.add('is-open');
                _pfOpenMenu = { btn: btn, menu: menu };
            }
        });
    });
}

async function loadMyFavorites(page) {
    const listEl = document.getElementById('myFavoritesList');
    const pager = document.getElementById('myFavoritesPager');
    const pagerInner = document.getElementById('myFavoritesPagerInner');
    if (!listEl) return;

    currentPage = Math.max(1, page || 1);
    const url = `/api/community/my-favorites?page=${currentPage}&size=${PAGE_SIZE}`;

    try {
        const response = await apiFetch(url, { credentials: 'same-origin' });
        const result = await response.json();

        if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
            return;
        }
        if (result.code !== 200) {
            listEl.innerHTML = `<p class="il-form-error">${escapeHtml(result.message || '加载失败')}</p>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        const posts = result.data || [];
        const pag = result.pagination || { page: 1, size: PAGE_SIZE, total: 0 };

        if (posts.length === 0) {
            listEl.innerHTML = `
                <div class="profile-favorites-empty">
                    <div class="il-empty-icon">
                        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/>
                        </svg>
                    </div>
                    <p class="il-empty-title">还没有收藏内容</p>
                    <p class="il-empty-text">在社区列表点击星标收藏文章</p>
                    <a href="/community.html" class="il-btn il-btn-primary il-btn-sm mt-3">去社区看看</a>
                </div>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        const wrap = document.createElement('div');
        wrap.className = 'profile-post-list';

        posts.forEach(p => {
            const badge = PROFILE_FAVORITE_CATEGORY_LABELS[p.category] || p.category;
            const views = p.viewCount != null ? p.viewCount : 0;
            const likes = p.likeCount != null ? p.likeCount : 0;
            const favs = p.favoriteCount != null ? p.favoriteCount : 0;
            const timeStr = formatTime(p.createdAt);

            const art = document.createElement('article');
            art.className = 'profile-post-card';
            art.innerHTML = `
                <div class="profile-post-card__body">
                    <div class="profile-post-card__main">
                        <h2 class="profile-post-card__title">
                            <a href="${articlePublicUrl(p.id)}" class="profile-post-card__title-link">${escapeHtml(p.title || '')}</a>
                        </h2>
                        <div class="profile-post-card__meta" role="list">
                            <span class="profile-post-card__chip" role="listitem">${escapeHtml(badge)}</span>
                            <span role="listitem">发布时间 ${escapeHtml(timeStr)}</span>
                            <span role="listitem">阅读 <strong>${views}</strong> 次</span>
                            <span role="listitem">点赞 <strong>${likes}</strong></span>
                            <span role="listitem">收藏 <strong>${favs}</strong></span>
                        </div>
                    </div>
                    <div class="profile-post-card__actions">
                        <button type="button" class="profile-post-more-btn" data-id="${p.id}" aria-label="更多操作" aria-haspopup="true" aria-expanded="false">
                            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
                        </button>
                    </div>
                </div>`;

            wrap.appendChild(art);
        });

        listEl.innerHTML = '';
        listEl.appendChild(wrap);

        bindFavCardMenus(wrap);

        renderPager(pag, pager, pagerInner);
    } catch (e) {
        console.error(e);
        listEl.innerHTML = '<p class="il-form-error">网络错误</p>';
        if (pager) pager.classList.add('d-none');
    }
}

function renderPager(pag, pagerEl, innerEl) {
    if (!pagerEl || !innerEl) return;
    const total = pag.total != null ? pag.total : 0;
    const size = pag.size != null ? pag.size : PAGE_SIZE;
    const page = pag.page != null ? pag.page : 1;
    const totalPages = Math.max(1, Math.ceil(total / size) || 1);

    if (totalPages <= 1) {
        pagerEl.classList.add('d-none');
        return;
    }

    pagerEl.classList.remove('d-none');
    pagerEl.classList.add('profile-posts-pager');
    innerEl.innerHTML = '';

    const prevLi = document.createElement('li');
    prevLi.className = 'page-item' + (page <= 1 ? ' disabled' : '');
    prevLi.innerHTML = `<a class="page-link" href="#" data-page="${page - 1}">上一页</a>`;
    innerEl.appendChild(prevLi);

    const infoLi = document.createElement('li');
    infoLi.className = 'page-item disabled';
    infoLi.innerHTML = `<span class="page-link">${page} / ${totalPages}</span>`;
    innerEl.appendChild(infoLi);

    const nextLi = document.createElement('li');
    nextLi.className = 'page-item' + (page >= totalPages ? ' disabled' : '');
    nextLi.innerHTML = `<a class="page-link" href="#" data-page="${page + 1}">下一页</a>`;
    innerEl.appendChild(nextLi);

    innerEl.querySelectorAll('a.page-link[data-page]').forEach(a => {
        a.addEventListener('click', function(ev) {
            ev.preventDefault();
            const parent = this.closest('.page-item');
            if (parent && parent.classList.contains('disabled')) return;
            const np = parseInt(this.getAttribute('data-page'), 10);
            if (!isNaN(np)) loadMyFavorites(np);
        });
    });
}

async function unfavorite(id) {
    if (!id) return;
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(id)}/favorite`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: '{}'
        });
        const result = await response.json();
        if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
            return;
        }
        if (result.code === 200) {
            showMessage('已取消收藏', 'success');
            loadMyFavorites(currentPage);
        } else {
            showMessage(result.message || '操作失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}
