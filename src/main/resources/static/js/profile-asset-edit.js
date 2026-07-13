// 个人中心 · 编辑成果（Markdown 编辑器）
// 三模式切换：分栏 / 编辑 / 预览

let assetId = null;
let currentFile = null;
let currentFileUrl = null;

document.addEventListener('DOMContentLoaded', function () {
    const params = new URLSearchParams(window.location.search);
    const rawId = params.get('id');
    if (!rawId) {
        showMessage('缺少成果 id', 'error');
        setTimeout(function () { window.location.href = '/profile-honors.html'; }, 1500);
        return;
    }
    assetId = rawId;

    initMarked();
    initToolbars();
    initModeSwitches();
    initFileButton();
    initSaveButton();
    initPreviewLink();
    loadAsset();
});

// ============ Marked.js 配置 ============
function initMarked() {
    if (typeof marked === 'undefined') {
        console.warn('marked.js 未加载');
        return;
    }
    marked.setOptions({ gfm: true, breaks: true, pedantic: false });
    marked.use({
        renderer: {
            heading: function (text, level) { return '<h' + level + '>' + text + '</h' + level + '>'; },
            link: function (href, title, text) {
                var t = title ? ' title="' + title + '"' : '';
                var rel = href && /^https?:\/\//.test(href) ? ' target="_blank" rel="noopener noreferrer"' : '';
                return '<a href="' + href + '"' + t + rel + '>' + text + '</a>';
            },
            image: function (href, title, text) {
                var t = title ? ' title="' + title + '"' : '';
                return '<img src="' + href + '" alt="' + text + '"' + t + ' loading="lazy">';
            },
        },
    });
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
function initToolbars() {
    document.querySelectorAll('.md-toolbar').forEach(function (bar) {
        // add has-toolbar class to the sibling pane-wrap
        var pw = bar.nextElementSibling;
        while (pw && !pw.classList.contains('md-pane-wrap')) pw = pw.nextElementSibling;
        if (pw) pw.classList.add('has-toolbar');

        bar.querySelectorAll('.md-toolbar-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var action = btn.getAttribute('data-action');
                var ta = getToolbarTextarea(bar);
                if (!ta || !action) return;
                applyMarkdownAction(ta, action);
            });
        });

        // 拖拽/粘贴图片上传
        var ta = getToolbarTextarea(bar);
        if (ta) initImageDrop(ta);
    });
}

function getToolbarTextarea(bar) {
    var el = bar.nextElementSibling;
    // skip the md-mode-bar if present
    while (el && !el.classList.contains('md-pane-wrap')) el = el.nextElementSibling;
    if (!el) return null;
    return el.querySelector('textarea');
}

function applyMarkdownAction(ta, action) {
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
        // 选中内容变链接/图片
        if (sel) {
            rep = m.prefix + sel + m.suffix;
        } else {
            rep = m.prefix + (m.selectLabel || m.label) + m.suffix;
            var labelLen = (m.selectLabel || m.label).length;
            start = start + m.prefix.length;
            end = start + labelLen;
        }
    } else if (m.prefix != null) {
        // 行首添加前缀（标题、列表、引用）
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
    if (action !== 'hr') {
        // selection is preserved by 'end' mode
    }
    ta.dispatchEvent(new Event('input', { bubbles: true }));
}
function initModeSwitches() {
    document.querySelectorAll('.md-mode-bar').forEach(function (bar) {
        bar.querySelectorAll('.md-mode-btn').forEach(function (btn) {
            btn.addEventListener('click', function () {
                var mode = btn.getAttribute('data-mode');
                var wrapId = bar.id === 'mdModeBarInsight' ? 'insightPaneWrap' : 'descPaneWrap';
                switchMode(wrapId, mode, bar);
            });
        });
    });

    // textarea input → live preview
    var descTa = document.getElementById('mdDescEditor');
    var insTa = document.getElementById('mdInsightEditor');
    if (descTa) descTa.addEventListener('input', function () { renderPreview('mdDescEditor', 'mdDescPreview'); });
    if (insTa) insTa.addEventListener('input', function () { renderPreview('mdInsightEditor', 'mdInsightPreview'); });
}

function switchMode(wrapId, mode, barEl) {
    var wrap = document.getElementById(wrapId);
    if (!wrap) return;

    barEl.querySelectorAll('.md-mode-btn').forEach(function (b) {
        b.classList.toggle('active', b.getAttribute('data-mode') === mode);
    });

    wrap.classList.remove('split', 'edit-only', 'preview-only');
    wrap.classList.add(mode === 'split' ? 'split' : (mode === 'edit' ? 'edit-only' : 'preview-only'));

    var divider = wrap.querySelector('.md-divider');
    if (divider) divider.classList.toggle('visible', mode === 'split');

    if (mode === 'split' || mode === 'preview') {
        var ta = wrap.querySelector('textarea');
        if (ta && ta.id === 'mdDescEditor') renderPreview('mdDescEditor', 'mdDescPreview');
        else if (ta && ta.id === 'mdInsightEditor') renderPreview('mdInsightEditor', 'mdInsightPreview');
    }
}

function renderPreview(textareaId, previewId) {
    var ta = document.getElementById(textareaId);
    var preview = document.getElementById(previewId);
    if (!ta || !preview) return;

    var raw = ta.value || '';
    if (typeof marked !== 'undefined') {
        try { preview.innerHTML = marked.parse(raw); }
        catch (e) { preview.innerHTML = '<p style="color:var(--ui-danger)">渲染错误</p>'; }
    } else {
        preview.innerHTML = '<pre style="white-space:pre-wrap;font-family:inherit;">' + escapeHtml(raw) + '</pre>';
    }
}

// ============ 构建 description（兼容旧格式） ============
function buildDescription(lead, insight, category) {
    var l = String(lead || '').trim();
    var i = String(insight || '').trim();
    if (typeof marked !== 'undefined') {
        l = l ? marked.parse(l) : '';
        i = i ? marked.parse(i) : '';
    }
    var body = l;
    if (i) body = body ? body + '\n\n' + i : i;
    var mdSrc = [lead || '', insight || ''].map(function (s) {
        return btoa(unescape(encodeURIComponent(s)));
    }).join('|');
    body = '<!--md:' + mdSrc + '-->' + body;
    var cat = String(category || '').trim();
    if (!cat) return body;
    if (body.indexOf('（分类：') !== -1) return body;
    return body + '（分类：' + cat + '）';
}

function parseDescription(raw) {
    var text = String(raw || '').trim();
    var category = '';
    var body = text;
    var mdMatch = body.match(/^<!--md:([A-Za-z0-9+/=]+(?:\|[A-Za-z0-9+/=]*)?)-->/);
    if (mdMatch) body = body.replace(mdMatch[0], '').trim();
    var m = body.match(/（分类：([^）]+)）/);
    if (m && m[1]) { category = m[1].trim(); body = body.replace(m[0], '').trim(); }

    var lead = '', insight = '';
    if (mdMatch) {
        // 从 base64 直接解码，避免 \n\n 拆分导致的跨字段串扰
        var parts = mdMatch[1].split('|');
        try { lead = decodeURIComponent(escape(atob(parts[0] || ''))) || ''; } catch(_) {}
        try { insight = decodeURIComponent(escape(atob(parts[1] || ''))) || ''; } catch(_) {}
    } else {
        // 旧格式兼容：按 \n\n 拆分
        var parts = body.split(/\n\s*\n/).map(function (p) { return p.trim(); }).filter(Boolean);
        lead = parts[0] || '';
        insight = parts.length > 1 ? parts.slice(1).join('\n\n') : '';
    }
    return {
        category: category,
        lead: lead,
        insight: insight,
        full: body,
        mdRaw: mdMatch ? mdMatch[1] : null
    };
}

// ============ 加载成果 ============
async function loadAsset() {
    try {
        var r = await apiFetch('/api/asset/list?page=1&size=100', { credentials: 'same-origin' });
        var d = await r.json();
        if (d.code !== 200) { showMessage(d.message || '加载失败', 'error'); return; }
        var asset = (d.data || []).find(function (a) { return String(a.id) === String(assetId); });
        if (!asset) { showMessage('成果不存在', 'error'); return; }

        fillForm(asset);
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

function fillForm(asset) {
    var parsed = parseDescription(asset.description || '');
    var titleEl = document.getElementById('editTitle');
    var catEl = document.getElementById('editCategory');
    var descEl = document.getElementById('mdDescEditor');
    var insEl = document.getElementById('mdInsightEditor');

    if (titleEl) titleEl.value = asset.title || '';
    if (catEl) catEl.value = asset.category || parsed.category || '其他';
    if (parsed.mdRaw) {
        var parts = parsed.mdRaw.split('|');
        if (descEl) descEl.value = decodeURIComponent(escape(atob(parts[0] || ''))) || '';
        if (insEl) insEl.value = decodeURIComponent(escape(atob(parts[1] || ''))) || '';
    } else {
        if (descEl) descEl.value = parsed.lead || parsed.full || '';
        if (insEl) insEl.value = parsed.insight || '';
    }

    if (asset.fileUrl) {
        currentFileUrl = asset.fileUrl;
        var name = String(asset.fileUrl).split('/').pop() || '已上传附件';
        document.getElementById('editFileHint').textContent = '当前附件：' + name + '。不选择新文件则保留原附件。';
    }

    // 设置预览链接
    var previewLink = document.getElementById('editPreviewLink');
    if (previewLink) previewLink.href = '/asset-detail.html?id=' + assetId;

    // 渲染初始预览
    setTimeout(function () {
        renderPreview('mdDescEditor', 'mdDescPreview');
        renderPreview('mdInsightEditor', 'mdInsightPreview');
    }, 100);
}

// ============ 文件 ============
function initFileButton() {
    document.getElementById('editFileBtn').addEventListener('click', function () {
        document.getElementById('editFileInput').click();
    });
    document.getElementById('editFileInput').addEventListener('change', function () {
        currentFile = this.files && this.files[0];
        if (currentFile) {
            document.getElementById('editFileHint').textContent = '已选择：' + currentFile.name;
        }
    });
}

function initPreviewLink() {
    var link = document.getElementById('editPreviewLink');
    if (link && assetId) link.href = '/asset-detail.html?id=' + assetId;
}

// ============ 保存 ============
function initSaveButton() {
    document.getElementById('editSaveBtn').addEventListener('click', saveAsset);
}

async function saveAsset() {
    var title = document.getElementById('editTitle').value.trim();
    if (!title) { showMessage('请填写成果名称', 'warning'); return; }

    var category = document.getElementById('editCategory').value || '';
    var lead = document.getElementById('mdDescEditor').value || '';
    var insight = document.getElementById('mdInsightEditor').value || '';
    var description = buildDescription(lead, insight, category);

    var fd = new FormData();
    fd.append('title', title);
    fd.append('description', description);
    if (currentFile) fd.append('file', currentFile);

    try {
        var r = await apiFetch('/api/asset/' + assetId, {
            method: 'PUT',
            body: fd,
            credentials: 'same-origin',
        });
        var j = await r.json();
        if (j.code === 200) {
            showMessage('保存成功', 'success');
            setTimeout(function () { window.location.href = '/profile-honors.html'; }, 600);
        } else {
            showMessage(j.message || '保存失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}
