// 个人中心 · 我的收藏（收藏夹）

const CATEGORY_LABELS = {
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

function escapeHtml(s) {
    if (s == null) return '';
    const div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
}

function articlePublicUrl(id) {
    return `/community/article/${encodeURIComponent(String(id))}`;
}

function iconStarSvg() {
    return (
        '<svg viewBox="0 0 24 24" aria-hidden="true" class="community-feed-action__icon">' +
        '<path d="M12 3.8l2.6 5.3 5.9.9-4.2 4.1 1 5.8L12 17.2 6.7 19.9l1-5.8L3.5 10l5.9-.9L12 3.8z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"></path>' +
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
            listEl.innerHTML = `<p class="text-danger small mb-0">${escapeHtml(result.message || '加载失败')}</p>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        const posts = result.data || [];
        const pag = result.pagination || { page: 1, size: PAGE_SIZE, total: 0 };

        if (posts.length === 0) {
            listEl.innerHTML = `
                <div class="text-center py-5 px-3 rounded-3 border border-dashed bg-white bg-opacity-50">
                    <p class="text-muted mb-2 fw-semibold">还没有收藏内容</p>
                    <p class="small text-secondary mb-3">在社区列表点击星标收藏文章</p>
                    <a href="/community.html" class="btn btn-primary">去社区看看</a>
                </div>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        const wrap = document.createElement('div');
        wrap.className = 'profile-post-list d-flex flex-column gap-3 w-100';

        posts.forEach(p => {
            const badge = CATEGORY_LABELS[p.category] || p.category;
            const views = p.viewCount != null ? p.viewCount : 0;
            const likes = p.likeCount != null ? p.likeCount : 0;
            const favs = p.favoriteCount != null ? p.favoriteCount : 0;
            const timeStr = formatTime(p.createdAt);

            const art = document.createElement('article');
            art.className = 'profile-post-card glass-panel';
            art.innerHTML = `
                <div class="profile-post-card__body">
                    <div class="profile-post-card__main">
                        <h2 class="profile-post-card__title">
                            <a href="${articlePublicUrl(p.id)}" class="profile-post-card__title-link">${escapeHtml(p.title || '')}</a>
                        </h2>
                        <div class="profile-post-card__meta" role="list">
                            <span class="profile-post-card__chip" role="listitem">${escapeHtml(badge)}</span>
                            <span class="profile-post-card__meta-item" role="listitem">
                                <span class="profile-post-card__meta-key">发布时间</span>
                                <time datetime="">${escapeHtml(timeStr)}</time>
                            </span>
                            <span class="profile-post-card__meta-item profile-post-card__meta-item--reads" role="listitem">
                                <span class="profile-post-card__meta-key">阅读</span>
                                <span class="profile-post-card__reads-num">${views}</span>
                                <span class="profile-post-card__reads-unit">次</span>
                            </span>
                            <span class="profile-post-card__meta-item" role="listitem">
                                <span class="profile-post-card__meta-key">点赞</span>
                                <span class="profile-post-card__stat-num">${likes}</span>
                            </span>
                            <span class="profile-post-card__meta-item" role="listitem">
                                <span class="profile-post-card__meta-key">收藏</span>
                                <span class="profile-post-card__stat-num">${favs}</span>
                            </span>
                        </div>
                    </div>
                    <div class="profile-post-card__actions">
                        <a href="${articlePublicUrl(p.id)}" class="btn btn-light border profile-post-card__btn" target="_blank" rel="noopener">查看</a>
                        <button type="button" class="community-feed-action community-feed-action--fav profile-unfavorite-btn" data-id="${p.id}">
                            ${iconStarSvg()}
                            <span class="community-feed-action__num">${favs}</span>
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
        listEl.innerHTML = '<p class="text-danger small mb-0">网络错误</p>';
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
    innerEl.innerHTML = '';

    const prevLi = document.createElement('li');
    prevLi.className = 'page-item' + (page <= 1 ? ' disabled' : '');
    prevLi.innerHTML = `<a class="page-link" href="#" data-page="${page - 1}">上一页</a>`;
    innerEl.appendChild(prevLi);

    const infoLi = document.createElement('li');
    infoLi.className = 'page-item disabled';
    infoLi.innerHTML = `<span class="page-link text-secondary">${page} / ${totalPages}</span>`;
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

