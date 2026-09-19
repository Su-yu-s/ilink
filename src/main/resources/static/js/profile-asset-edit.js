// 个人中心 · 编辑成果（双 Markdown 编辑器）
// v4 — 沉浸式工作台：双标签 / 实时预览 / 三模式 / 分栏拖拽 / 同步滚动 /
//      Tab 缩进 / 魔法输入 / 本地草稿 / 发布二次确认

let assetId = null;
let currentFile = null;
let currentFileUrl = null;
let currentFileName = '';
let removeFilePending = false;
let currentCover = null;
let currentCoverUrl = null;
let removeCoverPending = false;
let coverPreviewObjectUrl = null;
const COVER_MAX_BYTES = 5 * 1024 * 1024;
let isCreateMode = true;
let isDirty = false;
let isSaving = false;
let draftTimer = null;
const ASSET_DRAFT_KEY = 'ilink_asset_draft_v1';
const compactEditorQuery = window.matchMedia('(max-width: 1024px)');

document.addEventListener('DOMContentLoaded', function () {
    const params = new URLSearchParams(window.location.search);
    const rawId = params.get('id');
    assetId = rawId || null;
    isCreateMode = !assetId;

    configureWorkspaceMode();
    initMarked();
    initToolbars();
    initModeSwitches();
    initDividerDrags();
    initSyncScrolls();
    initEditorKeydowns();
    initEditorDragMask();
    initResponsiveEditorModes();
    initEditorTabs();
    initFileButton();
    initCoverButton();
    initSaveButton();
    initPublishModal();
    initDraftBar();
    initPreviewLink();
    initDirtyTracking();
    document.addEventListener('keydown', handleSaveShortcut);
    window.addEventListener('beforeunload', guardUnsavedChanges);
    if (assetId) {
        loadAsset();
    } else {
        renderPreview('mdDescEditor', 'mdDescPreview');
        renderPreview('mdInsightEditor', 'mdInsightPreview');
        updateWorkspaceStatus('尚未保存');
        updateEditorStats();
        offerDraftRestore();
        document.getElementById('editTitle')?.focus();
    }
});

function configureWorkspaceMode() {
    const title = isCreateMode ? '发布成果' : '编辑成果';
    document.title = title + ' - iLink';
    const pageTitle = document.getElementById('pageTitle');
    const saveLabel = document.getElementById('editSaveBtnLabel');
    if (pageTitle) pageTitle.textContent = title;
    if (saveLabel) saveLabel.textContent = isCreateMode ? '发布成果' : '保存修改';
}

function nowHHMM() {
    const d = new Date();
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function initEditorTabs() {
    document.querySelectorAll('[data-editor-tab]').forEach(function (button) {
        button.addEventListener('click', function () {
            const target = button.getAttribute('data-editor-tab');
            document.querySelectorAll('[data-editor-tab]').forEach(function (tab) {
                const active = tab === button;
                tab.classList.toggle('active', active);
                tab.setAttribute('aria-selected', String(active));
            });
            document.querySelectorAll('[data-editor-panel]').forEach(function (panel) {
                const active = panel.getAttribute('data-editor-panel') === target;
                panel.hidden = !active;
                panel.classList.toggle('active', active);
            });
            if (target === 'description') renderPreview('mdDescEditor', 'mdDescPreview');
            else renderPreview('mdInsightEditor', 'mdInsightPreview');
            updateEditorStats(target);
        });
    });
}

function initDirtyTracking() {
    ['editTitle', 'mdDescEditor', 'mdInsightEditor'].forEach(function (id) {
        document.getElementById(id)?.addEventListener('input', markDirty);
    });
    document.getElementById('editCategory')?.addEventListener('change', markDirty);
}

function handleSaveShortcut(event) {
    if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === 's') {
        event.preventDefault();
        saveAsset();
    }
}

function guardUnsavedChanges(event) {
    if (!isDirty) return;
    event.preventDefault();
    event.returnValue = '';
}

function markDirty() {
    if (isSaving) return;
    isDirty = true;
    updateWorkspaceStatus('编辑中…', 'is-dirty');
    scheduleDraftSave();
}

function markClean(message) {
    isDirty = false;
    updateWorkspaceStatus(message || '已保存', 'is-saved');
}

function updateWorkspaceStatus(text, stateClass) {
    const status = document.getElementById('saveStatus');
    if (!status) return;
    status.textContent = text;
    status.classList.remove('is-dirty', 'is-saving', 'is-saved');
    if (stateClass) status.classList.add(stateClass);
}

// ============ Marked.js 配置 ============
function initMarked() {
    if (!configureMarkdownRenderer()) console.warn('marked.js 未加载');
}

// ============ 拖拽/粘贴图片上传 ============
function initImageDrop(ta) {
    if (!ta || ta.dataset.imgDropBound) return;
    ta.dataset.imgDropBound = '1';
    let uploading = false;

    function insertAtCursor(url, alt) {
        ta.focus();
        const s = ta.selectionStart, e = ta.selectionEnd;
        ta.setRangeText('![' + (alt || '图片') + '](' + url + ')', s, e, 'end');
        ta.dispatchEvent(new Event('input', { bubbles: true }));
    }

    async function uploadFile(file) {
        if (!file || !/^image\//.test(file.type)) return false;
        if (uploading) { showMessage('有图片正在上传中，稍等片刻', 'warning'); return true; }
        uploading = true;
        try {
            const fd = new FormData();
            fd.append('file', file);
            const r = await apiFetch('/api/upload/attachment?kind=community', { method: 'POST', body: fd, credentials: 'same-origin' });
            const j = await r.json();
            if (j.code === 200 && j.data && j.data.url) {
                const uploaded = window.ILinkFiles.normalizeUploadResult(j.data, file);
                insertAtCursor(uploaded.url, (uploaded.name || '').replace(/\.[^.]+$/, ''));
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
        const items = e.clipboardData && e.clipboardData.items;
        if (!items) return;
        for (let i = 0; i < items.length; i++) {
            if (items[i].type.indexOf('image/') === 0) {
                e.preventDefault();
                uploadFile(items[i].getAsFile());
                return;
            }
        }
    });
    ta.addEventListener('dragover', e => e.preventDefault());
    ta.addEventListener('drop', function (e) {
        e.preventDefault();
        const files = e.dataTransfer && e.dataTransfer.files;
        if (files && files.length) uploadFile(files[0]);
    });
}

// ============ 富文本工具栏 ============
function initToolbars() {
    document.querySelectorAll('.md-toolbar').forEach(function (bar) {
        const panel = getPanelForBar(bar);
        const pw = panel ? panel.querySelector('.md-pane-wrap') : null;
        if (pw) pw.classList.add('has-toolbar');

        bar.querySelectorAll('.md-toolbar-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                const action = btn.getAttribute('data-action');
                const ta = getToolbarTextarea(bar);
                if (!ta || !action) return;
                applyMarkdownAction(ta, action);
            });
        });

        const ta = getToolbarTextarea(bar);
        if (ta) initImageDrop(ta);
    });
}

function getPanelForBar(bar) {
    const key = bar.getAttribute('data-toolbar-for');
    if (key) return document.querySelector(`[data-editor-panel="${key}"]`);
    return bar.closest('[data-editor-panel]');
}

function getToolbarTextarea(bar) {
    const panel = getPanelForBar(bar);
    return panel ? panel.querySelector('textarea') : null;
}

function applyMarkdownAction(ta, action) {
    const start0 = ta.selectionStart, end0 = ta.selectionEnd;
    const text = ta.value;
    const sel = text.substring(start0, end0);
    let rep = '', start = start0, end = end0;

    const markers = {
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
        table: { line: '\n| 表头 | 表头 |\n| --- | --- |\n| 内容 | 内容 |\n' },
        hr: { line: '\n---\n' },
    };

    const m = markers[action];
    if (!m) return;

    if (m.line != null) {
        rep = m.line;
    } else if (m.prefix != null && m.suffix != null) {
        if (sel) rep = m.prefix + sel + m.suffix;
        else {
            rep = m.prefix + (m.selectLabel || m.label) + m.suffix;
            const labelLen = (m.selectLabel || m.label).length;
            start = start0 + m.prefix.length;
            end = start + labelLen;
        }
    } else if (m.prefix != null) {
        const lineStart = text.lastIndexOf('\n', start0 - 1) + 1;
        rep = m.prefix + text.substring(lineStart, end0);
        start = lineStart;
        end = lineStart + rep.length;
    } else if (m.wrap != null) {
        const w = m.wrap, wLen = w.length;
        if (!sel) {
            rep = w + m.label + w;
            start = start0 + wLen;
            end = start + m.label.length;
        } else {
            const innerSel = sel.substring(wLen, sel.length - wLen);
            if (sel.startsWith(w) && sel.endsWith(w) && innerSel.length > 0) {
                rep = innerSel;
            } else {
                const before = text.substring(Math.max(0, start0 - wLen), start0);
                const after = text.substring(end0, Math.min(text.length, end0 + wLen));
                if (before === w && after === w) {
                    rep = sel;
                    start = start0 - wLen;
                    end = end0 + wLen;
                } else {
                    rep = w + sel + w;
                    end = end0 + wLen * 2;
                }
            }
        }
    }

    ta.focus();
    ta.setRangeText(rep, start, end, 'end');
    ta.dispatchEvent(new Event('input', { bubbles: true }));
}

// ============ 模式切换 ============
function initModeSwitches() {
    document.querySelectorAll('.md-mode-bar').forEach(function (bar) {
        bar.querySelectorAll('.md-mode-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                const mode = btn.getAttribute('data-mode');
                if (mode === 'split' && compactEditorQuery.matches) return;
                const wrapId = bar.id === 'mdModeBarInsight' ? 'insightPaneWrap' : 'descPaneWrap';
                switchMode(wrapId, mode, bar);
            });
        });
    });

    const descTa = document.getElementById('mdDescEditor');
    const insTa = document.getElementById('mdInsightEditor');
    if (descTa) descTa.addEventListener('input', function () { renderPreview('mdDescEditor', 'mdDescPreview'); updateEditorStats('description'); });
    if (insTa) insTa.addEventListener('input', function () { renderPreview('mdInsightEditor', 'mdInsightPreview'); updateEditorStats('insight'); });
}

function initResponsiveEditorModes() {
    function sync() {
        document.querySelectorAll('.md-mode-bar').forEach(function (bar) {
            const splitButton = bar.querySelector('[data-mode="split"]');
            if (splitButton) splitButton.disabled = compactEditorQuery.matches;
            if (compactEditorQuery.matches) {
                const wrapId = bar.id === 'mdModeBarInsight' ? 'insightPaneWrap' : 'descPaneWrap';
                const wrap = document.getElementById(wrapId);
                if (wrap && wrap.classList.contains('split')) switchMode(wrapId, 'edit', bar);
            }
        });
    }
    sync();
    compactEditorQuery.addEventListener?.('change', sync);
}

function switchMode(wrapId, mode, barEl) {
    const wrap = document.getElementById(wrapId);
    if (!wrap) return;
    if (barEl) {
        barEl.querySelectorAll('.md-mode-btn').forEach(b => {
            b.classList.toggle('active', b.getAttribute('data-mode') === mode);
        });
    }
    wrap.classList.remove('split', 'edit-only', 'preview-only');
    wrap.classList.add(mode === 'split' ? 'split' : (mode === 'edit' ? 'edit-only' : 'preview-only'));
    const divider = wrap.querySelector('.md-divider');
    if (divider) divider.classList.toggle('visible', mode === 'split');
    if (mode === 'split' || mode === 'preview') {
        const ta = wrap.querySelector('textarea');
        if (ta && ta.id === 'mdDescEditor') renderPreview('mdDescEditor', 'mdDescPreview');
        else if (ta && ta.id === 'mdInsightEditor') renderPreview('mdInsightEditor', 'mdInsightPreview');
    }
}

// ============ 分栏拖拽（两个编辑器） ============
function initDividerDrags() {
    setupDividerDrag('descPaneWrap');
    setupDividerDrag('insightPaneWrap');
}

function setupDividerDrag(wrapId) {
    const wrap = document.getElementById(wrapId);
    if (!wrap) return;
    const divider = wrap.querySelector('.md-divider');
    const editPane = wrap.querySelector('.md-edit-pane');
    const previewPane = wrap.querySelector('.md-preview-pane');
    if (!divider || !editPane || !previewPane) return;

    let dragging = false, startX = 0, startLeftWidth = 0;
    divider.addEventListener('mousedown', function (e) {
        if (compactEditorQuery.matches || !wrap.classList.contains('split')) return;
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
        const wrapWidth = wrap.getBoundingClientRect().width;
        const dividerW = divider.getBoundingClientRect().width || 6;
        const minW = Math.min(200, wrapWidth * 0.2);
        const maxW = wrapWidth - dividerW - Math.min(200, wrapWidth * 0.2);
        const clamped = Math.max(minW, Math.min(maxW, startLeftWidth + e.clientX - startX));
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

// ============ 同步滚动 ============
function initSyncScrolls() {
    setupSyncScroll('mdDescEditor', 'descPaneWrap');
    setupSyncScroll('mdInsightEditor', 'insightPaneWrap');
}

function setupSyncScroll(textareaId, wrapId) {
    const editor = document.getElementById(textareaId);
    const wrap = document.getElementById(wrapId);
    if (!editor || !wrap) return;
    const preview = wrap.querySelector('.md-preview-pane');
    if (!preview) return;
    let fromEditor = false, fromPreview = false;
    editor.addEventListener('scroll', function () {
        if (fromPreview) return;
        fromEditor = true;
        const em = editor.scrollHeight - editor.clientHeight;
        const pm = preview.scrollHeight - preview.clientHeight;
        if (em > 0) preview.scrollTop = (editor.scrollTop / em) * pm;
        setTimeout(() => { fromEditor = false; }, 60);
    });
    preview.addEventListener('scroll', function () {
        if (fromEditor) return;
        fromPreview = true;
        const em = editor.scrollHeight - editor.clientHeight;
        const pm = preview.scrollHeight - preview.clientHeight;
        if (pm > 0) editor.scrollTop = (preview.scrollTop / pm) * em;
        setTimeout(() => { fromPreview = false; }, 60);
    });
}

// ============ 键盘增强（Tab / 魔法 / 快捷键） ============
function initEditorKeydowns() {
    ['mdDescEditor', 'mdInsightEditor'].forEach(bindEditorKeys);
}

function bindEditorKeys(id) {
    const ta = document.getElementById(id);
    if (!ta) return;
    ta.addEventListener('keydown', function (e) {
        const mod = e.ctrlKey || e.metaKey;
        if (mod) {
            const key = String(e.key).toLowerCase();
            if (key === 'b') { e.preventDefault(); applyMarkdownAction(ta, 'bold'); return; }
            if (key === 'i') { e.preventDefault(); applyMarkdownAction(ta, 'italic'); return; }
            if (key === 'k') { e.preventDefault(); applyMarkdownAction(ta, 'link'); return; }
            if (e.key === 'Enter') { e.preventDefault(); saveAsset(); return; }
        }
        if (e.key === 'Tab') {
            e.preventDefault();
            const start = ta.selectionStart, end = ta.selectionEnd;
            if (start === end || !e.shiftKey) {
                if (start === end) ta.setRangeText('    ', start, end, 'end');
                else {
                    const block = ta.value.substring(start, end);
                    ta.setRangeText(block.split('\n').map(l => '    ' + l).join('\n'), start, end, 'select');
                }
            } else {
                const block = ta.value.substring(start, end);
                ta.setRangeText(block.split('\n').map(l => l.replace(/^ {1,4}/, '')).join('\n'), start, end, 'select');
            }
            ta.dispatchEvent(new Event('input', { bubbles: true }));
            return;
        }
        if (e.key === ' ') {
            const pos = ta.selectionStart;
            const before = ta.value.substring(0, pos);
            const lineStart = before.lastIndexOf('\n') + 1;
            const line = before.substring(lineStart);
            const magic = { '#': '# ', '##': '## ', '###': '### ', '-': '- ', '>': '> ' };
            if (magic[line]) {
                e.preventDefault();
                ta.setRangeText(magic[line], lineStart, pos, 'end');
                ta.dispatchEvent(new Event('input', { bubbles: true }));
            } else if (/^\d+\.$/.test(line)) {
                e.preventDefault();
                ta.setRangeText(line + ' ', lineStart, pos, 'end');
                ta.dispatchEvent(new Event('input', { bubbles: true }));
            }
        }
    });
}

// ============ 编辑器拖拽遮罩 ============
function initEditorDragMask() {
    const card = document.querySelector('.publishing-workspace__editor');
    if (!card) return;
    let depth = 0;
    const hasFiles = e => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files');
    card.addEventListener('dragenter', e => {
        if (!hasFiles(e)) return;
        depth++;
        card.classList.add('is-drag-over');
    });
    card.addEventListener('dragover', e => { if (hasFiles(e)) e.preventDefault(); });
    card.addEventListener('dragleave', () => {
        depth = Math.max(0, depth - 1);
        if (depth === 0) card.classList.remove('is-drag-over');
    });
    card.addEventListener('drop', () => {
        depth = 0;
        card.classList.remove('is-drag-over');
    });
}

function renderPreview(textareaId, previewId) {
    const ta = document.getElementById(textareaId);
    const preview = document.getElementById(previewId);
    if (!ta || !preview) return;
    const raw = ta.value || '';
    if (!raw.trim()) {
        const hint = textareaId === 'mdInsightEditor'
            ? '记录你的竞赛心得、经验教训...'
            : '开始输入，这里会实时预览你的成果...';
        preview.innerHTML = `<p style="color:#d1d5db;font-style:italic;text-align:center;margin-top:80px;">${hint}</p>`;
        return;
    }
    if (typeof marked !== 'undefined') renderMarkdownSafe(preview, raw);
    else preview.innerHTML = '<pre style="white-space:pre-wrap;font-family:inherit;">' + escapeHtml(raw) + '</pre>';
}

// ============ 构建 description（兼容旧格式） ============
function buildDescription(lead, insight, category) {
    const l = markdownToSafeHtml(String(lead || '').trim());
    const i = markdownToSafeHtml(String(insight || '').trim());
    let body = l;
    if (i) body = body ? body + '\n\n' + i : i;
    const mdSrc = [lead || '', insight || ''].map(encodeMarkdownSource).join('|');
    body = markdownSourceMarker(mdSrc) + body;
    const cat = String(category || '').trim();
    if (!cat) return body;
    if (body.indexOf('（分类：') !== -1) return body;
    return body + '（分类：' + cat + '）';
}

function parseDescription(raw) {
    const text = String(raw || '').trim();
    let category = '';
    let body = text;
    const mdPayload = markdownSourcePayload(body);
    body = stripMarkdownSourceMarker(body);
    const m = body.match(/（分类：([^）]+)）/);
    if (m && m[1]) { category = m[1].trim(); body = body.replace(m[0], '').trim(); }

    let lead = '', insight = '';
    if (mdPayload) {
        const parts = mdPayload.split('|');
        lead = decodeMarkdownSource(parts[0] || '');
        insight = decodeMarkdownSource(parts[1] || '');
    } else {
        const parts = body.split(/\n\s*\n/).map(p => p.trim()).filter(Boolean);
        lead = parts[0] || '';
        insight = parts.length > 1 ? parts.slice(1).join('\n\n') : '';
    }
    return { category, lead, insight, full: body, mdRaw: mdPayload || null };
}

// ============ 加载成果 ============
async function loadAsset() {
    try {
        const r = await apiFetch('/api/asset/list?page=1&size=100', { credentials: 'same-origin' });
        const d = await r.json();
        if (d.code !== 200) { showMessage(d.message || '加载失败', 'error'); return; }
        const asset = (d.data || []).find(a => String(a.id) === String(assetId));
        if (!asset) { showMessage('成果不存在', 'error'); return; }
        fillForm(asset);
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

function fillForm(asset) {
    const parsed = parseDescription(asset.description || '');
    const titleEl = document.getElementById('editTitle');
    const catEl = document.getElementById('editCategory');
    const descEl = document.getElementById('mdDescEditor');
    const insEl = document.getElementById('mdInsightEditor');

    if (titleEl) titleEl.value = asset.title || '';
    if (catEl) catEl.value = asset.category || parsed.category || '其他';
    if (parsed.mdRaw) {
        const parts = parsed.mdRaw.split('|');
        if (descEl) descEl.value = decodeMarkdownSource(parts[0] || '');
        if (insEl) insEl.value = decodeMarkdownSource(parts[1] || '');
    } else {
        if (descEl) descEl.value = parsed.lead || parsed.full || '';
        if (insEl) insEl.value = parsed.insight || '';
    }

    if (asset.fileUrl) {
        currentFileUrl = asset.fileUrl;
        // originalFileName 为空的历史数据会回退成 UUID 存储名，用公共 helper 换成「成果名.pdf」
        currentFileName = window.ILinkFiles.friendlyDownloadName(
            asset.originalFileName, asset.fileUrl, asset.title);
        renderFileArea();
    }
    currentCoverUrl = asset.coverUrl || null;
    renderCoverArea();

    markClean('已载入');
    const previewLink = document.getElementById('editPreviewLink');
    if (previewLink) previewLink.href = '/asset-detail.html?id=' + assetId;

    setTimeout(function () {
        renderPreview('mdDescEditor', 'mdDescPreview');
        renderPreview('mdInsightEditor', 'mdInsightPreview');
        updateEditorStats();
    }, 100);
}

// ============ 文件 ============
function initFileButton() {
    const dropzone = document.getElementById('editFileBtn');
    const input = document.getElementById('editFileInput');
    if (!dropzone || !input) return;
    dropzone.addEventListener('click', function () { input.click(); });
    dropzone.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); }
    });
    ['dragenter', 'dragover'].forEach(evt => dropzone.addEventListener(evt, e => {
        e.preventDefault(); e.stopPropagation();
        dropzone.classList.add('is-dragover');
    }));
    ['dragleave', 'dragend'].forEach(evt => dropzone.addEventListener(evt, e => {
        e.preventDefault(); e.stopPropagation();
        dropzone.classList.remove('is-dragover');
    }));
    dropzone.addEventListener('drop', e => {
        e.preventDefault(); e.stopPropagation();
        dropzone.classList.remove('is-dragover');
        const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
        if (file) pickAssetFile(file);
    });
    input.addEventListener('change', function () {
        const file = this.files && this.files[0];
        if (file) pickAssetFile(file);
        this.value = '';
    });
}

function updateDropzoneSub(text) {
    const sub = document.getElementById('editFileBtnSub');
    // 这里只放状态，不放文件名——存储名很长会把拖拽区撑破
    if (sub) sub.textContent = text || '尚未选择文件';
}

function pickAssetFile(file) {
    currentFile = file;
    removeFilePending = false;
    renderFileArea();
    markDirty();
}

function removeAssetFile() {
    if (currentFile) {
        // 先丢弃还没上传的本地文件，回落到服务端已有的附件
        currentFile = null;
    } else if (currentFileUrl) {
        removeFilePending = true;
        currentFileUrl = null;
        currentFileName = '';
    } else {
        return;
    }
    renderFileArea();
    markDirty();
}

/** 附件区域的唯一渲染入口：本地新文件 > 服务端已有附件 > 空态 */
function renderFileArea() {
    const preview = document.getElementById('editFilePreview');
    const hint = document.getElementById('editFileHint');
    if (!preview || !hint) return;
    preview.innerHTML = '';

    if (currentFile) {
        preview.hidden = false;
        renderFileCard(currentFile.name, currentFile.size, '待上传');
        hint.textContent = '已选择新文件，保存成果后上传并替换当前附件。';
        updateDropzoneSub('已选择新文件');
        return;
    }
    if (currentFileUrl) {
        preview.hidden = false;
        renderFileCard(currentFileName, 0, '当前附件');
        hint.textContent = '当前附件将会保留，选择新文件可替换。';
        updateDropzoneSub('已保留原附件');
        return;
    }
    preview.hidden = true;
    hint.textContent = removeFilePending
        ? '保存后将移除该附件，选择新文件可改为替换。'
        : '支持 PDF / ZIP / 文档 / 图片，单个文件不超过 20MB';
    updateDropzoneSub(removeFilePending ? '待移除附件' : '尚未选择文件');
}

function renderFileCard(name, size, statusText) {
    const preview = document.getElementById('editFilePreview');
    if (!preview) return;
    const safeName = escapeHtml(name || '附件');
    const sizeText = window.ILinkFiles.formatSize(size);
    preview.innerHTML = window.ILinkFiles.iconMarkup(name) +
        '<div class="publishing-workspace__selected-file-body"><strong title="' + safeName + '">' +
        safeName + '</strong><small>' + escapeHtml(statusText || '') +
        (sizeText ? ' · ' + escapeHtml(sizeText) : '') + '</small></div>' +
        '<button type="button" class="il-upload-file__remove" data-action="remove-file" aria-label="移除附件 ' +
        safeName + '" title="移除"><span aria-hidden="true">×</span></button>';
    const removeButton = preview.querySelector('[data-action="remove-file"]');
    if (removeButton) removeButton.addEventListener('click', removeAssetFile);
}

function initPreviewLink() {
    const link = document.getElementById('editPreviewLink');
    if (link && assetId) link.href = '/asset-detail.html?id=' + assetId;
}

// ============ 封面 ============
function initCoverButton() {
    const drop = document.getElementById('editCoverDrop');
    const input = document.getElementById('editCoverInput');
    const removeButton = document.getElementById('editCoverRemove');
    if (!drop || !input) return;

    drop.addEventListener('click', function () { input.click(); });
    drop.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); }
    });
    ['dragenter', 'dragover'].forEach(evt => drop.addEventListener(evt, e => {
        e.preventDefault(); e.stopPropagation();
        drop.classList.add('is-dragover');
    }));
    ['dragleave', 'dragend'].forEach(evt => drop.addEventListener(evt, e => {
        e.preventDefault(); e.stopPropagation();
        drop.classList.remove('is-dragover');
    }));
    drop.addEventListener('drop', e => {
        e.preventDefault(); e.stopPropagation();
        drop.classList.remove('is-dragover');
        const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
        if (file) pickCoverFile(file);
    });
    input.addEventListener('change', function () {
        const file = this.files && this.files[0];
        if (file) pickCoverFile(file);
        this.value = '';
    });
    if (removeButton) removeButton.addEventListener('click', removeAssetCover);
}

function pickCoverFile(file) {
    if (!file.type || file.type.indexOf('image/') !== 0) {
        showMessage('封面需要是图片文件', 'warning');
        return;
    }
    if (file.size > COVER_MAX_BYTES) {
        showMessage('封面不能超过 5MB', 'warning');
        return;
    }
    currentCover = file;
    removeCoverPending = false;
    renderCoverArea();
    markDirty();
}

function removeAssetCover() {
    if (currentCover) {
        // 先丢弃还没上传的本地封面，回落到服务端已有的封面
        currentCover = null;
    } else if (currentCoverUrl) {
        removeCoverPending = true;
        currentCoverUrl = null;
    } else {
        return;
    }
    renderCoverArea();
    markDirty();
}

/** 封面区域的唯一渲染入口：本地新封面 > 服务端已有封面 > 空态 */
function renderCoverArea() {
    const drop = document.getElementById('editCoverDrop');
    const img = document.getElementById('editCoverImg');
    const removeButton = document.getElementById('editCoverRemove');
    const hint = document.getElementById('editCoverHint');
    if (!drop || !img || !hint) return;

    // 本地预览用的是 objectURL，换图或清空时必须回收，否则整轮编辑下来会持续占用内存
    if (coverPreviewObjectUrl) {
        URL.revokeObjectURL(coverPreviewObjectUrl);
        coverPreviewObjectUrl = null;
    }

    let source = '';
    if (currentCover) {
        coverPreviewObjectUrl = URL.createObjectURL(currentCover);
        source = coverPreviewObjectUrl;
        hint.textContent = '已选择新封面，保存成果后上传并替换当前封面。';
    } else if (currentCoverUrl) {
        source = currentCoverUrl;
        hint.textContent = '当前封面将会保留，选择新图片可替换。';
    } else {
        hint.textContent = removeCoverPending
            ? '保存后将移除封面，回到按分类自动配图。'
            : '不设置则按成果分类自动配图。';
    }

    if (source) {
        img.src = source;
        img.hidden = false;
        drop.classList.add('has-cover');
    } else {
        img.removeAttribute('src');
        img.hidden = true;
        drop.classList.remove('has-cover');
    }
    if (removeButton) removeButton.hidden = !source;
}

// ============ 保存 / 发布 ============
function initSaveButton() {
    document.getElementById('editSaveBtn')?.addEventListener('click', saveAsset);
}

function collectAssetForm() {
    const title = (document.getElementById('editTitle').value || '').trim();
    const category = document.getElementById('editCategory').value || '';
    const lead = document.getElementById('mdDescEditor').value || '';
    const insight = document.getElementById('mdInsightEditor').value || '';
    return { title, category, lead, insight };
}

function saveAsset() {
    if (isSaving) return;
    const { title } = collectAssetForm();
    if (!title) {
        showMessage('请填写成果名称', 'warning');
        document.getElementById('editTitle').focus();
        return;
    }
    if (isCreateMode) openPublishModal();
    else doSubmitAsset();
}

function initPublishModal() {
    const modal = document.getElementById('publishModal');
    if (!modal) return;
    document.getElementById('cancelPublish')?.addEventListener('click', closePublishModal);
    document.getElementById('confirmPublish')?.addEventListener('click', doSubmitAsset);
    modal.addEventListener('click', e => { if (e.target === modal) closePublishModal(); });
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape' && modal.classList.contains('is-open')) closePublishModal();
    });
}

function openPublishModal() {
    const modal = document.getElementById('publishModal');
    if (!modal) return doSubmitAsset();
    const { title, category } = collectAssetForm();
    const titleEl = document.getElementById('modalTitle');
    const catEl = document.getElementById('modalCategory');
    if (titleEl) titleEl.textContent = title || '（未填写）';
    if (catEl) catEl.textContent = category || '其他';
    modal.hidden = false;
    requestAnimationFrame(() => modal.classList.add('is-open'));
}

function closePublishModal() {
    const modal = document.getElementById('publishModal');
    if (!modal) return;
    modal.classList.remove('is-open');
    setTimeout(() => { modal.hidden = true; }, 160);
}

async function doSubmitAsset() {
    if (isSaving) return;
    closePublishModal();
    const { title, category, lead, insight } = collectAssetForm();
    const description = buildDescription(lead, insight, category);

    const fd = new FormData();
    fd.append('title', title);
    fd.append('description', description);
    fd.append('category', category || '其他');
    if (currentFile) fd.append('file', currentFile);
    // 已上传新文件时以新文件为准；只有没有新文件时才把「移除」意图发给服务端
    else if (removeFilePending) fd.append('removeFile', 'true');
    if (currentCover) fd.append('cover', currentCover);
    else if (removeCoverPending) fd.append('removeCover', 'true');

    const saveButton = document.getElementById('editSaveBtn');
    const saveLabel = document.getElementById('editSaveBtnLabel');
    const idleLabel = isCreateMode ? '发布成果' : '保存修改';
    isSaving = true;
    if (saveButton) saveButton.disabled = true;
    if (saveLabel) saveLabel.textContent = isCreateMode ? '发布中…' : '保存中…';
    updateWorkspaceStatus(isCreateMode ? '正在发布…' : '正在保存…', 'is-saving');
    try {
        const endpoint = isCreateMode ? '/api/asset/upload' : '/api/asset/' + encodeURIComponent(assetId);
        const r = await apiFetch(endpoint, {
            method: isCreateMode ? 'POST' : 'PUT',
            body: fd,
            credentials: 'same-origin',
        });
        const j = await r.json();
        if (j.code === 200) {
            const savedId = j.data && j.data.id != null ? j.data.id : assetId;
            clearDraft();
            markClean(isCreateMode ? '发布成功' : '保存成功');
            showMessage(isCreateMode ? '发布成功' : '保存成功', 'success');
            setTimeout(function () {
                window.location.href = savedId
                    ? '/asset-detail.html?id=' + encodeURIComponent(savedId)
                    : '/gallery.html';
            }, 600);
        } else {
            showMessage(j.message || '保存失败', 'error');
            updateWorkspaceStatus('保存失败，请重试', 'is-dirty');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
        updateWorkspaceStatus('保存失败，请重试', 'is-dirty');
    } finally {
        isSaving = false;
        if (saveButton) saveButton.disabled = false;
        if (saveLabel) saveLabel.textContent = idleLabel;
    }
}

// ============ 本地草稿（仅新建模式） ============
function scheduleDraftSave() {
    if (!isCreateMode) return;
    clearTimeout(draftTimer);
    draftTimer = setTimeout(() => {
        const { title, category, lead, insight } = collectAssetForm();
        if (!title && !lead.trim() && !insight.trim()) { clearDraft(); return; }
        try {
            localStorage.setItem(ASSET_DRAFT_KEY, JSON.stringify({ title, category, desc: lead, insight, ts: Date.now() }));
            updateWorkspaceStatus(`草稿已自动保存 ${nowHHMM()}`, 'is-saved');
        } catch (e) { /* noop */ }
    }, 800);
}

function readDraft() {
    try { return JSON.parse(localStorage.getItem(ASSET_DRAFT_KEY) || 'null'); } catch (e) { return null; }
}

function clearDraft() {
    try { localStorage.removeItem(ASSET_DRAFT_KEY); } catch (e) { /* noop */ }
}

function offerDraftRestore() {
    const draft = readDraft();
    const bar = document.getElementById('draftBar');
    if (!draft || !bar) return;
    if (!(draft.title && draft.title.trim()) && !(draft.desc && draft.desc.trim()) && !(draft.insight && draft.insight.trim())) return;
    bar.hidden = false;
    requestAnimationFrame(() => bar.classList.add('is-open'));
}

function initDraftBar() {
    const bar = document.getElementById('draftBar');
    if (!bar) return;
    document.getElementById('draftRestore')?.addEventListener('click', function () {
        const draft = readDraft();
        if (draft) {
            const set = (id, v) => { const el = document.getElementById(id); if (el) el.value = v || ''; };
            set('editTitle', draft.title);
            if (draft.category) { const c = document.getElementById('editCategory'); if (c) c.value = draft.category; }
            set('mdDescEditor', draft.desc);
            set('mdInsightEditor', draft.insight);
            renderPreview('mdDescEditor', 'mdDescPreview');
            renderPreview('mdInsightEditor', 'mdInsightPreview');
            updateEditorStats();
            markClean('已恢复本地草稿');
        }
        hideDraftBar();
    });
    document.getElementById('draftDiscard')?.addEventListener('click', function () {
        clearDraft();
        hideDraftBar();
    });
}

function hideDraftBar() {
    const bar = document.getElementById('draftBar');
    if (!bar) return;
    bar.classList.remove('is-open');
    setTimeout(() => { bar.hidden = true; }, 160);
}

// ============ 字数 / 行数（跟随当前标签，只数非空行） ============
function countTextStats(value) {
    const raw = String(value || '');
    const chars = raw.replace(/\s/g, '').length;
    const lines = raw ? raw.split(/\r\n|\r|\n/).filter(l => l.trim() !== '').length : 0;
    return { chars, lines };
}

function getActiveEditorKind(kind) {
    if (kind === 'description' || kind === 'insight') return kind;
    const activePanel = document.querySelector('[data-editor-panel].active:not([hidden])');
    if (activePanel) return activePanel.getAttribute('data-editor-panel');
    return 'description';
}

function updateEditorStats(kind) {
    const active = getActiveEditorKind(kind);
    const taId = active === 'insight' ? 'mdInsightEditor' : 'mdDescEditor';
    const ta = document.getElementById(taId);
    const wordEl = document.getElementById('wsWordCount');
    const lineEl = document.getElementById('wsLineCount');
    const labelEl = document.getElementById('wsActiveLabel');
    if (!ta) return;
    const stats = countTextStats(ta.value);
    if (wordEl) wordEl.textContent = stats.chars;
    if (lineEl) lineEl.textContent = stats.lines;
    if (labelEl) labelEl.textContent = active === 'insight' ? '竞赛心得' : '成果描述';
}
