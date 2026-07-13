// 交流社区：顶部分类标签、全部分区、博客式列表与分页

const COMM_FEED_CATEGORY_LABELS = {
    '': '全部',
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

const PAGE_SIZE = 10;
let currentCategory = '';
let currentPage = 1;

let composeQuill = null;
let pendingAttachments = [];
let currentUser = null;

document.addEventListener('DOMContentLoaded', async function() {
    await loadCurrentUser();
    document.querySelectorAll('#channelTabs .il-search-bar__tag').forEach(btn => {
        btn.addEventListener('click', function() {
            const cat = this.getAttribute('data-category') || '';
            selectCategory(cat);
        });
    });

    document.getElementById('refreshBtn')?.addEventListener('click', () => loadPosts(currentPage));
    const kw = document.getElementById('keywordInput');
    if (kw) {
        kw.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                currentPage = 1;
                loadPosts(1);
            }
        });
    }

    document.getElementById('openComposeBtn')?.addEventListener('click', openComposeModal);
    document.getElementById('submitPostBtn')?.addEventListener('click', submitPost);

    document.getElementById('composeAddAttachmentBtn')?.addEventListener('click', () => {
        document.getElementById('composeAttachmentInput')?.click();
    });
    document.getElementById('composeAttachmentInput')?.addEventListener('change', async function() {
        const files = this.files;
        this.value = '';
        if (!files || !files.length) return;
        for (let i = 0; i < files.length; i++) {
            if (pendingAttachments.length >= 10) {
                showMessage('附件最多 10 个', 'warning');
                break;
            }
            await uploadCommunityAttachment(files[i]);
        }
    });

    fillComposeCategorySelect();
    selectCategory('');
});

function openComposeModal() {
    const sel = document.getElementById('composeCategory');
    if (sel) {
        if (currentCategory && ['general', 'tech', 'competition', 'resource'].includes(currentCategory)) {
            sel.value = currentCategory;
        } else {
            sel.value = 'general';
        }
    }
    const title = document.getElementById('composeTitle');
    if (title) title.value = '';

    pendingAttachments = [];
    renderComposeAttachments();

    const q = ensureComposeQuill();
    if (q) {
        q.setContents([]);
    }

    const modalEl = document.getElementById('composeModal');
    if (modalEl) {
        modalEl.setAttribute('aria-hidden', 'false');
        modalEl.classList.add('show');
    }
}

function closeComposeModal() {
    const modalEl = document.getElementById('composeModal');
    if (modalEl) {
        modalEl.classList.remove('show');
        modalEl.setAttribute('aria-hidden', 'true');
    }
}

function ensureComposeQuill() {
    if (typeof Quill === 'undefined') {
        return null;
    }
    if (composeQuill) {
        return composeQuill;
    }
    const el = document.getElementById('composeEditor');
    if (!el) {
        return null;
    }
    const toolbarOptions = [
        [{ header: [1, 2, 3, false] }],
        ['bold', 'italic', 'underline', 'strike'],
        ['blockquote', 'code-block'],
        [{ list: 'ordered' }, { list: 'bullet' }],
        ['link', 'image'],
        ['clean']
    ];
    composeQuill = new Quill('#composeEditor', {
        theme: 'snow',
        modules: {
            toolbar: {
                container: toolbarOptions,
                handlers: {
                    image: function() {
                        uploadImageForQuill(this.quill);
                    }
                }
            }
        },
        placeholder: '建议结构：背景 → 过程 → 结果/建议。可插入图片、链接与代码块。'
    });
    return composeQuill;
}

function uploadImageForQuill(quill) {
    const input = document.createElement('input');
    input.setAttribute('type', 'file');
    input.setAttribute('accept', 'image/*');
    input.click();
    input.onchange = async function() {
        const file = input.files && input.files[0];
        if (!file) return;
        const fd = new FormData();
        fd.append('file', file);
        try {
            const r = await apiFetch('/api/upload/attachment?kind=avatar', {
                method: 'POST',
                body: fd,
                credentials: 'same-origin'
            });
            const j = await r.json();
            if (j.code !== 200 || !j.data || !j.data.url) {
                showMessage(j.message || '图片上传失败', 'error');
                return;
            }
            const range = quill.getSelection(true);
            const idx = range ? range.index : quill.getLength();
            quill.insertEmbed(idx, 'image', j.data.url);
            quill.setSelection(idx + 1);
        } catch (e) {
            console.error(e);
            showMessage('图片上传失败', 'error');
        }
    };
}

async function uploadCommunityAttachment(file) {
    const fd = new FormData();
    fd.append('file', file);
    try {
        const r = await apiFetch('/api/upload/attachment?kind=community', {
            method: 'POST',
            body: fd,
            credentials: 'same-origin'
        });
        const j = await r.json();
        if (j.code !== 200 || !j.data || !j.data.url) {
            showMessage(j.message || '上传失败', 'error');
            return;
        }
        pendingAttachments.push({ name: file.name, url: j.data.url });
        renderComposeAttachments();
    } catch (e) {
        console.error(e);
        showMessage('上传失败', 'error');
    }
}

function renderComposeAttachments() {
    const ul = document.getElementById('composeAttachmentList');
    if (!ul) return;
    ul.innerHTML = '';
    pendingAttachments.forEach((item, idx) => {
        const li = document.createElement('li');
        li.style.cssText = 'display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 0; border-bottom: 1px solid #e5e7eb;';
        li.innerHTML =
            '<span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.875rem; color: #4b5563;" title="' +
            escapeHtml(item.name) +
            '">' +
            escapeHtml(item.name) +
            '</span>' +
            '<button type="button" class="il-remove-attachment" style="background: transparent; border: none; color: #000000; cursor: pointer; padding: 4px 8px; border-radius: 6px; font-size: 0.8125rem;" data-idx="' +
            idx +
            '">移除</button>';
        li.querySelector('.il-remove-attachment')?.addEventListener('click', function() {
            const i = parseInt(this.getAttribute('data-idx'), 10);
            if (!isNaN(i)) {
                pendingAttachments.splice(i, 1);
                renderComposeAttachments();
            }
        });
        ul.appendChild(li);
    });
}

function fillComposeCategorySelect() {
    const sel = document.getElementById('composeCategory');
    if (!sel) return;
    sel.innerHTML = '';
    ['general', 'tech', 'competition', 'resource'].forEach(key => {
        const opt = document.createElement('option');
        opt.value = key;
        opt.textContent = COMM_FEED_CATEGORY_LABELS[key];
        sel.appendChild(opt);
    });
}


function selectCategory(cat) {
    currentCategory = cat == null ? '' : String(cat);
    currentPage = 1;

    document.querySelectorAll('#channelTabs .il-search-bar__tag').forEach(btn => {
        const bcat = btn.getAttribute('data-category') || '';
        btn.classList.toggle('active', bcat === currentCategory);
    });

    const sel = document.getElementById('composeCategory');
    if (sel) {
        if (currentCategory && ['general', 'tech', 'competition', 'resource'].includes(currentCategory)) {
            sel.value = currentCategory;
        } else {
            sel.value = 'general';
        }
    }

    loadPosts(1);
}

function getKeyword() {
    const el = document.getElementById('keywordInput');
    return el ? el.value.trim() : '';
}

function articlePageUrl(id) {
    return `/community/article/${encodeURIComponent(String(id))}`;
}

function goToArticle(id) {
    const url = articlePageUrl(id);
    if (window.ILink && window.ILink.navigate) {
        window.ILink.navigate(url);
    } else {
        window.location.href = url;
    }
}

async function loadCurrentUser() {
    try {
        const r = await apiFetch('/api/user/profile', { credentials: 'same-origin' });
        const contentType = r.headers.get('content-type') || '';
        if (!contentType.includes('application/json')) {
            currentUser = null;
            return;
        }
        const j = await r.json();
        if (j && j.code === 200) {
            currentUser = j.data || null;
            return;
        }
    } catch (e) {
        console.warn('当前未登录或用户信息读取失败', e);
    }
    currentUser = null;
}

function iconThumbUpSvg() {
    return (
        '<svg viewBox="0 0 24 24" aria-hidden="true" style="width: 18px; height: 18px;">' +
        '<path d="M9 10V6.8c0-1.7 1.2-3.3 2.9-3.7l.7-.2c.7-.2 1.4.3 1.4 1v4.1H18c1.1 0 2 .9 2 2 0 .2 0 .4-.1.6l-1.5 6.2c-.2.9-1 1.6-2 1.6H9" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"></path>' +
        '<rect x="4" y="10" width="4" height="9" rx="1" ry="1" fill="none" stroke="currentColor" stroke-width="1.8"></rect>' +
        '</svg>'
    );
}

function iconStarSvg() {
    return (
        '<svg viewBox="0 0 24 24" aria-hidden="true" style="width: 16px; height: 16px;">' +
        '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"></polygon>' +
        '</svg>'
    );
}

function iconCommentSvg() {
    return (
        '<svg viewBox="0 0 24 24" aria-hidden="true">' +
        '<path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4v8Z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"></path>' +
        '</svg>'
    );
}

function iconBadgeSvg(type) {
    if (type === 'hot') {
        return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 22c4 0 7-2.8 7-6.8 0-2.7-1.5-5.1-4.4-7.4.1 2.2-.8 3.5-2 4.1.1-3.6-1.4-6.4-4-8.4.1 4-1.6 5.8-2.7 7.5A7.4 7.4 0 0 0 5 15.2C5 19.2 8 22 12 22Z" fill="currentColor"></path></svg>';
    }
    if (type === 'pin') {
        return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 4 6 6-3.5 1.2-3.9 3.9.4 4.4-2 2-3.3-6.5L1.2 11.7l2-2 4.4.4 3.9-3.9L14 4Z" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"></path></svg>';
    }
    return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2 2.7 6.6 7.1.6-5.4 4.7 1.6 7-6-3.7-6 3.7 1.6-7-5.4-4.7 7.1-.6L12 2Z" fill="currentColor"></path></svg>';
}

function applyFeedInteractionUi(actionsEl, post) {
    if (!actionsEl || !post) return;
    const likeBtn = actionsEl.querySelector('[data-action="like"]');
    const favBtn = actionsEl.querySelector('[data-action="favorite"]');
    const likeCount = post.likeCount != null ? post.likeCount : 0;
    const favCount = post.favoriteCount != null ? post.favoriteCount : 0;
    if (likeBtn) {
        const on = !!post.liked;
        likeBtn.classList.toggle('il-feed-action--on', on);
        likeBtn.querySelector('.il-feed-action__num').textContent = String(likeCount);
    }
    if (favBtn) {
        const on = !!post.favorited;
        favBtn.classList.toggle('il-feed-action--on', on);
        favBtn.querySelector('.il-feed-action__num').textContent = String(favCount);
    }
}

async function toggleFeedInteraction(post, action, actionsEl) {
    if (!currentUser) {
        showMessage('请先登录后再操作', 'warning');
        setTimeout(() => { window.location.href = '/login.html'; }, 900);
        return;
    }
    const url = `/api/community/posts/${encodeURIComponent(post.id)}/${action === 'like' ? 'like' : 'favorite'}`;
    try {
        const response = await apiFetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: '{}'
        });
        const result = await response.json();
        if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login.html'; }, 1200);
            return;
        }
        if (result.code !== 200 || !result.data) {
            showMessage(result.message || '操作失败', 'error');
            return;
        }
        if (action === 'like') {
            post.liked = !!result.data.liked;
            post.likeCount = result.data.likeCount != null ? result.data.likeCount : (post.likeCount || 0);
        } else {
            post.favorited = !!result.data.favorited;
            post.favoriteCount = result.data.favoriteCount != null ? result.data.favoriteCount : (post.favoriteCount || 0);
        }
        applyFeedInteractionUi(actionsEl, post);
    } catch (e) {
        console.error(e);
        showMessage('网络错误，请稍后重试', 'error');
    }
}

async function loadPosts(page) {
    const listEl = document.getElementById('postList');
    const pager = document.getElementById('postPager');
    const pagerInner = document.getElementById('postPagerInner');
    if (!listEl) return;

    currentPage = Math.max(1, page || 1);

    let url = `/api/community/posts?page=${currentPage}&size=${PAGE_SIZE}`;
    if (currentCategory) {
        url += `&category=${encodeURIComponent(currentCategory)}`;
    }
    const kw = getKeyword();
    if (kw) url += `&keyword=${encodeURIComponent(kw)}`;

    try {
        const response = await apiFetch(url, { credentials: 'same-origin' });
        let result;
        try {
            result = await response.json();
        } catch (parseErr) {
            listEl.innerHTML = `
                <div class="il-empty-state">
                    <p>服务器返回异常，请稍后重试</p>
                </div>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        if (result.code !== 200 && result.code !== 0) {
            listEl.innerHTML = `
                <div class="il-empty-state">
                    <p>${escapeHtml(result.message || '加载失败')}</p>
                </div>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        const posts = result.data || [];
        const pagination = result.extra && result.extra.pagination;
        const total = pagination && pagination.total != null ? pagination.total : posts.length;

        if (posts.length === 0) {
            listEl.innerHTML = `
                <div class="il-empty-state">
                    <p>暂无文章</p>
                    <p style="font-size: 0.875rem; color: #9ca3af;">换个频道或关键词试试，或点击「写文章」发布第一篇</p>
                </div>`;
            if (pager) pager.classList.add('d-none');
            return;
        }

        listEl.innerHTML = '';
        posts.forEach(p => {
            const badge = COMM_FEED_CATEGORY_LABELS[p.category] || p.category;
            const tagClassMap = {
                '综合交流': 'il-tag--general',
                '技术讨论': 'il-tag--tech',
                '竞赛经验': 'il-tag--competition',
                '资源分享': 'il-tag--resource'
            };
            const tagClass = tagClassMap[badge] || 'il-tag--general';
            const views = p.viewCount != null ? p.viewCount : 0;
            const likes = p.likeCount != null ? p.likeCount : 0;
            const favs = p.favoriteCount != null ? p.favoriteCount : 0;
            const comments = p.commentCount != null ? p.commentCount : (p.comments != null ? p.comments : 0);
            const card = document.createElement('article');
            card.className = 'article-card il-post-card';
            card.setAttribute('role', 'link');
            card.setAttribute('tabindex', '0');
            card.setAttribute('aria-label', '阅读全文：' + (p.title || '未命名文章'));
            const detailUrl = articlePageUrl(p.id);

            const authorAvatar = (p.authorDisplay || '用户').charAt(0).toUpperCase();
            const authorAvatarUrl = p.authorAvatar || null;
            const badgeItems = [];
            if (p.pinned || p.top || p.isTop) {
                badgeItems.push({ cls: 'badge-pin', label: '置顶', type: 'pin' });
            }
            if (p.featured || p.isFeatured || p.recommended || p.official) {
                badgeItems.push({ cls: 'badge-featured', label: '精选', type: 'featured' });
                card.classList.add('featured');
            }
            if (p.hot || p.isHot) {
                badgeItems.push({ cls: 'badge-hot', label: '热门', type: 'hot' });
            }
            const badgesHtml = badgeItems.map(function(item) {
                return '<span class="article-badge ' + item.cls + '">' + iconBadgeSvg(item.type) + escapeHtml(item.label) + '</span>';
            }).join('');

            function buildCommunityAvatarHtml(avatarUrl, fallbackChar, authorName) {
                if (avatarUrl) {
                    return `<div class="author-avatar il-post-avatar">
                        <img src="${escapeHtml(avatarUrl)}" alt="${escapeHtml(authorName || '')}"
                             onerror="this.style.display='none'; var fallback=this.parentElement.querySelector('.il-post-avatar-fallback'); if(fallback){fallback.classList.remove('avatar-fallback--hidden'); fallback.removeAttribute('aria-hidden'); fallback.style.display='flex';}">
                        <span class="il-post-avatar-fallback avatar-fallback--hidden" aria-hidden="true">${escapeHtml(fallbackChar)}</span>
                    </div>`;
                }
                return `<div class="author-avatar il-post-avatar">
                    <span class="il-post-avatar-fallback" style="display:flex;">${escapeHtml(fallbackChar)}</span>
                </div>`;
            }

            card.innerHTML = `
                <div class="card-header il-post-header">
                    ${buildCommunityAvatarHtml(authorAvatarUrl, authorAvatar, p.authorDisplay)}
                    <div class="author-info il-post-author">
                        <div class="author-name il-post-author-name">
                            ${escapeHtml(p.authorDisplay || '')}
                            ${badgesHtml}
                        </div>
                        <div class="author-meta il-post-meta">
                            <span class="meta-item">${formatTime(p.createdAt)}</span>
                            <span class="meta-item">阅读 ${views}</span>
                            <span class="meta-item">评论 ${comments}</span>
                        </div>
                    </div>
                </div>
                <h2 class="card-title il-post-title">
                    ${escapeHtml(p.title)}
                </h2>
                <p class="card-excerpt il-post-preview">
                    ${escapeHtml(p.excerpt || '')}
                </p>
                <div class="card-tags il-post-tags">
                    <span class="card-tag primary il-tag ${tagClass}">${escapeHtml(badge)}</span>
                </div>
                <div class="card-divider"></div>
                <div class="card-footer il-post-footer">
                    <div class="card-actions il-post-stats">
                        <div class="il-feed-actions" data-post-id="${p.id}">
                            <button type="button" class="action-btn il-feed-action" data-action="like" aria-label="点赞">
                                ${iconThumbUpSvg()}
                                <span class="action-label">点赞</span>
                                <span class="il-feed-action__num">${likes}</span>
                            </button>
                            <button type="button" class="action-btn il-feed-action" data-action="favorite" aria-label="收藏">
                                ${iconStarSvg()}
                                <span class="action-label">收藏</span>
                                <span class="il-feed-action__num">${favs}</span>
                            </button>
                            <button type="button" class="action-btn il-feed-action" aria-label="评论">
                                ${iconCommentSvg()}
                                <span class="action-label">评论</span>
                                <span class="il-feed-action__num">${comments}</span>
                            </button>
                        </div>
                    </div>
                    <a href="${detailUrl}" class="read-more-btn il-btn il-btn-primary">阅读全文 <span aria-hidden="true">→</span></a>
                </div>`;
            card.addEventListener('click', (event) => {
                if (event.target.closest('a')) return;
                goToArticle(p.id);
            });
            card.addEventListener('keydown', (event) => {
                if (event.key !== 'Enter' && event.key !== ' ') return;
                if (event.target.closest('a, button, input, select, textarea')) return;
                event.preventDefault();
                goToArticle(p.id);
            });
            const actionsEl = card.querySelector('.il-feed-actions');
            if (actionsEl) {
                applyFeedInteractionUi(actionsEl, p);
                actionsEl.querySelector('[data-action="like"]')?.addEventListener('click', (ev) => {
                    ev.preventDefault();
                    ev.stopPropagation();
                    toggleFeedInteraction(p, 'like', actionsEl);
                });
                actionsEl.querySelector('[data-action="favorite"]')?.addEventListener('click', (ev) => {
                    ev.preventDefault();
                    ev.stopPropagation();
                    toggleFeedInteraction(p, 'favorite', actionsEl);
                });
            }
            listEl.appendChild(card);
        });

        const pag = { page: currentPage, size: PAGE_SIZE, total: total };
        renderPager(pag, pager, pagerInner);
    } catch (e) {
        console.error(e);
        listEl.innerHTML = `
            <div class="il-empty-state">
                <p>网络错误，请稍后重试</p>
            </div>`;
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
    prevLi.innerHTML = `<button class="page-link" type="button" data-page="${page - 1}">上一页</button>`;
    innerEl.appendChild(prevLi);

    const infoLi = document.createElement('li');
    infoLi.className = 'page-item disabled';
    infoLi.innerHTML = `<span class="page-link text-secondary">${page} / ${totalPages}</span>`;
    innerEl.appendChild(infoLi);

    const nextLi = document.createElement('li');
    nextLi.className = 'page-item' + (page >= totalPages ? ' disabled' : '');
    nextLi.innerHTML = `<button class="page-link" type="button" data-page="${page + 1}">下一页</button>`;
    innerEl.appendChild(nextLi);

    innerEl.querySelectorAll('button.page-link[data-page]').forEach(a => {
        a.addEventListener('click', function(ev) {
            ev.preventDefault();
            const parent = this.closest('.page-item');
            if (parent && parent.classList.contains('disabled')) return;
            const np = parseInt(this.getAttribute('data-page'), 10);
            if (!isNaN(np)) loadPosts(np);
        });
    });
}

async function submitPost() {
    const category = document.getElementById('composeCategory')?.value;
    const title = document.getElementById('composeTitle')?.value.trim() || '';
    const q = ensureComposeQuill();

    if (!q) {
        showMessage('编辑器加载失败，请刷新页面重试', 'error');
        return;
    }

    const plain = q.getText().trim();
    if (!title || !plain) {
        showMessage('请填写标题与正文', 'warning');
        return;
    }

    const html = q.root.innerHTML;

    try {
        const response = await apiFetch('/api/community/posts', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({
                category,
                title,
                content: html,
                attachments: pendingAttachments
            })
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('发布成功', 'success');
            closeComposeModal();
            const newId = result.data && result.data.id;
            if (newId) {
                goToArticle(newId);
            } else {
                selectCategory(category || '');
            }
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login.html'; }, 1200);
        } else {
            showMessage(result.message || '发布失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误，请稍后重试', 'error');
    }
}

