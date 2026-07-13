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
                    <div class="profile-post-card__actions" aria-label="操作">
                        <a href="${articlePublicUrl(p.id)}" class="il-btn il-btn-xs-ghost profile-post-card__btn" target="_blank" rel="noopener">查看</a>
                        <a href="${editUrl(p.id)}" class="il-btn il-btn-xs-dark profile-post-card__btn">编辑</a>
                        <button type="button" class="il-btn il-btn-xs-danger profile-post-card__btn profile-delete-post" data-id="${p.id}">删除</button>
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
        listEl.innerHTML = '<p class="il-form-error">网络错误</p>';
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
