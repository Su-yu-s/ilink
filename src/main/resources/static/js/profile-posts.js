// 个人中心 · 我的文章列表（卡片式布局）

const CATEGORY_LABELS = {
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

function escapeHtml(s) {
    if (s == null) return '';
    const div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
}

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
            listEl.innerHTML = `<p class="text-danger small mb-0">${escapeHtml(result.message || '加载失败')}</p>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        const posts = result.data || [];
        const pag = result.pagination || { page: 1, size: PAGE_SIZE, total: 0 };

        if (posts.length === 0) {
            listEl.innerHTML = `
                <div class="profile-posts-empty text-center py-5 px-3 rounded-3 border border-dashed bg-white bg-opacity-75">
                    <p class="text-muted mb-1 fw-semibold">还没有发布过文章</p>
                    <p class="small text-secondary mb-3">在交流社区写第一篇，会显示在这个列表里</p>
                    <a href="/community.html" class="btn btn-primary">前往交流社区</a>
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
                            <span class="profile-post-card__meta-item profile-post-card__meta-item--likes" role="listitem">
                                <span class="profile-post-card__meta-key">点赞</span>
                                <span class="profile-post-card__stat-num">${likes}</span>
                            </span>
                            <span class="profile-post-card__meta-item profile-post-card__meta-item--favs" role="listitem">
                                <span class="profile-post-card__meta-key">收藏</span>
                                <span class="profile-post-card__stat-num">${favs}</span>
                            </span>
                        </div>
                    </div>
                    <div class="profile-post-card__actions" aria-label="操作">
                        <a href="${articlePublicUrl(p.id)}" class="btn btn-light border profile-post-card__btn" target="_blank" rel="noopener">查看</a>
                        <a href="${editUrl(p.id)}" class="btn btn-primary profile-post-card__btn">编辑</a>
                        <button type="button" class="btn btn-outline-danger profile-post-card__btn profile-delete-post" data-id="${p.id}">删除</button>
                    </div>
                </div>`;
            wrap.appendChild(art);
        });

        wrap.querySelectorAll('.profile-delete-post').forEach(btn => {
            btn.addEventListener('click', function() {
                const id = this.getAttribute('data-id');
                if (id) deletePost(id);
            });
        });

        listEl.innerHTML = '';
        listEl.appendChild(wrap);

        renderPager(pag, pager, pagerInner);
    } catch (e) {
        console.error(e);
        listEl.innerHTML = '<p class="text-danger small mb-0">网络错误</p>';
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
