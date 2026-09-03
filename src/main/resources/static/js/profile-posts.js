// 个人中心 · 我的文章列表

const PROFILE_POST_CATEGORY_LABELS = {
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

const PAGE_SIZE = 10;
let currentPage = 1;

document.addEventListener('DOMContentLoaded', function() {
    loadMyPosts(1);
});

function articlePublicUrl(id) {
    return `/community/article/${encodeURIComponent(String(id))}`;
}

function editUrl(id) {
    return `/profile-article-edit.html?id=${encodeURIComponent(String(id))}`;
}

async function loadMyPosts(page) {
    const listEl = document.getElementById('myPostsList');
    const pager = document.getElementById('myPostsPager');
    const pagerInner = document.getElementById('myPostsPagerInner');
    if (!listEl) return;

    currentPage = Math.max(1, page || 1);
    const url = `/api/community/my-posts?page=${currentPage}&size=${PAGE_SIZE}`;

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
                <div class="profile-posts-empty">
                    <div class="il-empty-icon">
                        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                            <polyline points="14 2 14 8 20 8"/>
                            <line x1="16" y1="13" x2="8" y2="13"/>
                            <line x1="16" y1="17" x2="8" y2="17"/>
                        </svg>
                    </div>
                    <p class="il-empty-title">还没有发布过文章</p>
                    <p class="il-empty-text">在交流社区写第一篇，会显示在这个列表里</p>
                    <a href="/community.html" class="il-btn il-btn-primary il-btn-sm mt-3">前往交流社区</a>
                </div>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        const wrap = document.createElement('div');
        wrap.className = 'profile-post-list';

        posts.forEach(p => {
            const badge = PROFILE_POST_CATEGORY_LABELS[p.category] || p.category;
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
                        <button type="button" class="profile-post-more-btn" data-id="${p.id}" data-edit-url="${editUrl(p.id)}" aria-label="更多操作" aria-haspopup="true" aria-expanded="false">
                            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
                        </button>
                    </div>
                </div>`;
            wrap.appendChild(art);
        });

        bindPostCardMenus(wrap);

        listEl.innerHTML = '';
        listEl.appendChild(wrap);

        renderPager(pag, pager, pagerInner);
    } catch (e) {
        console.error(e);
        listEl.innerHTML = '<p class="il-form-error">网络错误</p>';
    }
}

// ── 卡片“更多”菜单管理（portal 模式：菜单挂载到 body 层级，脱离卡片文档流）──
let _ppOpenMenu = null;

function _ppCloseMenu() {
    if (!_ppOpenMenu) return;
    _ppOpenMenu.btn.setAttribute('aria-expanded', 'false');
    _ppOpenMenu.btn.classList.remove('is-open');
    if (_ppOpenMenu.menu && _ppOpenMenu.menu.parentNode) {
        _ppOpenMenu.menu.parentNode.removeChild(_ppOpenMenu.menu);
    }
    _ppOpenMenu = null;
}

function _ppEnsureGlobalListeners() {
    if (window._ppMenuBound) return;
    window._ppMenuBound = true;
    document.addEventListener('click', _ppCloseMenu);
    window.addEventListener('scroll', _ppCloseMenu, { capture: true, passive: true });
    window.addEventListener('resize', _ppCloseMenu, { passive: true });
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape') _ppCloseMenu();
    });
}

function _ppBuildMenu(btn) {
    const rect = btn.getBoundingClientRect();
    const editUrl = btn.getAttribute('data-edit-url') || '#';
    const id = btn.getAttribute('data-id');
    const menu = document.createElement('div');
    menu.className = 'profile-post-menu profile-post-menu--portal';
    menu.innerHTML =
        '<a href="' + editUrl + '" class="profile-post-menu__item">' +
            '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>' +
            '<span>编辑</span>' +
        '</a>' +
        '<div class="profile-post-menu__divider"></div>' +
        '<button type="button" class="profile-post-menu__item profile-post-menu__item--delete profile-delete-post" data-id="' + id + '">' +
            '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-2 14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/></svg>' +
            '<span>删除</span>' +
        '</button>';
    menu.style.position = 'fixed';
    menu.style.top = (rect.bottom + 4) + 'px';
    menu.style.right = (window.innerWidth - rect.right) + 'px';
    menu.style.zIndex = '10000';
    menu.addEventListener('click', function(e) { e.stopPropagation(); });
    menu.querySelector('.profile-delete-post').addEventListener('click', function(e) {
        e.stopPropagation();
        _ppCloseMenu();
        if (id) deletePost(id);
    });
    return menu;
}

function bindPostCardMenus(wrap) {
    _ppEnsureGlobalListeners();
    wrap.querySelectorAll('.profile-post-more-btn').forEach(function(btn) {
        btn.addEventListener('click', function(e) {
            e.stopPropagation();
            const willOpen = !_ppOpenMenu || _ppOpenMenu.btn !== btn;
            _ppCloseMenu();
            if (willOpen) {
                const menu = _ppBuildMenu(btn);
                document.body.appendChild(menu);
                btn.setAttribute('aria-expanded', 'true');
                btn.classList.add('is-open');
                _ppOpenMenu = { btn: btn, menu: menu };
            }
        });
    });
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
            if (!isNaN(np)) loadMyPosts(np);
        });
    });
}

async function deletePost(id) {
    if (!id || !confirm('确定删除这篇文章？评论将一并删除，且不可恢复。')) return;
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(id)}`, {
            method: 'DELETE',
            credentials: 'same-origin'
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('已删除', 'success');
            loadMyPosts(currentPage);
        } else {
            showMessage(result.message || '删除失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}
