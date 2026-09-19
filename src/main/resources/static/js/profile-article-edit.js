// 个人中心 · 编辑社区文章（Markdown 编辑器）
// v3 — 沉浸式工作台：实时预览 / 三模式 / 分栏拖拽 / 同步滚动 /
//      Tab 缩进 / Markdown 魔法输入 / 本地草稿 / 发布二次确认

const PROFILE_ARTICLE_CATEGORY_LABELS = {
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};
const ARTICLE_DRAFT_KEY = 'ilink_article_draft_v1';

let pendingAttachments = [];
let attachmentSequence = 0;
let postId = null;
let isCreateMode = true;
let isDirty = false;
let isSaving = false;
let draftTimer = null;
const compactEditorQuery = window.matchMedia('(max-width: 1024px)');
let currentMode = compactEditorQuery.matches ? 'edit' : 'split';

document.addEventListener('DOMContentLoaded', function () {
    const params = new URLSearchParams(window.location.search);
    const rawId = params.get('id');
    postId = rawId || null;
    isCreateMode = !postId;

    fillCategorySelect();
    configureWorkspaceMode();
    initMarked();
    initToolbar();
    initModeButtons();
    initResponsiveEditorMode();
    initDividerDrag();
    initSyncScroll();
    initEditorKeydown();
    initEditorDragMask();
    initAttachmentHandlers();
    initSaveButton();
    initPublishModal();
    initDraftBar();

    const mdEditor = document.getElementById('mdEditor');
    if (mdEditor) {
        mdEditor.addEventListener('input', function () {
            renderPreview();
            updateEditorStats();
            markDirty();
        });
    }

    document.getElementById('editTitle')?.addEventListener('input', markDirty);
    document.getElementById('editCategory')?.addEventListener('change', markDirty);
    document.addEventListener('keydown', handleSaveShortcut);
    window.addEventListener('beforeunload', guardUnsavedChanges);

    const preview = document.getElementById('editPreviewLink');
    if (preview && postId) {
        preview.href = `/community/article/${encodeURIComponent(postId)}`;
        preview.hidden = false;
    }

    if (postId) {
        loadPost();
    } else {
        renderPreview();
        updateEditorStats();
        offerDraftRestore();
        document.getElementById('editTitle')?.focus();
    }
});

function configureWorkspaceMode() {
    const title = isCreateMode ? '写文章' : '编辑文章';
    document.title = title + ' - iLink';
    const pageTitle = document.getElementById('pageTitle');
    const saveLabel = document.getElementById('editSaveBtnLabel');
    if (pageTitle) pageTitle.textContent = title;
    if (saveLabel) saveLabel.textContent = isCreateMode ? '发布文章' : '保存修改';
}

function handleSaveShortcut(event) {
    if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === 's') {
        event.preventDefault();
        savePost();
    }
}

function guardUnsavedChanges(event) {
    if (!isDirty) return;
    event.preventDefault();
    event.returnValue = '';
}

function nowHHMM() {
    const d = new Date();
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
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
    if (!configureMarkdownRenderer()) {
        console.warn('marked.js 未加载，预览功能不可用');
    }
}

// ============ 模式切换 ============
function initModeButtons() {
    const bar = document.getElementById('mdModeBar');
    if (!bar) return;
    bar.querySelectorAll('.md-mode-btn').forEach(btn => {
        btn.addEventListener('click', function () {
            const mode = this.getAttribute('data-mode');
            if (mode && !(mode === 'split' && compactEditorQuery.matches)) switchMode(mode);
        });
    });
}

function initResponsiveEditorMode() {
    const sync = () => {
        if (compactEditorQuery.matches && currentMode === 'split') currentMode = 'edit';
        switchMode(currentMode);
    };
    sync();
    compactEditorQuery.addEventListener?.('change', sync);
}

function switchMode(mode) {
    currentMode = mode;
    const wrap = document.getElementById('mdPaneWrap');
    if (!wrap) return;

    document.querySelectorAll('#mdModeBar .md-mode-btn').forEach(b => {
        b.classList.toggle('active', b.getAttribute('data-mode') === mode);
    });

    wrap.classList.remove('split', 'edit-only', 'preview-only');
    wrap.classList.add(mode === 'split' ? 'split' : (mode === 'edit' ? 'edit-only' : 'preview-only'));

    const divider = document.getElementById('mdDivider');
    if (divider) divider.classList.toggle('visible', mode === 'split');

    if (mode === 'split' || mode === 'preview') renderPreview();
}

// ============ 分栏拖拽 ============
function initDividerDrag() {
    const divider = document.getElementById('mdDivider');
    const editPane = document.getElementById('mdEditPane');
    const previewPane = document.getElementById('mdPreviewPane');
    const wrap = document.getElementById('mdPaneWrap');
    if (!divider || !editPane || !previewPane || !wrap) return;

    let dragging = false, startX = 0, startLeftWidth = 0;

    divider.addEventListener('mousedown', function (e) {
        if (currentMode !== 'split' || compactEditorQuery.matches) return;
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

// ============ 左右同步滚动 ============
function initSyncScroll() {
    const editor = document.getElementById('mdEditor');
    const preview = document.getElementById('mdPreviewPane');
    if (!editor || !preview) return;
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

// ============ 编辑器键盘增强（Tab 缩进 / 魔法输入 / 快捷键） ============
function initEditorKeydown() {
    const ta = document.getElementById('mdEditor');
    if (!ta) return;

    ta.addEventListener('keydown', function (e) {
        const mod = e.ctrlKey || e.metaKey;
        if (mod) {
            const key = String(e.key).toLowerCase();
            if (key === 'b') { e.preventDefault(); applyMdAction(ta, 'bold'); return; }
            if (key === 'i') { e.preventDefault(); applyMdAction(ta, 'italic'); return; }
            if (key === 'k') { e.preventDefault(); applyMdAction(ta, 'link'); return; }
            if (e.key === 'Enter') { e.preventDefault(); savePost(); return; }
        }

        if (e.key === 'Tab') {
            e.preventDefault();
            const start = ta.selectionStart, end = ta.selectionEnd;
            if (start === end || !e.shiftKey) {
                if (start === end) {
                    ta.setRangeText('    ', start, end, 'end');
                } else {
                    const block = ta.value.substring(start, end);
                    const indented = (e.shiftKey ? block : block).split('\n').map(l => '    ' + l).join('\n');
                    ta.setRangeText(indented, start, end, 'select');
                }
            } else {
                const block = ta.value.substring(start, end);
                const out = block.split('\n').map(l => l.replace(/^ {1,4}/, '')).join('\n');
                ta.setRangeText(out, start, end, 'select');
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

// ============ 实时预览 ============
function renderPreview() {
    const mdEditor = document.getElementById('mdEditor');
    const preview = document.getElementById('mdPreview');
    if (!mdEditor || !preview) return;
    const raw = mdEditor.value || '';
    if (!raw.trim()) {
        preview.innerHTML = '<p style="color:#d1d5db;font-style:italic;text-align:center;margin-top:80px;">开始输入，这里会实时预览你的文章...</p>';
        return;
    }
    if (typeof marked !== 'undefined') {
        renderMarkdownSafe(preview, raw);
    } else {
        preview.innerHTML = `<pre style="white-space:pre-wrap;font-family:inherit;">${escapeHtml(raw)}</pre>`;
    }
}

// ============ 附件处理（点击 + 拖拽复用） ============
function initAttachmentHandlers() {
    const input = document.getElementById('editAttachmentInput');
    const dropzone = document.getElementById('editAddAttachmentBtn');

    input?.addEventListener('change', async function () {
        const files = Array.from(this.files || []);
        this.value = '';
        await handleAttachmentFiles(files);
    });

    if (!dropzone) return;
    dropzone.addEventListener('click', () => {
        if (isAttachmentInputBlocked()) return;
        input?.click();
    });
    dropzone.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            if (!isAttachmentInputBlocked()) input?.click();
        }
    });
    ['dragenter', 'dragover'].forEach(evt => dropzone.addEventListener(evt, (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (!isAttachmentInputBlocked()) dropzone.classList.add('is-dragover');
    }));
    ['dragleave', 'dragend'].forEach(evt => dropzone.addEventListener(evt, (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropzone.classList.remove('is-dragover');
    }));
    dropzone.addEventListener('drop', async (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropzone.classList.remove('is-dragover');
        if (isAttachmentInputBlocked()) return;
        const files = Array.from((e.dataTransfer && e.dataTransfer.files) || []);
        await handleAttachmentFiles(files);
    });
    updateAttachmentControls();
}

function isAttachmentInputBlocked() {
    return pendingAttachments.some(item => item.status === 'uploading') || pendingAttachments.length >= 10;
}

async function handleAttachmentFiles(files) {
    if (!files || !files.length) return;
    if (pendingAttachments.some(item => item.status === 'uploading')) {
        showMessage('有附件正在上传，请稍候', 'warning');
        return;
    }
    markDirty();
    for (const file of files) {
        const duplicate = window.ILinkFiles.findDuplicate(pendingAttachments, file.name);
        if (duplicate) {
            const shouldReplace = window.confirm(`已存在同名附件“${file.name}”，是否替换？`);
            if (!shouldReplace) continue;
            const previous = { ...duplicate };
            duplicate.name = file.name || '附件';
            duplicate.size = Number(file.size) || 0;
            duplicate.contentType = file.type || 'application/octet-stream';
            duplicate.file = file;
            duplicate.status = 'uploading';
            duplicate.error = '';
            renderAttachments();
            await uploadCommunityAttachment(duplicate, previous);
            continue;
        }
        if (pendingAttachments.length >= 10) {
            showMessage('附件最多 10 个', 'warning');
            break;
        }
        const item = {
            id: `upload-${Date.now()}-${attachmentSequence++}`,
            name: file.name || '附件',
            size: Number(file.size) || 0,
            contentType: file.type || 'application/octet-stream',
            url: '',
            file,
            status: 'uploading',
            error: ''
        };
        pendingAttachments.push(item);
        renderAttachments();
        await uploadCommunityAttachment(item);
    }
}

async function uploadCommunityAttachment(item, previous) {
    if (!item || !item.file) return;
    item.status = 'uploading';
    item.error = '';
    renderAttachments();
    const fd = new FormData();
    fd.append('file', item.file);
    try {
        const r = await apiFetch('/api/upload/attachment?kind=community', {
            method: 'POST',
            body: fd,
            credentials: 'same-origin',
        });
        const j = await r.json();
        if (j.code !== 200 || !j.data || !j.data.url) {
            const message = j.message || '上传失败';
            if (previous) Object.assign(item, previous);
            else { item.status = 'error'; item.error = message; }
            renderAttachments();
            showMessage(previous ? `替换失败，已保留原文件：${message}` : message, 'error');
            return false;
        }
        const uploaded = window.ILinkFiles.normalizeUploadResult(j.data, item.file);
        item.name = uploaded.name;
        item.url = uploaded.url;
        item.size = uploaded.size;
        item.contentType = uploaded.contentType;
        item.status = 'success';
        item.error = '';
        renderAttachments();
        return true;
    } catch (e) {
        console.error(e);
        const message = e && e.name === 'AbortError' ? '上传超时，请重试' : '上传失败，请重试';
        if (previous) Object.assign(item, previous);
        else { item.status = 'error'; item.error = message; }
        renderAttachments();
        showMessage(previous ? `替换失败，已保留原文件：${message}` : message, 'error');
        return false;
    }
}

function renderAttachments() {
    const ul = document.getElementById('editAttachmentList');
    if (!ul) return;
    ul.innerHTML = '';
    pendingAttachments.forEach((item) => {
        const li = document.createElement('li');
        const status = item.status || 'success';
        li.className = `il-upload-file is-${status}`;
        li.dataset.uploadId = item.id;
        const meta = [];
        const sizeText = window.ILinkFiles.formatSize(item.size);
        if (sizeText) meta.push(`<span>${escapeHtml(sizeText)}</span>`);
        if (status === 'uploading') {
            meta.push('<span class="il-upload-file__spinner" aria-hidden="true"></span><span class="il-upload-file__status">上传中…</span>');
        } else if (status === 'error') {
            meta.push(`<span class="il-upload-file__status is-error">${escapeHtml(item.error || '上传失败')}</span>`);
        } else {
            meta.push('<span class="il-upload-file__status is-success">已上传</span>');
        }
        const retryButton = status === 'error'
            ? '<button type="button" class="il-upload-file__action" data-action="retry">重试</button>'
            : '';
        li.innerHTML =
            window.ILinkFiles.iconMarkup(item.name) +
            `<div class="il-upload-file__body"><span class="il-upload-file__name" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</span>` +
            `<div class="il-upload-file__meta">${meta.join('')}</div></div>` +
            `<div class="il-upload-file__actions">${retryButton}</div>` +
            `<button type="button" class="il-upload-file__remove" data-action="remove" aria-label="移除附件 ${escapeHtml(item.name)}" title="移除"><span aria-hidden="true">×</span></button>`;
        li.querySelector('[data-action="remove"]')?.addEventListener('click', function () {
            pendingAttachments = pendingAttachments.filter(candidate => candidate.id !== item.id);
            markDirty();
            renderAttachments();
        });
        li.querySelector('[data-action="retry"]')?.addEventListener('click', function () {
            uploadCommunityAttachment(item);
        });
        ul.appendChild(li);
    });
    updateAttachmentControls();
}

function updateAttachmentControls() {
    const uploading = pendingAttachments.some(item => item.status === 'uploading');
    const atLimit = pendingAttachments.length >= 10;
    const dropzone = document.getElementById('editAddAttachmentBtn');
    const dropLabel = document.getElementById('editAddAttachmentLabel');
    const saveButton = document.getElementById('editSaveBtn');
    if (dropzone) {
        const blocked = uploading || atLimit;
        dropzone.classList.toggle('is-disabled', blocked);
        dropzone.setAttribute('aria-disabled', blocked ? 'true' : 'false');
    }
    if (dropLabel) {
        dropLabel.textContent = uploading ? '正在上传…' : (atLimit ? '已达附件数量上限（10 个）' : '点击或拖拽文件上传');
    }
    if (saveButton) saveButton.disabled = uploading || isSaving;
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

        pendingAttachments = Array.isArray(d.attachments)
            ? d.attachments
                  .map(a => ({
                      id: `saved-${Date.now()}-${attachmentSequence++}`,
                      name: a && a.name != null ? String(a.name) : '附件',
                      url: a && a.url != null ? String(a.url) : '',
                      size: Number(a && a.size) || 0,
                      contentType: a && a.contentType ? String(a.contentType) : '',
                      file: null,
                      status: 'success',
                      error: '',
                  }))
                  .filter(a => a.url && a.url.startsWith('/uploads/'))
            : [];
        renderAttachments();

        const mdEditor = document.getElementById('mdEditor');
        if (mdEditor) {
            const raw = d.content || '';
            if (raw.trim()) {
                const sourcePayload = markdownSourcePayload(raw);
                if (sourcePayload) {
                    mdEditor.value = decodeMarkdownSource(sourcePayload);
                } else if (/<\/?[a-z][\s\S]*>/i.test(raw)) {
                    const temp = document.createElement('div');
                    temp.innerHTML = raw;
                    mdEditor.value = temp.textContent || raw;
                } else {
                    mdEditor.value = raw;
                }
            }
            renderPreview();
            updateEditorStats();
        }
        markClean('已载入');
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

// ============ 保存 / 发布 ============
function initSaveButton() {
    document.getElementById('editSaveBtn')?.addEventListener('click', savePost);
}

function collectDraftPayload() {
    const category = document.getElementById('editCategory')?.value;
    const title = document.getElementById('editTitle')?.value.trim() || '';
    const mdEditor = document.getElementById('mdEditor');
    const mdContent = mdEditor ? mdEditor.value.trim() : '';
    return { category, title, mdContent };
}

function validateBeforeSave() {
    const { title, mdContent } = collectDraftPayload();
    if (!title) {
        showMessage('请填写文章标题', 'warning');
        document.getElementById('editTitle')?.focus();
        return false;
    }
    if (!mdContent) {
        showMessage('请填写文章正文', 'warning');
        document.getElementById('mdEditor')?.focus();
        return false;
    }
    if (pendingAttachments.some(item => item.status === 'uploading')) {
        showMessage('附件仍在上传，请稍候', 'warning');
        return false;
    }
    if (pendingAttachments.some(item => item.status === 'error')) {
        showMessage('请重试或移除上传失败的附件', 'warning');
        return false;
    }
    return true;
}

function savePost() {
    if (isSaving) return;
    if (!validateBeforeSave()) return;
    // 新建模式：发布前二次确认；编辑已有文章：直接保存修改
    if (isCreateMode) openPublishModal();
    else doSubmitPost();
}

// ---- 发布二次确认 ----
function initPublishModal() {
    const modal = document.getElementById('publishModal');
    if (!modal) return;
    document.getElementById('cancelPublish')?.addEventListener('click', closePublishModal);
    document.getElementById('confirmPublish')?.addEventListener('click', doSubmitPost);
    modal.addEventListener('click', e => { if (e.target === modal) closePublishModal(); });
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape' && modal.classList.contains('is-open')) closePublishModal();
    });
}

function openPublishModal() {
    const modal = document.getElementById('publishModal');
    if (!modal) return doSubmitPost();
    const { title, category } = collectDraftPayload();
    const titleEl = document.getElementById('modalTitle');
    const catEl = document.getElementById('modalCategory');
    if (titleEl) titleEl.textContent = title || '（未填写）';
    if (catEl) catEl.textContent = PROFILE_ARTICLE_CATEGORY_LABELS[category] || category || '综合交流';
    modal.hidden = false;
    requestAnimationFrame(() => modal.classList.add('is-open'));
}

function closePublishModal() {
    const modal = document.getElementById('publishModal');
    if (!modal) return;
    modal.classList.remove('is-open');
    setTimeout(() => { modal.hidden = true; }, 160);
}

async function doSubmitPost() {
    if (isSaving) return;
    closePublishModal();
    const { category, title, mdContent } = collectDraftPayload();

    const htmlContent = markdownToSafeHtml(mdContent);
    const wrappedHtml = markdownSourceMarker(encodeMarkdownSource(mdContent)) + htmlContent;

    const saveButton = document.getElementById('editSaveBtn');
    const saveLabel = document.getElementById('editSaveBtnLabel');
    const idleLabel = isCreateMode ? '发布文章' : '保存修改';
    isSaving = true;
    if (saveButton) saveButton.disabled = true;
    if (saveLabel) saveLabel.textContent = isCreateMode ? '发布中…' : '保存中…';
    updateWorkspaceStatus(isCreateMode ? '正在发布…' : '正在保存…', 'is-saving');

    try {
        const endpoint = isCreateMode
            ? '/api/community/posts'
            : `/api/community/posts/${encodeURIComponent(postId)}`;
        const response = await apiFetch(endpoint, {
            method: isCreateMode ? 'POST' : 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({
                category,
                title,
                content: wrappedHtml,
                attachments: pendingAttachments
                    .filter(item => item.status === 'success' && item.url)
                    .map(item => ({ name: item.name, url: item.url })),
            }),
        });
        const result = await response.json();
        if (result.code === 200) {
            const savedId = result.data && result.data.id != null ? result.data.id : postId;
            clearDraft();
            markClean(isCreateMode ? '发布成功' : '保存成功');
            showMessage(isCreateMode ? '发布成功' : '保存成功', 'success');
            setTimeout(() => {
                window.location.href = savedId
                    ? `/community/article/${encodeURIComponent(savedId)}`
                    : '/community.html';
            }, 600);
        } else if (result.code === 401) {
            updateWorkspaceStatus('登录后可继续保存', 'is-dirty');
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
        } else {
            showMessage(result.message || '保存失败', 'error');
            updateWorkspaceStatus('保存失败，请重试', 'is-dirty');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
        updateWorkspaceStatus('保存失败，请重试', 'is-dirty');
    } finally {
        isSaving = false;
        if (saveButton) saveButton.disabled = pendingAttachments.some(item => item.status === 'uploading');
        if (saveLabel) saveLabel.textContent = idleLabel;
    }
}

// ============ 本地草稿（仅新建模式） ============
function scheduleDraftSave() {
    if (!isCreateMode) return;
    clearTimeout(draftTimer);
    draftTimer = setTimeout(() => {
        const { title, category, mdContent } = collectDraftPayload();
        if (!title && !mdContent) { clearDraft(); return; }
        try {
            localStorage.setItem(ARTICLE_DRAFT_KEY, JSON.stringify({ title, category, content: mdContent, ts: Date.now() }));
            updateWorkspaceStatus(`草稿已自动保存 ${nowHHMM()}`, 'is-saved');
        } catch (e) { /* 存储不可用时静默 */ }
    }, 800);
}

function readDraft() {
    try {
        const raw = localStorage.getItem(ARTICLE_DRAFT_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
}

function clearDraft() {
    try { localStorage.removeItem(ARTICLE_DRAFT_KEY); } catch (e) { /* noop */ }
}

function offerDraftRestore() {
    const draft = readDraft();
    const bar = document.getElementById('draftBar');
    if (!draft || !bar) return;
    if (!(draft.content && draft.content.trim()) && !(draft.title && draft.title.trim())) return;
    bar.hidden = false;
    requestAnimationFrame(() => bar.classList.add('is-open'));
}

function initDraftBar() {
    const bar = document.getElementById('draftBar');
    if (!bar) return;
    document.getElementById('draftRestore')?.addEventListener('click', function () {
        const draft = readDraft();
        if (draft) {
            const titleEl = document.getElementById('editTitle');
            const catEl = document.getElementById('editCategory');
            const ta = document.getElementById('mdEditor');
            if (titleEl) titleEl.value = draft.title || '';
            if (catEl && draft.category) catEl.value = draft.category;
            if (ta) ta.value = draft.content || '';
            renderPreview();
            updateEditorStats();
            markClean('已恢复本地草稿');
        }
        hideDraftBar();
        document.getElementById('mdEditor')?.focus();
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

// ============ 拖拽/粘贴图片上传 ============
function initImageDrop(ta) {
    if (!ta || ta.dataset.imgDropBound) return;
    ta.dataset.imgDropBound = '1';
    let uploading = false;

    function insertAtCursor(url, alt) {
        ta.focus();
        const s = ta.selectionStart, e = ta.selectionEnd;
        const md = '![' + (alt || '图片') + '](' + url + ')';
        ta.setRangeText(md, s, e, 'end');
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
function initToolbar() {
    const bar = document.querySelector('.md-toolbar');
    if (!bar) return;
    const card = document.querySelector('.publishing-workspace__editor');
    const pw = card ? card.querySelector('.md-pane-wrap') : null;
    if (pw) pw.classList.add('has-toolbar');

    bar.querySelectorAll('.md-toolbar-btn').forEach(btn => {
        btn.addEventListener('click', function () {
            const action = btn.getAttribute('data-action');
            const ta = document.getElementById('mdEditor');
            if (!ta || !action) return;
            applyMdAction(ta, action);
        });
    });

    const mdEditor = document.getElementById('mdEditor');
    if (mdEditor) initImageDrop(mdEditor);
}

function applyMdAction(ta, action) {
    const start0 = ta.selectionStart;
    const end0 = ta.selectionEnd;
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

// ============ 字数 / 行数 / 预计阅读 ============
function countTextStats(value) {
    const raw = String(value || '');
    const chars = raw.replace(/\s/g, '').length;
    const lines = raw ? raw.split(/\r\n|\r|\n/).filter(l => l.trim() !== '').length : 0;
    const readMinutes = Math.max(1, Math.ceil(chars / 400));
    return { chars, lines, readMinutes };
}

function updateEditorStats() {
    const mdEditor = document.getElementById('mdEditor');
    const wordEl = document.getElementById('wsWordCount');
    const lineEl = document.getElementById('wsLineCount');
    const readEl = document.getElementById('wsReadTime');
    if (!mdEditor) return;
    const stats = countTextStats(mdEditor.value);
    if (wordEl) wordEl.textContent = stats.chars;
    if (lineEl) lineEl.textContent = stats.lines;
    if (readEl) readEl.textContent = stats.readMinutes;
}
