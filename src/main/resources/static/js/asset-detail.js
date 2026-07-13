// 成果详情 · 分享卡片

function parseAssetDescription(raw) {
    const text = String(raw || '').trim();
    let body = text;
    // 去掉 markdown 前缀
    const mdMatch = body.match(/^<!--md:([^>]+)-->/);
    if (mdMatch) body = body.replace(mdMatch[0], '').trim();

    let category = '';
    const categoryMatch = body.match(/（分类：([^）]+)）/);
    if (categoryMatch && categoryMatch[1]) {
        category = categoryMatch[1].trim();
        body = body.replace(categoryMatch[0], '').trim();
    }

    let lead = '', insight = '';
    if (mdMatch) {
        // 从 base64 直接解码，避免 \n\n 跨字段串扰
        const mdParts = mdMatch[1].split('|');
        try { lead = decodeURIComponent(escape(atob(mdParts[0] || ''))) || ''; } catch(_) {}
        try { insight = decodeURIComponent(escape(atob(mdParts[1] || ''))) || ''; } catch(_) {}
    } else {
        // 旧格式兼容
        const parts = body.split(/\n\s*\n/).map(p => p.trim()).filter(Boolean);
        lead = parts[0] || '';
        insight = parts.length > 1 ? parts.slice(1).join('\n\n') : '';
    }
    return {
        category: category,
        lead: lead,
        extra: '',
        full: body,
        insight: insight
    };
}

function fileNameFromUrl(url, title) {
    if (!url) return (title || '成果附件') + '.file';
    const path = String(url).split('?')[0].split('#')[0];
    const seg = path.split('/').pop();
    if (seg && seg.indexOf('.') !== -1) return decodeURIComponent(seg);
    return (title || '成果附件') + '_附件';
}

function fileIconClass(name) {
    const lower = String(name).toLowerCase();
    if (lower.endsWith('.pdf')) return 'fa-file-pdf';
    if (lower.endsWith('.zip') || lower.endsWith('.rar')) return 'fa-file-zipper';
    if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg') || lower.endsWith('.gif')) {
        return 'fa-file-image';
    }
    if (lower.endsWith('.csv') || lower.endsWith('.xlsx')) return 'fa-file-lines';
    return 'fa-file';
}

// 分类标签颜色映射
const ASSET_DETAIL_CATEGORY_COLORS = {
    '技术开发': 'tech',
    '产品设计': 'art',
    '市场调研': 'management',
    '创新创业': 'innovation',
    '技术创新': 'tech',
    '综合交流': 'management',
    '资源分享': 'innovation'
};

function getCategoryColorClass(category) {
    if (!category || category === '未分类') return '';
    for (const [key, cls] of Object.entries(ASSET_DETAIL_CATEGORY_COLORS)) {
        if (category.includes(key)) return cls;
    }
    return 'tech';
}

// 互动按钮状态管理（localStorage）
const ASSET_DETAIL_ACTION_STORE = {
    getItem(key) {
        try {
            const data = JSON.parse(localStorage.getItem('ilink-asset-actions') || '{}');
            return data[key] || { liked: false, faved: false, likeDelta: 0, favDelta: 0 };
        } catch (e) {
            return { liked: false, faved: false, likeDelta: 0, favDelta: 0 };
        }
    },
    toggleItem(key, action) {
        try {
            const data = JSON.parse(localStorage.getItem('ilink-asset-actions') || '{}');
            let entry = data[key] || { liked: false, faved: false, likeDelta: 0, favDelta: 0 };
            if (action === 'like') {
                entry.liked = !entry.liked;
                entry.likeDelta = entry.liked ? 1 : 0;
            } else {
                entry.faved = !entry.faved;
                entry.favDelta = entry.faved ? 1 : 0;
            }
            data[key] = entry;
            localStorage.setItem('ilink-asset-actions', JSON.stringify(data));
            return entry;
        } catch (e) {
            return null;
        }
    },
    doAction(btn, baseCount) {
        const id = btn.getAttribute('data-id');
        const action = btn.getAttribute('data-action');
        if (!id || !action) return;
        const key = 'asset-' + id;
        const oldEntry = this.getItem(key);
        const wasOn = action === 'like' ? oldEntry.liked : oldEntry.faved;
        const entry = this.toggleItem(key, action);
        if (!entry) return;
        const isOn = action === 'like' ? entry.liked : entry.faved;
        const numEl = btn.querySelector('.detail-action-num');
        if (numEl) {
            const count = baseCount + (entry[action === 'like' ? 'likeDelta' : 'favDelta']);
            numEl.textContent = count;
        }
        if (isOn) {
            btn.classList.add('detail-action--on');
        } else {
            btn.classList.remove('detail-action--on');
        }
    }
};

const assetDetailUrlParams = new URLSearchParams(window.location.search);
const assetDetailId = assetDetailUrlParams.get('id');
let currentAssetDetail = null;

function setAssetDetailState(state, message) {
    const stateEl = document.getElementById('assetDetailState');
    const titleEl = document.getElementById('assetDetailStateTitle');
    const messageEl = document.getElementById('assetDetailStateMessage');
    const retryBtn = document.getElementById('assetDetailRetryBtn');
    const cardEl = document.getElementById('shareCard');

    if (state === 'ready') {
        if (stateEl) {
            stateEl.hidden = true;
            stateEl.setAttribute('aria-busy', 'false');
            stateEl.classList.remove('asset-detail-state--error');
        }
        if (cardEl) cardEl.hidden = false;
        return;
    }

    if (stateEl) {
        stateEl.hidden = false;
        stateEl.setAttribute('aria-busy', state === 'loading' ? 'true' : 'false');
        stateEl.classList.toggle('asset-detail-state--error', state === 'error');
    }
    if (cardEl) cardEl.hidden = true;
    if (retryBtn) retryBtn.hidden = state !== 'error';

    if (state === 'error') {
        if (titleEl) titleEl.textContent = '成果加载失败';
        if (messageEl) messageEl.textContent = message || '暂时无法获取成果详情，请稍后重试。';
    } else {
        if (titleEl) titleEl.textContent = '正在加载成果';
        if (messageEl) messageEl.textContent = '正在获取成果内容与附件信息，请稍候…';
    }
}

async function loadAssetDetail() {
    if (!assetDetailId) {
        setAssetDetailState('error', '链接中缺少成果 ID，请返回成果展示页重新选择。');
        return false;
    }

    setAssetDetailState('loading');
    try {
        const data = await request('/asset/' + assetDetailId, { silent: true });
        currentAssetDetail = data;
        renderAssetDetail(data);
        await setupOwnerActions(data);
        setAssetDetailState('ready');
        return true;
    } catch (error) {
        console.error('获取成果详情异常:', error);
        setAssetDetailState('error', error && error.message ? error.message : '暂时无法获取成果详情，请稍后重试。');
        return false;
    }
}

document.addEventListener('DOMContentLoaded', async function () {
    const retryBtn = document.getElementById('assetDetailRetryBtn');
    if (retryBtn && !retryBtn.dataset.bound) {
        retryBtn.dataset.bound = '1';
        retryBtn.addEventListener('click', loadAssetDetail);
    }

    const backBtn = document.getElementById('backBtn');
    if (backBtn) {
        backBtn.addEventListener('click', function () {
            window.location.href = '/gallery.html';
        });
    }

    if (window.AssetPublish) {
        AssetPublish.bind({
            onSuccess: async function () {
                await loadAssetDetail();
            }
        });
    }

    await loadAssetDetail();
});

async function setupOwnerActions(asset) {
    const editBtn = document.getElementById('assetEditBtn');
    if (!editBtn) return;
    let meId = null;
    try {
        // 成果详情是公开页面。未登录时这里只是隐藏编辑入口，不能触发全局登录跳转。
        const me = await request('/user/profile', { silent: true });
        meId = me && me.id != null ? me.id : null;
    } catch (e) {
        editBtn.hidden = true;
        return;
    }
    const ownerId = asset.userId != null ? asset.userId : (asset.ownerPreview && asset.ownerPreview.id);
    if (meId != null && ownerId != null && String(meId) === String(ownerId)) {
        editBtn.hidden = false;
        if (!editBtn.dataset.bound) {
            editBtn.dataset.bound = '1';
            editBtn.addEventListener('click', function () {
                if (window.AssetPublish && currentAssetDetail) {
                    AssetPublish.openEdit(currentAssetDetail);
                }
            });
        }
    } else {
        editBtn.hidden = true;
    }
}

function renderAssetDetail(asset) {
    const title = asset.title || '未命名成果';
    const parsed = parseAssetDescription(asset.description);
    const category = (asset.category && String(asset.category).trim()) || parsed.category || '未分类';

    const titleEl = document.getElementById('assetTitle');
    if (titleEl) titleEl.textContent = title;

    const honorBadge = document.getElementById('assetHonorBadge');
    const honorText = document.getElementById('assetHonorText');
    if (honorBadge && honorText && category && category !== '未分类') {
        honorText.textContent = category;
        honorBadge.hidden = false;
    }

    const authorElem = document.getElementById('assetAuthor');
    if (authorElem) {
        const op = asset.ownerPreview;
        if (op && op.id != null) {
            const name = typeof displayUsername === 'function' ? displayUsername(op) : '';
            const avatarHtml =
                typeof galleryPublisherAvatarHtml === 'function'
                    ? galleryPublisherAvatarHtml(op, 'publisher-avatar--md')
                    : '';
            authorElem.innerHTML =
                avatarHtml +
                '<span class="author-name-text">' +
                escapeHtml(name || '匿名用户') +
                '</span>';
            if (typeof hideGalleryPublisherAvatarFallbacks === 'function') {
                hideGalleryPublisherAvatarFallbacks(authorElem);
            }
        } else {
            authorElem.innerHTML = '<span class="author-name-text">匿名用户</span>';
        }
    }

    const categoryEl = document.getElementById('assetCategory');
    if (categoryEl) {
        categoryEl.textContent = category;
        const colorClass = getCategoryColorClass(category);
        if (colorClass) {
            categoryEl.className = 'gallery-tag--' + colorClass;
        }
    }

    const timeEl = document.getElementById('assetCreateTime');
    if (timeEl) timeEl.textContent = typeof formatTime === 'function' ? formatTime(asset.createdAt) : '-';

    const leadEl = document.getElementById('assetDescLead');
    const extraEl = document.getElementById('assetDescExtra');
    const leadText = parsed.lead || parsed.full || '暂无描述';
    if (leadEl) {
        leadEl.innerHTML = typeof marked !== 'undefined'
            ? marked.parse(leadText)
            : escapeHtml(leadText).replace(/\n/g, '<br>');
    }
    if (extraEl) {
        if (parsed.extra && parsed.extra !== leadText) {
            extraEl.innerHTML = typeof marked !== 'undefined'
                ? marked.parse(parsed.extra)
                : escapeHtml(parsed.extra).replace(/\n/g, '<br>');
            extraEl.hidden = false;
        } else {
            extraEl.hidden = true;
            extraEl.innerHTML = '';
        }
    }

    const insightEl = document.getElementById('assetInsight');
    if (insightEl) {
        const insightBody = parsed.insight;
        if (insightBody) {
            insightEl.innerHTML = typeof marked !== 'undefined'
                ? marked.parse(insightBody)
                : escapeHtml(insightBody).replace(/\n/g, '<br>');
            const op = asset.ownerPreview;
            const signer =
                op && typeof displayUsername === 'function' ? displayUsername(op) : '发布者';
            insightEl.innerHTML +=
                '<div class="insight-signature">—— ' +
                escapeHtml(signer) +
                '<br>发布于 iLink 成果展示</div>';
        } else {
            insightEl.innerHTML =
                '<p class="insight-empty">暂无竞赛心得。发布或编辑成果时，在「竞赛心得」栏填写内容即可展示在本区域。</p>';
        }
    }

    const attachSection = document.getElementById('assetAttachmentSection');
    const filesEl = document.getElementById('assetFiles');
    if (asset.fileUrl && filesEl) {
        const fname = fileNameFromUrl(asset.fileUrl, title);
        const icon = fileIconClass(fname);
        if (attachSection) attachSection.hidden = false;
        filesEl.innerHTML =
            '<div class="file-item">' +
            '<div class="file-info">' +
            '<div class="file-icon"><i class="fas ' +
            icon +
            '" aria-hidden="true"></i></div>' +
            '<div class="file-details"><h4>' +
            escapeHtml(fname) +
            '</h4><span>点击下载发布者上传的附件</span></div>' +
            '</div>' +
            '<a class="download-btn" href="/api/asset/download/' +
            asset.id +
            '"><i class="fas fa-download" aria-hidden="true"></i> 下载</a>' +
            '</div>';
    } else {
        if (attachSection) attachSection.hidden = true;
        if (filesEl) filesEl.innerHTML = '';
    }

    // 初始化互动按钮（点赞/收藏）状态
    const likeBtn = document.getElementById('detailLikeBtn');
    const favBtn = document.getElementById('detailFavBtn');
    const storageKey = 'asset-' + asset.id;
    const stored = ASSET_DETAIL_ACTION_STORE.getItem(storageKey);
    const likeCount = (asset.likeCount || 0) + stored.likeDelta;
    const favCount = (asset.favoriteCount || 0) + stored.favDelta;

    if (likeBtn) {
        likeBtn.setAttribute('data-id', asset.id);
        const likeNum = document.getElementById('detailLikeNum');
        if (likeNum) likeNum.textContent = likeCount;
        if (stored.liked) likeBtn.classList.add('detail-action--on');
        if (!likeBtn.dataset.bound) {
            likeBtn.dataset.bound = '1';
            likeBtn.addEventListener('click', function () {
                ASSET_DETAIL_ACTION_STORE.doAction(likeBtn, asset.likeCount || 0);
            });
        }
    }

    if (favBtn) {
        favBtn.setAttribute('data-id', asset.id);
        const favNum = document.getElementById('detailFavNum');
        if (favNum) favNum.textContent = favCount;
        if (stored.faved) favBtn.classList.add('detail-action--on');
        if (!favBtn.dataset.bound) {
            favBtn.dataset.bound = '1';
            favBtn.addEventListener('click', function () {
                ASSET_DETAIL_ACTION_STORE.doAction(favBtn, asset.favoriteCount || 0);
            });
        }
    }
}
