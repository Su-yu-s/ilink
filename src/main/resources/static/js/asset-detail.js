// 成果详情 · 分享卡片

function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

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
    if (categoryEl) categoryEl.textContent = category;

    const timeEl = document.getElementById('assetCreateTime');
    if (timeEl) timeEl.textContent = typeof formatTime === 'function' ? formatTime(asset.createdAt) : '-';

    const viewEl = document.getElementById('assetViewCount');
    if (viewEl) viewEl.textContent = '浏览 ' + (asset.viewCount || 0);

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
}
