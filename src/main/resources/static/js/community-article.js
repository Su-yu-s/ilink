// 社区文章详情页：正文、阅读量、评论

const COMMUNITY_ARTICLE_CATEGORY_LABELS = {
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

function resolvePostId() {
    const m = window.location.pathname.match(/\/community\/article\/(\d+)\/?$/);
    const raw = m ? m[1] : (new URLSearchParams(window.location.search).get('id') || '').trim();
    return /^\d+$/.test(raw) && Number(raw) > 0 ? raw : null;
}

const postId = resolvePostId();
let currentUser = null;
let articleData = null;

function setArticleLoadState(type, message) {
    const state = document.getElementById('articleLoadState');
    const content = document.getElementById('articleContent');
    const comments = document.getElementById('articleComments');
    const title = document.getElementById('articleLoadStateTitle');
    const messageEl = document.getElementById('articleLoadStateMessage');
    const retry = document.getElementById('articleRetryBtn');
    const indicator = state && state.querySelector('.community-article-state__indicator');
    const success = type === 'success';
    const loading = type === 'loading';

    if (state) {
        state.hidden = success;
        state.classList.toggle('community-article-state--error', type === 'error');
        state.setAttribute('aria-busy', loading ? 'true' : 'false');
    }
    if (content) content.hidden = !success;
    if (comments) comments.hidden = !success;
    if (indicator) indicator.hidden = !loading;
    if (retry) retry.hidden = loading || success;
    if (title) title.textContent = loading ? '正在加载文章' : '文章暂时无法显示';
    if (messageEl) {
        messageEl.textContent = message || (loading ? '正在获取正文与作者信息，请稍候…' : '请检查网络后重新加载。');
    }
}

async function retryArticleLoad() {
    const loaded = await loadArticle();
    if (loaded) await loadComments();
}

function communityAvatarHtml(authorId, authorAvatar, authorDisplay, extraClass) {
    if (authorId == null) return '';
    const preview = {
        id: authorId,
        avatar: authorAvatar || '',
        username: authorDisplay || '',
        realName: authorDisplay || ''
    };
    if (typeof galleryPublisherAvatarHtml === 'function') {
        return galleryPublisherAvatarHtml(preview, extraClass || 'community-article__avatar');
    }
    if (typeof publisherAvatarFromAuthorFields === 'function') {
        const fallback = publisherAvatarFromAuthorFields(authorId, authorAvatar, authorDisplay);
        if (extraClass && fallback) {
            return fallback.replace('class="publisher-avatar', 'class="publisher-avatar ' + extraClass);
        }
        return fallback;
    }
    return '';
}

function fixCommunityAvatars(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll('.community-article__avatar img, .community-article-comment__avatar img').forEach(function (img) {
        if (!img.getAttribute('src') || img.style.display === 'none') return;
        const wrap = img.closest('.il-avatar-wrap');
        if (!wrap) return;
        wrap.querySelectorAll('.il-avatar-fallback').forEach(function (fb) {
            fb.classList.add('il-avatar-fallback--hidden');
            fb.setAttribute('aria-hidden', 'true');
        });
    });
}

document.addEventListener('DOMContentLoaded', async function() {
    currentUser = await loadCurrentUser();
    document.getElementById('articleRetryBtn')?.addEventListener('click', retryArticleLoad);
    document.getElementById('submitCommentBtn')?.addEventListener('click', submitComment);
    document.getElementById('deleteArticleBtn')?.addEventListener('click', deleteArticle);
    document.getElementById('articleLikeBtn')?.addEventListener('click', toggleArticleLike);
    document.getElementById('articleFavBtn')?.addEventListener('click', toggleArticleFavorite);

    if (!postId) {
        const breadcrumbTitle = document.getElementById('breadcrumbTitle');
        if (breadcrumbTitle) breadcrumbTitle.textContent = '无效链接';
        setArticleLoadState('error', '链接中缺少有效的文章 ID，请返回交流社区重新选择。');
        return;
    }
    await retryArticleLoad();
});

async function loadCurrentUser() {
    try {
        const r = await apiFetch('/api/user/profile');
        const j = await r.json();
        if (j.code === 200) return j.data;
    } catch (e) { console.error(e); }
    return null;
}

function applyInteractionUi(d) {
    const lc = document.getElementById('articleLikeCount');
    const fc = document.getElementById('articleFavCount');
    const lb = document.getElementById('articleLikeBtn');
    const fb = document.getElementById('articleFavBtn');
    const lk = d.likeCount != null ? d.likeCount : 0;
    const fv = d.favoriteCount != null ? d.favoriteCount : 0;
    if (lc) lc.textContent = String(lk);
    if (fc) fc.textContent = String(fv);
    if (lb) {
        const on = !!d.liked;
        lb.classList.toggle('community-article__action-btn--active', on);
        lb.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
    if (fb) {
        const on = !!d.favorited;
        fb.classList.toggle('community-article__action-btn--active', on);
        fb.setAttribute('aria-pressed', on ? 'true' : 'false');
    }
}

async function toggleArticleLike() {
    if (!currentUser) {
        showMessage('请先登录后再点赞', 'warning');
        setTimeout(() => { window.location.href = '/login'; }, 900);
        return;
    }
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}/like`, {
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
        if (result.code !== 200 || !result.data) {
            showMessage(result.message || '操作失败', 'error');
            return;
        }
        articleData.liked = result.data.liked;
        articleData.likeCount = result.data.likeCount;
        applyInteractionUi(articleData);
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

async function toggleArticleFavorite() {
    if (!currentUser) {
        showMessage('请先登录后再收藏', 'warning');
        setTimeout(() => { window.location.href = '/login'; }, 900);
        return;
    }
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}/favorite`, {
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
        if (result.code !== 200 || !result.data) {
            showMessage(result.message || '操作失败', 'error');
            return;
        }
        articleData.favorited = result.data.favorited;
        articleData.favoriteCount = result.data.favoriteCount;
        applyInteractionUi(articleData);
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

function renderArticleBody(el, content) {
    if (!el) return;
    const raw = content == null ? '' : String(content);
    if (!raw) {
        el.textContent = '';
        el.classList.add('community-post-body--plain');
        return;
    }
    const looksLikeHtml = /<[a-z][\s\S]*>/i.test(raw);
    if (looksLikeHtml && typeof DOMPurify !== 'undefined') {
        el.innerHTML = DOMPurify.sanitize(raw, { USE_PROFILES: { html: true } });
        el.classList.remove('community-post-body--plain');
    } else {
        el.textContent = raw;
        el.classList.add('community-post-body--plain');
    }
}

async function loadArticle() {
    setArticleLoadState('loading');
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}`);
        let result;
        try {
            result = await response.json();
        } catch (error) {
            throw new Error('服务器返回了无法解析的数据，请稍后重试');
        }
        if (!response.ok || !result || (result.code !== 200 && result.code !== 0)) {
            throw new Error((result && result.message) || `文章请求失败（${response.status}）`);
        }
        if (!result.data || typeof result.data !== 'object') {
            throw new Error('文章数据为空，请稍后重试');
        }
        articleData = result.data;
        document.title = `${articleData.title || '文章'} - iLink`;

        const catLabel = COMMUNITY_ARTICLE_CATEGORY_LABELS[articleData.category] || articleData.category || '文章';
        const breadcrumbCategory = document.getElementById('breadcrumbCategory');
        const breadcrumbTitle = document.getElementById('breadcrumbTitle');
        if (breadcrumbCategory) breadcrumbCategory.textContent = catLabel;
        if (breadcrumbTitle) breadcrumbTitle.textContent = articleData.title || '文章';

        const views = articleData.viewCount != null ? articleData.viewCount : 0;
        const avSlot = document.getElementById('articleMetaAvatar');
        const authorNameEl = document.getElementById('articleAuthorName');
        const metaCategory = document.getElementById('articleMetaCategory');
        const metaDate = document.getElementById('articleMetaDate');
        const viewCountEl = document.getElementById('articleViewCount');
        if (avSlot) {
            avSlot.innerHTML = communityAvatarHtml(
                articleData.authorId,
                articleData.authorAvatar,
                articleData.authorDisplay,
                'community-article__avatar'
            );
        }
        if (authorNameEl) {
            authorNameEl.textContent = articleData.authorDisplay || '匿名用户';
        }
        if (metaCategory) metaCategory.textContent = catLabel;
        if (metaDate) metaDate.textContent = formatTime(articleData.createdAt);
        if (viewCountEl) viewCountEl.textContent = String(views);
        fixCommunityAvatars(avSlot);

        applyInteractionUi(articleData);

        document.getElementById('articleTitle').textContent = articleData.title || '';
        const body = document.getElementById('articleBody');
        renderArticleBody(body, articleData.content || '');

        const attEl = document.getElementById('articleAttachments');
        if (attEl) {
            const list = articleData.attachments;
            if (Array.isArray(list) && list.length > 0) {
                attEl.classList.remove('d-none');
                attEl.innerHTML =
                    '<h2 class="community-article-attachments__title">附件</h2><ul class="list-unstyled mb-0 community-article-attachment-list"></ul>';
                const ul = attEl.querySelector('ul');
                list.forEach(a => {
                    const name = a && a.name != null ? String(a.name) : '附件';
                    const url = a && a.url != null ? String(a.url) : '';
                    if (!url || !url.startsWith('/uploads/')) return;
                    const li = document.createElement('li');
                    li.className = 'mb-1';
                    li.innerHTML =
                        '<a href="' +
                        escapeHtml(url) +
                        '" download class="text-decoration-none" target="_blank" rel="noopener">' +
                        escapeHtml(name) +
                        '</a>';
                    ul.appendChild(li);
                });
                if (!ul.children.length) {
                    attEl.classList.add('d-none');
                    attEl.innerHTML = '';
                }
            } else {
                attEl.classList.add('d-none');
                attEl.innerHTML = '';
            }
        }

        const delBtn = document.getElementById('deleteArticleBtn');
        if (delBtn && currentUser && (
            String(currentUser.id) === String(articleData.authorId) || currentUser.role === 'ADMIN'
        )) {
            delBtn.classList.remove('d-none');
        }
        setArticleLoadState('success');
        return true;
    } catch (e) {
        console.error(e);
        const breadcrumbTitle = document.getElementById('breadcrumbTitle');
        if (breadcrumbTitle) breadcrumbTitle.textContent = '加载失败';
        setArticleLoadState('error', e && e.message ? e.message : '网络异常，请稍后重试。');
        return false;
    }
}

async function loadComments() {
    const listEl = document.getElementById('commentList');
    const hint = document.getElementById('commentCountHint');
    if (!listEl) return;

    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}/comments`);
        const result = await response.json();
        if (result.code !== 200) {
            listEl.innerHTML = `<div class="community-article-comment-list__empty"><p>${escapeHtml(result.message || '评论加载失败')}</p><button type="button" class="il-btn community-article-comment-list__retry">重新加载评论</button></div>`;
            listEl.querySelector('.community-article-comment-list__retry')?.addEventListener('click', loadComments);
            return;
        }
        const comments = result.data || [];
        if (hint) hint.textContent = comments.length ? `共 ${comments.length} 条` : '暂无评论';

        if (comments.length === 0) {
            listEl.innerHTML = '<p class="community-article-comment-list__empty">还没有评论，来抢沙发吧</p>';
            return;
        }

        listEl.innerHTML = '';
        comments.forEach(c => {
            const row = document.createElement('article');
            row.className = 'community-article-comment';
            const cAv = communityAvatarHtml(
                c.userId,
                c.authorAvatar,
                c.authorDisplay,
                'community-article-comment__avatar'
            );
            row.replaceChildren();
            const avatarSlot = document.createElement('div');
            avatarSlot.className = 'community-article-comment__avatar-slot';
            avatarSlot.innerHTML = cAv;
            const main = document.createElement('div');
            main.className = 'community-article-comment__main';
            const head = document.createElement('div');
            head.className = 'community-article-comment__head';
            const meta = document.createElement('div');
            const authorSpan = document.createElement('span');
            authorSpan.className = 'community-article-comment__author';
            authorSpan.textContent = c.authorDisplay || '匿名用户';
            const timeSpan = document.createElement('span');
            timeSpan.className = 'community-article-comment__time';
            timeSpan.textContent = formatTime(c.createdAt);
            meta.appendChild(authorSpan);
            meta.appendChild(timeSpan);
            head.appendChild(meta);
            if (c.canDelete) {
                const delBtn = document.createElement('button');
                delBtn.type = 'button';
                delBtn.className = 'community-article-comment__delete';
                delBtn.dataset.cid = String(c.id);
                delBtn.textContent = '删除';
                delBtn.addEventListener('click', () => deleteComment(delBtn.getAttribute('data-cid')));
                head.appendChild(delBtn);
            }
            const bodyEl = document.createElement('div');
            bodyEl.className = 'community-article-comment__body';
            bodyEl.textContent = c.content || '';
            main.appendChild(head);
            main.appendChild(bodyEl);
            row.appendChild(avatarSlot);
            row.appendChild(main);
            fixCommunityAvatars(row);
            listEl.appendChild(row);
        });
    } catch (e) {
        console.error(e);
        listEl.innerHTML = '<div class="community-article-comment-list__empty"><p>评论加载异常</p><button type="button" class="il-btn community-article-comment-list__retry">重新加载评论</button></div>';
        listEl.querySelector('.community-article-comment-list__retry')?.addEventListener('click', loadComments);
    }
}

async function submitComment() {
    const ta = document.getElementById('newCommentInput');
    const content = (ta?.value || '').trim();
    if (!content) {
        showMessage('请输入评论内容', 'warning');
        return;
    }
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}/comments`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content })
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('评论成功', 'success');
            if (ta) ta.value = '';
            await loadComments();
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
        } else {
            showMessage(result.message || '失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

async function deleteComment(cid) {
    if (!cid || !confirm('删除该评论？')) return;
    try {
        const response = await apiFetch(`/api/community/comments/${encodeURIComponent(cid)}`, { method: 'DELETE' });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('已删除', 'success');
            await loadComments();
        } else {
            showMessage(result.message || '删除失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

async function deleteArticle() {
    if (!confirm('确定删除整篇文章及下属评论？不可恢复。')) return;
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}`, { method: 'DELETE' });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('已删除', 'success');
            setTimeout(() => { window.location.href = '/community.html'; }, 600);
        } else {
            showMessage(result.message || '删除失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}
