/**
 * 成果发布 / 编辑（Markdown 编辑器版 v2.0）
 * 三模式切换：分栏 / 编辑 / 预览
 */
(function (global) {
    'use strict';

    var state = {
        mode: 'create',
        assetId: null,
        onSuccess: null
    };

    function $(id) {
        return document.getElementById(id);
    }

    // ============ Markdown 解析/构建（保持向后兼容） ============
    function parseDescription(raw) {
        var text = String(raw || '').trim();
        var category = '';
        var body = text;
        var mdMatch = body.match(/^<!--md:([A-Za-z0-9+/=]+(?:\|[A-Za-z0-9+/=]*)?)-->/);
        if (mdMatch) {
            body = body.replace(mdMatch[0], '').trim();
        }
        var m = body.match(/（分类：([^）]+)）/);
        if (m && m[1]) {
            category = m[1].trim();
            body = body.replace(m[0], '').trim();
        }
        var lead = '', insight = '';
        if (mdMatch) {
            var mdParts = mdMatch[1].split('|');
            try { lead = decodeURIComponent(escape(atob(mdParts[0] || ''))) || ''; } catch(_) {}
            try { insight = decodeURIComponent(escape(atob(mdParts[1] || ''))) || ''; } catch(_) {}
        } else {
            var parts = body.split(/\n\s*\n/).map(function (p) {
                return p.trim();
            }).filter(Boolean);
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

    function buildDescription(lead, insight, category) {
        var l = String(lead || '').trim();
        var i = String(insight || '').trim();
        if (typeof marked !== 'undefined') {
            l = l ? marked.parse(l) : '';
            i = i ? marked.parse(i) : '';
        }
        var body = l;
        if (i) body = body ? body + '\n\n' + i : i;
        var mdSrc = [lead || '', insight || ''].map(function(s) {
            return btoa(unescape(encodeURIComponent(s)));
        }).join('|');
        body = '<!--md:' + mdSrc + '-->' + body;
        var cat = String(category || '').trim();
        if (!cat) return body;
        if (body.indexOf('（分类：') !== -1) return body;
        return body + '（分类：' + cat + '）';
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

    // ============ 三模式编辑器 ============

    function initMdPanes(modalEl) {
        if (!modalEl) modalEl = document.getElementById('assetPublishModal');
        if (!modalEl) return;

        // bind mode bar buttons
        modalEl.querySelectorAll('.md-mode-bar').forEach(function(bar) {
            bar.querySelectorAll('.md-mode-btn').forEach(function(btn) {
                if (btn.dataset.mdBound) return;
                btn.dataset.mdBound = '1';
                btn.addEventListener('click', function() {
                    var mode = btn.getAttribute('data-mode');
                    var group = bar.getAttribute('data-group');
                    setMdMode(group, mode, bar);
                });
            });
        });

        // bind textarea input → live preview
        ['pubDescLead', 'pubInsight'].forEach(function(id) {
            var ta = modalEl.querySelector('#' + id);
            if (!ta || ta.dataset.mdInputBound) return;
            ta.dataset.mdInputBound = '1';
            ta.addEventListener('input', function() {
                renderMdPreview(id);
            });
            // 拖拽/粘贴图片上传
            initImageDrop(ta);
        });
    }

    function getPaneWrap(group) {
        if (group === 'desc') return document.getElementById('pubDescPaneWrap');
        if (group === 'insight') return document.getElementById('pubInsightPaneWrap');
        return null;
    }

    function setMdMode(group, mode, barEl) {
        var wrap = getPaneWrap(group);
        if (!wrap) return;

        // update active button in this group
        var bar = barEl || wrap.parentElement.querySelector('.md-mode-bar');
        if (bar) {
            bar.querySelectorAll('.md-mode-btn').forEach(function(b) {
                b.classList.toggle('active', b.getAttribute('data-mode') === mode);
            });
        }

        wrap.classList.remove('split', 'edit-only', 'preview-only');
        wrap.classList.add(mode === 'split' ? 'split' : (mode === 'edit' ? 'edit-only' : 'preview-only'));

        // divider visibility
        var divider = wrap.querySelector('.md-divider');
        if (divider) divider.classList.toggle('visible', mode === 'split');

        // render preview when switching to split/preview
        if (mode === 'split' || mode === 'preview') {
            var textarea = wrap.querySelector('textarea');
            if (textarea) renderMdPreview(textarea.id);
        }
    }

    function renderMdPreview(textareaId) {
        var previewId;
        if (textareaId === 'pubDescLead') previewId = 'pubDescPreview';
        else if (textareaId === 'pubInsight') previewId = 'pubInsightPreview';
        else return;

        var ta = $(textareaId);
        var preview = $(previewId);
        if (!ta || !preview) return;

        var raw = ta.value || '';
        if (typeof marked !== 'undefined') {
            try { preview.innerHTML = marked.parse(raw); }
            catch (e) { preview.innerHTML = '<p style="color:var(--ui-danger)">渲染错误</p>'; }
        } else {
            preview.innerHTML = '<pre style="white-space:pre-wrap;font-family:inherit;">' + escapeHtml(raw) + '</pre>';
        }
    }

    // ============ 表单操作 ============
    function resetForm() {
        var title = $('pubTitle');
        var cat = $('pubCategory');
        var lead = $('pubDescLead');
        var insight = $('pubInsight');
        var file = $('pubFile');
        var hint = $('pubFileHint');
        if (title) title.value = '';
        if (cat) cat.value = '竞赛获奖';
        if (lead) lead.value = '';
        if (insight) insight.value = '';
        if (file) file.value = '';
        if (hint) hint.textContent = '支持 PDF、ZIP、图片等，便于他人下载参考。';

        // reset preview panes
        var descPrev = $('pubDescPreview');
        var insPrev = $('pubInsightPreview');
        if (descPrev) descPrev.innerHTML = '';
        if (insPrev) insPrev.innerHTML = '';

        // reset to split mode
        setMdMode('desc', 'split');
        setMdMode('insight', 'split');
    }

    function fillForm(asset) {
        var parsed = parseDescription(asset.description || '');
        var title = $('pubTitle');
        var cat = $('pubCategory');
        var lead = $('pubDescLead');
        var insight = $('pubInsight');
        var hint = $('pubFileHint');
        if (title) title.value = asset.title || '';
        if (cat) cat.value = asset.category || parsed.category || '其他';
        if (parsed.mdRaw) {
            var parts = parsed.mdRaw.split('|');
            if (lead) lead.value = decodeURIComponent(escape(atob(parts[0] || ''))) || '';
            if (insight) insight.value = decodeURIComponent(escape(atob(parts[1] || ''))) || '';
        } else {
            if (lead) lead.value = parsed.lead || parsed.full || '';
            if (insight) insight.value = parsed.insight || '';
        }
        if (hint) {
            if (asset.fileUrl) {
                var name = String(asset.fileUrl).split('/').pop() || '已上传附件';
                hint.textContent = '当前附件：' + name + '。不选择新文件则保留原附件。';
            } else {
                hint.textContent = '当前无附件，可选择文件上传。';
            }
        }
        // render initial previews
        setTimeout(function() {
            renderMdPreview('pubDescLead');
            renderMdPreview('pubInsight');
        }, 50);
    }

    function setModalLabels(mode) {
        var titleEl = $('assetPublishModalTitle');
        var btn = $('assetPublishSubmitBtn');
        if (titleEl) titleEl.textContent = mode === 'edit' ? '编辑成果' : '发布成果';
        if (btn) btn.textContent = mode === 'edit' ? '保存修改' : '发布成果';
    }

    function openModal() {
        var modal = $('assetPublishModal');
        if (modal && typeof bootstrap !== 'undefined') {
            bootstrap.Modal.getOrCreateInstance(modal).show();
        }
    }

    function hideModal() {
        var modal = $('assetPublishModal');
        if (modal && typeof bootstrap !== 'undefined') {
            var inst = bootstrap.Modal.getInstance(modal);
            if (inst) inst.hide();
        }
    }

    async function submit() {
        var title = ($('pubTitle') || {}).value;
        if (!title || !String(title).trim()) {
            showMessage('请填写成果名称', 'warning');
            return;
        }
        var category = ($('pubCategory') || {}).value || '';
        var lead = ($('pubDescLead') || {}).value || '';
        var insight = ($('pubInsight') || {}).value || '';
        var description = buildDescription(lead, insight, category);

        var fd = new FormData();
        fd.append('title', String(title).trim());
        fd.append('description', description);
        var fileInput = $('pubFile');
        if (fileInput && fileInput.files && fileInput.files[0]) {
            fd.append('file', fileInput.files[0]);
        }

        var url = state.mode === 'edit' && state.assetId
            ? '/api/asset/' + state.assetId
            : '/api/asset/upload';
        var method = state.mode === 'edit' ? 'PUT' : 'POST';

        try {
            var fetchFn = (global.ILink && typeof global.ILink.apiFetch === 'function')
                ? global.ILink.apiFetch
                : (typeof global.apiFetch === 'function' ? global.apiFetch : fetch);
            var r = await fetchFn(url, { method: method, body: fd, credentials: 'same-origin' });
            var j = await r.json();
            if (j.code === 200) {
                showMessage(state.mode === 'edit' ? '保存成功' : '发布成功', 'success');
                hideModal();
                resetForm();
                if (typeof state.onSuccess === 'function') {
                    state.onSuccess(j.data, state.mode);
                }
            } else {
                showMessage(j.message || '操作失败', 'error');
            }
        } catch (e) {
            console.error(e);
            showMessage('网络异常', 'error');
        }
    }

    function bind(options) {
        options = options || {};
        state.onSuccess = options.onSuccess || null;
        var btn = $('assetPublishSubmitBtn');
        if (btn && !btn.dataset.bound) {
            btn.dataset.bound = '1';
            btn.addEventListener('click', submit);
        }
        initMdPanes();
    }

    function openCreate(onSuccess) {
        state.mode = 'create';
        state.assetId = null;
        if (onSuccess) state.onSuccess = onSuccess;
        resetForm();
        setModalLabels('create');
        openModal();
        setTimeout(function() { initMdPanes(); }, 100);
    }

    function openEdit(asset, onSuccess) {
        if (!asset || !asset.id) return;
        state.mode = 'edit';
        state.assetId = asset.id;
        if (onSuccess) state.onSuccess = onSuccess;
        fillForm(asset);
        setModalLabels('edit');
        openModal();
        setTimeout(function() { initMdPanes(); }, 100);
    }

    global.AssetPublish = {
        parseDescription: parseDescription,
        buildDescription: buildDescription,
        bind: bind,
        openCreate: openCreate,
        openEdit: openEdit,
        submit: submit
    };
})(typeof window !== 'undefined' ? window : this);
