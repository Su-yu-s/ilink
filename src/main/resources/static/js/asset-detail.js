// 成果详情 · 分享卡片

function parseAssetDescription(raw) {
    const text = String(raw || '').trim();
    let category = '';
    let body = text;
    const categoryMatch = text.match(/（分类：([^）]+)）/);
    if (categoryMatch && categoryMatch[1]) {
        category = categoryMatch[1].trim();
        body = text.replace(categoryMatch[0], '').trim();
    }
    const parts = body.split(/\n\s*\n/).map(function (p) {
        return p.trim();
    }).filter(Boolean);
    return {
        category: category,
        lead: parts[0] || '',
        extra: parts.length > 1 ? parts.slice(1).join('\n\n') : '',
        full: body,
        insight: parts.length > 1 ? parts.slice(1).join('\n\n') : ''
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
const CATEGORY_COLORS = {
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
    for (const [key, cls] of Object.entries(CATEGORY_COLORS)) {
        if (category.includes(key)) return cls;
    }
    return 'tech';
}

// 互动按钮状态管理（localStorage）
const ActionStore = {
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

const urlParams = new URLSearchParams(window.location.search);
const assetId = urlParams.get('id');
let currentAsset = null;

document.addEventListener('DOMContentLoaded', async function () {
    if (!assetId) {
        showMessage('缺少作品ID参数', 'error');
        setTimeout(function () {
            window.location.href = '/gallery.html';
        }, 1000);
        return;
    }

    if (window.AssetPublish) {
        AssetPublish.bind({
            onSuccess: async function () {
                try {
                    const data = await request('/asset/' + assetId);
                    currentAsset = data;
                    renderAssetDetail(data);
                } catch (e) {
                    console.error(e);
                }
            }
        });
    }

    try {
        const data = await request('/asset/' + assetId);
        currentAsset = data;
        renderAssetDetail(data);
        await setupOwnerActions(data);
    } catch (error) {
        console.error('获取作品详情异常:', error);
        setTimeout(function () {
            window.location.href = '/gallery.html';
        }, 1000);
    }

    const backBtn = document.getElementById('backBtn');
    if (backBtn) {
        backBtn.addEventListener('click', function () {
            window.location.href = '/gallery.html';
        });
    }
});

async function setupOwnerActions(asset) {
    const editBtn = document.getElementById('assetEditBtn');
    if (!editBtn) return;
    let meId = null;
    try {
        const me = await request('/user/profile');
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
                if (window.AssetPublish && currentAsset) {
                    AssetPublish.openEdit(currentAsset);
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
    if (leadEl) leadEl.textContent = leadText;
    if (extraEl) {
        if (parsed.extra && parsed.extra !== leadText) {
            extraEl.textContent = parsed.extra;
            extraEl.hidden = false;
        } else {
            extraEl.hidden = true;
            extraEl.textContent = '';
        }
    }

    const insightEl = document.getElementById('assetInsight');
    if (insightEl) {
        const insightBody = parsed.insight;
        if (insightBody) {
            const paragraphs = insightBody.split(/\n+/).filter(Boolean);
            insightEl.innerHTML = paragraphs
                .map(function (p, i) {
                    if (i === 0 && paragraphs.length > 1) {
                        return (
                            '<div class="insight-quote"><i class="fas fa-lightbulb" aria-hidden="true"></i> ' +
                            escapeHtml(p) +
                            '</div>'
                        );
                    }
                    return '<p>' + escapeHtml(p) + '</p>';
                })
                .join('');
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
    const stored = ActionStore.getItem(storageKey);
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
                ActionStore.doAction(likeBtn, asset.likeCount || 0);
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
                ActionStore.doAction(favBtn, asset.favoriteCount || 0);
            });
        }
    }
}
