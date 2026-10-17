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

// 评论区分页状态
const COMMENT_PAGE_SIZE = 20;
const COMMENT_MAX_LENGTH = 2000;
let commentPage = 1;
let commentTotal = 0;      // 展示用：含回复的总条数
let commentRootTotal = 0;  // 分页用：顶层评论条数
let commentLoading = false;

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
        displayName: authorDisplay || '',
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
    initCommentComposer();

    if (!postId) {
        const breadcrumbTitle = document.getElementById('breadcrumbTitle');
        if (breadcrumbTitle) breadcrumbTitle.textContent = '无效链接';
        setArticleLoadState('error', '链接中缺少有效的文章 ID，请返回交流社区重新选择。');
        return;
    }
    await retryArticleLoad();
});

async function loadCurrentUser() {
    // 匿名访客直接返回 null：此前无条件请求 /api/user/profile 会让每个匿名访客
    // 在控制台拿到一条 401 报错（核实报告 P2-6）
    try {
        return await loadCurrentUserOnce();
    } catch (e) {
        console.error(e);
        return null;
    }
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
        redirectToLogin(900);
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
            redirectToLogin(1200);
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
        redirectToLogin(900);
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
            redirectToLogin(1200);
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
    const sourcePayload = markdownSourcePayload(raw);
    if (sourcePayload && configureMarkdownRenderer()) {
        renderMarkdownSafe(el, decodeMarkdownSource(sourcePayload));
        el.classList.remove('community-post-body--plain');
        return;
    }
    const storedHtml = stripMarkdownSourceMarker(raw);
    const looksLikeHtml = /<[a-z][\s\S]*>/i.test(storedHtml);
    if (looksLikeHtml && typeof DOMPurify !== 'undefined') {
        el.innerHTML = DOMPurify.sanitize(storedHtml, { USE_PROFILES: { html: true } });
        el.classList.remove('community-post-body--plain');
    } else {
        el.textContent = storedHtml;
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
                    const url = a && a.url != null ? String(a.url) : '';
                    if (!url || !url.startsWith('/uploads/')) return;
                    const name = window.ILinkFiles.friendlyDownloadName(
                        a && a.name != null ? String(a.name) : '',
                        url,
                        articleData.title || '文章附件'
                    );
                    const li = document.createElement('li');
                    li.className = 'community-article-attachment-item';
                    li.innerHTML =
                        '<a href="' +
                        escapeHtml(url) +
                        '" download="' +
                        escapeHtml(name) +
                        '" class="community-article-attachment-card" title="下载 ' +
                        escapeHtml(name) +
                        '">' +
                        window.ILinkFiles.iconMarkup(name) +
                        '<span class="community-article-attachment-card__body"><strong>' +
                        escapeHtml(name) +
                        '</strong><small>点击下载</small></span>' +
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
        if (delBtn && currentUser && String(currentUser.id) === String(articleData.authorId)) {
            // 仅发布者本人可见删除入口；管理员的删除能力在管理后台
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

async function loadComments(reset = true) {
    const listEl = document.getElementById('commentList');
    if (!listEl || commentLoading) return;

    commentLoading = true;
    if (reset) {
        commentPage = 1;
        listEl.innerHTML = '<p class="community-article-comment-list__empty">加载评论中…</p>';
    }
    try {
        const response = await apiFetch(
            `/api/community/posts/${encodeURIComponent(postId)}/comments?page=${commentPage}&size=${COMMENT_PAGE_SIZE}`);
        const result = await response.json();
        if (result.code !== 200) {
            listEl.innerHTML = `<div class="community-article-comment-list__empty"><p>${escapeHtml(result.message || '评论加载失败')}</p><button type="button" class="il-btn community-article-comment-list__retry">重新加载评论</button></div>`;
            listEl.querySelector('.community-article-comment-list__retry')?.addEventListener('click', () => loadComments(true));
            return;
        }
        const list = result.data || [];
        commentRootTotal = Number(result.extra?.pagination?.total ?? list.length);
        commentTotal = Number(result.extra?.commentTotal ?? commentRootTotal);
        if (reset) listEl.innerHTML = '';

        if (commentTotal === 0) {
            listEl.innerHTML = '<p class="community-article-comment-list__empty">还没有评论，来说两句吧</p>';
        } else {
            list.forEach(c => listEl.appendChild(renderCommentItem(c)));
        }
        updateCommentCountHint();
        updateLoadMoreVisibility();
    } catch (e) {
        console.error(e);
        listEl.innerHTML = '<div class="community-article-comment-list__empty"><p>评论加载异常</p><button type="button" class="il-btn community-article-comment-list__retry">重新加载评论</button></div>';
        listEl.querySelector('.community-article-comment-list__retry')?.addEventListener('click', () => loadComments(true));
    } finally {
        commentLoading = false;
    }
}

/** 渲染单条评论（顶层与回复共用；回复仅一层，不再嵌套） */
function renderCommentItem(c, options) {
    const isReply = !!(options && options.isReply);
    const row = document.createElement('article');
    row.className = 'community-article-comment' + (isReply ? ' community-article-comment--reply' : '');
    row.dataset.cid = String(c.id);

    const avatarSlot = document.createElement('div');
    avatarSlot.className = 'community-article-comment__avatar-slot';
    avatarSlot.innerHTML = communityAvatarHtml(
        c.userId,
        c.authorAvatar,
        c.authorDisplay,
        'community-article-comment__avatar'
    );

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

    const actions = document.createElement('div');
    actions.className = 'community-article-comment__actions';
    if (currentUser) {
        const replyBtn = document.createElement('button');
        replyBtn.type = 'button';
        replyBtn.className = 'community-article-comment__action community-article-comment__reply';
        replyBtn.textContent = '回复';
        replyBtn.addEventListener('click', () => openReplyBox(row, c));
        actions.appendChild(replyBtn);
    }
    if (c.canDelete) {
        const delBtn = document.createElement('button');
        delBtn.type = 'button';
        delBtn.className = 'community-article-comment__action community-article-comment__delete';
        delBtn.dataset.cid = String(c.id);
        delBtn.textContent = '删除';
        delBtn.addEventListener('click', () => deleteComment(delBtn.getAttribute('data-cid')));
        actions.appendChild(delBtn);
    }
    if (actions.childElementCount > 0) {
        head.appendChild(actions);
    }

    const bodyEl = document.createElement('div');
    bodyEl.className = 'community-article-comment__body';
    if (c.replyToDisplay) {
        const prefix = document.createElement('span');
        prefix.className = 'community-article-comment__reply-to';
        prefix.textContent = '回复 @' + c.replyToDisplay + '：';
        bodyEl.appendChild(prefix);
    }
    bodyEl.appendChild(document.createTextNode(c.content || ''));

    main.appendChild(head);
    main.appendChild(bodyEl);

    // 顶层评论：挂载回复列表（回复本身不再承载回复）
    if (!isReply) {
        const repliesEl = document.createElement('div');
        repliesEl.className = 'il-comment-replies';
        (c.replies || []).forEach(r => repliesEl.appendChild(renderCommentItem(r, { isReply: true })));
        if (repliesEl.childElementCount > 0) {
            main.appendChild(repliesEl);
        }
    }

    row.appendChild(avatarSlot);
    row.appendChild(main);
    fixCommunityAvatars(row);
    return row;
}

/** 在指定评论下方展开内联回复框（同一时刻只保留一个） */
function openReplyBox(row, target) {
    const main = row.querySelector('.community-article-comment__main');
    if (!main) return;
    const existing = main.querySelector('.il-comment-reply-box');
    if (existing) {
        existing.querySelector('textarea')?.focus();
        return;
    }
    document.querySelectorAll('.il-comment-reply-box').forEach(box => box.remove());

    const box = document.createElement('div');
    box.className = 'il-comment-reply-box';
    const textarea = document.createElement('textarea');
    textarea.className = 'il-comment-reply-box__input';
    textarea.rows = 2;
    textarea.maxLength = COMMENT_MAX_LENGTH;
    textarea.placeholder = '回复 @' + (target.authorDisplay || '匿名用户') + '...';
    const actions = document.createElement('div');
    actions.className = 'il-comment-reply-box__actions';
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'il-comment-cancel';
    cancel.textContent = '取消';
    const submit = document.createElement('button');
    submit.type = 'button';
    submit.className = 'il-comment-submit';
    submit.textContent = '发布';
    submit.disabled = true;

    const sync = () => {
        const ready = textarea.value.trim().length > 0;
        submit.disabled = !ready;
        submit.classList.toggle('is-ready', ready);
    };
    textarea.addEventListener('input', sync);
    textarea.addEventListener('keydown', event => {
        if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
            event.preventDefault();
            submitReply(textarea, submit, target);
        } else if (event.key === 'Escape') {
            event.preventDefault();
            box.remove();
        }
    });
    cancel.addEventListener('click', () => box.remove());
    submit.addEventListener('click', () => submitReply(textarea, submit, target));

    actions.appendChild(cancel);
    actions.appendChild(submit);
    box.appendChild(textarea);
    box.appendChild(actions);
    main.appendChild(box);
    setTimeout(() => textarea.focus(), 20);
}

async function submitReply(textarea, submitBtn, target) {
    const content = (textarea?.value || '').trim();
    if (!content || submitBtn?.classList.contains('is-loading')) return;

    submitBtn.classList.add('is-loading');
    submitBtn.disabled = true;
    submitBtn.textContent = '发布中…';
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}/comments`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content, parentId: target.id })
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('回复成功', 'success');
            prependReply(target, result.data);
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            redirectToLogin(1200);
        } else {
            showMessage(result.message || '回复失败', 'error');
            submitBtn.classList.remove('is-loading');
            submitBtn.disabled = false;
            submitBtn.textContent = '发布';
        }
    } catch (e) {
        console.error(e);
        showMessage('网络异常，请稍后重试', 'error');
        submitBtn.classList.remove('is-loading');
        submitBtn.disabled = false;
        submitBtn.textContent = '发布';
    }
}

/** 新回复插到所属顶层评论的回复区末尾（会话顺序） */
function prependReply(target, reply) {
    if (!reply) return;
    // 找到该条评论所在的顶层评论行，回复统一挂到顶层行下
    const rootRow = target.parentId
        ? document.querySelector(`.community-article-comment[data-cid="${target.parentId}"]`)
        : document.querySelector(`.community-article-comment[data-cid="${target.id}"]`);
    if (!rootRow) return;
    const main = rootRow.querySelector('.community-article-comment__main');
    let repliesEl = main?.querySelector('.il-comment-replies');
    if (!repliesEl && main) {
        repliesEl = document.createElement('div');
        repliesEl.className = 'il-comment-replies';
        main.appendChild(repliesEl);
    }
    if (!repliesEl) return;
    repliesEl.appendChild(renderCommentItem(reply, { isReply: true }));
    commentTotal += 1;
    updateCommentCountHint();
    main.querySelector('.il-comment-reply-box')?.remove();
}

function updateCommentCountHint() {
    const hint = document.getElementById('commentCountHint');
    if (!hint) return;
    // 0 条时只留空状态文案，不显示「0 条评论」
    hint.textContent = commentTotal > 0 ? `${commentTotal} 条评论` : '';
}

function updateLoadMoreVisibility() {
    const wrap = document.getElementById('commentMoreWrap');
    const listEl = document.getElementById('commentList');
    if (!wrap) return;
    const rendered = listEl ? listEl.querySelectorAll(':scope > .community-article-comment').length : 0;
    wrap.hidden = rendered >= commentRootTotal;
}

// ============ 评论编辑器：收起态 / 展开态 / 提交中 ============

function initCommentComposer() {
    const trigger = document.getElementById('commentComposerTrigger');
    const editor = document.getElementById('commentComposerEditor');
    const avatar = document.getElementById('commentComposerAvatar');
    const textarea = document.getElementById('newCommentInput');
    if (!trigger || !editor) return;

    if (!currentUser) {
        // 未登录：收起态改为登录引导（保留同样的视觉位置）
        trigger.classList.add('is-guest');
        const label = trigger.querySelector('span');
        if (label) label.textContent = '登录后参与讨论';
        trigger.addEventListener('click', () => redirectToLogin(0));
        if (avatar) avatar.hidden = true;
        return;
    }

    if (avatar) {
        avatar.innerHTML = communityAvatarHtml(
            currentUser.id,
            currentUser.avatar,
            currentUser.displayName || currentUser.realName,
            'il-comment-composer__avatar-img'
        );
        fixCommunityAvatars(avatar);
    }

    trigger.addEventListener('click', openCommentEditor);
    document.getElementById('cancelCommentBtn')?.addEventListener('click', () => closeCommentEditor(true));
    document.getElementById('loadMoreCommentsBtn')?.addEventListener('click', () => {
        commentPage += 1;
        loadComments(false);
    });

    if (textarea) {
        textarea.addEventListener('input', updateCommentComposerState);
        textarea.addEventListener('keydown', event => {
            if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
                event.preventDefault();
                submitComment();
            } else if (event.key === 'Escape') {
                event.preventDefault();
                closeCommentEditor(true);
            }
        });
    }
    updateCommentComposerState();
}

function openCommentEditor() {
    const trigger = document.getElementById('commentComposerTrigger');
    const editor = document.getElementById('commentComposerEditor');
    const textarea = document.getElementById('newCommentInput');
    if (!trigger || !editor) return;
    trigger.hidden = true;
    editor.hidden = false;
    // 等入场动画起步后再聚焦，避免页面滚动跳动
    setTimeout(() => textarea?.focus(), 20);
}

function closeCommentEditor(clearContent) {
    const trigger = document.getElementById('commentComposerTrigger');
    const editor = document.getElementById('commentComposerEditor');
    const textarea = document.getElementById('newCommentInput');
    if (!trigger || !editor) return;
    if (clearContent && textarea) textarea.value = '';
    editor.hidden = true;
    trigger.hidden = false;
    updateCommentComposerState();
}

function updateCommentComposerState() {
    const textarea = document.getElementById('newCommentInput');
    const submitBtn = document.getElementById('submitCommentBtn');
    const counter = document.getElementById('commentCharCount');
    if (!textarea) return;
    const length = textarea.value.length;

    if (submitBtn && !submitBtn.classList.contains('is-loading')) {
        const ready = length > 0;
        submitBtn.disabled = !ready;
        submitBtn.classList.toggle('is-ready', ready);
    }
    if (counter) {
        counter.hidden = length <= 100;   // 超 100 字才显示计数
        counter.textContent = `${length}/${COMMENT_MAX_LENGTH}`;
        counter.classList.toggle('is-warn', length > 1800 && length < COMMENT_MAX_LENGTH);
        counter.classList.toggle('is-over', length >= COMMENT_MAX_LENGTH);
    }
}

function setCommentSubmitting(submitting) {
    const submitBtn = document.getElementById('submitCommentBtn');
    if (!submitBtn) return;
    submitBtn.classList.toggle('is-loading', submitting);
    submitBtn.disabled = submitting;
    submitBtn.textContent = submitting ? '发布中…' : '发布';
    if (!submitting) updateCommentComposerState();
}

/** 新评论插入列表顶部，无需整表重载 */
function prependComment(comment) {
    const listEl = document.getElementById('commentList');
    if (!listEl || !comment) return;
    listEl.querySelector('.community-article-comment-list__empty')?.remove();
    listEl.prepend(renderCommentItem(comment));
    commentTotal += 1;
    commentRootTotal += 1;
    updateCommentCountHint();
    updateLoadMoreVisibility();
}

async function submitComment() {
    const textarea = document.getElementById('newCommentInput');
    const submitBtn = document.getElementById('submitCommentBtn');
    const content = (textarea?.value || '').trim();
    if (!content) return;
    if (submitBtn?.classList.contains('is-loading')) return;   // 提交中禁止重复点击

    setCommentSubmitting(true);
    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}/comments`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ content })
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('评论成功', 'success');
            prependComment(result.data);
            closeCommentEditor(true);   // 成功：清空并收起到默认态
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            redirectToLogin(1200);
        } else {
            // 失败：保留输入内容，按钮恢复可点
            showMessage(result.message || '评论失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络异常，请稍后重试', 'error');
    } finally {
        setCommentSubmitting(false);
    }
}

async function deleteComment(cid) {
    if (!cid || !uiConfirm('删除该评论？')) return;
    try {
        const response = await apiFetch(`/api/community/comments/${encodeURIComponent(cid)}`, { method: 'DELETE' });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('已删除', 'success');
            // 只移除本地这一条，保留当前分页位置
            const row = document.querySelector(`.community-article-comment[data-cid="${cid}"]`);
            if (row) {
                const isRoot = !row.classList.contains('community-article-comment--reply');
                // 删顶层评论时后端会级联删掉它的回复，本地计数一并扣除
                const replies = row.querySelectorAll('.community-article-comment--reply').length;
                row.remove();
                commentTotal = Math.max(0, commentTotal - 1 - (isRoot ? replies : 0));
                if (isRoot) {
                    commentRootTotal = Math.max(0, commentRootTotal - 1);
                }
                updateCommentCountHint();
                updateLoadMoreVisibility();
            }
            const listEl = document.getElementById('commentList');
            if (commentRootTotal === 0 && listEl) {
                listEl.innerHTML = '<p class="community-article-comment-list__empty">还没有评论，来说两句吧</p>';
            }
        } else {
            showMessage(result.message || '删除失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络异常，请稍后重试', 'error');
    }
}

async function deleteArticle() {
    if (!uiConfirm('确定删除整篇文章及下属评论？不可恢复。')) return;
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
