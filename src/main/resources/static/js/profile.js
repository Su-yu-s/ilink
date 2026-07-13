// Profile center: overview, profile details, and personal achievements.

async function uploadAttachmentFile(file, kind) {
    const fd = new FormData();
    fd.append('file', file);
    const res = await apiFetch(`/api/upload/attachment?kind=${encodeURIComponent(kind)}`, {
        method: 'POST',
        body: fd,
        credentials: 'same-origin'
    });
    const text = await res.text();
    try {
        return JSON.parse(text);
    } catch (e) {
        if (res.status === 401 || res.status === 403) {
            return { code: res.status, message: '请重新登录后再试' };
        }
        if (res.status === 413) {
            return { code: 413, message: '文件过大，请上传小于 10MB 的文件' };
        }
        return { code: res.ok ? 200 : res.status || 500, message: '服务器响应异常，请确认已登录且接口可用' };
    }
}

function parseHonorsJson(str) {
    if (!str || !String(str).trim()) return [];
    try {
        const a = JSON.parse(str);
        return Array.isArray(a) ? a : [];
    } catch (e) {
        return [];
    }
}

function honorTypeLabel(type) {
    const m = {
        scholarship: '奖学金',
        honor: '荣誉称号',
        competition: '竞赛获奖',
        paper: '论文发表',
        research: '科研项目',
        work: '作品 / 项目',
        other: '其他'
    };
    return m[type] || '其他';
}

function honorLevelLabel(level) {
    const m = {
        school: '校级',
        provincial: '省级',
        national: '国家级',
        international: '国际级',
        other: '其他'
    };
    return m[level] || '';
}

/** 概览 / 预览列表：按级别挂色（级别越高视觉越「重」） */
function honorLevelTierClass(level) {
    if (!level) return 'honor-level-item--tier-none';
    const k = String(level).toLowerCase().trim();
    if (k === 'international') return 'honor-level-item--tier-international';
    if (k === 'national') return 'honor-level-item--tier-national';
    if (k === 'provincial') return 'honor-level-item--tier-provincial';
    if (k === 'school') return 'honor-level-item--tier-school';
    if (k === 'other') return 'honor-level-item--tier-other';
    return 'honor-level-item--tier-other';
}

function newHonorId() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    return 'h' + Date.now() + Math.random().toString(16).slice(2);
}

function normalizeHonor(x) {
    return {
        id: x.id || newHonorId(),
        type: x.type || 'other',
        title: x.title || '',
        level: x.level || '',
        issuer: x.issuer || '',
        period: x.period || '',
        detail: x.detail || '',
        proofUrl: x.proofUrl || '',
        awardRank: x.awardRank || '',
        teamScope: x.teamScope || '',
        amount: x.amount || '',
        venue: x.venue || '',
        doi: x.doi || '',
        validUntil: x.validUntil || ''
    };
}

const HONORS_DRAFT_KEY = 'ilink-honors-draft-v2';

let honorsState = [];
/** 概览区「我的组队 / 我的申请」与统计用，保存后沿用避免闪烁 */
let lastActivity = { published: [], applications: [] };

function teamDemandStatusLabel(status) {
    if (status === 'OPEN') return '招募中';
    if (status === 'CLOSED') return '已结束';
    if (status === 'TEAMING') return '已组队';
    return status || '';
}

function teamApplicationStatusLabel(status) {
    const m = { PENDING: '待审核', APPROVED: '已通过', REJECTED: '未通过' };
    return m[status] || status || '';
}

async function fetchProfileActivity() {
    try {
        const [r1, r2] = await Promise.all([apiFetch('/api/team/my/published'), apiFetch('/api/team/my/applications')]);
        const j1 = await r1.json();
        const j2 = await r2.json();
        const published = j1.code === 200 && Array.isArray(j1.data) ? j1.data : [];
        const applications = j2.code === 200 && Array.isArray(j2.data) ? j2.data : [];
        return { published, applications };
    } catch (e) {
        console.warn('加载组队活动失败', e);
        return { published: [], applications: [] };
    }
}

/** 成果保存前校验 */
function validateHonorsBeforeSave() {
    for (let i = 0; i < honorsState.length; i++) {
        const x = normalizeHonor(honorsState[i]);
        const hasOther =
            x.issuer ||
            x.period ||
            x.detail ||
            x.proofUrl ||
            x.awardRank ||
            x.amount ||
            x.venue ||
            x.doi ||
            x.validUntil ||
            x.teamScope;
        if (hasOther && !String(x.title || '').trim()) {
            return '请填写成果名称，或清空该条其它内容后删除';
        }
    }
    return null;
}

function collectHonorsFromState() {
    return honorsState.map((h) => normalizeHonor(h));
}

function honorsEmptyEditorHtml() {
    return `<div class="honors-editor-empty honors-editor-empty--card" role="status">
            <p class="honors-editor-empty__title">暂无成果条目</p>
            <p class="honors-editor-empty__text">点击「添加成果」在弹出窗口中选择类型并填写；加入列表后点「保存成果」同步账户。</p>
        </div>`;
}

function persistHonorsDraft() {
    try {
        localStorage.setItem(HONORS_DRAFT_KEY, JSON.stringify(honorsState));
    } catch (e) {
        /* ignore */
    }
}

function updateHonorsBatchToolbar() {
    const ed = document.getElementById('honorsEditor');
    const batchBtn = document.getElementById('batchDeleteHonorsBtn');
    const selectAll = document.getElementById('honorSelectAll');
    if (!batchBtn) return;
    const rows = ed ? ed.querySelectorAll('.honor-list-card') : [];
    const n = rows.length;
    const checked = ed ? ed.querySelectorAll('.honor-select:checked').length : 0;
    batchBtn.disabled = checked === 0;
    if (selectAll) {
        selectAll.disabled = n === 0;
        if (n === 0) {
            selectAll.checked = false;
            selectAll.indeterminate = false;
        } else {
            selectAll.checked = checked === n;
            selectAll.indeterminate = checked > 0 && checked < n;
        }
    }
}

function honorCardSubtitle(x) {
    const h = normalizeHonor(x);
    const parts = [];
    if (h.awardRank) parts.push(h.awardRank);
    if (h.teamScope === 'TEAM') parts.push('团队');
    if (h.teamScope === 'INDIVIDUAL') parts.push('个人');
    if (h.period) parts.push(h.period);
    if (h.amount && h.type === 'scholarship') parts.push(h.amount);
    if (h.venue && h.type === 'paper') parts.push(h.venue);
    return parts.join(' · ');
}

function renderHonorCard(h) {
    const x = normalizeHonor(h);
    const idAttr = escapeHtml(String(x.id));
    const tier = honorLevelTierClass(x.level);
    const typeLb = honorTypeLabel(x.type);
    const lv = x.level ? honorLevelLabel(x.level) : '';
    const sub = honorCardSubtitle(x);
    const subHtml = sub ? `<div class="honor-list-card__meta small text-muted">${escapeHtml(sub)}</div>` : '';
    return `
        <div class="honor-list-card honors-editor-block" data-honor-id="${idAttr}">
            <div class="honor-list-card__inner d-flex gap-2 align-items-start">
                <input type="checkbox" class="form-check-input honor-select honor-select-row flex-shrink-0 mt-1" aria-label="选择本条成果">
                <div class="flex-grow-1 min-w-0">
                    <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
                        <span class="honor-chip honor-chip--type honor-chip--t-${escapeHtml(x.type)}">${escapeHtml(typeLb)}</span>
                        ${
                            lv
                                ? `<span class="honor-chip honor-chip--lvl honor-level-pill ${tier}">${escapeHtml(lv)}</span>`
                                : ''
                        }
                    </div>
                    <div class="honor-list-card__title">${escapeHtml((x.title || '').trim() || '（未命名）')}</div>
                    ${subHtml}
                </div>
                <div class="honor-list-card__actions flex-shrink-0 d-flex gap-1">
                    <button type="button" class="btn btn-sm btn-outline-primary honor-btn-edit">编辑</button>
                </div>
            </div>
        </div>
    `;
}

function syncOffcanvasSections(type) {
    document.querySelectorAll('.honor-oc-section').forEach(function (el) {
        const raw = el.getAttribute('data-honor-section') || '';
        const types = raw.split(/\s+/).filter(Boolean);
        const show = types.length === 0 || types.includes(type);
        el.classList.toggle('d-none', !show);
    });
}

function setOffcanvasTitleLabel(type) {
    const map = {
        competition: '竞赛 / 作品名称',
        scholarship: '奖学金名称',
        honor: '称号名称',
        paper: '论文标题',
        research: '课题 / 项目名称',
        work: '作品 / 项目名称',
        other: '成果名称'
    };
    const el = document.getElementById('oc-title-label');
    if (el) {
        el.innerHTML = (map[type] || '名称') + ' <span class="text-danger">*</span>';
    }
}

function fillOffcanvas(h) {
    const x = normalizeHonor(h);
    document.getElementById('oc-id').value = x.id;
    const typeSel = document.getElementById('oc-type-select');
    if (typeSel) typeSel.value = x.type || 'other';
    document.getElementById('oc-title').value = x.title || '';
    document.getElementById('oc-award-rank').value = x.awardRank || '';
    document.getElementById('oc-team').value = x.teamScope || '';
    document.getElementById('oc-level').value = x.level || '';
    document.getElementById('oc-amount').value = x.amount || '';
    document.getElementById('oc-venue').value = x.venue || '';
    document.getElementById('oc-doi').value = x.doi || '';
    document.getElementById('oc-valid').value = x.validUntil || '';
    document.getElementById('oc-issuer').value = x.issuer || '';
    document.getElementById('oc-detail').value = x.detail || '';
    document.getElementById('oc-proof-url').value = x.proofUrl || '';
    const st = document.getElementById('oc-proof-status');
    if (st) st.textContent = x.proofUrl ? '已填写链接' : '';
    const p = (x.period || '').trim();
    if (/^\d{4}-\d{2}$/.test(p)) {
        document.getElementById('oc-month').value = p;
        document.getElementById('oc-period-text').value = '';
    } else {
        document.getElementById('oc-month').value = '';
        document.getElementById('oc-period-text').value = p;
    }
    setOffcanvasTitleLabel(x.type);
    syncOffcanvasSections(x.type);
}

function readOffcanvas() {
    const idInp = document.getElementById('oc-id').value.trim();
    const typeSel = document.getElementById('oc-type-select');
    const type = (typeSel && typeSel.value) || 'other';
    const month = document.getElementById('oc-month').value.trim();
    const pt = document.getElementById('oc-period-text').value.trim();
    let period = month || pt;
    return normalizeHonor({
        id: idInp || newHonorId(),
        type: type,
        title: document.getElementById('oc-title').value.trim(),
        level: document.getElementById('oc-level').value || '',
        issuer: document.getElementById('oc-issuer').value.trim(),
        period: period,
        detail: document.getElementById('oc-detail').value.trim(),
        proofUrl: document.getElementById('oc-proof-url').value.trim(),
        awardRank: document.getElementById('oc-award-rank').value || '',
        teamScope: document.getElementById('oc-team').value || '',
        amount: document.getElementById('oc-amount').value.trim(),
        venue: document.getElementById('oc-venue').value.trim(),
        doi: document.getElementById('oc-doi').value.trim(),
        validUntil: document.getElementById('oc-valid').value.trim()
    });
}

let honorEditTargetId = null;

function openHonorModal(opts) {
    const presetType = opts && opts.presetType;
    const honorId = opts && opts.honorId;
    honorEditTargetId = honorId || null;
    const form = document.getElementById('honorEditForm');
    const modalEl = document.getElementById('honorEditModal');
    const titleEl = document.getElementById('honorModalLabel');
    if (!form || !modalEl || typeof bootstrap === 'undefined') return;

    const isEdit = Boolean(honorId);
    if (titleEl) titleEl.textContent = isEdit ? '编辑成果' : '添加成果';

    if (honorId) {
        const h = honorsState.find(function (x) {
            return String(x.id) === String(honorId);
        });
        if (h) fillOffcanvas(h);
    } else {
        form.reset();
        document.getElementById('oc-id').value = '';
        const typeSel = document.getElementById('oc-type-select');
        const pt = presetType || 'competition';
        if (typeSel) typeSel.value = pt;
        setOffcanvasTitleLabel(pt);
        const ym = new Date().toISOString().slice(0, 7);
        document.getElementById('oc-month').value = ym;
        document.getElementById('oc-period-text').value = '';
        const st = document.getElementById('oc-proof-status');
        if (st) st.textContent = '';
        syncOffcanvasSections(pt);
    }
    bootstrap.Modal.getOrCreateInstance(modalEl).show();
    setTimeout(function () {
        document.getElementById('oc-title')?.focus();
    }, 300);
}

function commitHonorModal(closeAfter) {
    const h = readOffcanvas();
    if (!h.title) {
        showMessage('请填写名称', 'warning');
        return;
    }
    const idx = honorsState.findIndex(function (x) {
        return String(x.id) === String(h.id);
    });
    if (idx >= 0) {
        honorsState[idx] = h;
    } else {
        honorsState.push(h);
    }
    renderHonorList();
    persistHonorsDraft();
    refreshHonorsPreview();
    if (closeAfter) {
        const modalEl = document.getElementById('honorEditModal');
        if (modalEl) bootstrap.Modal.getInstance(modalEl)?.hide();
        honorEditTargetId = null;
    } else {
        honorEditTargetId = null;
        const typeSel = document.getElementById('oc-type-select');
        const t = (typeSel && typeSel.value) || 'competition';
        document.getElementById('honorEditForm').reset();
        if (typeSel) typeSel.value = t;
        document.getElementById('oc-id').value = '';
        setOffcanvasTitleLabel(t);
        document.getElementById('oc-month').value = new Date().toISOString().slice(0, 7);
        document.getElementById('oc-period-text').value = '';
        const st = document.getElementById('oc-proof-status');
        if (st) st.textContent = '';
        syncOffcanvasSections(t);
        const titleEl = document.getElementById('honorModalLabel');
        if (titleEl) titleEl.textContent = '添加成果';
        document.getElementById('oc-title')?.focus();
    }
}

function honorMetaParts(x) {
    const h = normalizeHonor(x);
    const parts = [];
    if (h.awardRank) parts.push(h.awardRank);
    if (h.teamScope === 'TEAM') parts.push('团队');
    if (h.teamScope === 'INDIVIDUAL') parts.push('个人');
    if (h.period) parts.push(h.period);
    if (h.amount && h.type === 'scholarship') parts.push(h.amount);
    if (h.venue && h.type === 'paper') parts.push(h.venue);
    if (h.issuer) parts.push(h.issuer);
    if (h.doi && h.type === 'paper') parts.push(h.doi);
    if (h.validUntil && h.type === 'honor') parts.push('有效期 ' + h.validUntil);
    return parts.map((p) => escapeHtml(p)).join(' · ');
}

/** 证明材料 URL：用于决定是否展示图片缩略图 */
function honorProofMediaKind(url) {
    const s = String(url || '').trim().toLowerCase();
    if (!s) return 'none';
    const path = s.split('?')[0].split('#')[0];
    if (/\.(jpe?g|png|gif|webp|bmp|svg|avif|jfif|heic|heif)$/i.test(path)) return 'image';
    if (/\.pdf$/i.test(path)) return 'pdf';
    return 'link';
}

function honorProofSafeUrl(url) {
    const s = String(url || '').trim();
    if (!s) return '';
    try {
        // 先解码再编码，避免对已编码 URL 二次编码导致 404
        return encodeURI(decodeURI(s));
    } catch (_e) {
        try {
            return encodeURI(s);
        } catch (_e2) {
            return s;
        }
    }
}

function honorProofImageOnError(img) {
    if (!img) return;
    const tried = img.getAttribute('data-encoded-retry') === '1';
    if (!tried) {
        img.setAttribute('data-encoded-retry', '1');
        const raw = img.getAttribute('data-raw-src') || img.src || '';
        const retry = honorProofSafeUrl(raw);
        if (retry && retry !== img.src) {
            img.src = retry;
            return;
        }
    }
    const p = img.parentNode;
    if (p) p.classList.add('honors-preview-proof--broken');
    img.style.display = 'none';
}

/** 成果卡片右侧：证明材料预览（图片 / PDF 占位 / 通用链接） */
function honorProofAsideHtml(proofUrl) {
    const u = String(proofUrl || '').trim();
    if (!u) return '';
    const safe = escapeHtml(u);
    const safeSrc = escapeHtml(honorProofSafeUrl(u));
    const kind = honorProofMediaKind(u);
    if (kind === 'image') {
        const proofLinkStyle =
            'display:block;width:88px;height:88px;max-width:88px;max-height:88px;overflow:hidden;border-radius:8px;flex-shrink:0;';
        const proofImgStyle =
            'width:88px;height:88px;max-width:88px;max-height:88px;object-fit:cover;display:block;border-radius:8px;';
        return (
            '<div class="honors-preview-card__proof">' +
            '<a class="honors-preview-proof honors-preview-proof--image" style="' +
            proofLinkStyle +
            '" href="' +
            safe +
            '" target="_blank" rel="noopener" title="查看证明材料">' +
            '<img class="honors-preview-proof__img" style="' +
            proofImgStyle +
            '" src="' +
            safeSrc +
            '" data-raw-src="' +
            safe +
            '" alt="证明材料缩略图" loading="lazy" onerror="honorProofImageOnError(this)">' +
            '</a></div>'
        );
    }
    if (kind === 'pdf') {
        return (
            '<div class="honors-preview-card__proof">' +
            '<a class="honors-preview-proof honors-preview-proof--pdf" href="' +
            safe +
            '" target="_blank" rel="noopener">' +
            '<span class="honors-preview-proof__pdf-badge" aria-hidden="true">PDF</span>' +
            '<span class="honors-preview-proof__pdf-label">证明文件</span>' +
            '</a></div>'
        );
    }
    return (
        '<div class="honors-preview-card__proof">' +
        '<a class="honors-preview-proof honors-preview-proof--link" href="' +
        safe +
        '" target="_blank" rel="noopener">' +
        '<span class="honors-preview-proof__link-label">证明材料</span>' +
        '</a></div>'
    );
}

/** 概览 / 公开主页头像（固定尺寸，避免原图撑破布局） */
function profileSummaryAvatarHtml(userLike) {
    const u = userLike || {};
    const seed =
        (u.username && String(u.username).trim()) ||
        (u.realName && String(u.realName).trim()) ||
        '用';
    const initials = String(seed).charAt(0).toUpperCase();
    const av = u.avatar && String(u.avatar).trim();
    if (av) {
        const wrapStyle =
            'width:80px;height:80px;min-width:80px;min-height:80px;max-width:80px;max-height:80px;border-radius:50%;overflow:hidden;flex-shrink:0;display:block;';
        const imgStyle = 'width:100%;height:100%;max-width:80px;max-height:80px;object-fit:cover;display:block;border-radius:50%;';
        return (
            '<div class="profile-summary-avatar" role="img" aria-label="用户头像" style="' +
            wrapStyle +
            '">' +
            '<img src="' +
            escapeHtml(av) +
            '" alt="" class="profile-summary-avatar__img" style="' +
            imgStyle +
            '" loading="lazy" referrerpolicy="no-referrer" ' +
            "onerror=\"this.style.display='none';this.parentElement.classList.add('profile-summary-avatar--fallback');\">" +
            '<span class="profile-summary-avatar__initial" aria-hidden="true">' +
            escapeHtml(initials) +
            '</span></div>'
        );
    }
    return (
        '<div class="profile-summary-avatar profile-summary-avatar--fallback" role="img" aria-label="用户头像">' +
        '<span class="profile-summary-avatar__initial">' +
        escapeHtml(initials) +
        '</span></div>'
    );
}

function refreshHonorsPreview() {
    const mount = document.getElementById('honorsPreviewMount');
    if (!mount) return;
    const list = collectHonorsFromState();
    if (!list.length) {
        mount.innerHTML =
            '<div class="honors-preview-empty honors-preview-empty--muted" role="status">暂无内容：添加并保存成果后，将在此显示与概览一致的摘要。</div>';
        return;
    }
    mount.innerHTML = `<ul class="list-unstyled mb-0 honors-preview-mount__list">
        ${list
            .map((x) => {
                const tier = honorLevelTierClass(x.level);
                const lv = x.level ? honorLevelLabel(x.level) : '';
                const meta = honorMetaParts(x);
                const proof = String(x.proofUrl || '').trim();
                const proofAside = honorProofAsideHtml(proof);
                const withProofClass = proof ? ' honors-preview-mount__item--with-proof' : '';
                return `<li class="honors-preview-mount__item honor-level-item ${tier}${withProofClass}">
                <div class="honors-preview-card__layout">
                <div class="honors-preview-card__main">
                <div class="honors-preview-card__head">
                    <span class="meta-chip honors-preview-mount__chip">${escapeHtml(honorTypeLabel(x.type))}</span>
                    ${
                        lv
                            ? `<span class="honor-level-pill ${tier}">${escapeHtml(lv)}</span>`
                            : ''
                    }
                </div>
                <div class="honors-preview-card__body">
                    <p class="honors-preview-card__title">${escapeHtml(x.title || '')}</p>
                    ${meta ? `<p class="honors-preview-card__meta text-muted">${meta}</p>` : ''}
                </div>
                ${
                    x.detail
                        ? `<p class="honors-preview-mount__detail small text-muted mb-0 mt-2">${escapeHtml(x.detail)}</p>`
                        : ''
                }
                </div>
                ${proofAside}
                </div>
            </li>`;
            })
            .join('')}
    </ul>`;
}

function renderHonorList() {
    const root = document.getElementById('honorsEditor');
    if (!root) return;
    if (!honorsState.length) {
        root.innerHTML = honorsEmptyEditorHtml();
        refreshHonorsPreview();
        updateHonorsBatchToolbar();
        return;
    }
    root.innerHTML = honorsState.map((h) => renderHonorCard(h)).join('');
    refreshHonorsPreview();
    updateHonorsBatchToolbar();
}

function renderHonorsEditor() {
    renderHonorList();
}

function getUserRoleDisplayName(role) {
    switch (role) {
        case 'STUDENT':
            return '学生';
        case 'TEACHER':
            return '教师';
        case 'ADMIN':
            return '管理员';
        default:
            return role || '';
    }
}

function renderTeamActivityLists(activity) {
    const myTeams = document.getElementById('content-teams');
    const myApplications = document.getElementById('content-applications');
    if (!myTeams || !myApplications) return;
    const published = activity?.published || [];
    const applications = activity?.applications || [];

    if (!published.length) {
        myTeams.innerHTML = `
            <div class="empty-state home-empty profile-empty-cta">
                <div class="home-empty__art" aria-hidden="true">
                    <svg viewBox="0 0 120 100" width="120" height="100" class="home-empty__svg"><circle cx="40" cy="38" r="18" fill="rgba(58,90,169,0.15)"/><circle cx="82" cy="42" r="14" fill="rgba(42,143,134,0.18)"/><path d="M20 88 Q60 62 100 88" stroke="rgba(58,90,169,0.25)" stroke-width="3" fill="none" stroke-linecap="round"/></svg>
                </div>
                <h4>暂无组队</h4>
                <p>发布需求或加入团队后，将在这里集中展示。</p>
                <div class="d-flex flex-wrap gap-2 justify-content-center mt-3">
                    <a href="/team-publish.html" class="btn btn-primary btn-sm">发布我的第一个组队</a>
                    <a href="/team-market.html" class="btn btn-outline-primary btn-sm">去组队大厅看看</a>
                </div>
            </div>
        `;
    } else {
        myTeams.innerHTML = `<ul class="list-group list-group-flush profile-activity-list">
            ${published
                .map((t) => {
                    const st = teamDemandStatusLabel(t.status);
                    const title = escapeHtml(t.title || '未命名');
                    const id = t.id != null ? Number(t.id) : '';
                    return `<li class="list-group-item d-flex flex-wrap align-items-center justify-content-between gap-2 px-0 py-3">
                        <div class="min-w-0">
                            <a class="fw-semibold text-decoration-none text-truncate d-inline-block mw-100" href="/team-detail.html?id=${id}">${title}</a>
                            <div class="small text-muted mt-1">${escapeHtml(st)} · ${formatTime(t.createdAt)}</div>
                        </div>
                        <a href="/team-detail.html?id=${id}" class="btn btn-sm btn-outline-primary flex-shrink-0">查看/编辑</a>
                    </li>`;
                })
                .join('')}
        </ul>`;
    }

    if (!applications.length) {
        myApplications.innerHTML = `
            <div class="empty-state home-empty profile-empty-cta">
                <div class="home-empty__art" aria-hidden="true">
                    <svg viewBox="0 0 120 100" width="120" height="100" class="home-empty__svg home-empty__svg--doc"><rect x="36" y="22" width="48" height="62" rx="8" fill="rgba(255,255,255,0.55)" stroke="rgba(58,90,169,0.28)" stroke-width="2"/><path d="M48 40h24M48 52h24M48 64h16" stroke="rgba(71,85,105,0.45)" stroke-width="3" stroke-linecap="round"/></svg>
                </div>
                <h4>暂无申请</h4>
                <p>申请加入组队或导师项目后，记录会出现在这里。</p>
                <div class="d-flex flex-wrap gap-2 justify-content-center mt-3">
                    <a href="/team-market.html" class="btn btn-primary btn-sm">去组队大厅</a>
                    <a href="/teacher-wall.html" class="btn btn-outline-primary btn-sm">导师招贤</a>
                </div>
            </div>
        `;
    } else {
        myApplications.innerHTML = `<ul class="list-group list-group-flush profile-activity-list">
            ${applications
                .map((a) => {
                    const title = escapeHtml(a.teamTitle || '组队');
                    const st = teamApplicationStatusLabel(a.status);
                    const tid = a.teamId != null ? Number(a.teamId) : '';
                    return `<li class="list-group-item d-flex flex-wrap align-items-center justify-content-between gap-2 px-0 py-3">
                        <div class="min-w-0">
                            <a class="fw-semibold text-decoration-none text-truncate d-inline-block mw-100" href="/team-detail.html?id=${tid}">${title}</a>
                            <div class="small text-muted mt-1">${escapeHtml(st)} · ${formatTime(a.createdAt)}</div>
                        </div>
                        <a href="/team-detail.html?id=${tid}" class="btn btn-sm btn-outline-primary flex-shrink-0">查看组队</a>
                    </li>`;
                })
                .join('')}
        </ul>`;
    }
}

function renderOverview(user, honorsList, activity) {
    const userInfoContainer = document.getElementById('userInfo');
    const act = activity || lastActivity || { published: [], applications: [] };
    if (!userInfoContainer) {
        renderTeamActivityLists(act);
        return;
    }
    const teamCount = act.published ? act.published.length : 0;
    const appCount = act.applications ? act.applications.length : 0;

    const iconHonors =
        '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3l2.4 7.4H22l-6 4.6 2.3 7L12 17.8 5.7 22 8 14.4 2 10.6h7.6L12 3z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/></svg>';
    const iconTeams =
        '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2M9 11a4 4 0 100-8 4 4 0 000 8zm12 10v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    const iconApply =
        '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/><path d="M14 2v6h6M16 13H8M16 17H8M10 9H8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';

    const honorCount = honorsList ? honorsList.length : 0;
    const honorPreview =
        honorCount > 0
            ? `<div class="profile-honors-snippet profile-honors-snippet--in-card">
                    <h3 class="profile-snippet-title">成果与荣誉</h3>
                    <ul class="list-unstyled mb-0 profile-honors-preview-list">
                        ${honorsList
                            .slice(0, 4)
                            .map((x) => {
                                const lv = x.level ? honorLevelLabel(x.level) : '';
                                const tier = honorLevelTierClass(x.level);
                                const meta = honorMetaParts(x);
                                const proof = String(x.proofUrl || '').trim();
                                const proofAside = honorProofAsideHtml(proof);
                                const lvHtml = lv
                                    ? `<span class="honor-level-pill ${tier}">${escapeHtml(lv)}</span>`
                                    : '';
                                const withProof = proof ? ' profile-honors-preview-list__item--with-proof' : '';
                                return `<li class="profile-honors-preview-list__item honor-level-item ${tier}${withProof}">
                                    <div class="honors-preview-card__layout">
                                    <div class="honors-preview-card__main">
                                    <div class="profile-honors-card__head">
                                        <span class="meta-chip meta-chip--muted">${escapeHtml(honorTypeLabel(x.type))}</span>
                                        ${lvHtml}
                                    </div>
                                    <div class="profile-honors-card__body">
                                        <p class="profile-honors-card__title">${escapeHtml(x.title || '')}</p>
                                        ${meta ? `<p class="profile-honors-card__meta text-muted">${meta}</p>` : ''}
                                    </div>
                                    </div>
                                    ${proofAside}
                                    </div>
                                </li>`;
                            })
                            .join('')}
                    </ul>
                    ${
                        honorCount > 4
                            ? `<p class="small text-muted mb-0 mt-2">还有 ${honorCount - 4} 条，<a href="/profile-honors.html">在个人成果中编辑全部</a>。</p>`
                            : ''
                    }
               </div>`
            : `<div class="profile-honors-snippet profile-honors-snippet--in-card">
                    <h3 class="profile-snippet-title">成果与荣誉</h3>
                    <div class="alert profile-honors-banner d-flex flex-wrap align-items-center justify-content-between gap-2 mb-0 py-2 px-3" role="status">
                        <span class="mb-0">尚未填写，完善后将在本页展示摘要。</span>
                        <a href="/profile-honors.html" class="btn btn-sm btn-primary flex-shrink-0">立即完善</a>
                    </div>
               </div>`;

    const role = user.role || 'STUDENT';
    const roleLabel = getUserRoleDisplayName(role);
    const avatarHtml = profileSummaryAvatarHtml(user);

    const displayName = escapeHtml(
        (user.username && String(user.username).trim()) || user.realName || '用户'
    );
    const identityTitle = `${displayName} · ${escapeHtml(roleLabel)}`;
    const emailLine = user.email ? escapeHtml(user.email) : '未填写邮箱';
    const basicRows = role === 'TEACHER'
        ? [
            { label: '姓名', value: user.realName || '' },
            { label: '用户名', value: user.username || '' },
            { label: '专业领域', value: user.major || '' },
            { label: '任职单位', value: user.school || '' },
            { label: '院系 / 部门', value: user.college || '' }
        ]
        : [
            { label: '姓名', value: user.realName || '' },
            { label: '用户名', value: user.username || '' },
            { label: '年级', value: user.grade || '' },
            { label: '专业', value: user.major || '' },
            { label: '学校', value: user.school || '' },
            { label: '学院', value: user.college || '' }
        ];
    const basicInfoHtml = `
        <div class="profile-public-basic">
            <h3 class="profile-snippet-title mb-2">基本信息</h3>
            <div class="profile-public-basic__grid">
                ${basicRows
                    .map(function (x) {
                        const val = String(x.value || '').trim();
                        return `
                        <div class="profile-public-basic__item">
                            <span class="profile-public-basic__label">${escapeHtml(x.label)}</span>
                            <span class="profile-public-basic__value">${escapeHtml(val || '未填写')}</span>
                        </div>`;
                    })
                    .join('')}
            </div>
        </div>
    `;
    const bioText = String(user.bio || '').trim();
    const bioHtml = `
        <div class="profile-public-bio">
            <h3 class="profile-snippet-title mb-2">个人简介</h3>
            <p class="profile-public-bio__text mb-0">${escapeHtml(bioText || '暂未填写个人简介。你可以在编辑资料里补充技能与可提供帮助。')}</p>
        </div>
    `;
    const posts = Array.isArray(user.publishedPosts) ? user.publishedPosts : [];
    const categoryMap = {
        general: '综合交流',
        tech: '技术讨论',
        competition: '竞赛经验',
        resource: '资源分享'
    };
    const postsHtml = `
        <div class="profile-public-posts">
            <h3 class="profile-snippet-title mb-2">发布过的文字</h3>
            ${
                posts.length
                    ? `<ul class="list-unstyled mb-0 profile-public-posts__list">
                        ${posts
                            .map(function (p) {
                                const cat = categoryMap[p.category] || p.category || '未分类';
                                return `<li class="profile-public-posts__item">
                                    <div class="profile-public-posts__head">
                                        <a class="profile-public-posts__title" href="/community-article.html?id=${encodeURIComponent(String(p.id || ''))}">
                                            ${escapeHtml(p.title || '未命名文章')}
                                        </a>
                                        <span class="meta-chip meta-chip--muted">${escapeHtml(cat)}</span>
                                    </div>
                                    <p class="profile-public-posts__excerpt mb-1">${escapeHtml(p.excerpt || '暂无摘要')}</p>
                                    <p class="profile-public-posts__meta mb-0 text-muted">${formatTime(p.createdAt)}</p>
                                </li>`;
                            })
                            .join('')}
                    </ul>`
                    : '<p class="small text-muted mb-0">暂无公开发布内容。</p>'
            }
        </div>
    `;

    userInfoContainer.innerHTML = `
        <div class="profile-summary profile-summary--inline page-transition">
            <div class="profile-summary-identity">
                ${avatarHtml}
                <div class="profile-summary-identity__text min-w-0">
                    <p class="profile-summary-identity__title mb-1">${identityTitle}</p>
                    <p class="profile-summary-identity__meta mb-1">${emailLine}</p>
                    <p class="profile-summary-identity__meta profile-summary-identity__meta--muted mb-0">注册于 ${formatTime(user.createdAt)}</p>
                </div>
            </div>
            <hr class="profile-summary-rule" />
            <div class="profile-quick-stats profile-quick-stats--full">
                <div class="profile-stat-pill">
                    <span class="profile-stat-pill__icon" aria-hidden="true">${iconHonors}</span>
                    <span class="profile-stat-pill__label">成果</span>
                    <span class="profile-stat-pill__value">${honorCount}</span>
                </div>
                <div class="profile-stat-pill">
                    <span class="profile-stat-pill__icon" aria-hidden="true">${iconTeams}</span>
                    <span class="profile-stat-pill__label">我的组队</span>
                    <span class="profile-stat-pill__value">${teamCount}</span>
                </div>
                <div class="profile-stat-pill">
                    <span class="profile-stat-pill__icon" aria-hidden="true">${iconApply}</span>
                    <span class="profile-stat-pill__label">我的申请</span>
                    <span class="profile-stat-pill__value">${appCount}</span>
                </div>
            </div>
            <hr class="profile-summary-rule" />
            ${basicInfoHtml}
            <hr class="profile-summary-rule" />
            ${bioHtml}
            <hr class="profile-summary-rule" />
            ${postsHtml}
            <hr class="profile-summary-rule" />
            ${honorPreview}
            <hr class="profile-summary-rule" />
            <div class="profile-summary-foot-actions d-flex flex-wrap gap-2 align-items-center">
                <a href="/profile-edit.html" class="btn btn-outline-primary">编辑资料</a>
                <a href="/team-publish.html" class="btn btn-primary">发布组队</a>
                <a href="/team-market.html" class="btn btn-outline-secondary">去组队大厅</a>
            </div>
        </div>
    `;

    renderTeamActivityLists(act);
}

/** 他人公开主页：仅概览 + 成果摘要，无邮箱 / 活动 Tab / 编辑入口 */
function renderPublicOverview(vo, honorsList) {
    const userInfoContainer = document.getElementById('userInfo');
    if (!userInfoContainer) return;

    const honorCount = honorsList ? honorsList.length : 0;
    const honorPreview =
        honorCount > 0
            ? `<div class="profile-honors-snippet profile-honors-snippet--in-card">
                    <h3 class="profile-snippet-title">成果与荣誉</h3>
                    <ul class="list-unstyled mb-0 profile-honors-preview-list">
                        ${honorsList
                            .slice(0, 4)
                            .map((x) => {
                                const lv = x.level ? honorLevelLabel(x.level) : '';
                                const tier = honorLevelTierClass(x.level);
                                const meta = honorMetaParts(x);
                                const proof = String(x.proofUrl || '').trim();
                                const proofAside = honorProofAsideHtml(proof);
                                const lvHtml = lv
                                    ? `<span class="honor-level-pill ${tier}">${escapeHtml(lv)}</span>`
                                    : '';
                                const withProof = proof ? ' profile-honors-preview-list__item--with-proof' : '';
                                return `<li class="profile-honors-preview-list__item honor-level-item ${tier}${withProof}">
                                    <div class="honors-preview-card__layout">
                                    <div class="honors-preview-card__main">
                                    <div class="profile-honors-card__head">
                                        <span class="meta-chip meta-chip--muted">${escapeHtml(honorTypeLabel(x.type))}</span>
                                        ${lvHtml}
                                    </div>
                                    <div class="profile-honors-card__body">
                                        <p class="profile-honors-card__title">${escapeHtml(x.title || '')}</p>
                                        ${meta ? `<p class="profile-honors-card__meta text-muted">${meta}</p>` : ''}
                                    </div>
                                    </div>
                                    ${proofAside}
                                    </div>
                                </li>`;
                            })
                            .join('')}
                    </ul>
                    ${
                        honorCount > 4
                            ? `<p class="small text-muted mb-0 mt-2">还有 ${honorCount - 4} 条未在此页展示。</p>`
                            : ''
                    }
               </div>`
            : `<div class="profile-honors-snippet profile-honors-snippet--in-card">
                    <h3 class="profile-snippet-title">成果与荣誉</h3>
                    <p class="small text-muted mb-0">尚未填写公开成果摘要。</p>
               </div>`;

    const role = vo.role || 'STUDENT';
    const roleLabel = getUserRoleDisplayName(role);
    const avatarHtml = profileSummaryAvatarHtml(vo);

    const displayName = escapeHtml(
        (vo.username && String(vo.username).trim()) || vo.realName || '用户'
    );
    const identityTitle = `${displayName} · ${escapeHtml(roleLabel)}`;
    const basicRows = role === 'TEACHER'
        ? [
            { label: '姓名', value: vo.realName || '' },
            { label: '用户名', value: vo.username || '' },
            { label: '专业领域', value: vo.major || '' },
            { label: '任职单位', value: vo.school || '' },
            { label: '院系 / 部门', value: vo.college || '' }
        ]
        : [
            { label: '姓名', value: vo.realName || '' },
            { label: '用户名', value: vo.username || '' },
            { label: '年级', value: vo.grade || '' },
            { label: '专业', value: vo.major || '' },
            { label: '学校', value: vo.school || '' },
            { label: '学院', value: vo.college || '' }
        ];
    const basicInfoHtml = `
        <div class="profile-public-basic">
            <h3 class="profile-snippet-title mb-2">基本信息</h3>
            <div class="profile-public-basic__grid">
                ${basicRows
                    .map(function (x) {
                        const val = String(x.value || '').trim();
                        return `
                        <div class="profile-public-basic__item">
                            <span class="profile-public-basic__label">${escapeHtml(x.label)}</span>
                            <span class="profile-public-basic__value">${escapeHtml(val || '未填写')}</span>
                        </div>`;
                    })
                    .join('')}
            </div>
        </div>
    `;
    const bioText = String(vo.bio || '').trim();
    const bioHtml = `
        <div class="profile-public-bio">
            <h3 class="profile-snippet-title mb-2">个人简介</h3>
            <p class="profile-public-bio__text mb-0">${escapeHtml(bioText || '暂未填写个人简介。')}</p>
        </div>
    `;
    const posts = Array.isArray(vo.publishedPosts) ? vo.publishedPosts : [];
    const categoryMap = {
        general: '综合交流',
        tech: '技术讨论',
        competition: '竞赛经验',
        resource: '资源分享'
    };
    const postsHtml = `
        <div class="profile-public-posts">
            <h3 class="profile-snippet-title mb-2">发布过的文字</h3>
            ${
                posts.length
                    ? `<ul class="list-unstyled mb-0 profile-public-posts__list">
                        ${posts
                            .map(function (p) {
                                const cat = categoryMap[p.category] || p.category || '未分类';
                                return `<li class="profile-public-posts__item">
                                    <div class="profile-public-posts__head">
                                        <a class="profile-public-posts__title" href="/community-article.html?id=${encodeURIComponent(String(p.id || ''))}">
                                            ${escapeHtml(p.title || '未命名文章')}
                                        </a>
                                        <span class="meta-chip meta-chip--muted">${escapeHtml(cat)}</span>
                                    </div>
                                    <p class="profile-public-posts__excerpt mb-1">${escapeHtml(p.excerpt || '暂无摘要')}</p>
                                    <p class="profile-public-posts__meta mb-0 text-muted">${formatTime(p.createdAt)}</p>
                                </li>`;
                            })
                            .join('')}
                    </ul>`
                    : '<p class="small text-muted mb-0">暂无公开发布内容。</p>'
            }
        </div>
    `;

    userInfoContainer.innerHTML = `
        <div class="profile-summary profile-summary--inline page-transition">
            <div class="profile-summary-identity">
                ${avatarHtml}
                <div class="profile-summary-identity__text min-w-0">
                    <p class="profile-summary-identity__title mb-1">${identityTitle}</p>
                    <p class="profile-summary-identity__meta profile-summary-identity__meta--muted mb-0">注册于 ${formatTime(vo.createdAt)}</p>
                </div>
            </div>
            <hr class="profile-summary-rule" />
            <div class="profile-quick-stats profile-quick-stats--full">
                <div class="profile-stat-pill">
                    <span class="profile-stat-pill__label">公开成果条数</span>
                    <span class="profile-stat-pill__value">${honorCount}</span>
                </div>
            </div>
            <hr class="profile-summary-rule" />
            ${basicInfoHtml}
            <hr class="profile-summary-rule" />
            ${bioHtml}
            <hr class="profile-summary-rule" />
            ${postsHtml}
            <hr class="profile-summary-rule" />
            ${honorPreview}
        </div>
    `;
}

function getProfilePage() {
    var hash = (window.location.hash || '').replace(/^#/, '');
    if (hash === 'teams') return 'teams';
    var page = document.body.getAttribute('data-profile-page');
    if (page) return page;
    // 如果没有 data-profile-page 属性但有 hash，也返回对应值
    if (hash === 'settings') return 'overview';
    if (hash === 'honors') return 'overview';
    return 'overview';
}

async function fetchPublicProfileById(userId) {
    if (userId == null || String(userId).trim() === '') return null;
    try {
        const r = await apiFetch('/api/user/public/' + encodeURIComponent(String(userId).trim()), {
            credentials: 'same-origin'
        });
        const ct = r.headers.get('content-type') || '';
        if (!ct.includes('application/json')) return null;
        const result = await r.json();
        if (Number(result.code) === 200 && result.data) return result.data;
    } catch (e) {
        console.warn('获取公开主页补充数据失败', e);
    }
    return null;
}

/** 竞赛小队：展示用户已加入的团队列表，支持选择团队进入二级子面板 */
async function renderJoinedTeams() {
    const container = document.getElementById('joinedTeamsList');
    if (!container) return;
    try {
        const res = await apiFetch('/api/team/my/joined');
        const data = await res.json();
        if (data.code !== 200 || !Array.isArray(data.data)) {
            container.innerHTML = '<div class="text-center py-4 text-muted">加载失败</div>';
            return;
        }
        const teams = data.data;
        if (!teams.length) {
            container.innerHTML =
                '<div class="il-empty-state">' +
                    '<div class="il-empty-icon"><svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg></div>' +
                    '<p class="il-empty-title">还没有加入任何团队</p>' +
                    '<p class="il-empty-text">去组队大厅看看，找到志同道合的伙伴</p>' +
                '</div>';
            return;
        }
        container.innerHTML = teams.map(function (t) {
            var teamId = Number(t.teamId);
            var title = escapeHtml(t.teamTitle || '未命名');
            var status = t.status || '';
            var statusLabel = teamDemandStatusLabel(status);
            var statusClass = teamDemandStatusBadgeClass(status);
            var joined = t.joinedAt ? formatTime(t.joinedAt) : '';
            var isCreator = !!t.isCreator;
            var badge = isCreator ? ' <span class="meta-chip meta-chip--primary">队长</span>' : '';
            // 异步加载成员数和任务数（不阻塞渲染）
            (function fetchTeamExtras(tid) {
                Promise.all([
                    apiFetch('/api/team/' + tid + '/members').then(function(r){ return r.json(); }).then(function(j){ return Array.isArray(j.data) ? j.data.length : 0; }).catch(function(){ return 0; }),
                    apiFetch('/api/tasks?teamId=' + tid).then(function(r){ return r.json(); }).then(function(j){
                        var tasks = Array.isArray(j.data) ? j.data : [];
                        var pending = 0;
                        for (var i = 0; i < tasks.length; i++) {
                            var s = (tasks[i].status || 'pending').toLowerCase();
                            if (s === 'pending' || s === 'todo') pending++;
                        }
                        return { total: tasks.length, pending: pending };
                    }).catch(function(){ return { total: 0, pending: 0 }; })
                ]).then(function(results) {
                    var memberCount = results[0];
                    var taskInfo = results[1];
                    var card = document.querySelector('[data-team-id="' + tid + '"]');
                    if (!card) return;
                    var metaEl = card.querySelector('.il-team-card__meta');
                    if (metaEl) {
                        metaEl.innerHTML = '<span>加入于 ' + joined + '</span>' +
                            ' <span class="il-team-card__meta-sep">·</span>' +
                            '<span class="il-team-card__stat">成员 <span class="il-team-card__stat-value">' + memberCount + '</span> 人</span>' +
                            ' <span class="il-team-card__meta-sep">·</span>' +
                            '<span class="il-team-card__stat">待办 <span class="il-team-card__stat-value">' + taskInfo.pending + '</span> 个</span>';
                    }
                    var actionsEl = card.querySelector('.il-team-card__actions');
                    if (actionsEl && (status === 'TEAMING' || status === 'CLOSED')) {
                        actionsEl.innerHTML =
                            '<a href="/team-space.html?id=' + tid + '" class="il-team-card__btn il-team-card__btn--primary">进入空间</a>' +
                            '<a href="/team-detail.html?id=' + tid + '" class="il-team-card__btn">查看详情</a>';
                    }
                });
            })(teamId);
            return '<div class="il-team-card ' + teamCardStatusClass(status) + '" data-team-id="' + teamId + '">' +
                '<div class="il-team-card__inner">' +
                '<div class="il-team-card__header">' +
                    '<div class="il-team-card__icon">' + teamIconSvg + '</div>' +
                    '<div class="il-team-card__body">' +
                        '<a class="il-team-card__title" href="/team-detail.html?id=' + teamId + '">' + title + badge + '</a>' +
                        '<div class="il-team-card__meta">' +
                            '<span>加载中...</span>' +
                        '</div>' +
                    '</div>' +
                    '<span class="il-team-card__badge ' + statusClass + '">' + statusLabel + '</span>' +
                    '<span class="il-team-card__arrow" aria-hidden="true">' + arrowSvg + '</span>' +
                '</div>' +
                '</div>' +
                '<div class="il-team-card__divider"></div>' +
                '<div class="il-team-card__actions">' +
                    '<a href="/team-detail.html?id=' + teamId + '" class="il-team-card__btn">查看详情</a>' +
                    (status === 'TEAMING' || status === 'CLOSED' ? '<a href="/team-space.html?id=' + teamId + '" class="il-team-card__btn il-team-card__btn--secondary">进入空间</a>' : '') +
                '</div>' +
            '</div>';
        }).join('');
    } catch (e) {
        console.error('加载竞赛小队失败', e);
        container.innerHTML = '<div class="text-center py-4 text-muted">网络异常，请稍后重试</div>';
    }
}

// 竞赛小队图标 & 箭头 SVG（复用）
var teamIconSvg = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>';
var arrowSvg = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>';

/** 在竞赛小队 tab 内渲染二级子面板（选择团队后） */
async function renderTeamSubPanel(teamId) {
    var container = document.getElementById('joinedTeamsList');
    if (!container) return;
    container.innerHTML = '<div class="text-center py-4"><div class="spinner-border" role="status"></div></div>';

    try {
        // 加载团队信息
        var teamRes = await apiFetch('/api/team/' + teamId);
        var teamData = await teamRes.json();
        var team = (teamData.code === 200) ? teamData.data : null;
        var teamName = team ? escapeHtml(team.title || '未知团队') : '未知团队';

        // 加载任务列表
        var tasksRes = await apiFetch('/api/team/' + teamId + '/tasks');
        var tasksData = await tasksRes.json();
        var tasks = (tasksData.code === 200) ? (Array.isArray(tasksData.data) ? tasksData.data : []) : [];

        // 统计各状态任务数
        var todoCount = 0, inProgressCount = 0, reviewCount = 0, completedCount = 0;
        tasks.forEach(function(t) {
            var s = (t.status || 'pending').toLowerCase();
            if (s === 'pending' || s === 'todo') todoCount++;
            else if (s === 'in_progress' || s === 'progress') inProgressCount++;
            else if (s === 'review' || s === 'rework') reviewCount++;
            else completedCount++;
        });

        container.innerHTML =
            '<div class="il-team-sub-panel">' +
                '<div class="il-team-sub-panel__header">' +
                    '<button type="button" class="il-back-btn" onclick="renderJoinedTeams()">' +
                        '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5M12 19L5 12L12 5"/></svg>' +
                        '返回团队列表' +
                    '</button>' +
                    '<h3 class="il-team-sub-panel__title">' + teamName + '</h3>' +
                '</div>' +
                '<div class="il-team-sub-panel__stats">' +
                    '<div class="il-stat-pill"><span class="il-stat-pill__label">待办</span><span class="il-stat-pill__value">' + todoCount + '</span></div>' +
                    '<div class="il-stat-pill"><span class="il-stat-pill__label">进行中</span><span class="il-stat-pill__value">' + inProgressCount + '</span></div>' +
                    '<div class="il-stat-pill"><span class="il-stat-pill__label">待审核</span><span class="il-stat-pill__value">' + reviewCount + '</span></div>' +
                    '<div class="il-stat-pill"><span class="il-stat-pill__label">已完成</span><span class="il-stat-pill__value">' + completedCount + '</span></div>' +
                '</div>' +
                '<div class="il-team-sub-panel__tabs">' +
                    '<button class="il-tab active" onclick="switchSubTab(\'kanban\', this)">任务看板</button>' +
                    '<button class="il-tab" onclick="switchSubTab(\'assign\', this)">分配任务</button>' +
                    '<button class="il-tab" onclick="switchSubTab(\'chat\', this)">交流沟通</button>' +
                '</div>' +
                '<div id="subTab-kanban" class="sub-tab-content">' +
                    (tasks.length ? renderKanbanTasks(tasks) : '<div class="kanban-empty"><p>暂无任务</p></div>') +
                '</div>' +
                '<div id="subTab-assign" class="sub-tab-content" style="display:none;">' +
                    '<div class="kanban-empty"><p>任务分配功能开发中</p></div>' +
                '</div>' +
                '<div id="subTab-chat" class="sub-tab-content" style="display:none;">' +
                    '<div class="kanban-empty"><p>团队交流功能开发中</p></div>' +
                '</div>' +
            '</div>';
    } catch (e) {
        console.error('加载团队子面板失败', e);
        container.innerHTML = '<div class="text-center py-4 text-muted">加载失败，请稍后重试</div>';
    }
}

function switchSubTab(tabName, btn) {
    document.querySelectorAll('.sub-tab-content').forEach(function(el) { el.style.display = 'none'; });
    document.querySelectorAll('.il-team-sub-panel__tabs .il-tab').forEach(function(t) { t.classList.remove('active'); });
    var target = document.getElementById('subTab-' + tabName);
    if (target) target.style.display = 'block';
    if (btn) btn.classList.add('active');
}

function renderKanbanTasks(tasks) {
    if (!tasks || tasks.length === 0) return '<div class="kanban-empty"><p>暂无任务</p></div>';
    return '<div class="kanban-mini-list">' + tasks.map(function(t) {
        var title = escapeHtml(t.taskTitle || '未命名任务');
        var status = (t.status || 'pending').toLowerCase();
        var statusMap = { pending: '待办', todo: '待办', in_progress: '进行中', progress: '进行中', review: '待审核', rework: '待审核', completed: '已完成', done: '已完成' };
        var statusLabel = statusMap[status] || status;
        var deadline = t.deadline ? formatDateOnlyProfile(t.deadline) : '';
        return '<div class="kanban-mini-card">' +
            '<div class="kanban-mini-card__head">' +
                '<span class="kanban-mini-card__title">' + title + '</span>' +
                '<span class="meta-chip meta-chip--muted">' + statusLabel + '</span>' +
            '</div>' +
            (deadline ? '<div class="kanban-mini-card__meta">截止：' + deadline + '</div>' : '') +
        '</div>';
    }).join('') + '</div>';
}

function formatDateOnlyProfile(value) {
    if (!value) return '长期有效';
    var d = new Date(value);
    if (Number.isNaN(d.getTime())) return String(value).slice(0, 10);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
function applyUserToProfileForm(user) {
    if (!user) return;
    const usernameElem = document.getElementById('username');
    const emailElem = document.getElementById('email');
    const realNameElem = document.getElementById('realName');
    const avatarElem = document.getElementById('avatar');
    const roleElem = document.getElementById('role');
    const genderElem = document.getElementById('gender');
    const gradeElem = document.getElementById('grade');
    const majorElem = document.getElementById('major');
    const schoolElem = document.getElementById('school');
    const collegeElem = document.getElementById('college');
    const bioElem = document.getElementById('bio');
    const phoneElem = document.getElementById('phoneDisplay');
    if (usernameElem) usernameElem.value = user.username || '';
    if (emailElem) emailElem.value = user.email || '';
    if (realNameElem) realNameElem.value = user.realName || '';
    if (avatarElem) avatarElem.value = user.avatar || '';
    if (roleElem) roleElem.value = user.role || 'STUDENT';
    if (genderElem) genderElem.value = user.gender || '';
    if (gradeElem) gradeElem.value = user.grade || '';
    if (majorElem) majorElem.value = user.major || '';
    if (schoolElem) schoolElem.value = user.school || '';
    if (collegeElem) collegeElem.value = user.college || '';
    if (bioElem) bioElem.value = user.bio || '';
    if (phoneElem) {
        const p = user.phoneNumber;
        phoneElem.value = p != null && String(p).trim() !== '' ? String(p) : '';
        if (!phoneElem.value) phoneElem.placeholder = '注册时未绑定';
    }
}

async function saveProfilePayload(includeHonors) {
    const usernameElem = document.getElementById('username');
    const emailElem = document.getElementById('email');
    const realNameElem = document.getElementById('realName');
    const avatarElem = document.getElementById('avatar');
    const genderElem = document.getElementById('gender');
    const gradeElem = document.getElementById('grade');
    const majorElem = document.getElementById('major');
    const schoolElem = document.getElementById('school');
    const collegeElem = document.getElementById('college');
    const bioElem = document.getElementById('bio');
    const profileData = {};
    // 仅提交当前页面存在的字段，避免在成果页把资料字段误清空
    if (usernameElem) profileData.username = usernameElem.value;
    if (emailElem) profileData.email = emailElem.value;
    if (realNameElem) profileData.realName = realNameElem.value;
    if (avatarElem) profileData.avatar = avatarElem.value;
    if (genderElem) profileData.gender = genderElem.value;
    if (gradeElem && window.__ilinkProfileRole !== 'TEACHER') profileData.grade = gradeElem.value;
    if (majorElem) profileData.major = majorElem.value;
    if (schoolElem) profileData.school = schoolElem.value;
    if (collegeElem) profileData.college = collegeElem.value;
    if (bioElem) profileData.bio = bioElem.value;
    if (includeHonors) {
        profileData.honors = JSON.stringify(collectHonorsFromState());
    }
    const response = await apiFetch('/api/user/profile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify(profileData)
    });
    return response.json();
}

function setProfileText(id, value, fallback) {
    const element = document.getElementById(id);
    if (element) element.textContent = String(value || '').trim() || fallback || '未设置';
}

function applyRoleAwareProfileLabels(user) {
    const isTeacher = user && user.role === 'TEACHER';
    window.__ilinkProfileRole = isTeacher ? 'TEACHER' : (user && user.role) || 'STUDENT';
    const teacherFields = document.getElementById('teacherProfileFields');
    const studentGradeGroup = document.getElementById('studentGradeGroup');
    const grade = document.getElementById('grade');
    const schoolLabel = document.getElementById('schoolLabel');
    const majorLabel = document.getElementById('majorLabel');
    const collegeLabel = document.getElementById('collegeLabel');
    const school = document.getElementById('school');
    const major = document.getElementById('major');
    const college = document.getElementById('college');
    const formTitle = document.querySelector('#profileForm .il-section-header .il-section-title');
    const formSubtitle = document.querySelector('#profileForm .il-section-header .il-section-subtitle');

    if (teacherFields) teacherFields.hidden = !isTeacher;
    if (studentGradeGroup) studentGradeGroup.hidden = isTeacher;
    if (grade) grade.disabled = isTeacher;
    if (schoolLabel) schoolLabel.textContent = isTeacher ? '任职单位' : '学校';
    if (majorLabel) majorLabel.textContent = isTeacher ? '专业领域' : '专业';
    if (collegeLabel) collegeLabel.textContent = isTeacher ? '院系 / 部门' : '学院';
    if (school) school.placeholder = '例如：南京晓庄学院';
    if (major) major.placeholder = isTeacher ? '例如：软件工程、人工智能' : '例如：软件工程';
    if (college) college.placeholder = isTeacher ? '例如：计算机学院 / 信息中心' : '例如：信息工程学院';
    if (formTitle) formTitle.textContent = isTeacher ? '教师基本信息' : '基本信息';
    if (formSubtitle) formSubtitle.textContent = isTeacher
        ? '完善任职信息与导师档案，让学生准确了解您的指导方向'
        : '完善个人资料，让更多人了解你';
}

function applyRoleAwareOverviewLayout(user) {
    const isTeacher = user && user.role === 'TEACHER';
    const activityCard = document.getElementById('activitySectionCard');
    if (activityCard) {
        activityCard.hidden = isTeacher;
        activityCard.setAttribute('aria-hidden', isTeacher ? 'true' : 'false');
    }

    const institution = document.getElementById('profileInstitutionText');
    if (institution) {
        institution.textContent = isTeacher
            ? (user.school || '未设置任职单位')
            : (user.school || '未设置学校');
    }
}

async function syncTeacherProfile(user, page) {
    applyRoleAwareProfileLabels(user);
    applyRoleAwareOverviewLayout(user);
    const isTeacher = user && user.role === 'TEACHER';
    const summary = document.getElementById('teacherProfileSummary');
    if (!isTeacher) {
        if (summary) summary.hidden = true;
        return;
    }

    try {
        const response = await apiFetch('/api/teacher/me', { credentials: 'same-origin' });
        const result = await response.json();
        if (!response.ok || !result || Number(result.code) !== 200 || !result.data) {
            throw new Error((result && result.message) || '导师档案暂时不可用');
        }
        const teacher = result.data;
        if (page === 'edit') {
            const title = document.getElementById('professionalTitle');
            const research = document.getElementById('teacherResearch');
            const introduction = document.getElementById('teacherIntroduction');
            const projects = document.getElementById('teacherProjects');
            if (title) title.value = teacher.professionalTitle || '';
            if (research) research.value = teacher.researchDirection || '';
            if (introduction) introduction.value = teacher.introduction || '';
            if (projects) projects.value = teacher.projects || '';
        }
        if (summary) {
            summary.hidden = false;
            setProfileText('teacherSummaryExpertise', teacher.expertise, '未设置');
            setProfileText('teacherSummaryTitle', teacher.professionalTitle, '未设置');
            setProfileText('teacherSummaryResearch', teacher.researchDirection, '未设置');
            setProfileText('teacherSummaryProjects', teacher.projects, '未设置');
            const detailLink = document.getElementById('teacherProfileDetailLink');
            if (detailLink && teacher.id) {
                detailLink.href = '/teacher-detail.html?id=' + encodeURIComponent(String(teacher.id));
            }
            const institution = document.getElementById('profileInstitutionText');
            if (institution) institution.textContent = user.school || '未设置任职单位';
        }
    } catch (error) {
        console.error('同步导师资料失败:', error);
        if (summary) {
            summary.hidden = false;
            setProfileText('teacherSummaryExpertise', user.major, '未设置');
            setProfileText('teacherSummaryTitle', '', '待完善');
            setProfileText('teacherSummaryResearch', '', '待完善');
            setProfileText('teacherSummaryProjects', '', '待完善');
        }
    }
}

async function saveTeacherProfilePayload() {
    const payload = {
        professionalTitle: (document.getElementById('professionalTitle') || {}).value || '',
        researchDirection: (document.getElementById('teacherResearch') || {}).value || '',
        introduction: (document.getElementById('teacherIntroduction') || {}).value || '',
        projects: (document.getElementById('teacherProjects') || {}).value || ''
    };
    const response = await apiFetch('/api/teacher/profile', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify(payload)
    });
    const result = await response.json();
    if (!response.ok || !result || Number(result.code) !== 200) {
        throw new Error((result && result.message) || '导师资料保存失败');
    }
    return result;
}

let myTeamsStatusFilter = 'ALL';
let myTeamsSort = 'createdDesc';

function teamDemandStatusLabel(status) {
    const map = { OPEN: '招募中', TEAMING: '已组队', CLOSED: '已结束' };
    return map[status] || status || '';
}

function teamDemandStatusBadgeClass(status) {
    const map = { OPEN: 'il-team-card__badge--open', TEAMING: 'il-team-card__badge--teaming', CLOSED: 'il-team-card__badge--closed' };
    return map[status] || 'il-team-card__badge--closed';
}

function teamCardStatusClass(status) {
    const map = { OPEN: 'il-team-card--open', TEAMING: 'il-team-card--teaming', CLOSED: 'il-team-card--closed' };
    return map[status] || 'il-team-card--closed';
}

function teamApplicationStatusBadge(status) {
    const map = { PENDING: 'il-team-card__badge--pending', APPROVED: 'il-team-card__badge--approved', REJECTED: 'il-team-card__badge--rejected' };
    return map[status] || 'il-team-card__badge--closed';
}

function formatDateOnlyProfile(value) {
    if (!value) return '长期有效';
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return String(value).slice(0, 10);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function filteredProfileTeams(published) {
    let rows = Array.isArray(published) ? published.slice() : [];
    if (myTeamsStatusFilter !== 'ALL') {
        rows = rows.filter((team) => team.status === myTeamsStatusFilter);
    }
    if (myTeamsSort === 'createdAsc') {
        rows.sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0));
    } else if (myTeamsSort === 'deadlineAsc') {
        rows.sort((a, b) => new Date(a.deadline || '9999-12-31') - new Date(b.deadline || '9999-12-31'));
    } else if (myTeamsSort === 'applicantsDesc') {
        rows.sort((a, b) => Number(b.applicationCount || 0) - Number(a.applicationCount || 0));
    } else {
        rows.sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
    }
    return rows;
}

function profileTeamActionsHtml(team) {
    const id = Number(team.id);
    const actions = [`<a href="/team-detail.html?id=${id}" class="il-team-card__btn">查看详情</a>`];
    if (team.canEdit) {
        actions.push(`<a href="/team-detail.html?id=${id}" class="il-team-card__btn">编辑</a>`);
    }
    if (team.canMoveToTeaming) {
        const label = team.isFull ? '标记为已组队' : '完成组队';
        actions.push(`<button type="button" class="il-team-card__btn profile-team-action" data-action="teaming" data-id="${id}">${label}</button>`);
    }
    if (team.canClose) {
        actions.push(`<button type="button" class="il-team-card__btn profile-team-action" data-action="close" data-id="${id}">结束项目</button>`);
    }
    if (team.canDelete) {
        actions.push(`<button type="button" class="il-team-card__btn il-team-card__btn--danger profile-team-action" data-action="delete" data-id="${id}">删除</button>`);
    }
    return actions.join('');
}

function renderTeamActivityLists(activity) {
    const myTeams = document.getElementById('content-teams');
    const myApplications = document.getElementById('content-applications');
    if (!myTeams || !myApplications) return;
    const published = activity?.published || [];
    const applications = activity?.applications || [];
    const visibleTeams = filteredProfileTeams(published);

    const teamIcon = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>';
    const docIcon = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6z"/><path d="M14 2v6h6"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/></svg>';
    const arrowSvg = '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>';

    const controls = `
        <div class="il-activity-toolbar">
            <div class="il-activity-toolbar__filters">
                <select class="il-activity-toolbar__select" id="myTeamsStatusFilter">
                    <option value="ALL"${myTeamsStatusFilter === 'ALL' ? ' selected' : ''}>全部状态</option>
                    <option value="OPEN"${myTeamsStatusFilter === 'OPEN' ? ' selected' : ''}>招募中</option>
                    <option value="TEAMING"${myTeamsStatusFilter === 'TEAMING' ? ' selected' : ''}>已组队</option>
                    <option value="CLOSED"${myTeamsStatusFilter === 'CLOSED' ? ' selected' : ''}>已结束</option>
                </select>
                <select class="il-activity-toolbar__select" id="myTeamsSort">
                    <option value="createdDesc"${myTeamsSort === 'createdDesc' ? ' selected' : ''}>最新发布</option>
                    <option value="createdAsc"${myTeamsSort === 'createdAsc' ? ' selected' : ''}>最早发布</option>
                    <option value="deadlineAsc"${myTeamsSort === 'deadlineAsc' ? ' selected' : ''}>截止时间近</option>
                    <option value="applicantsDesc"${myTeamsSort === 'applicantsDesc' ? ' selected' : ''}>报名人数多</option>
                </select>
            </div>
            <a href="/team-publish.html" class="il-activity-toolbar__action">发布组队</a>
        </div>`;

    if (!published.length) {
        myTeams.innerHTML = `${controls}<div class="il-empty-state"><div class="il-empty-icon"><svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg></div><p class="il-empty-title">还没有组队</p><p class="il-empty-text">发布需求后，将在这里集中管理</p></div>`;
    } else if (!visibleTeams.length) {
        myTeams.innerHTML = `${controls}<div class="il-empty-state"><p class="il-empty-title">当前筛选无结果</p><p class="il-empty-text">切换状态筛选查看其他组队</p></div>`;
    } else {
        myTeams.innerHTML = `${controls}${visibleTeams.map((team) => {
            const id = Number(team.id);
            const title = escapeHtml(team.title || '未命名组队');
            const requiredCount = team.requiredMemberCount || '待定';
            const applicationCount = Number(team.applicationCount || 0);
            const approvedCount = Number(team.approvedMemberCount || 0);
            return '<div class="il-team-card ' + teamCardStatusClass(team.status) + '">' +
                '<div class="il-team-card__inner">' +
                '<div class="il-team-card__header">' +
                    '<div class="il-team-card__icon">' + teamIcon + '</div>' +
                    '<div class="il-team-card__body">' +
                        '<a class="il-team-card__title" href="/team-detail.html?id=' + id + '">' + title + '</a>' +
                        '<div class="il-team-card__meta">' +
                            '<span>发布：' + formatTime(team.createdAt) + '</span>' +
                            '<span class="il-team-card__meta-sep">·</span>' +
                            '<span>截止：' + formatDateOnlyProfile(team.deadline) + '</span>' +
                        '</div>' +
                        '<div class="il-team-card__meta">' +
                            '<span class="il-team-card__stat">报名 <span class="il-team-card__stat-value">' + applicationCount + '</span> 人</span>' +
                            '<span class="il-team-card__meta-sep">·</span>' +
                            '<span class="il-team-card__stat">已加入 <span class="il-team-card__stat-value">' + approvedCount + '</span> 人</span>' +
                            '<span class="il-team-card__meta-sep">·</span>' +
                            '<span class="il-team-card__stat">需求 <span class="il-team-card__stat-value">' + requiredCount + '</span> 人</span>' +
                        '</div>' +
                    '</div>' +
                    '<span class="il-team-card__badge ' + teamDemandStatusBadgeClass(team.status) + '">' + escapeHtml(teamDemandStatusLabel(team.status)) + '</span>' +
                    '<span class="il-team-card__arrow" aria-hidden="true">' + arrowSvg + '</span>' +
                '</div>' +
                '</div>' +
                '<div class="il-team-card__divider"></div>' +
                '<div class="il-team-card__actions">' + profileTeamActionsHtml(team) + '</div>' +
            '</div>';
        }).join('')}`;
    }

    if (!applications.length) {
        myApplications.innerHTML = '<div class="il-empty-state"><div class="il-empty-icon"><svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8l-6-6z"/><path d="M14 2v6h6"/><path d="M16 13H8"/><path d="M16 17H8"/><path d="M10 9H8"/></svg></div><p class="il-empty-title">还没有申请</p><p class="il-empty-text">申请加入感兴趣的团队吧</p></div>';
    } else {
        myApplications.innerHTML = applications.map((a) => {
            const title = escapeHtml(a.teamTitle || '组队');
            const st = teamApplicationStatusLabel(a.status);
            const tid = a.teamId != null ? Number(a.teamId) : '';
            return '<div class="il-team-card">' +
                '<div class="il-team-card__inner">' +
                '<div class="il-team-card__header">' +
                    '<div class="il-team-card__icon">' + docIcon + '</div>' +
                    '<div class="il-team-card__body">' +
                        '<a class="il-team-card__title" href="/team-detail.html?id=' + tid + '">' + title + '</a>' +
                        '<div class="il-team-card__meta">' +
                            '<span>' + escapeHtml(st) + '</span>' +
                            '<span class="il-team-card__meta-sep">·</span>' +
                            '<span>' + formatTime(a.createdAt) + '</span>' +
                        '</div>' +
                    '</div>' +
                    '<span class="il-team-card__badge ' + teamApplicationStatusBadge(a.status) + '">' + escapeHtml(st) + '</span>' +
                    '<span class="il-team-card__arrow" aria-hidden="true">' + arrowSvg + '</span>' +
                '</div>' +
                '</div>' +
                '<div class="il-team-card__divider"></div>' +
                '<div class="il-team-card__actions">' +
                    '<a href="/team-detail.html?id=' + tid + '" class="il-team-card__btn">查看组队</a>' +
                '</div>' +
            '</div>';
        }).join('');
    }

    bindProfileTeamActivityControls();
}

function bindProfileTeamActivityControls() {
    const statusSelect = document.getElementById('myTeamsStatusFilter');
    const sortSelect = document.getElementById('myTeamsSort');
    if (statusSelect) {
        statusSelect.addEventListener('change', function() {
            myTeamsStatusFilter = this.value || 'ALL';
            renderTeamActivityLists(lastActivity);
        });
    }
    if (sortSelect) {
        sortSelect.addEventListener('change', function() {
            myTeamsSort = this.value || 'createdDesc';
            renderTeamActivityLists(lastActivity);
        });
    }
    document.querySelectorAll('.profile-team-action').forEach((btn) => {
        btn.addEventListener('click', async function() {
            const id = this.dataset.id;
            const action = this.dataset.action;
            await handleProfileTeamAction(id, action);
        });
    });
}

async function handleProfileTeamAction(id, action) {
    if (!id) return;
    const actionConfig = {
        teaming: { status: 'TEAMING', confirmText: '确认完成组队，状态变为已组队？' },
        close: { status: 'CLOSED', confirmText: '确认结束该项目？' }
    };
    try {
        if (action === 'delete') {
            if (!confirm('确认删除该组队需求？删除后不可恢复。')) return;
            await request(`/team/${id}`, { method: 'DELETE' });
            showMessage('删除成功', 'success');
        } else if (actionConfig[action]) {
            const config = actionConfig[action];
            if (!confirm(config.confirmText)) return;
            await request(`/team/${id}/status`, {
                method: 'PUT',
                body: JSON.stringify({ status: config.status })
            });
            showMessage('状态已更新', 'success');
        }
        lastActivity = await fetchProfileActivity();
        renderTeamActivityLists(lastActivity);
    } catch (error) {
        console.error('组队操作失败:', error);
    }
}

document.addEventListener('DOMContentLoaded', async function () {
    const page = getProfilePage();

    /* 旧书签 /profile.html#honors、#settings */
    if (page === 'overview') {
        const hash = (window.location.hash || '').replace(/^#/, '');
        if (hash === 'settings') {
            window.location.replace('/profile-edit.html');
            return;
        }
        if (hash === 'honors') {
            window.location.replace('/profile-honors.html');
            return;
        }
    }

    if (page === 'public') {
        const params = new URLSearchParams(window.location.search);
        const uid = params.get('id');
        const userInfoContainer = document.getElementById('userInfo');
        const renderMissing = function (msg) {
            if (userInfoContainer) {
                userInfoContainer.innerHTML =
                    '<div class="alert alert-light border text-secondary">' + escapeHtml(msg) + '</div>';
            }
        };
        if (!uid || !String(uid).trim()) {
            renderMissing('链接无效：缺少用户 ID。');
            showMessage('缺少用户 ID', 'warning');
            return;
        }
        try {
            const r = await apiFetch('/api/user/public/' + encodeURIComponent(String(uid).trim()), {
                credentials: 'same-origin'
            });
            const ct = r.headers.get('content-type') || '';
            if (!ct.includes('application/json')) {
                showMessage('请先登录后查看', 'warning');
                setTimeout(function () {
                    window.location.href = '/login';
                }, 900);
                return;
            }
            const result = await r.json();
            if (Number(result.code) === 404) {
                renderMissing('用户不存在或已注销。');
                showMessage(result.message || '用户不存在', 'warning');
                return;
            }
            if (Number(result.code) !== 200 || !result.data) {
                renderMissing(result.message || '暂时无法加载该用户主页。');
                showMessage(result.message || '加载失败', 'error');
                return;
            }
            const honorsList = parseHonorsJson(result.data.honors).map(function (x) {
                return normalizeHonor(x);
            });
            renderPublicOverview(result.data, honorsList);
        } catch (e) {
            console.error(e);
            renderMissing('网络异常，请稍后重试。');
            showMessage('系统异常，请稍后重试', 'error');
        }
        return;
    }

    function bindAvatarPreview() {
        const inp = document.getElementById('avatar');
        const wrap = document.getElementById('avatarPreviewWrap');
        const img = document.getElementById('avatarPreviewImg');
        if (!inp || !wrap || !img) return;
        const apply = () => {
            const u = inp.value.trim();
            if (!u) {
                wrap.hidden = true;
                img.removeAttribute('src');
                return;
            }
            wrap.hidden = false;
            img.alt = '头像预览';
            img.src = u;
        };
        if (inp.dataset.avatarPreviewBound === '1') {
            apply();
            return;
        }
        inp.dataset.avatarPreviewBound = '1';
        inp.addEventListener('input', apply);
        inp.addEventListener('change', apply);
        img.addEventListener('error', () => {
            wrap.hidden = true;
        });
        apply();
    }

    function wireHonorsPage() {
        const honorsEditorRoot = document.getElementById('honorsEditor');
        if (honorsEditorRoot && honorsEditorRoot.dataset.honorsWired !== '1') {
            honorsEditorRoot.dataset.honorsWired = '1';

            honorsEditorRoot.addEventListener('click', function (e) {
                const editBtn = e.target.closest('.honor-btn-edit');
                if (editBtn) {
                    e.preventDefault();
                    const card = editBtn.closest('.honor-list-card');
                    const id = card && card.dataset.honorId;
                    if (id) openHonorModal({ honorId: id });
                }
            });

            honorsEditorRoot.addEventListener('change', function (e) {
                const t = e.target;
                if (t && t.classList && t.classList.contains('honor-select')) {
                    updateHonorsBatchToolbar();
                }
            });
        }

        const addHonorBtn = document.getElementById('addHonorBtn');
        if (addHonorBtn && addHonorBtn.dataset.honorsBound !== '1') {
            addHonorBtn.dataset.honorsBound = '1';
            addHonorBtn.addEventListener('click', function () {
                openHonorModal({ presetType: 'competition' });
            });
        }

        const manageBtn = document.getElementById('honorsToggleManageBtn');
        const listSection = document.getElementById('honorsListSection');
        if (manageBtn && listSection && manageBtn.dataset.honorsBound !== '1') {
            manageBtn.dataset.honorsBound = '1';
            manageBtn.addEventListener('click', function () {
                const on = listSection.classList.toggle('honors-manage-mode');
                manageBtn.setAttribute('aria-pressed', on ? 'true' : 'false');
                manageBtn.textContent = on ? '完成管理' : '管理成果';
                if (!on) {
                    const ed = document.getElementById('honorsEditor');
                    if (ed) {
                        ed.querySelectorAll('.honor-select').forEach(function (cb) {
                            cb.checked = false;
                        });
                    }
                    const s = document.getElementById('honorSelectAll');
                    if (s) {
                        s.checked = false;
                        s.indeterminate = false;
                    }
                }
                updateHonorsBatchToolbar();
            });
        }

        const typeSel = document.getElementById('oc-type-select');
        if (typeSel && typeSel.dataset.honorsBound !== '1') {
            typeSel.dataset.honorsBound = '1';
            typeSel.addEventListener('change', function () {
                const t = typeSel.value || 'other';
                setOffcanvasTitleLabel(t);
                syncOffcanvasSections(t);
            });
        }

        const selAll = document.getElementById('honorSelectAll');
        if (selAll && selAll.dataset.honorsBound !== '1') {
            selAll.dataset.honorsBound = '1';
            selAll.addEventListener('change', function () {
                const ed = document.getElementById('honorsEditor');
                if (!ed) return;
                const on = this.checked;
                ed.querySelectorAll('.honor-select').forEach(function (cb) {
                    cb.checked = on;
                });
                updateHonorsBatchToolbar();
            });
        }

        const batchDel = document.getElementById('batchDeleteHonorsBtn');
        if (batchDel && batchDel.dataset.honorsBound !== '1') {
            batchDel.dataset.honorsBound = '1';
            batchDel.addEventListener('click', function () {
                const ed = document.getElementById('honorsEditor');
                if (!ed) return;
                const ids = Array.prototype.map
                    .call(ed.querySelectorAll('.honor-select:checked'), function (cb) {
                        const c = cb.closest('.honor-list-card');
                        return c && c.dataset.honorId;
                    })
                    .filter(Boolean);
                if (!ids.length) return;
                if (!confirm('确定删除选中的 ' + ids.length + ' 条成果？')) return;
                honorsState = honorsState.filter(function (h) {
                    return ids.indexOf(String(h.id)) === -1;
                });
                renderHonorList();
                persistHonorsDraft();
            });
        }

        const ocSave = document.getElementById('oc-save');
        if (ocSave && ocSave.dataset.honorsBound !== '1') {
            ocSave.dataset.honorsBound = '1';
            ocSave.addEventListener('click', function () {
                commitHonorModal(true);
            });
        }
        const ocSaveCont = document.getElementById('oc-save-continue');
        if (ocSaveCont && ocSaveCont.dataset.honorsBound !== '1') {
            ocSaveCont.dataset.honorsBound = '1';
            ocSaveCont.addEventListener('click', function () {
                commitHonorModal(false);
            });
        }

        const proofFile = document.getElementById('oc-proof-file');
        const proofPick = document.getElementById('oc-proof-pick');
        const dz = document.getElementById('honorDropzone');
        function handleProofFile(file) {
            if (!file) return;
            const urlInp = document.getElementById('oc-proof-url');
            const st = document.getElementById('oc-proof-status');
            uploadAttachmentFile(file, 'proof')
                .then((result) => {
                    const u = result.data && (result.data.url || result.data.URL);
                    if (Number(result.code) === 200 && u && urlInp) {
                        urlInp.value = u;
                        if (st) st.textContent = '已上传';
                        showMessage('证明材料已上传', 'success');
                    } else {
                        showMessage(result.message || '上传失败', 'error');
                    }
                })
                .catch((err) => {
                    console.error(err);
                    showMessage('上传异常', 'error');
                });
        }
        if (proofPick && proofFile && proofPick.dataset.honorsBound !== '1') {
            proofPick.dataset.honorsBound = '1';
            proofPick.addEventListener('click', function () {
                proofFile.click();
            });
        }
        if (proofFile && proofFile.dataset.honorsBound !== '1') {
            proofFile.dataset.honorsBound = '1';
            proofFile.addEventListener('change', function () {
                const f = proofFile.files && proofFile.files[0];
                proofFile.value = '';
                handleProofFile(f);
            });
        }
        if (dz && dz.dataset.honorsDropBound !== '1') {
            dz.dataset.honorsDropBound = '1';
            ;['dragenter', 'dragover'].forEach(function (ev) {
                dz.addEventListener(ev, function (e) {
                    e.preventDefault();
                    e.stopPropagation();
                    dz.classList.add('honor-dropzone--active');
                });
            });
            dz.addEventListener('dragleave', function (e) {
                e.preventDefault();
                dz.classList.remove('honor-dropzone--active');
            });
            dz.addEventListener('drop', function (e) {
                e.preventDefault();
                e.stopPropagation();
                dz.classList.remove('honor-dropzone--active');
                const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
                handleProofFile(f);
            });
        }

        document.getElementById('saveHonorsBtn')?.addEventListener('click', async function () {
            const errMsg = validateHonorsBeforeSave();
            if (errMsg) {
                showMessage(errMsg, 'error');
                return;
            }
            try {
                const result = await saveProfilePayload(true);
                if (Number(result.code) === 200) {
                    showMessage('成果已保存', 'success');
                    const u = result.data;
                    if (u) {
                        honorsState = parseHonorsJson(u.honors).map((x) => normalizeHonor(x));
                        try {
                            localStorage.removeItem(HONORS_DRAFT_KEY);
                        } catch (e) {
                            /* ignore */
                        }
                        renderHonorsEditor();
                    }
                } else {
                    showMessage('保存失败: ' + (result.message || ''), 'error');
                }
            } catch (e) {
                console.error(e);
                showMessage('系统异常，请稍后重试', 'error');
            }
        });
    }

    function wireAvatarUploadPage() {
        const fileInput = document.getElementById('avatarFileInput');
        const btn = document.getElementById('avatarUploadBtn');
        const clearBtn = document.getElementById('avatarClearBtn');
        const hint = document.getElementById('avatarUploadHint');
        if (!fileInput || !btn || btn.dataset.wired === '1') return;
        btn.dataset.wired = '1';
        btn.addEventListener('click', () => fileInput.click());
        fileInput.addEventListener('change', async () => {
            const f = fileInput.files && fileInput.files[0];
            if (!f) return;
            if (hint) hint.textContent = '上传中…';
            try {
                const result = await uploadAttachmentFile(f, 'avatar');
                const okCode = Number(result.code) === 200;
                const url = result.data && (result.data.url || result.data.URL);
                if (okCode && url) {
                    const av = document.getElementById('avatar');
                    if (av) av.value = url;
                    bindAvatarPreview();
                    if (hint) hint.textContent = '已上传，请保存资料';
                    clearBtn && clearBtn.classList.remove('d-none');
                    showMessage('头像已上传，保存资料后将在全站生效', 'success');
                    if (typeof applyAccountMenuFromUser === 'function') {
                        const uEl = document.getElementById('username');
                        const rEl = document.getElementById('realName');
                        const roleEl = document.getElementById('role');
                        applyAccountMenuFromUser({
                            username: uEl ? uEl.value : '',
                            realName: rEl ? rEl.value : '',
                            avatar: url,
                            role: roleEl ? roleEl.value : ''
                        });
                    }
                } else {
                    if (hint) hint.textContent = '';
                    showMessage(result.message || '上传失败', 'error');
                }
            } catch (err) {
                console.error(err);
                if (hint) hint.textContent = '';
                showMessage('上传异常', 'error');
            }
            fileInput.value = '';
        });
        clearBtn &&
            clearBtn.addEventListener('click', () => {
                const av = document.getElementById('avatar');
                if (av) av.value = '';
                bindAvatarPreview();
                clearBtn.classList.add('d-none');
                if (hint) hint.textContent = '';
                if (typeof applyAccountMenuFromUser === 'function') {
                    const uEl = document.getElementById('username');
                    const rEl = document.getElementById('realName');
                    const roleEl = document.getElementById('role');
                    applyAccountMenuFromUser({
                        username: uEl ? uEl.value : '',
                        realName: rEl ? rEl.value : '',
                        avatar: '',
                        role: roleEl ? roleEl.value : ''
                    });
                }
            });
    }

    function updateAvatarClearButton() {
        const av = document.getElementById('avatar');
        const clearBtn = document.getElementById('avatarClearBtn');
        if (clearBtn && av) {
            clearBtn.classList.toggle('d-none', !String(av.value || '').trim());
        }
    }

    if (page === 'honors') {
        wireHonorsPage();
    }

    if (page === 'edit') {
        wireAvatarUploadPage();
        /* 从往返缓存恢复时 DOMContentLoaded 不会再次执行，需重新拉取并填表 */
        window.addEventListener('pageshow', function (ev) {
            if (!ev.persisted) return;
            apiFetch('/api/user/profile', { credentials: 'same-origin' })
                .then(function (r) {
                    const c = r.headers.get('content-type') || '';
                    if (!c.includes('application/json')) return null;
                    return r.json();
                })
                .then(function (result) {
                    if (!result || Number(result.code) !== 200) return;
                    const u = result.data;
                    honorsState = parseHonorsJson(u.honors).map(function (x) {
                        return normalizeHonor(x);
                    });
                    applyUserToProfileForm(u);
                    syncTeacherProfile(u, page);
                    bindAvatarPreview();
                    updateAvatarClearButton();
                })
                .catch(function () { showMessage('保存失败，请重试', 'error'); });
        });
    }

    try {
        const needActivity = page === 'overview';
        let response;
        if (needActivity) {
            const [r, act] = await Promise.all([
                apiFetch('/api/user/profile', { credentials: 'same-origin' }),
                fetchProfileActivity()
            ]);
            lastActivity = act;
            response = r;
        } else {
            response = await apiFetch('/api/user/profile', { credentials: 'same-origin' });
        }

        const ct = response.headers.get('content-type') || '';
        if (!ct.includes('application/json') && response.status === 401) {
            console.error('获取用户信息：响应非 JSON', response.status);
            showMessage('登录状态异常或会话已失效，请重新登录', 'warning');
            setTimeout(function () {
                window.location.href = '/login';
            }, 1400);
            return;
        }

        const result = await response.json();

        if (Number(result.code) !== 200 && Number(result.code) !== 401) {
            showMessage('获取用户信息失败: ' + (result.message || '请稍后重试'), 'error');
            return;
        }

        if (Number(result.code) === 200) {
            const user = result.data;
            honorsState = parseHonorsJson(user.honors).map((x) => normalizeHonor(x));

            applyUserToProfileForm(user);
            await syncTeacherProfile(user, page);

            if (page === 'overview') {
                bindAvatarPreview();
                const publicData = await fetchPublicProfileById(user.id);
                const merged = publicData
                    ? Object.assign({}, user, {
                          publishedPosts: Array.isArray(publicData.publishedPosts) ? publicData.publishedPosts : [],
                          bio: publicData.bio != null ? publicData.bio : user.bio
                      })
                    : user;
                renderOverview(merged, honorsState, lastActivity);
            } else if (page === 'edit') {
                bindAvatarPreview();
                updateAvatarClearButton();
                /* 不再自动 focus 邮箱：易触发浏览器自动填充覆盖接口刚写入的值 */
                (function scheduleEditFormRefill(u) {
                    function refill() {
                        applyUserToProfileForm(u);
                        bindAvatarPreview();
                        updateAvatarClearButton();
                    }
                    queueMicrotask(refill);
                    setTimeout(refill, 0);
                    setTimeout(refill, 80);
                    setTimeout(refill, 300);
                })(user);
            } else if (page === 'honors') {
                renderHonorsEditor();
                persistHonorsDraft();
            } else if (page === 'teams') {
                // 隐藏"我的活动"标题和tab栏，改为"我的小队"
                var titleEl = document.getElementById('activitySectionTitle');
                var tabsEl = document.getElementById('activityTabs');
                if (titleEl) titleEl.textContent = '我的小队';
                if (tabsEl) tabsEl.style.display = 'none';
                renderJoinedTeams();
            }
        } else {
            showMessage('获取用户信息失败: ' + result.message, 'error');
            setTimeout(() => {
                window.location.href = '/login';
            }, 1500);
        }
    } catch (error) {
        console.error('获取用户信息异常:', error);
        showMessage('系统异常，请稍后重试', 'error');
    }

    const profileForm = document.getElementById('profileForm');
    if (profileForm && page === 'edit') {
        profileForm.addEventListener('submit', async function (e) {
            e.preventDefault();
            if (!profileForm.checkValidity()) {
                profileForm.classList.add('was-validated');
                const invalid = profileForm.querySelector(':invalid');
                if (invalid) {
                    const label = invalid.id ? profileForm.querySelector(`label[for="${invalid.id}"]`) : null;
                    const fieldName = label ? label.textContent.replace('*', '').trim() : '表单项';
                    let message = invalid.validationMessage || `${fieldName}填写不正确`;
                    if (invalid.id === 'email') {
                        message = '请输入正确的邮箱地址，例如 name@example.com';
                    } else if (invalid.id === 'username') {
                        message = '请输入用户名';
                    }
                    invalid.focus();
                    showMessage(message, 'warning');
                } else {
                    showMessage('请检查表单必填项', 'warning');
                }
                return;
            }
            profileForm.classList.remove('was-validated');
            const btn = document.getElementById('profileSaveBtn');
            if (btn) {
                btn.disabled = true;
            }
            try {
                const result = await saveProfilePayload(false);
                if (Number(result.code) === 200) {
                    if (window.__ilinkProfileRole === 'TEACHER') {
                        try {
                            await saveTeacherProfilePayload();
                        } catch (teacherError) {
                            console.error('导师资料保存失败:', teacherError);
                            showMessage('基本资料已保存，但导师资料未保存：' + (teacherError.message || '请重试'), 'warning');
                            return;
                        }
                    }
                    showMessage('保存成功，资料已更新', 'success');
                    bindAvatarPreview();
                    if (result.data && typeof applyAccountMenuFromUser === 'function') {
                        applyAccountMenuFromUser(result.data);
                    }
                } else {
                    showMessage('更新失败: ' + (result.message || ''), 'error');
                }
            } catch (err) {
                console.error('更新资料异常:', err);
                showMessage('系统异常，请稍后重试', 'error');
            } finally {
                if (btn) btn.disabled = false;
            }
        });
    }
});
