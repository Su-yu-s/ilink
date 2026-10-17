// 批阅工作台：左侧待批阅任务与材料、中间预览、右侧逐份点评
// 原本长在组队详情页里，团队空间也要用它，故抽成公共脚本（两个页面都引入）。

/** 工作台所属团队：两个页面的 URL 都带 ?id= */
function reviewTeamId() {
    return new URLSearchParams(window.location.search).get('id') || '';
}

/** 工作台关闭后由页面注册的回调刷新自己的任务列表 */
window.onReviewWorkbenchClosed = window.onReviewWorkbenchClosed || null;

const wb = {
    taskId: null, submissionId: null, files: [], idx: 0,
    fileComments: {}, overall: '', verdict: null,
    // 左侧是「待批阅任务 → 各自材料」两级列表：进工作台时一次拉齐，切换任务不再发请求
    pendingTasks: [],
    materialsCache: {},
    /** 材料加载被权限拒绝时的提示，非空说明「看不到」是权限问题而非没有材料 */
    materialsError: null
};

/** 点评按「任务::文件名」存，避免不同任务下的同名附件互相覆盖 */
function wbCommentKey(taskId, fileName) {
    return Number(taskId) + '::' + fileName;
}

/** 从某任务的提交记录里取出最近一次的材料与元信息 */
function parseSubmission(submissions) {
    const latest = Array.isArray(submissions) && submissions.length ? submissions[0] : null;
    if (!latest) return { submissionId: null, files: [], submitterName: '', submittedAt: '', remark: '' };
    let materials = [];
    if (latest.attachments) {
        try {
            const parsed = JSON.parse(latest.attachments);
            materials = Array.isArray(parsed) ? parsed : [];
        } catch (e) { materials = []; }
    }
    const files = materials.map(function (item) {
        // 预览组件读的是 fileUrl，只给 url 会一路落到「文件加载中…」的兜底图标
        if (typeof item === 'string') {
            const name = item.split('|').pop() || '附件';
            const url = item.split('|')[0];
            return { name: name, url: url, fileUrl: url, fileType: wbFileTypeOf(name) };
        }
        const url = item.url || '';
        return {
            id: item.libraryFileId, name: item.name || '附件', url: url, fileUrl: url,
            fileType: wbFileTypeOf(item.name || ''), fileSize: item.size || null
        };
    }).filter(f => f.url);
    return {
        submissionId: latest.id, files: files,
        submitterName: latest.submitterName || '', submittedAt: latest.createdAt || '',
        remark: latest.content || ''
    };
}

/** 与 ILinkPreview.resolveType 保持一致的附件类型判断（批阅工作台用） */
function wbFileTypeOf(name) {
    const ext = String(name || '').split('.').pop().toLowerCase();
    if (['png','jpg','jpeg','gif','webp','bmp','svg'].includes(ext)) return 'img';
    if (['zip','rar','7z','tar','gz'].includes(ext)) return 'zip';
    if (['md','markdown'].includes(ext)) return 'md';
    if (ext === 'pdf') return 'pdf';
    if (['xlsx','xls'].includes(ext)) return 'excel';
    if (['pptx','ppt'].includes(ext)) return 'ppt';
    if (['docx','doc'].includes(ext)) return 'word';
    if (['txt','csv','json','xml','yml','yaml','log','sql','html','css','js','java','py'].includes(ext)) return 'text';
    return 'other';
}

async function openReviewWorkbench(taskId) {
    await loadPendingReviewTasks();
    if (!wb.pendingTasks.length) {
        showMessage('当前没有待批阅的任务', 'warning');
        // 独立页面上没有别的内容可看，提示过后送回任务看板
        if (document.body.dataset.appPage === 'team-review') {
            setTimeout(function () {
                window.location.href = '/team-space.html?id=' + encodeURIComponent(reviewTeamId());
            }, 1200);
        }
        return;
    }
    selectWbTask(taskId);
    // 清空上一次遗留的结论区，避免串味到新任务
    document.getElementById('rwFileComment').value = '';
    document.getElementById('rwOverallComment').value = '';
    document.getElementById('rwVdPass').classList.remove('active');
    document.getElementById('rwVdReject').classList.remove('active');
    document.getElementById('rwSubmitBtn').disabled = true;
    document.getElementById('rwVerdictHint').textContent = '通过后任务完成；驳回后任务回到进行中。';
    bindWbEvents();
    renderWbMaterials();
    renderWbFile();
    updateWbProgress();
    const bench = document.getElementById('reviewWorkbench');
    bench.hidden = false;
    requestAnimationFrame(() => bench.classList.add('show'));
}

/** 拉齐全部待批阅任务及其交付材料：左侧两级列表一次成型，切换任务不再发请求 */
async function loadPendingReviewTasks() {
    const tasks = await request('/tasks?teamId=' + reviewTeamId(), { silent: true });
    wb.pendingTasks = (Array.isArray(tasks) ? tasks : []).filter(function (t) {
        return String(t.status || '').toUpperCase() === 'REVIEW';
    });
    wb.materialsCache = {};
    wb.materialsError = null;
    await Promise.all(wb.pendingTasks.map(async function (task) {
        try {
            const subs = await request('/tasks/' + task.id + '/submissions', { silent: true });
            wb.materialsCache[task.id] = parseSubmission(subs);
        } catch (e) {
            wb.materialsCache[task.id] = parseSubmission(null);
            // 权限被拒时别静默降级成「没有材料」——那会把权限问题伪装成数据问题
            if (e && (e.status === 403 || e.code === 403)) {
                wb.materialsError = '没有查看任务材料的权限，请联系队长确认你的团队身份';
            }
        }
    }));
}

/** 切到某个待批阅任务；各任务已写的点评按 wbCommentKey 保留 */
function selectWbTask(taskId) {
    const task = wb.pendingTasks.find(t => Number(t.id) === Number(taskId)) || wb.pendingTasks[0];
    if (!task) return false;
    // 切换前先把当前文件的点评落袋
    const cur = wb.files[wb.idx];
    if (cur) wb.fileComments[wbCommentKey(wb.taskId, cur.name)] = document.getElementById('rwFileComment').value;
    const cached = wb.materialsCache[task.id] || parseSubmission(null);
    wb.taskId = task.id;
    wb.submissionId = cached.submissionId;
    wb.files = cached.files;
    wb.idx = 0;
    wb.overall = '';
    wb.verdict = null;
    document.getElementById('rwTitle').textContent = '批阅 · ' + (task.taskTitle || '任务');
    document.getElementById('rwSubtitle').innerHTML =
        `<span>${window.ILinkPreview.escapeHtml(cached.submitterName || '队员')}</span><span class="rw-dot">·</span>` +
        `<span>${window.ILinkPreview.escapeHtml(cached.submittedAt ? formatTime(cached.submittedAt) + ' 提交' : '暂无提交记录')}</span>` +
        `<span class="rw-dot">·</span><span>共 ${wb.files.length} 份材料</span>` +
        (cached.remark ? `<span class="rw-dot">·</span><span>"${window.ILinkPreview.escapeHtml(String(cached.remark).slice(0, 40))}"</span>` : '');
    const first = wb.files[0];
    document.getElementById('rwFileComment').value =
        first ? (wb.fileComments[wbCommentKey(task.id, first.name)] || '') : '';
    document.getElementById('rwOverallComment').value = '';
    document.getElementById('rwVdPass').classList.remove('active');
    document.getElementById('rwVdReject').classList.remove('active');
    document.getElementById('rwVerdictHint').textContent = '通过后任务完成；驳回后任务回到进行中。';
    document.getElementById('rwSubmitBtn').disabled = true;
    renderWbMaterials();
    renderWbFile();
    updateWbProgress();
    return true;
}

function bindWbEvents() {
    if (bindWbEvents.done) return;
    bindWbEvents.done = true;
    const close = function () { closeWorkbench(); };
    document.getElementById('rwBackBtn').addEventListener('click', close);
    document.getElementById('rwCancelBtn').addEventListener('click', close);
    document.getElementById('rwCancelBtn2').addEventListener('click', close);
    document.getElementById('rwFileComment').addEventListener('input', function (e) {
        const f = wb.files[wb.idx];
        if (f) wb.fileComments[wbCommentKey(wb.taskId, f.name)] = e.target.value;
        updateWbProgress();
    });
    document.getElementById('rwOverallComment').addEventListener('input', function (e) {
        wb.overall = e.target.value;
        updateWbVerdictState();
    });
    document.getElementById('rwVdPass').addEventListener('click', function () { setWbVerdict('PASS'); });
    document.getElementById('rwVdReject').addEventListener('click', function () { setWbVerdict('REJECT'); });
    document.getElementById('rwSubmitBtn').addEventListener('click', submitWorkbenchReview);
}

function renderWbMaterials() {
    const html = wb.pendingTasks.map(function (task) {
        const isCurrent = Number(task.id) === Number(wb.taskId);
        const cached = wb.materialsCache[task.id] || { files: [] };
        const files = isCurrent ? wb.files : cached.files;
        const done = files.filter(function (f) {
            return (wb.fileComments[wbCommentKey(task.id, f.name)] || '').trim();
        }).length;
        const mats = files.length
            ? files.map(function (f, i) {
                const has = (wb.fileComments[wbCommentKey(task.id, f.name)] || '').trim();
                const active = isCurrent && i === wb.idx;
                return `<div class="rw-mat-item${active ? ' active' : ''}" data-task="${Number(task.id)}" data-index="${i}">
                    <span class="rw-mat-icon">${window.ILinkPreview.fileIcon(f.fileType, f.name)}</span>
                    <span class="rw-mat-info"><span class="rw-mat-name">${window.ILinkPreview.escapeHtml(f.name)}</span>
                    <span class="rw-mat-meta">${window.ILinkPreview.escapeHtml(window.ILinkPreview.typeLabel(f.fileType, f.name))}</span></span>
                    <span class="rw-mat-badge${has ? ' ok' : ''}">${has ? '&#10003;' : ''}</span>
                </div>`;
            }).join('')
            : '<div class="rw-mat-none">提交里没有附件材料</div>';
        return `<div class="rw-task-group${isCurrent ? ' active' : ''}">
            <div class="rw-task-head" data-task="${Number(task.id)}">
                <span class="rw-task-name">${window.ILinkPreview.escapeHtml(task.taskTitle || '未命名任务')}</span>
                <span class="rw-task-sub">${window.ILinkPreview.escapeHtml(task.assigneeName || '未分配')} · ${done}/${files.length}</span>
            </div>
            <div class="rw-task-mats">${mats}</div>
        </div>`;
    }).join('');
    document.getElementById('rwMatList').innerHTML = html;
    document.querySelectorAll('#rwMatList .rw-mat-item').forEach(function (item) {
        item.addEventListener('click', function () {
            const taskId = Number(item.dataset.task);
            if (taskId !== Number(wb.taskId)) selectWbTask(taskId);
            switchWbFile(Number(item.dataset.index));
        });
    });
    // 点任务标题就切过去看它的材料（首个文件）
    document.querySelectorAll('#rwMatList .rw-task-head').forEach(function (head) {
        head.addEventListener('click', function () {
            const taskId = Number(head.dataset.task);
            if (taskId !== Number(wb.taskId)) selectWbTask(taskId);
        });
    });
}

function switchWbFile(index) {
    const current = wb.files[wb.idx];
    if (current) wb.fileComments[wbCommentKey(wb.taskId, current.name)] = document.getElementById('rwFileComment').value;
    if (!wb.files[index]) return;
    wb.idx = index;
    document.getElementById('rwFileComment').value =
        wb.fileComments[wbCommentKey(wb.taskId, wb.files[index].name)] || '';
    renderWbFile();
    renderWbMaterials();
    updateWbProgress();
}

function renderWbFile() {
    const f = wb.files[wb.idx];
    if (!f) {
        document.getElementById('rwPvHead').innerHTML = '';
        document.getElementById('rwPvCanvas').innerHTML = wb.materialsError
            ? '<div class="ilpv-fallback"><div class="ilpv-fallback__name">材料加载失败</div>' +
              '<span class="ilpv-hinttext">' + window.ILinkPreview.escapeHtml(wb.materialsError) + '</span></div>'
            : '<div class="ilpv-fallback"><div class="ilpv-fallback__name">这个提交没有附件材料</div>' +
              '<span class="ilpv-hinttext">可直接在右侧写整体点评后给出结论</span></div>';
        document.getElementById('rwCurrentFile').innerHTML = '<span class="rw-fi-name">无材料</span>';
        return;
    }
    document.getElementById('rwPvHead').innerHTML =
        `<span class="rw-pv-icon">${window.ILinkPreview.fileIcon(f.fileType, f.name)}</span>` +
        `<span class="rw-pv-name">${window.ILinkPreview.escapeHtml(f.name)}</span>` +
        `<span class="rw-pv-sub">${window.ILinkPreview.escapeHtml(window.ILinkPreview.typeLabel(f.fileType, f.name))}</span>` +
        `<span class="rw-pv-hint">右侧写点评 · 切文件自动保存</span>`;
    window.ILinkPreview.renderInto(document.getElementById('rwPvCanvas'), f);
    document.getElementById('rwPvScroll').scrollTop = 0;
    document.getElementById('rwCurrentFile').innerHTML =
        `<span class="rw-fi-icon">${window.ILinkPreview.fileIcon(f.fileType, f.name)}</span>` +
        `<span class="rw-fi-name">${window.ILinkPreview.escapeHtml(f.name)}</span>`;
}

function updateWbProgress() {
    const total = wb.files.length;
    const done = wb.files.filter(function (f) {
        return (wb.fileComments[wbCommentKey(wb.taskId, f.name)] || '').trim();
    }).length;
    document.getElementById('rwProgress').textContent = done + ' / ' + total + ' 已点评';
    document.getElementById('rwReviewBadge').textContent = done + '/' + total;
    const cur = wb.files[wb.idx];
    const has = cur && (wb.fileComments[wbCommentKey(wb.taskId, cur.name)] || '').trim();
    const hint = document.getElementById('rwFileCommentHint');
    hint.textContent = has ? '已填写' : '未填写';
    hint.style.color = has ? '#10b981' : '#9aa1ab';
    renderWbMaterials();
}

function setWbVerdict(v) {
    wb.verdict = v;
    document.getElementById('rwVdPass').classList.toggle('active', v === 'PASS');
    document.getElementById('rwVdReject').classList.toggle('active', v === 'REJECT');
    const hint = document.getElementById('rwVerdictHint');
    if (v === 'REJECT') {
        hint.textContent = '驳回时，请至少填写一条点评（针对文件或整体）。';
        hint.classList.add('danger');
        hint.classList.remove('success');
    } else {
        hint.textContent = '通过后，任务将标记为已完成。';
        hint.classList.add('success');
        hint.classList.remove('danger');
    }
    updateWbVerdictState();
}

function updateWbVerdictState() {
    const btn = document.getElementById('rwSubmitBtn');
    if (!wb.verdict) { btn.disabled = true; return; }
    if (wb.verdict === 'REJECT') {
        // 只认当前任务的点评：上一条任务留下的点评不构成这条任务的驳回理由
        const prefix = wb.taskId + '::';
        const hasAny = (wb.overall && wb.overall.trim()) ||
            Object.keys(wb.fileComments)
                .some(k => k.indexOf(prefix) === 0 && (wb.fileComments[k] || '').trim());
        btn.disabled = !hasAny;
    } else {
        btn.disabled = false;
    }
}

async function submitWorkbenchReview() {
    if (!wb.verdict) { showMessage('请选择批阅结论', 'warning'); return; }
    const current = wb.files[wb.idx];
    if (current) wb.fileComments[wbCommentKey(wb.taskId, current.name)] = document.getElementById('rwFileComment').value;
    // 只提交当前任务的点评：列表里可能还挂着别的任务，别把它们一起带上
    const prefix = Number(wb.taskId) + '::';
    const fileComments = Object.keys(wb.fileComments)
        .filter(name => name.indexOf(prefix) === 0 && wb.fileComments[name] && wb.fileComments[name].trim())
        .map(name => ({ fileName: name.slice(prefix.length), comment: wb.fileComments[name].trim() }));
    const verdict = wb.verdict;
    try {
        await request('/tasks/' + wb.taskId + '/review', {
            method: 'PUT',
            body: JSON.stringify({
                // 工作台内部用 PASS/REJECT 表示结论，后端只认 COMPLETED/RETURNED
                action: verdict === 'PASS' ? 'COMPLETED' : 'RETURNED',
                submissionId: wb.submissionId,
                overall: wb.overall.trim(),
                fileComments: fileComments
            })
        });
        const doneTaskId = Number(wb.taskId);
        wb.pendingTasks = wb.pendingTasks.filter(function (t) { return Number(t.id) !== doneTaskId; });
        const verdictText = verdict === 'PASS' ? '已通过，任务完成' : '已驳回，任务回到进行中';
        // 还有别的待批阅就接着批，不必退出工作台
        if (wb.pendingTasks.length) {
            selectWbTask(wb.pendingTasks[0].id);
            showMessage(verdictText + '，已切到下一条', 'success');
            return;
        }
        showMessage(verdictText, 'success');
        closeWorkbench();
    } catch (error) {
        showMessage(error.message || '提交批阅失败', 'error');
    }
}

/**
 * 关掉工作台。
 * team-review.html 是只装工作台的独立页面，收起来就只剩白板了，所以直接回任务看板；
 * 嵌在组队详情页里时只需收起浮层并让页面刷新任务列表。
 */
function closeWorkbench() {
    const bench = document.getElementById('reviewWorkbench');
    if (!bench) return;
    if (document.body.dataset.appPage === 'team-review') {
        window.location.href = '/team-space.html?id=' + encodeURIComponent(reviewTeamId());
        return;
    }
    bench.classList.remove('show');
    setTimeout(function () { bench.hidden = true; notifyWorkbenchClosed(); }, 200);
}

/** 通知页面刷新自己的任务列表（团队空间 / 详情页各自注册回调） */
function notifyWorkbenchClosed() {
    if (typeof window.onReviewWorkbenchClosed === 'function') {
        window.onReviewWorkbenchClosed();
    }
}

/** 页面加载时若 URL 带 reviewTaskId，直接把工作台开到位 */
async function openReviewWorkbenchFromUrl() {
    const taskId = Number(new URLSearchParams(window.location.search).get('reviewTaskId'));
    if (!taskId) return;
    try {
        await openReviewWorkbench(taskId);
    } catch (error) {
        showMessage(error.message || '打开批阅工作台失败', 'error');
    } finally {
        // 无论成功失败都撤掉遮罩，别把人留在空白页
        document.documentElement.style.opacity = '';
    }
}

