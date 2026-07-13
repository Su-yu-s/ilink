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

function iconStarSvg() {
    return (
        '<svg viewBox="0 0 24 24" aria-hidden="true" width="20" height="20" stroke="currentColor" stroke-width="2" fill="none" stroke-linejoin="round">' +
        '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>' +
        '</svg>'
    );
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
                        <a href="${articlePublicUrl(p.id)}" class="il-btn il-btn-xs-ghost profile-post-card__btn" target="_blank" rel="noopener">查看</a>
                        <button type="button" class="community-feed-action community-feed-action--fav profile-unfavorite-btn" data-id="${p.id}" title="取消收藏">
                            ${iconStarSvg()}
                        </button>
                    </div>
                </div>`;

            wrap.appendChild(art);
        });

        listEl.innerHTML = '';
        listEl.appendChild(wrap);

        wrap.querySelectorAll('.profile-unfavorite-btn').forEach(btn => {
            btn.addEventListener('click', function() {
                const id = this.getAttribute('data-id');
                if (id) unfavorite(id);
            });
        });

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
