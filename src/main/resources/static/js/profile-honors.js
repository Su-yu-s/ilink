(function() {
    'use strict';

    var DRAFT_KEY = 'ilink-honors-v4';
    var TYPE_MAP = { competition:'竞赛获奖', scholarship:'奖学金', honor:'荣誉称号', paper:'论文发表', research:'科研项目', work:'作品/项目', other:'其他' };
    var LEVEL_MAP = { school:'校级', provincial:'省级', national:'国家级', international:'国际级', other:'其他' };
    var honors = [];
    var manageMode = false;

    function $(id) { return document.getElementById(id); }

    function toast(msg, type) {
        var map = { ok: 'success', err: 'error', warn: 'warning' };
        if (typeof showMessage === 'function') {
            showMessage(msg, map[type] || type || 'info');
        }
    }

    function newId() { return 'h_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8); }
    function norm(h) { if(!h) h={}; return { id:String(h.id||newId()), type:h.type||'other', title:h.title||'', level:h.level||'', issuer:h.issuer||'', period:h.period||'', detail:h.detail||'', proofUrl:h.proofUrl||'', awardRank:h.awardRank||'', teamScope:h.teamScope||'' }; }
    function esc(s) { return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }
    function parseList(raw) { if(!raw||!String(raw).trim()) return []; try { var a=JSON.parse(raw); return Array.isArray(a)?a:[]; } catch(e){ return []; } }
    function loadDraft() { try { var r=localStorage.getItem(DRAFT_KEY); return r?JSON.parse(r):null; } catch(e){ return null; } }
    function saveDraft() { try { localStorage.setItem(DRAFT_KEY, JSON.stringify(honors)); } catch(e){} }
    function clearDraft() { try { localStorage.removeItem(DRAFT_KEY); } catch(e){} }

    function honorLevelTierClass(level) {
        if(!level) return 'tier-none';
        var k = String(level).toLowerCase();
        if(k==='international') return 'tier-international';
        if(k==='national') return 'tier-national';
        if(k==='provincial') return 'tier-provincial';
        if(k==='school') return 'tier-school';
        return 'tier-other';
    }

    function updateNavbar(user) {
        var auth = $('authArea'), ua = $('userArea');
        if(!user) { if(auth) auth.style.display=''; if(ua) ua.style.display='none'; return; }
        if(auth) auth.style.display='none';
        if(ua) ua.style.display='';
        var name = user.realName || user.username || '用户';
        var nm = $('userName'); if(nm) nm.textContent = name;
        var fb = $('userAvatarFb'); if(fb) fb.textContent = name.charAt(0).toUpperCase();
        var av = $('userAvatar'), af = $('userAvatarFb');
        if(user.avatar && String(user.avatar).trim()) {
            if(av) { av.src = user.avatar; av.style.display = ''; }
            if(af) af.style.display = 'none';
        } else {
            if(av) av.style.display = 'none';
            if(af) af.style.display = 'flex';
        }
    }

    function honorProofMediaKind(url) {
        var s = String(url || '').trim().toLowerCase();
        if (!s) return 'none';
        var path = s.split('?')[0].split('#')[0];
        if (/\.(jpe?g|png|gif|webp|bmp|svg|avif|jfif|heic|heif)$/i.test(path)) return 'image';
        if (/\.pdf$/i.test(path)) return 'pdf';
        return 'link';
    }

    function honorProofSafeUrl(url) {
        var s = String(url || '').trim();
        if (!s) return '';
        try { return encodeURI(decodeURI(s)); }
        catch (_e) { try { return encodeURI(s); } catch (_e2) { return s; } }
    }

    function honorProofImageOnError(img) {
        if (!img) return;
        var tried = img.getAttribute('data-encoded-retry') === '1';
        if (!tried) {
            img.setAttribute('data-encoded-retry', '1');
            var raw = img.getAttribute('data-raw-src') || img.src || '';
            var retry = honorProofSafeUrl(raw);
            if (retry && retry !== img.src) { img.src = retry; return; }
        }
        var p = img.parentNode;
        if (p) p.classList.add('honors-preview-proof--broken');
        img.style.display = 'none';
    }

    /** 证明材料预览（图片缩略图 / PDF 图标 / 通用链接） */
    function honorProofAsideHtml(proofUrl) {
        var u = String(proofUrl || '').trim();
        if (!u) return '';
        var safe = esc(u);
        var kind = honorProofMediaKind(u);
        if (kind === 'image') {
            var imgSrc = esc(honorProofSafeUrl(u));
            return '<div class="honors-preview-card__proof il-honor-item__proof">' +
                '<a class="honors-preview-proof honors-preview-proof--image" href="' + safe + '" target="_blank" rel="noopener" title="查看证明材料">' +
                '<img class="honors-preview-proof__img" src="' + imgSrc + '" data-raw-src="' + safe + '" alt="证明材料缩略图" loading="lazy" onerror="honorProofImageOnError(this)">' +
                '</a></div>';
        }
        if (kind === 'pdf') {
            return '<div class="honors-preview-card__proof il-honor-item__proof">' +
                '<a class="honors-preview-proof honors-preview-proof--pdf" href="' + safe + '" target="_blank" rel="noopener">' +
                '<span class="honors-preview-proof__pdf-badge" aria-hidden="true">PDF</span>' +
                '<span class="honors-preview-proof__pdf-label">证明文件</span>' +
                '</a></div>';
        }
        return '<div class="honors-preview-card__proof il-honor-item__proof">' +
            '<a class="honors-preview-proof honors-preview-proof--link" href="' + safe + '" target="_blank" rel="noopener">' +
            '<span class="honors-preview-proof__link-label">证明材料</span>' +
            '</a></div>';
    }

    function render() {
        var container = $('honorsEditor');
        if(!container) return;

        if(!honors.length) {
            container.innerHTML = '<div class="honors-empty"><div class="honors-empty__icon"><svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 3l2.4 7.4H22l-6 4.6 2.3 7L12 17.8 5.7 22 8 14.4 2 10.6h7.6L12 3z"/></svg></div><p class="honors-empty__title">暂无成果条目</p><p class="honors-empty__text">点击「添加成果」开始记录您的获奖与荣誉</p></div>';
            return;
        }

        var html = '';
        for(var i = 0; i < honors.length; i++) {
            var h = norm(honors[i]);
            var t = TYPE_MAP[h.type] || '其他';
            var lv = LEVEL_MAP[h.level] || '';
            var tier = honorLevelTierClass(h.level);
            var parts = [];
            if(h.awardRank) parts.push(h.awardRank);
            if(h.issuer) parts.push(h.issuer);
            if(h.period) parts.push(h.period);
            var meta = parts.join(' · ');
            var proofAside = honorProofAsideHtml(h.proofUrl);

            html += '<div class="honor-list-card honors-editor-block" data-honor-id="' + esc(h.id) + '">' +
                '<div class="il-honor-item' + (proofAside ? ' il-honor-item--with-proof' : '') + '">' +
                    '<input type="checkbox" class="honor-select il-honor-item__check" aria-label="选择成果">' +
                    '<div class="il-honor-item__main">' +
                        '<div class="il-honor-item__tags">' +
                            '<span class="honor-chip honor-chip--type honor-chip--t-' + h.type + '">' + esc(t) + '</span>' +
                            (lv ? '<span class="honor-level-pill ' + tier + '">' + esc(lv) + '</span>' : '') +
                        '</div>' +
                        '<div class="il-honor-item__title">' + esc(h.title || '（未命名）') + '</div>' +
                        (meta ? '<div class="il-honor-item__meta">' + esc(meta) + '</div>' : '') +
                    '</div>' +
                    '<div class="il-honor-item__actions">' +
                        '<button type="button" class="il-btn il-btn-ghost il-btn-sm btn-edit" data-eid="' + esc(h.id) + '">编辑</button>' +
                    '</div>' +
                    (proofAside ? proofAside : '') +
                '</div></div>';
        }
        container.innerHTML = html;
    }

    function updateBatch() {
        var cbs = document.querySelectorAll('.honor-select:checked');
        var cEl = $('count'); if(cEl) cEl.textContent = cbs.length;
        var dBtn = $('btnDel'); if(dBtn) dBtn.disabled = cbs.length === 0;
        var all = document.querySelectorAll('.honor-select');
        var ca = $('checkAll');
        if(ca) { ca.checked = all.length > 0 && cbs.length === all.length; ca.indeterminate = cbs.length > 0 && cbs.length < all.length; }
    }

    function syncSections() {
        var sel = $('selType'); if(!sel) return;
        var v = sel.value;
        var map = { competition:'竞赛/作品名称', scholarship:'奖学金名称', honor:'称号名称', paper:'论文标题', research:'课题/项目名称', work:'作品/项目名称', other:'成果名称' };
        var lbl = $('lblTitle'); if(lbl) lbl.textContent = map[v] || '名称';
        var sects = document.querySelectorAll('.honors-section');
        for(var i = 0; i < sects.length; i++) {
            var types = (sects[i].getAttribute('data-section') || '').split(/\s+/).filter(Boolean);
            sects[i].style.display = types.indexOf(v) >= 0 ? '' : 'none';
        }
    }

    function openModal(honorId) {
        var ids = ['hid','txtTitle','txtIssuer','txtDetail','txtProofUrl','txtPeriod'];
        for(var i = 0; i < ids.length; i++) { var el = $(ids[i]); if(el) el.value = ''; }
        if($('selType')) $('selType').value = 'competition';
        if($('selLevel')) $('selLevel').value = '';
        if($('selRank')) $('selRank').value = '';
        if($('selTeam')) $('selTeam').value = '';
        if($('proofStatus')) $('proofStatus').textContent = '';
        if($('modalTitle')) $('modalTitle').textContent = honorId ? '编辑成果' : '添加成果';
        var fm = $('txtMonth'); if(fm && !honorId) fm.value = new Date().toISOString().slice(0, 7);
        updateProofPreview('');

        if(honorId) {
            var targetId = String(honorId);
            var found = null;
            for(var j = 0; j < honors.length; j++) { if(String(honors[j].id) === targetId) { found = norm(honors[j]); break; } }
            if(found) {
                var sv = function(id, val) { var el = $(id); if(el) el.value = val || ''; };
                sv('hid', found.id); sv('selType', found.type); sv('txtTitle', found.title);
                sv('selRank', found.awardRank); sv('selTeam', found.teamScope); sv('selLevel', found.level);
                sv('txtIssuer', found.issuer); sv('txtDetail', found.detail); sv('txtProofUrl', found.proofUrl);
                updateProofPreview(found.proofUrl);
                var p = (found.period || '').trim();
                if(/^\d{4}-\d{2}$/.test(p)) { sv('txtMonth', p); sv('txtPeriod', ''); }
                else { sv('txtMonth', ''); sv('txtPeriod', p); }
            }
        }
        syncSections();
        var modalEl = $('honorModal');
        if(modalEl && typeof bootstrap !== 'undefined') {
            bootstrap.Modal.getOrCreateInstance(modalEl).show();
            setTimeout(function() { var tf = $('txtTitle'); if(tf) tf.focus(); }, 300);
        }
    }

    function readForm() {
        var gv = function(id) { var el = $(id); return el ? el.value.trim() : ''; };
        return norm({
            id: gv('hid') || newId(), type: ($('selType') && $('selType').value) || 'other',
            title: gv('txtTitle'), level: ($('selLevel') && $('selLevel').value) || '',
            issuer: gv('txtIssuer'), period: gv('txtMonth') || gv('txtPeriod'),
            detail: gv('txtDetail'), proofUrl: gv('txtProofUrl'),
            awardRank: ($('selRank') && $('selRank').value) || '',
            teamScope: ($('selTeam') && $('selTeam').value) || ''
        });
    }

    function commitModal(closeAfter) {
        var h = readForm();
        if(!h.title) { toast('请填写名称', 'warn'); return; }
        var idx = -1;
        for(var i = 0; i < honors.length; i++) { if(String(honors[i].id) === String(h.id)) { idx = i; break; } }
        if(idx >= 0) { honors[idx] = h; } else { honors.push(h); }
        render(); saveDraft();
        if(closeAfter) {
            var mel = $('honorModal');
            if(mel && typeof bootstrap !== 'undefined') { var inst = bootstrap.Modal.getInstance(mel); if(inst) inst.hide(); }
        } else {
            var curType = ($('selType') && $('selType').value) || 'competition';
            ['hid','txtTitle','txtIssuer','txtDetail','txtProofUrl','txtPeriod'].forEach(function(id) { var el = $(id); if(el) el.value = ''; });
            if($('fileProof')) $('fileProof').value = '';
            if($('proofStatus')) $('proofStatus').textContent = '';
            if($('selRank')) $('selRank').value = '';
            if($('selTeam')) $('selTeam').value = '';
            if($('selLevel')) $('selLevel').value = '';
            var fm2 = $('txtMonth'); if(fm2) fm2.value = new Date().toISOString().slice(0, 7);
            if($('modalTitle')) $('modalTitle').textContent = '添加成果';
            if($('selType')) $('selType').value = curType;
            syncSections();
            var tf2 = $('txtTitle'); if(tf2) tf2.focus();
        }
    }

    function uploadFile(file) {
        if(!file) return;
        var st = $('proofStatus'); if(st) st.textContent = '上传中...';
        var fd = new FormData(); fd.append('file', file);
        apiFetch('/api/upload/attachment?kind=proof', { method:'POST', body:fd, credentials:'same-origin' })
            .then(function(r) { return r.json(); })
            .then(function(result) {
                if(Number(result.code) === 200 && result.data && (result.data.url || result.data.URL)) {
                    var url = result.data.url || result.data.URL;
                    var pfu = $('txtProofUrl'); if(pfu) pfu.value = url;
                    updateProofPreview(url);
                    if(st) st.textContent = '已上传'; toast('上传成功', 'ok');
                } else { if(st) st.textContent = ''; toast(result.message || '上传失败', 'err'); }
            })
            .catch(function() { if(st) st.textContent = ''; toast('上传异常', 'err'); });
    }

    function saveToServer() {
        var btn = $('btnSave'); if(!btn) return;
        btn.disabled = true; btn.textContent = '保存中...';
        apiFetch('/api/user/profile', { method:'POST', headers:{'Content-Type':'application/json'}, credentials:'same-origin', body:JSON.stringify({ honors: JSON.stringify(honors) }) })
            .then(function(r) { return r.json(); })
            .then(function(result) {
                if(Number(result.code) === 200) { toast('保存成功', 'ok'); clearDraft(); }
                else if(Number(result.code) === 401) { toast('请先登录', 'warn'); setTimeout(function() { window.location.href = '/login.html'; }, 1500); }
                else { toast('保存失败: ' + (result.message || ''), 'err'); }
            })
            .catch(function() { toast('网络异常', 'err'); })
            .finally(function() { btn.disabled = false; btn.textContent = '保存成果'; });
    }

    function loadData() {
        apiFetch('/api/user/profile', { credentials: 'same-origin' })
            .then(function(r) {
                var ct = r.headers.get('content-type') || '';
                if(!ct.includes('application/json')) throw new Error('NOT_JSON');
                return r.json();
            })
            .then(function(result) {
                if(Number(result.code) === 200 && result.data) {
                    var serverList = parseList(result.data.honors).map(norm);
                    var draft = loadDraft();
                    honors = (draft && draft.length > 0) ? draft.map(norm) : serverList;
                    updateNavbar(result.data);
                } else if(Number(result.code) === 401) {
                    toast('请先登录', 'warn');
                    setTimeout(function() { window.location.href = '/login.html'; }, 1500);
                    return;
                }
                render();
            })
            .catch(function(err) {
                if(err.message === 'NOT_JSON') {
                    toast('登录状态异常，请重新登录', 'warn');
                    setTimeout(function() { window.location.href = '/login.html'; }, 1500);
                    return;
                }
                var draft = loadDraft();
                if(draft && draft.length > 0) { honors = draft.map(norm); render(); toast('已加载本地缓存', 'warn'); }
            });
    }

    function bindEvents() {
        var selType = $('selType'); if(selType) selType.onchange = syncSections;
        var btnAdd = $('btnAdd'); if(btnAdd) btnAdd.onclick = function() { openModal(null); };

        var btnManage = $('btnManage');
        if(btnManage) btnManage.onclick = function() {
            manageMode = !manageMode;
            btnManage.textContent = manageMode ? '完成管理' : '管理成果';
            btnManage.classList.toggle('is-active', manageMode);
            var section = document.getElementById('honorsListSection');
            if(section) {
                if(manageMode) section.classList.add('honors-manage-mode');
                else section.classList.remove('honors-manage-mode');
            }
            var cbs = document.querySelectorAll('.honor-select');
            for(var i = 0; i < cbs.length; i++) { if(!manageMode) cbs[i].checked = false; }
            var ca = $('checkAll'); if(ca) { ca.checked = false; ca.indeterminate = false; }
            updateBatch();
        };

        var checkAll = $('checkAll');
        if(checkAll) checkAll.onchange = function() {
            var on = this.checked, cbs = document.querySelectorAll('.honor-select');
            for(var i = 0; i < cbs.length; i++) cbs[i].checked = on;
            updateBatch();
        };

        var btnDel = $('btnDel');
        if(btnDel) btnDel.onclick = function() {
            var ids = [], cbs = document.querySelectorAll('.honor-select:checked');
            for(var i = 0; i < cbs.length; i++) {
                var card = cbs[i].closest('.honor-list-card');
                if(card) ids.push(card.getAttribute('data-honor-id'));
            }
            if(!ids.length) return;
            if(!confirm('确定删除选中的 ' + ids.length + ' 条成果？')) return;
            honors = honors.filter(function(h) { return ids.indexOf(h.id) === -1; });
            render(); saveDraft(); updateBatch();
            toast('已删除 ' + ids.length + ' 条', 'ok');
        };

        var editorEl = $('honorsEditor');
        if(editorEl) {
            editorEl.addEventListener('click', function(e) {
                var btn = e.target.closest('.btn-edit');
                if(btn) { var id = btn.getAttribute('data-eid'); if(id) openModal(id); }
            });
            editorEl.addEventListener('change', function(e) {
                if(e.target.classList.contains('honor-select')) updateBatch();
            });
        }

        var btnModalSave = $('btnModalSave'); if(btnModalSave) btnModalSave.onclick = function() { commitModal(true); };
        var btnSaveMore = $('btnSaveMore'); if(btnSaveMore) btnSaveMore.onclick = function() { commitModal(false); };

        var dropzone = $('dropzone'), fileInput = $('fileProof');
        if(dropzone && fileInput) {
            dropzone.onclick = function() { fileInput.click(); };
            fileInput.onchange = function() { var f = fileInput.files && fileInput.files[0]; fileInput.value = ''; uploadFile(f); };
            ['dragenter','dragover'].forEach(function(ev) {
                dropzone.addEventListener(ev, function(e) { e.preventDefault(); e.stopPropagation(); dropzone.classList.add('active'); });
            });
            dropzone.addEventListener('dragleave', function(e) { e.preventDefault(); dropzone.classList.remove('active'); });
            dropzone.addEventListener('drop', function(e) { e.preventDefault(); e.stopPropagation(); dropzone.classList.remove('active'); var f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0]; uploadFile(f); });
        }

        // 证明材料输入框变更时，实时预览
        var txtProof = $('txtProofUrl');
        if(txtProof && txtProof.dataset.honorsProofBound !== '1') {
            txtProof.dataset.honorsProofBound = '1';
            txtProof.addEventListener('input', function() { updateProofPreview(this.value); });
        }

        var btnSave = $('btnSave'); if(btnSave) btnSave.onclick = saveToServer;
    }

    /** 根据 URL 实时预览证明材料（图片/PDF/无） */
    function updateProofPreview(url) {
        var box = $('proofPreviewBox'), img = $('proofPreviewImg'), link = $('proofPreviewLink');
        var pdfInd = $('proofPdfIndicator');
        var pst = $('proofStatus');
        if(box) box.style.display = 'none';
        if(pdfInd) pdfInd.style.display = 'none';
        if(link) link.href = '#';
        var u = String(url || '').trim();
        if(!u) {
            if(pst) pst.textContent = '';
            return;
        }
        if(pst) pst.textContent = '已有关联链接';
        var kind = honorProofMediaKind(u);
        if(kind === 'image') {
            if(img) { img.src = honorProofSafeUrl(u); img.setAttribute('data-raw-src', u); }
            if(link) link.href = esc(u);
            if(box) box.style.display = '';
        } else if(kind === 'pdf') {
            if(pdfInd) pdfInd.style.display = '';
            if(link) link.href = esc(u);
        } else {
            if(link) link.href = esc(u);
        }
    }

    document.addEventListener('DOMContentLoaded', function() {
        loadData();
        bindEvents();
    });
})();
