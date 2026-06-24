/**
 * 成果发布 / 编辑（与详情页展示字段一致）
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

    function parseDescription(raw) {
        var text = String(raw || '').trim();
        var category = '';
        var body = text;
        var m = text.match(/（分类：([^）]+)）/);
        if (m && m[1]) {
            category = m[1].trim();
            body = text.replace(m[0], '').trim();
        }
        var parts = body.split(/\n\s*\n/).map(function (p) {
            return p.trim();
        }).filter(Boolean);
        return {
            category: category,
            lead: parts[0] || '',
            insight: parts.length > 1 ? parts.slice(1).join('\n\n') : '',
            full: body
        };
    }

    function buildDescription(lead, insight, category) {
        var l = String(lead || '').trim();
        var i = String(insight || '').trim();
        var body = l;
        if (i) {
            body = body ? body + '\n\n' + i : i;
        }
        var cat = String(category || '').trim();
        if (!cat) {
            return body;
        }
        if (body.indexOf('（分类：') !== -1) {
            return body;
        }
        return body + '（分类：' + cat + '）';
    }

    function toast(msg, type) {
        if (global.ILink && global.ILink.showMessage) {
            global.ILink.showMessage(msg, type === 'error' ? 'error' : type === 'warn' ? 'warning' : 'success');
            return;
        }
        alert(msg);
    }

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
    }

    function fillForm(asset) {
        var parsed = parseDescription(asset.description || '');
        var title = $('pubTitle');
        var cat = $('pubCategory');
        var lead = $('pubDescLead');
        var insight = $('pubInsight');
        var hint = $('pubFileHint');
        if (title) title.value = asset.title || '';
        if (cat) {
            cat.value = asset.category || parsed.category || '其他';
        }
        if (lead) lead.value = parsed.lead || parsed.full || '';
        if (insight) insight.value = parsed.insight || '';
        if (hint) {
            if (asset.fileUrl) {
                var name = String(asset.fileUrl).split('/').pop() || '已上传附件';
                hint.textContent = '当前附件：' + name + '。不选择新文件则保留原附件。';
            } else {
                hint.textContent = '当前无附件，可选择文件上传。';
            }
        }
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
            toast('请填写成果名称', 'warn');
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
                toast(state.mode === 'edit' ? '保存成功' : '发布成功', 'ok');
                hideModal();
                resetForm();
                if (typeof state.onSuccess === 'function') {
                    state.onSuccess(j.data, state.mode);
                }
            } else {
                toast(j.message || '操作失败', 'error');
            }
        } catch (e) {
            console.error(e);
            toast('网络异常', 'error');
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
    }

    function openCreate(onSuccess) {
        state.mode = 'create';
        state.assetId = null;
        if (onSuccess) state.onSuccess = onSuccess;
        resetForm();
        setModalLabels('create');
        openModal();
    }

    function openEdit(asset, onSuccess) {
        if (!asset || !asset.id) return;
        state.mode = 'edit';
        state.assetId = asset.id;
        if (onSuccess) state.onSuccess = onSuccess;
        fillForm(asset);
        setModalLabels('edit');
        openModal();
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
