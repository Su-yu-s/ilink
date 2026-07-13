// 个人中心 · 编辑社区文章（Markdown 编辑器）
// v2 — marked.js GFM 实时预览，三模式切换，可拖拽分栏

const PROFILE_ARTICLE_CATEGORY_LABELS = {
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

let pendingAttachments = [];
let postId = null;
let currentMode = 'split';   // 'edit' | 'split' | 'preview'

document.addEventListener('DOMContentLoaded', function () {
    const params = new URLSearchParams(window.location.search);
    const rawId = params.get('id');
    if (!rawId) {
        showMessage('缺少文章 id', 'error');
        setTimeout(() => { window.location.href = '/profile-posts.html'; }, 1500);
        return;
    }
    postId = rawId;

    fillCategorySelect();
    initMarked();
    initToolbar();
    initModeButtons();
    initDividerDrag();
    initAttachmentHandlers();
    initSaveButton();

    const mdEditor = document.getElementById('mdEditor');
    if (mdEditor) {
        mdEditor.addEventListener('input', renderPreview);
    }

    const preview = document.getElementById('editPreviewLink');
    if (preview) {
        preview.href = `/community/article/${encodeURIComponent(postId)}`;
    }

    loadPost();
});

// ============ Marked.js 配置 ============
function initMarked() {
    if (typeof marked === 'undefined') {
        console.warn('marked.js 未加载，预览功能不可用');
        return;
    }
    marked.setOptions({
        gfm: true,
        breaks: true,
        pedantic: false,
    });

    // 配置 marked 使用 github-markdown-css 兼容的渲染
    marked.use({
        renderer: {
            heading(text, level) {
                const escaped = text.replace(/</g, '&lt;').replace(/>/g, '&gt;');
                return `<h${level}>${escaped}</h${level}>`;
            },
            link(href, title, text) {
                const t = title ? ` title="${title}"` : '';
                const rel = href && /^https?:\/\//.test(href) ? ' target="_blank" rel="noopener noreferrer"' : '';
                return `<a href="${href}"${t}${rel}>${text}</a>`;
            },
            image(href, title, text) {
                const t = title ? ` title="${title}"` : '';
                return `<img src="${href}" alt="${text}"${t} loading="lazy">`;
            },
        },
    });
}

// ============ 模式切换 ============
function initModeButtons() {
    const bar = document.getElementById('mdModeBar');
    if (!bar) return;

    bar.querySelectorAll('.md-mode-btn').forEach(btn => {
        btn.addEventListener('click', function () {
            const mode = this.getAttribute('data-mode');
            if (mode) switchMode(mode);
        });
    });
}

function switchMode(mode) {
    currentMode = mode;
    const wrap = document.getElementById('mdPaneWrap');
    if (!wrap) return;

    // 更新按钮激活态
    document.querySelectorAll('.md-mode-btn').forEach(b => {
        b.classList.toggle('active', b.getAttribute('data-mode') === mode);
    });

    // 更新面板 CSS 类
    wrap.classList.remove('split', 'edit-only', 'preview-only');
    wrap.classList.add(mode === 'split' ? 'split' : (mode === 'edit' ? 'edit-only' : 'preview-only'));

    // 显示/隐藏分栏拖拽条
    const divider = document.getElementById('mdDivider');
    if (divider) {
        divider.classList.toggle('visible', mode === 'split');
    }

    // 切换模式时重新渲染预览
    if (mode === 'split' || mode === 'preview') {
        renderPreview();
    }
}

// ============ 分栏拖拽 ============
function initDividerDrag() {
    const divider = document.getElementById('mdDivider');
    const editPane = document.getElementById('mdEditPane');
    const previewPane = document.getElementById('mdPreviewPane');
    const wrap = document.getElementById('mdPaneWrap');

    if (!divider || !editPane || !previewPane || !wrap) return;

    let dragging = false;
    let startX = 0;
    let startLeftWidth = 0;

    divider.addEventListener('mousedown', function (e) {
        if (currentMode !== 'split') return;
        dragging = true;
        divider.classList.add('dragging');
        startX = e.clientX;
        startLeftWidth = editPane.getBoundingClientRect().width;
        document.body.style.cursor = 'col-resize';
        document.body.style.userSelect = 'none';
        e.preventDefault();
    });

    document.addEventListener('mousemove', function (e) {
        if (!dragging) return;
        const dx = e.clientX - startX;
        const wrapWidth = wrap.getBoundingClientRect().width;
        const dividerW = divider.getBoundingClientRect().width || 6;
        const newLeftW = startLeftWidth + dx;
        // 限制最小/最大宽度
        const minW = 200;
        const maxW = wrapWidth - dividerW - 200;
        const clamped = Math.max(minW, Math.min(maxW, newLeftW));
        editPane.style.flex = 'none';
        editPane.style.width = clamped + 'px';
        previewPane.style.flex = '1';
    });

    document.addEventListener('mouseup', function () {
        if (!dragging) return;
        dragging = false;
        divider.classList.remove('dragging');
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
    });
}

// ============ 实时预览 ============
function renderPreview() {
    const mdEditor = document.getElementById('mdEditor');
    const preview = document.getElementById('mdPreview');
    if (!mdEditor || !preview) return;

    const raw = mdEditor.value || '';

    if (typeof marked !== 'undefined') {
        try {
            preview.innerHTML = marked.parse(raw);
        } catch (e) {
            preview.innerHTML = `<p style="color:var(--ui-danger)">Markdown 渲染错误：${escapeHtml(e.message)}</p>`;
        }
    } else {
        // 无 marked 时显示纯文本
        preview.innerHTML = `<pre style="white-space:pre-wrap;font-family:inherit;">${escapeHtml(raw)}</pre>`;
    }
}

// ============ 附件处理 ============
function initAttachmentHandlers() {
    document.getElementById('editAddAttachmentBtn')?.addEventListener('click', () => {
        document.getElementById('editAttachmentInput')?.click();
    });
    document.getElementById('editAttachmentInput')?.addEventListener('change', async function () {
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
}

async function uploadCommunityAttachment(file) {
    const fd = new FormData();
    fd.append('file', file);
    try {
        const r = await apiFetch('/api/upload/attachment?kind=community', {
            method: 'POST',
            body: fd,
            credentials: 'same-origin',
        });
        const j = await r.json();
        if (j.code !== 200 || !j.data || !j.data.url) {
            showMessage(j.message || '上传失败', 'error');
            return;
        }
        pendingAttachments.push({ name: file.name, url: j.data.url });
        renderAttachments();
    } catch (e) {
        console.error(e);
        showMessage('上传失败', 'error');
    }
}

function renderAttachments() {
    const ul = document.getElementById('editAttachmentList');
    if (!ul) return;
    ul.innerHTML = '';
    pendingAttachments.forEach((item, idx) => {
        const li = document.createElement('li');
        li.className = 'd-flex align-items-center justify-content-between gap-2 py-1 border-bottom';
        li.innerHTML =
            '<span class="text-truncate small flex-grow-1" title="' +
            escapeHtml(item.name) +
            '">' +
            escapeHtml(item.name) +
            '</span>' +
            '<button type="button" class="btn btn-sm btn-link text-danger p-0 flex-shrink-0" data-idx="' +
            idx +
            '">移除</button>';
        li.querySelector('button')?.addEventListener('click', function () {
            const i = parseInt(this.getAttribute('data-idx'), 10);
            if (!isNaN(i)) {
                pendingAttachments.splice(i, 1);
                renderAttachments();
            }
        });
        ul.appendChild(li);
    });
}

// ============ 加载文章 ============
function fillCategorySelect() {
    const sel = document.getElementById('editCategory');
    if (!sel) return;
    sel.innerHTML = '';
    ['general', 'tech', 'competition', 'resource'].forEach(key => {
        const opt = document.createElement('option');
        opt.value = key;
        opt.textContent = PROFILE_ARTICLE_CATEGORY_LABELS[key];
        sel.appendChild(opt);
    });
}

async function loadPost() {
    try {
        const response = await apiFetch(
            `/api/community/posts/${encodeURIComponent(postId)}/for-edit`,
            { credentials: 'same-origin' },
        );
        const result = await response.json();

        if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
            return;
        }
        if (result.code === 403 || result.code === 404) {
            showMessage(result.message || '无法加载', 'error');
            setTimeout(() => { window.location.href = '/profile-posts.html'; }, 1500);
            return;
        }
        if (result.code !== 200 || !result.data) {
            showMessage(result.message || '加载失败', 'error');
            return;
        }

        const d = result.data;
        const titleEl = document.getElementById('editTitle');
        const catEl = document.getElementById('editCategory');
        if (titleEl) titleEl.value = d.title || '';
        if (catEl && d.category) catEl.value = d.category;

        // 附件
        pendingAttachments = Array.isArray(d.attachments)
            ? d.attachments
                  .map(a => ({
                      name: a && a.name != null ? String(a.name) : '附件',
                      url: a && a.url != null ? String(a.url) : '',
                  }))
                  .filter(a => a.url && a.url.startsWith('/uploads/'))
            : [];
        renderAttachments();

        // 正文：优先恢复嵌入的 Markdown 源码，兼容旧 Quill HTML
        const mdEditor = document.getElementById('mdEditor');
        if (mdEditor) {
            const raw = d.content || '';
            if (raw.trim()) {
                // 检查是否有嵌入的 markdown 源码 <!--md:base64-->
                const mdMatch = raw.match(/^<!--md:([A-Za-z0-9+/=]+)-->/);
                if (mdMatch) {
                    try {
                        mdEditor.value = decodeURIComponent(escape(atob(mdMatch[1])));
                    } catch (_) {
                        mdEditor.value = raw;
                    }
                } else if (/<\/?[a-z][\s\S]*>/i.test(raw)) {
                    // 旧 Quill HTML → 提取纯文本
                    const temp = document.createElement('div');
                    temp.innerHTML = raw;
                    mdEditor.value = temp.textContent || raw;
                } else {
                    // 纯文本 / Markdown
                    mdEditor.value = raw;
                }
            }
            renderPreview();
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

// ============ 保存文章 ============
function initSaveButton() {
    document.getElementById('editSaveBtn')?.addEventListener('click', savePost);
}

async function savePost() {
    const category = document.getElementById('editCategory')?.value;
    const title = document.getElementById('editTitle')?.value.trim() || '';
    const mdEditor = document.getElementById('mdEditor');

    if (!mdEditor) {
        showMessage('编辑器未就绪', 'error');
        return;
    }

    const mdContent = mdEditor.value.trim();
    if (!title || !mdContent) {
        showMessage('请填写标题与正文', 'warning');
        return;
    }

    // 将 Markdown 渲染为 HTML，并嵌入源码供二次编辑恢复
    let htmlContent = '';
    if (typeof marked !== 'undefined') {
        try {
            htmlContent = marked.parse(mdContent);
        } catch (e) {
            console.error('Markdown 渲染失败', e);
            htmlContent = `<p>${escapeHtml(mdContent).replace(/\n/g, '<br>')}</p>`;
        }
    } else {
        htmlContent = `<p>${escapeHtml(mdContent).replace(/\n/g, '<br>')}</p>`;
    }

    // 将原始 Markdown 以 base64 嵌入 HTML 注释，二次编辑时可恢复
    const mdB64 = btoa(unescape(encodeURIComponent(mdContent)));
    const wrappedHtml = `<!--md:${mdB64}-->${htmlContent}`;

    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({
                category,
                title,
                content: wrappedHtml,
                attachments: pendingAttachments,
            }),
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('保存成功', 'success');
            setTimeout(() => {
                window.location.href = '/profile-posts.html';
            }, 600);
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
        } else {
            showMessage(result.message || '保存失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

// ============ 拖拽/粘贴图片上传 ============
function initImageDrop(ta) {
    if (!ta || ta.dataset.imgDropBound) return;
    ta.dataset.imgDropBound = '1';
    var uploading = false;

    function insertAtCursor(url, alt) {
        ta.focus();
        var s = ta.selectionStart, e = ta.selectionEnd;
        var md = '![' + (alt || '图片') + '](' + url + ')';
        ta.setRangeText(md, s, e, 'end');
        ta.dispatchEvent(new Event('input', { bubbles: true }));
    }

    async function uploadFile(file) {
        if (!file || !/^image\//.test(file.type)) return false;
        if (uploading) { showMessage('有图片正在上传中，稍等片刻', 'warning'); return true; }
        uploading = true;
        try {
            var fd = new FormData();
            fd.append('file', file);
            var r = await apiFetch('/api/upload/attachment?kind=community', { method: 'POST', body: fd, credentials: 'same-origin' });
            var j = await r.json();
            if (j.code === 200 && j.data && j.data.url) {
                insertAtCursor(j.data.url, (file.name || '').replace(/\.[^.]+$/, ''));
                showMessage('图片已插入', 'success');
            } else {
                showMessage(j.message || '上传失败', 'error');
            }
        } catch (e) {
            console.error(e);
            showMessage('上传失败', 'error');
        } finally {
            uploading = false;
        }
        return true;
    }

    ta.addEventListener('paste', function (e) {
        var items = e.clipboardData && e.clipboardData.items;
        if (!items) return;
        for (var i = 0; i < items.length; i++) {
            if (items[i].type.indexOf('image/') === 0) {
                e.preventDefault();
                uploadFile(items[i].getAsFile());
                return;
            }
        }
    });

    ta.addEventListener('dragover', function (e) { e.preventDefault(); e.stopPropagation(); });
    ta.addEventListener('drop', function (e) {
        e.preventDefault(); e.stopPropagation();
        var files = e.dataTransfer && e.dataTransfer.files;
        if (files && files.length) uploadFile(files[0]);
    });
}

// ============ 富文本工具栏 ============
function initToolbar() {
    var bar = document.querySelector('.md-toolbar');
    if (!bar) return;
    // add has-toolbar class to the sibling pane-wrap
    var pw = bar.nextElementSibling;
    while (pw && !pw.classList.contains('md-pane-wrap')) pw = pw.nextElementSibling;
    if (pw) pw.classList.add('has-toolbar');

    bar.querySelectorAll('.md-toolbar-btn').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var action = btn.getAttribute('data-action');
            var ta = document.getElementById('mdEditor');
            if (!ta || !action) return;
            applyMdAction(ta, action);
        });
    });

    // 拖拽/粘贴图片上传
    var mdEditor = document.getElementById('mdEditor');
    if (mdEditor) initImageDrop(mdEditor);
}

function applyMdAction(ta, action) {
    var start = ta.selectionStart;
    var end = ta.selectionEnd;
    var text = ta.value;
    var sel = text.substring(start, end);
    var rep = '';

    var markers = {
        bold: { wrap: '**', label: '粗体' },
        italic: { wrap: '*', label: '斜体' },
        strikethrough: { wrap: '~~', label: '删除线' },
        code: { wrap: '`', label: '代码' },
        h2: { prefix: '## ', label: '标题' },
        h3: { prefix: '### ', label: '小标题' },
        ul: { prefix: '- ', label: '列表' },
        ol: { prefix: '1. ', label: '有序' },
        quote: { prefix: '> ', label: '引用' },
        link: { prefix: '[', suffix: '](url)', label: '链接', selectLabel: '链接文字' },
        image: { prefix: '![', suffix: '](url)', label: '图片', selectLabel: '图片描述' },
        hr: { line: '\n---\n', label: '分隔线' },
    };

    var m = markers[action];
    if (!m) return;

    if (m.line != null) {
        rep = m.line;
    } else if (m.prefix != null && m.suffix != null) {
        if (sel) {
            rep = m.prefix + sel + m.suffix;
        } else {
            rep = m.prefix + (m.selectLabel || m.label) + m.suffix;
            var labelLen = (m.selectLabel || m.label).length;
            start = start + m.prefix.length;
            end = start + labelLen;
        }
    } else if (m.prefix != null) {
        var lineStart = text.lastIndexOf('\n', start - 1) + 1;
        var lineText = text.substring(lineStart, end);
        rep = m.prefix + lineText;
        start = lineStart;
        end = lineStart + rep.length;
    } else if (m.wrap != null) {
        var w = m.wrap;
        var wLen = w.length;
        // 无选中 → 插入带占位的标记
        if (!sel) {
            rep = w + m.label + w;
            start = start + wLen;
            end = start + m.label.length;
        } else {
            // 检查选中内容本身是否已被标记包裹
            var innerSel = sel.substring(wLen, sel.length - wLen);
            if (sel.startsWith(w) && sel.endsWith(w) && innerSel.length > 0) {
                // 选中了 **加粗** → 去掉标记
                rep = innerSel;
            } else {
                // 检查选中内容前后紧邻位置是否有标记对
                var before = text.substring(Math.max(0, start - wLen), start);
                var afterStart = end;
                var afterEnd = Math.min(text.length, end + wLen);
                var after = text.substring(afterStart, afterEnd);
                if (before === w && after === w) {
                    // 标记对紧贴选中内容两侧 → 去掉标记
                    rep = sel;
                    start = start - wLen;
                    end = end + wLen;
                } else {
                    // 没有已有标记 → 添加
                    rep = w + sel + w;
                    start = start;
                    end = end + wLen * 2;
                }
            }
        }
    }

    ta.focus();
    ta.setRangeText(rep, start, end, 'end');
    ta.dispatchEvent(new Event('input', { bubbles: true }));
}
