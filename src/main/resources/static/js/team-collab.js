// 团队空间 · 协作资产：文档 / 表格 / 竞赛材料
(function () {
    'use strict';

    var params = new URLSearchParams(window.location.search);
    var teamId = params.get('id') || params.get('teamId');
    if (!teamId) return;

    var LANG = (document.documentElement.lang || 'zh').toLowerCase().indexOf('en') === 0
        ? 'en' : 'zh';
    var TEXT = {
        zh: {
            newDoc: '新建文档', newSheet: '新建表格', addRequirement: '添加要求',
            save: '保存', saved: '已保存', saving: '保存中…', dirty: '未保存',
            history: '历史版本', export: '导出', delete: '删除', restore: '恢复',
            readOnly: '只读', leaseHeld: '队友正在编辑',
            docTitle: '文档标题', untitled: '未命名文档', emptyDoc: '还没有团队文档',
            emptySheet: '还没有团队表格', sheetHint: '双击单元格直接输入内容',
            addRow: '添加一行', title: '标题', cancel: '取消', confirm: '确定',
            material: '竞赛材料', required: '必交', optional: '选交',
            addEvidence: '关联证据', reviewMet: '确认满足', reviewPartial: '部分满足',
            reviewMissing: '确认缺失', reviewPending: '标记待复核',
            noRequirement: '还没有材料要求，先添加或让 Agent 依据赛事通知生成建议',
            evidence: '证据', deletedSource: '来源已删除',
            pickEvidence: '选择证据来源', sourceFile: '资料库文件', sourceDoc: '团队文档',
            sourceSheet: '团队表格', sourceTask: '团队任务',
            excerpt: '补充说明（可选）', ok: '操作完成', failed: '操作失败，请稍后重试',
            visibility: '可见范围', teamVisible: '团队可见', restricted: '限定成员',
            permissions: '成员权限', grant: '添加授权', revoke: '移除授权',
            selectMember: '选择成员', view: '可查看', edit: '可编辑', manage: '可管理',
            filter: '筛选', all: '全部', exportCsv: '导出 CSV',
            titleRequired: '请填写文档标题', keepOneRow: '表格至少保留一行',
            editRequirement: '编辑要求', archive: '归档',
            archiveConfirm: '归档后将从材料矩阵中移除，确定继续吗？',
            removeRow: '删除此行', removeRowConfirm: '确定删除这一行吗？',
            sheetHistory: '表格历史版本', noVersions: '暂无历史版本',
            restrictedHint: '仅创建者、队长和已授权成员可见',
            authTitle: '成员授权', deleteDocConfirm: '确定删除这份文档吗？',
            deleteSheetConfirm: '确定删除这个表格吗？',
            discardDoc: '当前文档有未保存的修改，确定离开吗？',
            discardSheet: '当前表格有未保存的修改，确定离开吗？',
            noteTitle: '补充说明', notePlaceholder: '说明缺口或复核依据（可留空）',
            markdownPlaceholder: '用 Markdown 写正文…', manualSave: '手动保存',
            editMode: '编辑', previewMode: '预览', autosaved: '已保存',
            searchDoc: '搜索文档标题', searchSheet: '搜索表格标题',
            noDocMatch: '没有标题匹配的文档', noSheetMatch: '没有标题匹配的表格',
            mdHeading: '标题', mdSubheading: '小标题', mdBold: '粗体', mdItalic: '斜体',
            mdStrike: '删除线', mdBullet: '无序列表', mdNumber: '有序列表', mdQuote: '引用',
            mdCode: '代码', mdLink: '链接', mdTable: '表格', mdDivider: '分隔线',
            agentSuggestion: 'Agent 建议', remove: '移除',
            statusMet: '已确认满足', statusPartial: '部分满足', statusMissing: '确认缺失',
            statusReview: '待复核', statusUnchecked: '未核验',
            requiredMet: '必交已满足', metCount: '已确认满足', reviewCount: '待复核',
            missingCount: '确认缺失', uncheckedCount: '尚未核验',
            reqTitle: '标题', reqDescription: '说明', reqCategory: '分类',
            reqCategoryPlaceholder: '报名 / 佐证 / 承诺书', reqRequired: '必交材料',
            sourceType: '来源类型', selectItem: '选择条目', loading: '加载中…',
            noItems: '暂无可关联条目', taskColumn: '任务', ownerColumn: '负责人',
            statusColumn: '状态', deadlineColumn: '截止时间', visibilityTeamConfirm:
                '改为团队可见后，所有团队成员都能查看这份文档。确定继续吗？',
            visibilitySheetConfirm:
                '改为团队可见后，所有团队成员都能查看这个表格。确定继续吗？',
            conflictRefresh: '队友刚刚更新了共享数据。你的未保存内容仍在，请谨慎保存或刷新后再编辑。',
            member: '成员', noGrants: '暂无成员授权', removeEvidenceAria: '移除证据',
            trash: '回收站', trashTitle: '协作资产回收站', emptyTrash: '回收站里没有可恢复的资产',
            trashHint: '删除后保留 30 天，仅队长或资产创建者可恢复。',
            trashReadOnlyHint: '当前团队为只读状态，只能查看回收站；恢复和彻底删除暂不可用。',
            documentAsset: '文档', spreadsheetAsset: '表格',
            deletedAt: '删除于', expiresAt: '保留至', purge: '彻底删除',
            purgeTitle: '彻底删除', purgeConfirm: '彻底删除后无法恢复，确定继续吗？',
            restoredAsset: '资产已恢复', purgedAsset: '资产已彻底删除'
        },
        en: {
            newDoc: 'New document', newSheet: 'New table', addRequirement: 'Add requirement',
            save: 'Save', saved: 'Saved', saving: 'Saving…', dirty: 'Unsaved',
            history: 'History', export: 'Export', delete: 'Delete', restore: 'Restore',
            readOnly: 'Read-only', leaseHeld: 'A teammate is editing',
            docTitle: 'Document title', untitled: 'Untitled document',
            emptyDoc: 'No team documents yet', emptySheet: 'No team tables yet',
            sheetHint: 'Click a cell to edit', addRow: 'Add row', title: 'Title',
            cancel: 'Cancel', confirm: 'Confirm', material: 'Competition materials',
            required: 'Required', optional: 'Optional', addEvidence: 'Link evidence',
            reviewMet: 'Confirm met', reviewPartial: 'Partially met',
            reviewMissing: 'Confirm missing', reviewPending: 'Mark for review',
            noRequirement: 'No material requirements yet',
            evidence: 'Evidence', deletedSource: 'source deleted',
            pickEvidence: 'Choose evidence source', sourceFile: 'Library file',
            sourceDoc: 'Team document', sourceSheet: 'Team table', sourceTask: 'Team task',
            excerpt: 'Note (optional)', ok: 'Done',
            failed: 'Something went wrong, please retry',
            visibility: 'Visibility', teamVisible: 'Team', restricted: 'Selected members',
            permissions: 'Member access', grant: 'Add access', revoke: 'Revoke',
            selectMember: 'Select member', view: 'View', edit: 'Edit', manage: 'Manage',
            filter: 'Filter', all: 'All', exportCsv: 'Export CSV',
            titleRequired: 'Title is required', keepOneRow: 'Keep at least one row',
            editRequirement: 'Edit requirement', archive: 'Archive',
            archiveConfirm: 'Archive this requirement and remove it from the matrix?',
            removeRow: 'Delete row', removeRowConfirm: 'Delete this row?',
            sheetHistory: 'Table history', noVersions: 'No previous versions',
            restrictedHint: 'Visible only to the creator, leader and granted members',
            authTitle: 'Member access', deleteDocConfirm: 'Delete this document?',
            deleteSheetConfirm: 'Delete this table?',
            discardDoc: 'Discard unsaved document changes?',
            discardSheet: 'Discard unsaved table changes?',
            noteTitle: 'Review note', notePlaceholder: 'Describe the gap or evidence',
            markdownPlaceholder: 'Write in Markdown…', manualSave: 'Manual save',
            editMode: 'Edit', previewMode: 'Preview', autosaved: 'Autosaved',
            searchDoc: 'Search document titles', searchSheet: 'Search table titles',
            noDocMatch: 'No matching document title', noSheetMatch: 'No matching table title',
            mdHeading: 'Heading', mdSubheading: 'Subheading', mdBold: 'Bold', mdItalic: 'Italic',
            mdStrike: 'Strikethrough', mdBullet: 'Bulleted list', mdNumber: 'Numbered list',
            mdQuote: 'Quote', mdCode: 'Code', mdLink: 'Link', mdTable: 'Table',
            mdDivider: 'Divider',
            agentSuggestion: 'Agent suggestion', remove: 'Remove',
            statusMet: 'Met', statusPartial: 'Partially met', statusMissing: 'Missing',
            statusReview: 'Needs review', statusUnchecked: 'Unchecked',
            requiredMet: 'Required items met', metCount: 'Confirmed met',
            reviewCount: 'Needs review', missingCount: 'Confirmed missing',
            uncheckedCount: 'Not checked',
            reqTitle: 'Title', reqDescription: 'Description', reqCategory: 'Category',
            reqCategoryPlaceholder: 'Registration / evidence / commitment',
            reqRequired: 'Required material', sourceType: 'Source type',
            selectItem: 'Select item', loading: 'Loading…', noItems: 'No linkable item',
            taskColumn: 'Task', ownerColumn: 'Owner', statusColumn: 'Status',
            deadlineColumn: 'Deadline',
            visibilityTeamConfirm:
                'Changing visibility to Team makes this document readable by all members. Continue?',
            visibilitySheetConfirm:
                'Changing visibility to Team makes this table readable by all members. Continue?',
            conflictRefresh:
                'A teammate updated shared data. Your unsaved changes are intact; review before saving.',
            member: 'Member', noGrants: 'No explicit member grants',
            removeEvidenceAria: 'Remove evidence',
            trash: 'Trash', trashTitle: 'Collaboration asset trash',
            emptyTrash: 'No recoverable assets in trash',
            trashHint: 'Deleted assets stay here for 30 days. Only the leader or creator can restore them.',
            trashReadOnlyHint: 'This team is read-only. You can inspect trash, but restore and permanent deletion are disabled.',
            documentAsset: 'Document', spreadsheetAsset: 'Table',
            deletedAt: 'Deleted', expiresAt: 'Expires', purge: 'Delete permanently',
            purgeTitle: 'Delete permanently',
            purgeConfirm: 'This cannot be undone. Delete the asset permanently?',
            restoredAsset: 'Asset restored', purgedAsset: 'Asset permanently deleted'
        }
    };
    var T = TEXT[LANG];

    var state = {
        tab: 'docs',
        documents: [],
        spreadsheets: [],
        doc: null,
        docLease: null,
        docDirty: false,
        docReadOnly: false,
        docConflict: false,
        docMode: 'edit',
        docDraftText: null,
        docSaveTimer: null,
        docRenderedFor: null,
        docQuery: '',
        sheet: null,
        sheetLease: null,
        sheetDirty: false,
        sheetReadOnly: false,
        sheetConflict: false,
        sheetVersionsOpen: false,
        sheetSaveTimer: null,
        sheetRenderedFor: null,
        sheetFocus: null,
        sheetQuery: '',
        visibilityConsented: false,
        lastSaveAt: 0,
        matrix: null,
        members: null,
        matrixFilter: { status: '', required: '', category: '' },
        canMaintain: false,
        canCreateRequirement: false,
        // 由 /collaboration/bootstrap 下发的服务端能力；null 表示尚未拿到。
        caps: null,
        versionsOpen: false,
        renewTimer: null,
        saveInFlight: 0,
        loading: false
    };

    function byId(id) { return document.getElementById(id); }

    function esc(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function uuid() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID();
        }
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
            var r = Math.random() * 16 | 0;
            var v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }

    function notify(message, type) {
        if (typeof window.showMessage === 'function') {
            window.showMessage(message, type || 'success');
        }
    }

    function confirmAction(message, onConfirm) {
        openDialog(T.confirm, '<p class="tc-dialog__message">' + esc(message) + '</p>',
            [{ label: T.cancel, onClick: closeDialog },
                {
                    label: T.confirm, primary: true, onClick: async function () {
                        closeDialog();
                        await onConfirm();
                    }
                }]);
    }

    function confirmAsync(message) {
        return new Promise(function (resolve) {
            openDialog(T.confirm, '<p class="tc-dialog__message">' + esc(message) + '</p>',
                [{ label: T.cancel, onClick: function () { closeDialog(); resolve(false); } },
                    {
                        label: T.confirm, primary: true, onClick: function () {
                            closeDialog();
                            resolve(true);
                        }
                    }]);
        });
    }

    async function loadMembers() {
        if (Array.isArray(state.members)) return state.members;
        try {
            var rows = await api('/team-space/' + teamId + '/members', { silent: true });
            state.members = Array.isArray(rows) ? rows.filter(function (member) {
                return member && member.status !== 'PENDING';
            }) : [];
        } catch (error) {
            state.members = [];
        }
        return state.members;
    }

    async function api(path, options) {
        if (typeof window.request === 'function') {
            return window.request(path, options || {});
        }
        var response = await fetch('/api' + path, Object.assign({
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' }
        }, options || {}));
        var result = await response.json();
        if (!response.ok || !result || Number(result.code) !== 200) {
            var error = new Error(localizedError(result && result.message, result));
            error.status = response.status;
            error.response = result;
            error.errorCode = result && ((result.extra && result.extra.errorCode)
                || result.errorCode);
            throw error;
        }
        return result.data;
    }

    function errorPayload(error) {
        return error && error.response ? error.response : {};
    }

    function errorCodeOf(error) {
        var payload = errorPayload(error);
        return (payload.extra && payload.extra.errorCode)
            || payload.errorCode
            || (error && error.errorCode)
            || '';
    }

    function localizeError(error) {
        return localizedError(error && error.message, errorPayload(error));
    }

    function localizedError(message, data) {
        if (LANG !== 'en') return message || T.failed;
        var code = data && ((data.extra && data.extra.errorCode) || data.errorCode);
        var errors = {
            TEAM_READ_ONLY: 'This team is read-only in its current state.',
            ASSET_FORBIDDEN: 'You do not have permission to perform this action.',
            ASSET_NOT_FOUND: 'The requested collaboration asset was not found.',
            EDIT_LEASE_HELD: 'A teammate is currently editing this asset.',
            EDIT_LEASE_INVALID: 'Your editing lease is no longer valid.',
            EDIT_LEASE_EXPIRED: 'Your editing lease has expired. Please re-enter edit mode.',
            EDIT_VERSION_CONFLICT: 'A teammate updated this asset. Review the latest version.',
            REQUEST_ID_REUSED: 'This save request was already used for different content.',
            CONTENT_TOO_LARGE: 'The content exceeds the allowed size.',
            SPREADSHEET_SCHEMA_INVALID: 'The table content is invalid.',
            EXPORT_FAILED: 'The export could not be generated.',
            MATERIAL_EVIDENCE_INVALID: 'The material evidence is invalid or no longer available.',
            AGENT_ACTION_CONFLICT: 'The collaboration state changed. Please review and retry.'
        };
        if (errors[code]) return errors[code];
        return typeof window.translateUiCopyText === 'function'
            ? window.translateUiCopyText(message || T.failed) : (message || T.failed);
    }

    function showBanner(message, kind) {
        var banner = byId('tcBanner');
        if (!banner) return;
        if (!message) {
            banner.hidden = true;
            banner.textContent = '';
            banner.className = 'tc-banner';
            return;
        }
        banner.hidden = false;
        banner.textContent = message;
        banner.className = 'tc-banner' + (kind === 'danger' ? ' is-danger'
            : kind === 'warn' ? ' is-warn' : '');
    }

    function showLeaseHeld(message) {
        if ((state.tab === 'docs' && state.docConflict)
            || (state.tab === 'sheets' && state.sheetConflict)) return;
        showBanner(message, 'warn');
    }

    function showRemoteConflict() {
        if (state.tab === 'docs') state.docConflict = true;
        else if (state.tab === 'sheets') state.sheetConflict = true;
        showBanner(T.conflictRefresh, 'warn');
    }

    function formatDate(value) {
        if (!value) return '';
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value).slice(0, 10);
        var month = String(date.getMonth() + 1).padStart(2, '0');
        var day = String(date.getDate()).padStart(2, '0');
        return date.getFullYear() + '-' + month + '-' + day;
    }

    // ==================== 页签与动作 ====================

    function switchTab(tab) {
        state.tab = tab;
        document.querySelectorAll('[data-tc-tab]').forEach(function (button) {
            var active = button.dataset.tcTab === tab;
            button.classList.toggle('active', active);
            button.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        document.querySelectorAll('[data-tc-view]').forEach(function (view) {
            view.hidden = view.dataset.tcView !== tab;
        });
        renderHeadActions();
        if (tab === 'docs') loadDocuments();
        else if (tab === 'sheets') loadSpreadsheets();
        else loadMatrix();
    }

    function renderHeadActions() {
        var host = byId('tcHeadActions');
        if (!host) return;
        var canWrite = !state.caps || state.caps.canWrite !== false;
        var actions = '';
        if (state.tab === 'docs' || state.tab === 'sheets') {
            actions += '<button class="tc-btn tc-btn--ghost" type="button" id="tcTrashBtn">'
                + esc(T.trash) + '</button>';
        }
        if (state.tab === 'docs') {
            if (canWrite) {
                actions += '<button class="tc-btn tc-btn--primary" type="button" id="tcNewDocBtn">+ '
                    + esc(T.newDoc) + '</button>';
            }
            host.innerHTML = actions;
            var trashBtn = byId('tcTrashBtn');
            if (trashBtn) trashBtn.addEventListener('click', openTrashDialog);
            var newDocBtn = byId('tcNewDocBtn');
            if (newDocBtn) newDocBtn.addEventListener('click', createDocument);
        } else if (state.tab === 'sheets') {
            if (canWrite) {
                actions += '<button class="tc-btn tc-btn--primary" type="button" id="tcNewSheetBtn">+ '
                    + esc(T.newSheet) + '</button>';
            }
            host.innerHTML = actions;
            var trashBtn = byId('tcTrashBtn');
            if (trashBtn) trashBtn.addEventListener('click', openTrashDialog);
            var newSheetBtn = byId('tcNewSheetBtn');
            if (newSheetBtn) newSheetBtn.addEventListener('click', createSpreadsheet);
        } else {
            host.innerHTML = canWrite && state.canCreateRequirement
                ? '<button class="tc-btn tc-btn--primary" type="button" id="tcNewReqBtn">+ '
                    + esc(T.addRequirement) + '</button>' : '';
            if (canWrite && state.canCreateRequirement) {
                byId('tcNewReqBtn').addEventListener('click', openRequirementDialog);
            }
        }
    }

    async function openTrashDialog() {
        try {
            var rows = await api('/team/' + teamId + '/collaboration/trash');
            var assets = Array.isArray(rows) ? rows : [];
            var body = '<p class="tc-dialog__message">' + esc(T.trashHint) + '</p>';
            var canWrite = !state.caps || state.caps.canWrite !== false;
            if (!canWrite) {
                body += '<p class="tc-dialog__message tc-dialog__message--warn">'
                    + esc(T.trashReadOnlyHint) + '</p>';
            }
            if (!assets.length) {
                body += '<div class="tc-empty">' + esc(T.emptyTrash) + '</div>';
            } else {
                body += '<ul class="tc-trash-list">' + assets.map(function (asset) {
                    var isDoc = String(asset.assetType).toUpperCase() === 'DOCUMENT';
                    var typeLabel = isDoc ? T.documentAsset : T.spreadsheetAsset;
                    return '<li class="tc-trash-item" data-trash-item data-trash-type="'
                        + esc(asset.assetType) + '" data-trash-id="' + esc(asset.assetId)
                        + '" data-trash-version="' + esc(asset.version) + '">'
                        + '<span class="tc-trash-item__type">' + esc(typeLabel) + '</span>'
                        + '<span class="tc-trash-item__main"><strong>'
                        + esc(asset.title || T.untitled) + '</strong><span>'
                        + esc(T.deletedAt) + ' ' + esc(formatDate(asset.deletedAt))
                        + ' · ' + esc(T.expiresAt) + ' ' + esc(formatDate(asset.expiresAt))
                        + '</span></span>'
                        + (canWrite ? '<span class="tc-trash-item__actions">'
                            + '<button class="tc-btn tc-btn--ghost" type="button" data-trash-restore>'
                            + esc(T.restore) + '</button>'
                            + '<button class="tc-btn tc-btn--danger" type="button" data-trash-purge>'
                            + esc(T.purge) + '</button></span>' : '')
                        + '</li>';
                }).join('') + '</ul>';
            }
            openDialog(T.trashTitle, body, [{ label: T.cancel, onClick: closeDialog }]);
            document.querySelectorAll('[data-trash-restore]').forEach(function (button) {
                button.addEventListener('click', async function () {
                    var item = button.closest('[data-trash-item]');
                    if (!item) return;
                    button.disabled = true;
                    try {
                        await api('/team/' + teamId + '/collaboration/trash/'
                            + encodeURIComponent(item.dataset.trashType) + '/'
                            + encodeURIComponent(item.dataset.trashId) + '/restore', {
                            method: 'POST',
                            body: JSON.stringify({ version: Number(item.dataset.trashVersion) })
                        });
                        closeDialog();
                        await loadDocuments();
                        await loadSpreadsheets();
                        notify(T.restoredAsset);
                    } catch (error) {
                        button.disabled = false;
                        showBanner(localizeError(error), 'danger');
                    }
                });
            });
            document.querySelectorAll('[data-trash-purge]').forEach(function (button) {
                button.addEventListener('click', function () {
                    var item = button.closest('[data-trash-item]');
                    if (!item) return;
                    openPurgeDialog(item.dataset.trashType, item.dataset.trashId,
                        Number(item.dataset.trashVersion),
                        item.querySelector('strong') ? item.querySelector('strong').textContent : '');
                });
            });
        } catch (error) {
            showBanner(localizeError(error), 'danger');
        }
    }

    function openPurgeDialog(assetType, assetId, version, title) {
        openDialog(T.purgeTitle,
            '<p class="tc-dialog__message">' + esc(title || '') + '</p>'
                + '<p class="tc-dialog__message">' + esc(T.purgeConfirm) + '</p>',
            [{ label: T.cancel, onClick: openTrashDialog },
                {
                    label: T.purge, primary: true, onClick: async function () {
                        try {
                            await api('/team/' + teamId + '/collaboration/trash/'
                                + encodeURIComponent(assetType) + '/'
                                + encodeURIComponent(assetId)
                                + '?version=' + encodeURIComponent(version), { method: 'DELETE' });
                            notify(T.purgedAsset);
                            await openTrashDialog();
                        } catch (error) {
                            showBanner(localizeError(error), 'danger');
                        }
                    }
                }]);
    }

    // ==================== 文档 ====================

    /** 标题过滤：忽略大小写与首尾空格，空关键词表示不过滤。 */
    function filterByTitle(rows, query) {
        if (!query) return rows;
        return rows.filter(function (row) {
            return String(row.title || T.untitled).toLowerCase().indexOf(query) >= 0;
        });
    }

    /**
     * 首屏一次取回团队状态、角色能力和两份资产清单，避免三个接口串行等待。
     * 之后页签切换仍走各自的列表接口，bootstrap 只在首次载入和外部变更时刷新。
     */
    async function loadBootstrap() {
        var data = await api('/team/' + teamId + '/collaboration/bootstrap');
        if (!data) return null;
        state.caps = {
            teamStatus: data.teamStatus,
            role: data.role,
            canWrite: data.canWrite !== false,
            canExport: data.canExport === true,
            readOnlyReason: data.readOnlyReason || ''
        };
        state.documents = Array.isArray(data.documents) ? data.documents : [];
        state.spreadsheets = Array.isArray(data.spreadsheets) ? data.spreadsheets : [];
        return state.caps;
    }

    /** 只读原因：优先沿用服务端下发的说明，其次按团队状态兜底。 */
    function readOnlyReason() {
        if (!state.caps || state.caps.canWrite) return '';
        if (state.caps.readOnlyReason) return state.caps.readOnlyReason;
        return T.readOnly;
    }

    async function loadDocuments() {
        var list = byId('tcDocList');
        if (!list) return;
        try {
            var rows = await api('/team/' + teamId + '/documents');
            state.documents = Array.isArray(rows) ? rows : [];
            byId('tcDocCount').textContent = String(state.documents.length);
            if (state.doc && !state.documents.some(function (row) {
                return String(row.id) === String(state.doc.id);
            })) {
                stopRenew();
                state.doc = null;
                state.docLease = null;
                state.docDirty = false;
                state.docReadOnly = false;
                state.docConflict = false;
                byId('tcDocMain').innerHTML = '<div class="tc-empty tc-empty--center">'
                    + esc(T.emptyDoc) + '</div>';
            }
            renderDocumentList();
        } catch (error) {
            list.innerHTML = '<div class="tc-empty">' + esc(localizeError(error)) + '</div>';
        }
    }

    /** 列表渲染与网络加载分开，这样输入关键词时不会重复请求接口。 */
    function renderDocumentList() {
        var list = byId('tcDocList');
        if (!list) return;
        if (!state.documents.length) {
            list.innerHTML = '<div class="tc-empty">' + esc(T.emptyDoc) + '</div>';
            return;
        }
        var visible = filterByTitle(state.documents, state.docQuery);
        if (!visible.length) {
            list.innerHTML = '<div class="tc-empty">' + esc(T.noDocMatch) + '</div>';
            return;
        }
        list.innerHTML = visible.map(function (row) {
            var active = state.doc && state.doc.id === row.id ? ' active' : '';
            return '<button type="button" class="tc-list-item' + active
                + '" data-doc-id="' + esc(row.id) + '">'
                + '<span class="tc-list-item__title">' + esc(row.title || T.untitled) + '</span>'
                + '<span class="tc-list-item__meta">v' + esc(row.version) + ' · '
                + esc(formatDate(row.updatedAt)) + '</span></button>';
        }).join('');
        list.querySelectorAll('[data-doc-id]').forEach(function (item) {
            item.addEventListener('click', function () {
                openDocument(Number(item.dataset.docId));
            });
        });
    }

    function releaseLease(type, assetId, lease) {
        if (!type || !assetId || !lease || !lease.leaseToken) return Promise.resolve();
        return api('/team/' + teamId + '/collaboration/' + type + '/' + assetId + '/lease', {
            method: 'DELETE',
            body: JSON.stringify({ leaseToken: lease.leaseToken }),
            silent: true
        }).catch(function () { return null; });
    }

    async function openDocument(id) {
        if (state.doc && state.doc.id !== id && state.docDirty) {
            // Autosave means a dirty document is almost always a race with the
            // pending debounce, so flush it instead of asking to discard.
            if (state.docLease && !state.docReadOnly) {
                await saveDocument('AUTO');
                if (state.docDirty) {
                    confirmAction(T.discardDoc, function () { openDocument(id); });
                    return;
                }
            } else {
                confirmAction(T.discardDoc, function () { openDocument(id); });
                return;
            }
        }
        cancelDocSave();
        state.docRenderedFor = null;
        state.docDraftText = null;
        // A pending widening must not survive the asset it was granted for.
        state.visibilityConsented = false;
        stopRenew();
        await releaseLease('DOCUMENT', state.doc && state.doc.id, state.docLease);
        state.docLease = null;
        state.docConflict = false;
        try {
            var view = await api('/team/' + teamId + '/documents/' + id);
            state.doc = view;
            state.docDirty = false;
            // 团队整体只读时，资产上的 canEdit 也要一并降级。
            state.docReadOnly = !view.canEdit || (state.caps && state.caps.canWrite === false);
            state.docConflict = false;
            showBanner('');
            if (view.canEdit && (!state.caps || state.caps.canWrite !== false)) {
                try {
                    state.docLease = await api('/team/' + teamId + '/collaboration/DOCUMENT/'
                        + id + '/lease', { method: 'POST' });
                    startRenew('DOCUMENT', id, function () { return state.docLease; },
                        function () { return state.doc ? state.doc.version : 0; });
                } catch (leaseError) {
                    var payload = errorPayload(leaseError).data || {};
                    if (errorCodeOf(leaseError) === 'EDIT_LEASE_HELD') {
                        state.docReadOnly = true;
                        var holder = payload.activeLease && payload.activeLease.displayName;
                        showLeaseHeld((holder ? holder + ' ' : '') + T.leaseHeld);
                    } else {
                        throw leaseError;
                    }
                }
            }
            renderDocument();
            markActiveListItem('doc', id);
        } catch (error) {
            showBanner(localizeError(error), 'danger');
        }
    }

    function markActiveListItem(kind, id) {
        var attr = kind === 'doc' ? 'data-doc-id' : 'data-sheet-id';
        document.querySelectorAll('.tc-list-item').forEach(function (item) {
            item.classList.toggle('active', item.getAttribute(attr) === String(id));
        });
    }

    function renderDocument() {
        var host = byId('tcDocMain');
        var doc = state.doc;
        if (!doc) {
            host.innerHTML = '<div class="tc-empty tc-empty--center">' + esc(T.emptyDoc) + '</div>';
            return;
        }
        var readonly = state.docReadOnly;
        var preview = !readonly && state.docMode === 'preview';
        // Only the document currently on screen may borrow the live textarea,
        // otherwise switching assets would paste one doc's draft into another.
        var draft = docDraft();
        host.innerHTML = '<div class="tc-doc">'
            + '<div class="tc-doc__bar">'
            + '<input class="tc-doc__title" id="tcDocTitle" type="text" maxlength="120" value="'
            + esc(doc.title || '') + '"' + (readonly ? ' readonly' : '') + '>'
            + '<span class="tc-status" id="tcDocState" role="status" aria-live="polite" aria-atomic="true">'
            + esc(readonly ? T.readOnly : T.saved) + '</span>'
            + (readonly ? '' : '<div class="tc-doc__modes" role="group">'
                + '<button class="tc-mode-btn' + (preview ? '' : ' active') + '" type="button" '
                    + 'aria-pressed="' + (preview ? 'false' : 'true') + '" '
                    + 'data-doc-mode="edit">' + esc(T.editMode) + '</button>'
                + '<button class="tc-mode-btn' + (preview ? ' active' : '') + '" type="button" '
                    + 'aria-pressed="' + (preview ? 'true' : 'false') + '" '
                    + 'data-doc-mode="preview">' + esc(T.previewMode) + '</button></div>')
            + '<button class="tc-btn" type="button" id="tcDocHistoryBtn">' + esc(T.history) + '</button>'
            + '<button class="tc-btn" type="button" id="tcDocExportBtn">' + esc(T.export) + '</button>'
            + (doc.canManage ? '<button class="tc-btn" type="button" id="tcDocAclBtn">'
                + esc(T.permissions) + '</button>' : '')
            + (doc.canManage ? '<button class="tc-btn tc-btn--danger tc-btn--ghost" type="button" id="tcDocDeleteBtn">'
                + esc(T.delete) + '</button>' : '')
            + '<button class="tc-btn tc-btn--primary" type="button" id="tcDocSaveBtn"'
            + (readonly ? ' disabled' : '') + '>' + esc(T.save) + '</button>'
            + '</div>'
            + '<div class="tc-doc__meta"><span id="tcDocVersion">v' + esc(doc.version) + '</span>'
            + '<span id="tcDocEditor">' + esc(doc.lastEditorName || doc.creatorName || '') + '</span>'
            + '<span id="tcDocUpdated">' + esc(formatDate(doc.updatedAt)) + '</span>'
            + (doc.canManage ? '<label class="tc-visibility"><span>' + esc(T.visibility) + '</span>'
                + '<select id="tcDocVisibility"' + (readonly ? ' disabled' : '') + '>'
                + visibilityOptions(doc.visibility) + '</select></label>'
                : '<span>' + esc(doc.visibility === 'RESTRICTED' ? T.restricted : T.teamVisible) + '</span>')
            + '</div>'
            + (preview || readonly ? '' : docToolbarHtml())
            + (preview
                ? '<div class="tc-doc__preview" id="tcDocPreview" data-user-content></div>'
                : '<textarea class="tc-textarea" id="tcDocBody" placeholder="'
                    + esc(T.markdownPlaceholder) + '"' + (readonly ? ' readonly' : '') + '>'
                    + esc(draft) + '</textarea>')
            + '<div id="tcDocVersions"></div>'
            + '</div>';

        var title = byId('tcDocTitle');
        var body = byId('tcDocBody');
        function markDirty() {
            if (readonly) return;
            state.docDirty = true;
            if (body) state.docDraftText = body.value;
            setDocState(T.dirty, 'is-dirty');
            scheduleDocSave();
        }
        title.addEventListener('input', markDirty);
        if (body) {
            body.addEventListener('input', markDirty);
            body.addEventListener('keydown', onDocEditorKeydown);
            bindDocToolbar();
        }
        if (preview) renderMarkdownSafe(byId('tcDocPreview'), draft);
        host.querySelectorAll('[data-doc-mode]').forEach(function (button) {
            button.addEventListener('click', function () {
                if (body) state.docDraftText = body.value;
                state.docMode = button.dataset.docMode;
                renderDocument();
            });
        });
        var visibility = byId('tcDocVisibility');
        if (visibility) {
            visibility.addEventListener('change', function () {
                // Widening visibility must be consented to at the moment of the
                // change, so autosave can never publish a restricted doc.
                if (state.doc.visibility === 'RESTRICTED' && visibility.value === 'TEAM') {
                    confirmAsync(T.visibilityTeamConfirm).then(function (ok) {
                        if (!ok) {
                            visibility.value = 'RESTRICTED';
                            return;
                        }
                        state.visibilityConsented = 'DOCUMENT:' + state.doc.id;
                        markDirty();
                    });
                    return;
                }
                if (visibility.value === 'RESTRICTED') showBanner(T.restrictedHint, 'warn');
                markDirty();
            });
        }
        byId('tcDocSaveBtn').addEventListener('click', function () { saveDocument('MANUAL'); });
        byId('tcDocHistoryBtn').addEventListener('click', toggleVersions);
        byId('tcDocExportBtn').addEventListener('click', function () {
            window.location.href = '/api/team/' + teamId + '/documents/' + doc.id + '/export.md';
        });
        var deleteBtn = byId('tcDocDeleteBtn');
        if (deleteBtn) deleteBtn.addEventListener('click', deleteDocument);
        var aclBtn = byId('tcDocAclBtn');
        if (aclBtn) aclBtn.addEventListener('click', function () {
            openAclDialog('DOCUMENT', doc.id, doc.title);
        });
        if (state.versionsOpen) loadVersions();
        state.docRenderedFor = doc.id;
    }

    function docDraft() {
        var doc = state.doc;
        if (!doc) return '';
        if (String(state.docRenderedFor) !== String(doc.id)) {
            return doc.contentMarkdown || '';
        }
        var body = byId('tcDocBody');
        if (body) return body.value;
        if (typeof state.docDraftText === 'string') return state.docDraftText;
        return doc.contentMarkdown || '';
    }

    function docToolbarHtml() {
        var groups = [
            ['h2', 'H2', T.mdHeading, ' is-text'],
            ['h3', 'H3', T.mdSubheading, ' is-text'],
            ['bold', '<b>B</b>', T.mdBold, ''],
            ['italic', '<i>I</i>', T.mdItalic, ''],
            ['strike', '<s>S</s>', T.mdStrike, ''],
            ['sep'],
            ['bullet', '&bull;', T.mdBullet, ''],
            ['number', '1.', T.mdNumber, ' is-text'],
            ['quote', '&rdquo;', T.mdQuote, ''],
            ['code', '&lt;/&gt;', T.mdCode, ' is-text'],
            ['sep'],
            ['link', '&#128279;', T.mdLink, ''],
            ['table', '&#9638;', T.mdTable, ''],
            ['hr', '&mdash;', T.mdDivider, '']
        ];
        return '<div class="tc-doc__toolbar" role="toolbar">'
            + groups.map(function (item) {
                if (item[0] === 'sep') return '<span class="tc-md-sep" aria-hidden="true"></span>';
                return '<button class="tc-md-btn' + item[3] + '" type="button" data-md-action="'
                    + item[0] + '" title="' + esc(item[2]) + '" aria-label="' + esc(item[2]) + '">'
                    + item[1] + '</button>';
            }).join('') + '</div>';
    }

    function bindDocToolbar() {
        var bar = document.querySelector('.tc-doc__toolbar');
        if (!bar) return;
        bar.querySelectorAll('[data-md-action]').forEach(function (button) {
            button.addEventListener('click', function () {
                applyMarkdownAction(button.dataset.mdAction);
            });
        });
    }

    function onDocEditorKeydown(event) {
        if (!(event.ctrlKey || event.metaKey)) return;
        var key = String(event.key || '').toLowerCase();
        if (key === 's') {
            event.preventDefault();
            saveDocument('MANUAL');
            return;
        }
        var actions = { b: 'bold', i: 'italic', k: 'link' };
        if (!actions[key]) return;
        event.preventDefault();
        applyMarkdownAction(actions[key]);
    }

    /** Markdown 片段插入，与社区编辑器的工具栏行为保持一致。 */
    function applyMarkdownAction(action) {
        var ta = byId('tcDocBody');
        if (!ta || ta.readOnly) return;
        var start = ta.selectionStart, end = ta.selectionEnd;
        var text = ta.value, sel = text.substring(start, end), rep = '';
        var markers = {
            bold: { wrap: '**', label: T.mdBold },
            italic: { wrap: '*', label: T.mdItalic },
            strike: { wrap: '~~', label: T.mdStrike },
            code: { wrap: '`', label: T.mdCode },
            h2: { prefix: '## ', label: T.mdHeading },
            h3: { prefix: '### ', label: T.mdSubheading },
            bullet: { prefix: '- ', label: T.mdBullet },
            number: { prefix: '1. ', label: T.mdNumber },
            quote: { prefix: '> ', label: T.mdQuote },
            link: { prefix: '[', suffix: '](url)', label: T.mdLink },
            table: { line: '\n| ' + T.title + ' | ' + T.statusColumn + ' | ' + T.ownerColumn
                + ' |\n| --- | --- | --- |\n|  |  |  |\n' },
            hr: { line: '\n---\n' }
        };
        var m = markers[action];
        if (!m) return;
        if (m.line != null) {
            rep = m.line;
        } else if (m.prefix != null && m.suffix != null) {
            rep = m.prefix + (sel || m.label) + m.suffix;
        } else if (m.prefix != null) {
            var lineStart = text.lastIndexOf('\n', start - 1) + 1;
            rep = m.prefix + text.substring(lineStart, end);
            start = lineStart;
            end = lineStart + rep.length;
        } else if (m.wrap != null) {
            var w = m.wrap, wLen = w.length;
            var check = text.substring(Math.max(0, start - wLen), Math.min(text.length, end + wLen));
            if (check === w + sel + w) {
                rep = sel;
                start -= wLen;
                end += wLen;
            } else if (sel) {
                rep = w + sel + w;
            } else {
                rep = w + m.label + w;
                start += wLen;
                end = start + m.label.length;
            }
        }
        ta.focus();
        ta.setRangeText(rep, start, end, 'end');
        ta.dispatchEvent(new Event('input', { bubbles: true }));
    }

    function visibilityOptions(current) {
        return '<option value="TEAM"' + (current !== 'RESTRICTED' ? ' selected' : '') + '>'
            + esc(T.teamVisible) + '</option><option value="RESTRICTED"'
            + (current === 'RESTRICTED' ? ' selected' : '') + '>' + esc(T.restricted)
            + '</option>';
    }

    function setDocState(text, kind) {
        var chip = byId('tcDocState');
        if (!chip) return;
        chip.textContent = text;
        chip.className = 'tc-status' + (kind ? ' tc-status--' + kind : '');
    }

    /**
     * Autosave: 1.5s idle debounce with a 3s minimum gap between writes, one
     * request in flight at a time.
     */
    function scheduleDocSave() {
        if (state.docReadOnly || !state.doc || !state.docLease) return;
        cancelDocSave();
        var wait = Math.max(1500, 3000 - (Date.now() - (state.lastSaveAt || 0)));
        state.docSaveTimer = window.setTimeout(function () {
            state.docSaveTimer = null;
            saveDocument('AUTO');
        }, wait);
    }

    function cancelDocSave() {
        if (state.docSaveTimer) {
            window.clearTimeout(state.docSaveTimer);
            state.docSaveTimer = null;
        }
    }

    /**
     * Widening a restricted asset is consented once, for one asset, and is
     * consumed by the next successful save. Matching the recorded key keeps a
     * consent given on document A from silently publishing spreadsheet B.
     */
    function hasVisibilityConsent(assetType, assetId) {
        return state.visibilityConsented
            === assetType + ':' + String(assetId);
    }

    async function saveDocument(kind) {
        cancelDocSave();
        var doc = state.doc;
        var manual = kind !== 'AUTO';
        if (!doc || state.docReadOnly) return;
        var titleField = byId('tcDocTitle');
        var title = titleField ? titleField.value.trim() : (doc.title || '');
        if (!title) {
            if (manual) {
                notify(T.titleRequired, 'error');
                if (titleField) titleField.focus();
            } else {
                // An empty title would fail validation; the user may just be
                // mid-edit, so the autosave retries later instead of erroring.
                state.docSaveTimer = window.setTimeout(function () {
                    state.docSaveTimer = null;
                    saveDocument('AUTO');
                }, 4000);
            }
            return;
        }
        var content = docDraft();
        var visibility = byId('tcDocVisibility');
        var requestedVisibility = visibility ? visibility.value : doc.visibility;
        if (doc.visibility === 'RESTRICTED' && requestedVisibility === 'TEAM') {
            // Widening is consented once, at the moment the select changes.
            // Anything unattended must not publish a restricted asset.
            if (!hasVisibilityConsent('DOCUMENT', doc.id)) {
                requestedVisibility = 'RESTRICTED';
                if (visibility) visibility.value = 'RESTRICTED';
            }
        }
        if (state.saveInFlight > 0) {
            if (manual) scheduleDocSave();
            return;
        }
        setDocState(T.saving);
        state.saveInFlight += 1;
        try {
            var saved = await api('/team/' + teamId + '/documents/' + doc.id, {
                method: 'PUT',
                body: JSON.stringify({
                    title: title,
                    contentMarkdown: content,
                    visibility: requestedVisibility,
                    baseVersion: doc.version,
                    leaseToken: state.docLease ? state.docLease.leaseToken : null,
                    clientRequestId: uuid(),
                    saveKind: manual ? 'MANUAL' : 'AUTO',
                    changeSummary: manual ? T.manualSave : null
                }),
                silent: true
            });
            state.lastSaveAt = Date.now();
            // Consent is single-use: the next save must not widen on its own.
            state.visibilityConsented = false;
            var keepEditor = !manual && !state.docReadOnly && byId('tcDocBody');
            state.doc = saved;
            state.docDirty = false;
            state.docReadOnly = !saved.canEdit;
            state.docConflict = false;
            if (keepEditor && !state.docReadOnly) {
                // Autosave must not steal the caret away from the writer.
                updateDocMeta(saved);
                setDocState(T.autosaved, 'ok');
            } else {
                renderDocument();
                setDocState(T.saved, 'ok');
            }
            await loadDocuments();
            markActiveListItem('doc', saved.id);
        } catch (error) {
            state.lastSaveAt = Date.now();
            setDocState(T.dirty, 'is-dirty');
            var code = errorCodeOf(error);
            if (/VERSION|CONFLICT/.test(code)) {
                state.docConflict = true;
                showBanner(localizeError(error), 'danger');
                notify(localizeError(error), 'error');
            } else if (/LEASE/.test(code)) {
                state.docReadOnly = true;
                showLeaseHeld(T.leaseHeld);
                renderDocument();
            } else if (manual) {
                showBanner(localizeError(error), 'danger');
            }
        } finally {
            state.saveInFlight = Math.max(0, state.saveInFlight - 1);
        }
    }

    function updateDocMeta(doc) {
        var version = byId('tcDocVersion');
        var editor = byId('tcDocEditor');
        var updated = byId('tcDocUpdated');
        if (version) version.textContent = 'v' + doc.version;
        if (editor) editor.textContent = doc.lastEditorName || doc.creatorName || '';
        if (updated) updated.textContent = formatDate(doc.updatedAt);
    }

    async function toggleVersions() {
        state.versionsOpen = !state.versionsOpen;
        if (!state.versionsOpen) {
            var host = byId('tcDocVersions');
            if (host) host.innerHTML = '';
            return;
        }
        await loadVersions();
    }

    async function loadVersions() {
        var host = byId('tcDocVersions');
        if (!host || !state.doc) return;
        try {
            var rows = await api('/team/' + teamId + '/documents/' + state.doc.id
                + '/versions?limit=10');
            rows = Array.isArray(rows) ? rows : [];
            host.innerHTML = '<div class="tc-versions"><div class="tc-versions__head">'
                + esc(T.history) + '</div>' + rows.map(function (row) {
                    var current = state.doc.version === row.version;
                    return '<div class="tc-version"><span>v' + esc(row.version) + ' · '
                        + esc(formatDate(row.createdAt)) + ' · ' + esc(row.saveKind || '') + '</span>'
                        + (current || state.docReadOnly ? '' :
                            '<button class="tc-btn tc-btn--ghost" type="button" data-restore="'
                            + esc(row.version) + '">' + esc(T.restore) + '</button>')
                        + '</div>';
                }).join('') + '</div>';
            host.querySelectorAll('[data-restore]').forEach(function (button) {
                button.addEventListener('click', function () {
                    restoreVersion(Number(button.dataset.restore));
                });
            });
        } catch (error) {
            host.innerHTML = '<div class="tc-empty">' + esc(localizeError(error)) + '</div>';
        }
    }

    async function restoreVersion(version) {
        try {
            var restored = await api('/team/' + teamId + '/documents/' + state.doc.id
                + '/versions/' + version + '/restore', {
                method: 'POST',
                body: JSON.stringify({
                    leaseToken: state.docLease ? state.docLease.leaseToken : null,
                    clientRequestId: uuid()
                })
            });
            state.doc = restored;
            state.docDirty = false;
            state.docConflict = false;
            state.versionsOpen = false;
            renderDocument();
            await loadDocuments();
            markActiveListItem('doc', restored.id);
            notify(T.ok);
        } catch (error) {
            showBanner(localizeError(error), 'danger');
        }
    }

    async function deleteDocument() {
        if (!state.doc) return;
        confirmAction(T.deleteDocConfirm, async function () {
            try {
                await api('/team/' + teamId + '/documents/' + state.doc.id
                    + '?version=' + encodeURIComponent(state.doc.version), { method: 'DELETE' });
                state.doc = null;
                state.docLease = null;
                state.versionsOpen = false;
                byId('tcDocMain').innerHTML = '<div class="tc-empty tc-empty--center">'
                    + esc(T.emptyDoc) + '</div>';
                await loadDocuments();
                notify(T.ok);
            } catch (error) {
                showBanner(localizeError(error), 'danger');
            }
        });
    }

    async function createDocument() {
        await loadMembers();
        openDialog(T.newDoc,
            '<label class="tc-field">' + esc(T.title)
            + '<input type="text" id="tcNewDocTitle" maxlength="120" placeholder="'
            + esc(T.untitled) + '"></label>'
            + '<label class="tc-field">' + esc(T.visibility)
            + '<select id="tcNewDocVisibility"><option value="TEAM">' + esc(T.teamVisible)
            + '</option><option value="RESTRICTED">' + esc(T.restricted)
            + '</option></select></label>',
            [{ label: T.cancel, onClick: closeDialog },
                {
                    label: T.confirm, primary: true, onClick: async function () {
                        var title = byId('tcNewDocTitle').value.trim();
                        if (!title) { byId('tcNewDocTitle').focus(); return; }
                        var created = await api('/team/' + teamId + '/documents', {
                            method: 'POST',
                            body: JSON.stringify({
                                title: title, contentMarkdown: '',
                                visibility: byId('tcNewDocVisibility').value
                            })
                        });
                        closeDialog();
                        await loadDocuments();
                        await openDocument(created.id);
                    }
                }]);
        byId('tcNewDocTitle').focus();
    }

    // ==================== 表格 ====================

    async function loadSpreadsheets() {
        var list = byId('tcSheetList');
        if (!list) return;
        try {
            var rows = await api('/team/' + teamId + '/spreadsheets');
            state.spreadsheets = Array.isArray(rows) ? rows : [];
            byId('tcSheetCount').textContent = String(state.spreadsheets.length);
            if (state.sheet && !state.spreadsheets.some(function (row) {
                return String(row.id) === String(state.sheet.id);
            })) {
                stopRenew();
                state.sheet = null;
                state.sheetLease = null;
                state.sheetDirty = false;
                state.sheetReadOnly = false;
                state.sheetConflict = false;
                state.sheetVersionsOpen = false;
                byId('tcSheetMain').innerHTML = '<div class="tc-empty tc-empty--center">'
                    + esc(T.emptySheet) + '</div>';
            }
            renderSpreadsheetList();
        } catch (error) {
            list.innerHTML = '<div class="tc-empty">' + esc(localizeError(error)) + '</div>';
        }
    }

    function renderSpreadsheetList() {
        var list = byId('tcSheetList');
        if (!list) return;
        if (!state.spreadsheets.length) {
            list.innerHTML = '<div class="tc-empty">' + esc(T.emptySheet) + '</div>';
            return;
        }
        var visible = filterByTitle(state.spreadsheets, state.sheetQuery);
        if (!visible.length) {
            list.innerHTML = '<div class="tc-empty">' + esc(T.noSheetMatch) + '</div>';
            return;
        }
        list.innerHTML = visible.map(function (row) {
            var active = state.sheet && state.sheet.id === row.id ? ' active' : '';
            return '<button type="button" class="tc-list-item' + active
                + '" data-sheet-id="' + esc(row.id) + '">'
                + '<span class="tc-list-item__title">' + esc(row.title || T.untitled) + '</span>'
                + '<span class="tc-list-item__meta">v' + esc(row.version) + ' · '
                + esc(formatDate(row.updatedAt)) + '</span></button>';
        }).join('');
        list.querySelectorAll('[data-sheet-id]').forEach(function (item) {
            item.addEventListener('click', function () {
                openSpreadsheet(Number(item.dataset.sheetId));
            });
        });
    }

    async function openSpreadsheet(id) {
        if (state.sheet && state.sheet.id !== id && state.sheetDirty) {
            if (state.sheetLease && !state.sheetReadOnly) {
                await saveSpreadsheet('AUTO');
                if (state.sheetDirty) {
                    confirmAction(T.discardSheet, function () { openSpreadsheet(id); });
                    return;
                }
            } else {
                confirmAction(T.discardSheet, function () { openSpreadsheet(id); });
                return;
            }
        }
        cancelSheetSave();
        state.sheetRenderedFor = null;
        state.sheetFocus = null;
        state.visibilityConsented = false;
        stopRenew();
        await releaseLease('SPREADSHEET', state.sheet && state.sheet.id, state.sheetLease);
        state.sheetLease = null;
        state.sheetConflict = false;
        try {
            var view = await api('/team/' + teamId + '/spreadsheets/' + id);
            state.sheet = view;
            state.sheetVersionsOpen = false;
            state.sheetDirty = false;
            state.sheetReadOnly = !view.canEdit || (state.caps && state.caps.canWrite === false);
            state.sheetConflict = false;
            showBanner('');
            if (view.canEdit && (!state.caps || state.caps.canWrite !== false)) {
                try {
                    state.sheetLease = await api('/team/' + teamId + '/collaboration/SPREADSHEET/'
                        + id + '/lease', { method: 'POST' });
                    startRenew('SPREADSHEET', id, function () { return state.sheetLease; },
                        function () { return state.sheet ? state.sheet.version : 0; });
                } catch (leaseError) {
                    if (errorCodeOf(leaseError) === 'EDIT_LEASE_HELD') {
                        state.sheetReadOnly = true;
                        showLeaseHeld(T.leaseHeld);
                    } else {
                        throw leaseError;
                    }
                }
            }
            renderSpreadsheet();
            markActiveListItem('sheet', id);
        } catch (error) {
            showBanner(localizeError(error), 'danger');
        }
    }

    function renderSpreadsheet() {
        var host = byId('tcSheetMain');
        var sheet = state.sheet;
        if (!sheet) {
            host.innerHTML = '<div class="tc-empty tc-empty--center">' + esc(T.emptySheet) + '</div>';
            return;
        }
        var columns = Array.isArray(sheet.columns) ? sheet.columns : [];
        var rows = sheetDraftRows();
        var readonly = state.sheetReadOnly;
        var head = columns.map(function (col) {
            return '<th style="width:' + esc(Math.max(72, Math.min(480, col.width || 160))) + 'px"><span>'
                + esc(col.label || col.key) + '</span></th>';
        }).join('');
        var body = rows.map(function (row, index) {
            var cells = row.cells || {};
            return '<tr data-sheet-row="' + esc(row.id) + '"><td class="tc-grid__rowno">' + (index + 1)
                + (readonly ? '' : '<button class="tc-grid__remove" type="button" data-remove-row="'
                    + esc(row.id) + '" aria-label="' + esc(T.removeRow) + '" title="'
                    + esc(T.removeRow) + '">×</button>')
                + '</td>' + columns.map(function (col, colIndex) {
                return '<td><div contenteditable="' + (readonly ? 'false' : 'true')
                    + '" data-row-id="' + esc(row.id) + '" data-col-key="' + esc(col.key)
                    + '" data-col-index="' + colIndex + '" data-user-content tabindex="-1">'
                    + esc(cells[col.key] || '') + '</div></td>';
            }).join('') + '</tr>';
        }).join('');

        host.innerHTML = '<div class="tc-doc">'
            + '<div class="tc-doc__bar">'
            + '<input class="tc-doc__title" id="tcSheetTitle" type="text" maxlength="120" value="'
            + esc(sheet.title || '') + '"' + (readonly ? ' readonly' : '') + '>'
            + '<span class="tc-status" id="tcSheetState" role="status" aria-live="polite" aria-atomic="true">'
            + esc(readonly ? T.readOnly : T.saved) + '</span>'
            + '<button class="tc-btn" type="button" id="tcSheetCsvBtn">CSV</button>'
            + '<button class="tc-btn" type="button" id="tcSheetXlsxBtn">XLSX</button>'
            + '<button class="tc-btn" type="button" id="tcSheetHistoryBtn">' + esc(T.history) + '</button>'
            + (sheet.canManage ? '<button class="tc-btn" type="button" id="tcSheetAclBtn">'
                + esc(T.permissions) + '</button>' : '')
            + (sheet.canManage ? '<button class="tc-btn tc-btn--danger tc-btn--ghost" type="button" id="tcSheetDeleteBtn">'
                + esc(T.delete) + '</button>' : '')
            + (readonly ? '' : '<button class="tc-btn" type="button" id="tcSheetAddRowBtn">+ '
                + esc(T.addRow) + '</button>')
            + '<button class="tc-btn tc-btn--primary" type="button" id="tcSheetSaveBtn"'
            + (readonly ? ' disabled' : '') + '>' + esc(T.save) + '</button>'
            + '</div>'
            + '<div class="tc-doc__meta"><span>v' + esc(sheet.version) + '</span>'
            + '<span>' + esc(sheet.lastEditorName || '') + '</span>'
            + '<span>' + esc(formatDate(sheet.updatedAt)) + '</span>'
            + (sheet.canManage ? '<label class="tc-visibility"><span>' + esc(T.visibility) + '</span>'
                + '<select id="tcSheetVisibility"' + (readonly ? ' disabled' : '') + '>'
                + visibilityOptions(sheet.visibility) + '</select></label>'
                : '<span>' + esc(sheet.visibility === 'RESTRICTED' ? T.restricted : T.teamVisible) + '</span>')
            + '<span>' + esc(T.sheetHint) + '</span></div>'
            + '<div class="tc-sheet-wrap"><table class="tc-grid"><thead><tr>'
            + '<th class="tc-grid__rowno">#</th>' + head + '</tr></thead><tbody>'
            + body + '</tbody></table></div><div id="tcSheetVersions"></div></div>';

        var title = byId('tcSheetTitle');
        title.addEventListener('input', markSheetDirtyAndSave);
        host.querySelectorAll('[contenteditable="true"]').forEach(function (cell) {
            cell.addEventListener('input', markSheetDirtyAndSave);
            cell.addEventListener('keydown', onSheetCellKeydown);
            cell.addEventListener('blur', rememberSheetCell);
        });
        host.querySelectorAll('[data-remove-row]').forEach(function (button) {
            button.addEventListener('click', function () {
                removeSheetRow(button.dataset.removeRow);
            });
        });
        byId('tcSheetSaveBtn').addEventListener('click', function () {
            saveSpreadsheet('MANUAL');
        });
        title.addEventListener('keydown', function (event) {
            if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === 's') {
                event.preventDefault();
                saveSpreadsheet('MANUAL');
            }
        });
        byId('tcSheetCsvBtn').addEventListener('click', function () {
            window.location.href = '/api/team/' + teamId + '/spreadsheets/' + sheet.id + '/export.csv';
        });
        byId('tcSheetXlsxBtn').addEventListener('click', function () {
            window.location.href = '/api/team/' + teamId + '/spreadsheets/' + sheet.id + '/export.xlsx';
        });
        byId('tcSheetHistoryBtn').addEventListener('click', toggleSheetVersions);
        var aclBtn = byId('tcSheetAclBtn');
        if (aclBtn) aclBtn.addEventListener('click', function () {
            openAclDialog('SPREADSHEET', sheet.id, sheet.title);
        });
        var deleteBtn = byId('tcSheetDeleteBtn');
        if (deleteBtn) deleteBtn.addEventListener('click', deleteSpreadsheet);
        var visibility = byId('tcSheetVisibility');
        if (visibility) {
            visibility.addEventListener('change', function () {
                if (state.sheet.visibility === 'RESTRICTED' && visibility.value === 'TEAM') {
                    // Consent is captured here so an autosave can never widen
                    // a restricted table on its own.
                    confirmAsync(T.visibilitySheetConfirm).then(function (ok) {
                        if (!ok) {
                            visibility.value = 'RESTRICTED';
                            return;
                        }
                        state.visibilityConsented = 'SPREADSHEET:' + state.sheet.id;
                        markSheetDirtyAndSave();
                    });
                    return;
                }
                if (visibility.value === 'RESTRICTED') showBanner(T.restrictedHint, 'warn');
                markSheetDirtyAndSave();
            });
        }
        var addRowBtn = byId('tcSheetAddRowBtn');
        if (addRowBtn) addRowBtn.addEventListener('click', addSheetRow);
        if (state.sheetVersionsOpen) loadSheetVersions();
        state.sheetRenderedFor = sheet.id;
        restoreSheetFocus();
    }

    function markSheetDirty() {
        if (state.sheetReadOnly) return;
        state.sheetDirty = true;
        var chip = byId('tcSheetState');
        if (chip) {
            chip.textContent = T.dirty;
            chip.className = 'tc-status tc-status--dirty';
        }
    }

    function markSheetDirtyAndSave() {
        markSheetDirty();
        scheduleSheetSave();
    }

    function collectSheetRows() {
        var sheet = state.sheet;
        if (!sheet) return [];
        var byRow = {};
        (Array.isArray(sheet.rows) ? sheet.rows : []).forEach(function (row) {
            byRow[row.id] = Object.assign({}, row.cells || {});
        });
        // Cells are only read back from the DOM while that very table is on
        // screen; a stale grid must never leak into another sheet.
        if (String(state.sheetRenderedFor) === String(sheet.id)) {
            document.querySelectorAll('#tcSheetMain [contenteditable][data-row-id]')
                .forEach(function (cell) {
                    var rowId = cell.dataset.rowId;
                    var key = cell.dataset.colKey;
                    if (!byRow[rowId]) byRow[rowId] = {};
                    byRow[rowId][key] = cell.textContent || '';
                });
        }
        return sheet.rows.map(function (row) {
            return { id: row.id, cells: byRow[row.id] || {} };
        });
    }

    function sheetDraftRows() {
        var sheet = state.sheet;
        if (!sheet) return [];
        if (String(state.sheetRenderedFor) === String(sheet.id)) {
            var live = collectSheetRows();
            if (live.length) return live;
        }
        return Array.isArray(sheet.rows) ? sheet.rows : [];
    }

    /**
     * Grid keys: Tab/Shift+Tab walk across a row (wrapping into the next one),
     * Enter/Shift+Tab-free Enter walk down and up, Escape leaves the cell.
     * Moving past the last row appends one, which is what a team expects when
     * they fill a checklist top to bottom.
     */
    function onSheetCellKeydown(event) {
        var sheet = state.sheet;
        if (!sheet || state.sheetReadOnly) return;
        if ((event.ctrlKey || event.metaKey) && String(event.key).toLowerCase() === 's') {
            event.preventDefault();
            saveSpreadsheet('MANUAL');
            return;
        }
        if (event.ctrlKey || event.metaKey || event.altKey) return;
        var columns = Array.isArray(sheet.columns) ? sheet.columns : [];
        var rows = sheetDraftRows();
        var cell = event.currentTarget;
        if (!columns.length || !rows.length) return;
        var rowIndex = rows.findIndex(function (row) {
            return String(row.id) === String(cell.dataset.rowId);
        });
        var colIndex = Number(cell.dataset.colIndex);
        if (rowIndex < 0 || isNaN(colIndex)) return;
        if (event.key === 'Tab') {
            event.preventDefault();
            var flat = event.shiftKey ? colIndex - 1 : colIndex + 1;
            var nextRow = rowIndex + Math.floor(flat / columns.length);
            var nextCol = ((flat % columns.length) + columns.length) % columns.length;
            if (nextRow < 0) {
                focusSheetCell(rows[0], columns[0]);
            } else if (nextRow >= rows.length) {
                addSheetRow();
            } else {
                focusSheetCell(rows[nextRow], columns[nextCol]);
            }
            return;
        }
        if (event.key === 'Enter') {
            event.preventDefault();
            var target = event.shiftKey ? rowIndex - 1 : rowIndex + 1;
            if (target < 0) {
                focusSheetCell(rows[0], columns[colIndex]);
            } else if (target >= rows.length) {
                addSheetRow();
            } else {
                focusSheetCell(rows[target], columns[colIndex]);
            }
            return;
        }
        // 方向键在单元格之间移动；行尾向右、行首向左时落到相邻行，
        // 与 Excel / 飞书表格的习惯一致。仅在没有打开输入法组合时生效。
        if (!event.isComposing && (event.key === 'ArrowRight' || event.key === 'ArrowLeft'
            || event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
            var targetRow = rowIndex;
            var targetCol = colIndex;
            if (event.key === 'ArrowUp') targetRow -= 1;
            else if (event.key === 'ArrowDown') targetRow += 1;
            else if (event.key === 'ArrowRight') {
                targetCol += 1;
                if (targetCol >= columns.length) {
                    targetCol = 0;
                    targetRow += 1;
                }
            } else {
                targetCol -= 1;
                if (targetCol < 0) {
                    targetCol = columns.length - 1;
                    targetRow -= 1;
                }
            }
            if (targetRow < 0 || targetRow >= rows.length) return;
            event.preventDefault();
            focusSheetCell(rows[targetRow], columns[targetCol]);
            return;
        }
        if (event.key === 'Escape') {
            event.preventDefault();
            state.sheetFocus = null;
            cell.blur();
            if (byId('tcSheetTitle')) byId('tcSheetTitle').focus();
        }
    }

    function focusSheetCell(row, column) {
        if (!row || !column) return;
        state.sheetFocus = { rowId: row.id, colKey: column.key };
        var cell = findSheetCell(row.id, column.key);
        if (!cell) return;
        cell.focus();
        placeCaretAtEnd(cell);
        cell.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }

    function findSheetCell(rowId, colKey) {
        if (rowId == null || !colKey) return null;
        var cells = document.querySelectorAll('#tcSheetMain [contenteditable][data-row-id]');
        for (var i = 0; i < cells.length; i += 1) {
            if (String(cells[i].dataset.rowId) === String(rowId)
                && cells[i].dataset.colKey === colKey) {
                return cells[i];
            }
        }
        return null;
    }

    function placeCaretAtEnd(cell) {
        var range = document.createRange();
        range.selectNodeContents(cell);
        range.collapse(false);
        var selection = window.getSelection();
        if (!selection) return;
        selection.removeAllRanges();
        selection.addRange(range);
    }

    function rememberSheetCell(event) {
        var cell = event.currentTarget;
        if (!cell) return;
        state.sheetFocus = { rowId: cell.dataset.rowId, colKey: cell.dataset.colKey };
    }

    function restoreSheetFocus() {
        if (!state.sheetFocus || state.sheetReadOnly) return;
        var focus = state.sheetFocus;
        var cell = findSheetCell(focus.rowId, focus.colKey);
        if (!cell) return;
        cell.focus();
        placeCaretAtEnd(cell);
    }

    function addSheetRow() {
        if (!state.sheet || state.sheetReadOnly) return;
        var columns = Array.isArray(state.sheet.columns) ? state.sheet.columns : [];
        var newId = 'r_' + uuid().replace(/-/g, '').slice(0, 12);
        state.sheet.rows = collectSheetRows().concat([{
            id: newId,
            cells: {}
        }]);
        state.sheetDirty = true;
        state.sheetFocus = { rowId: newId, colKey: columns.length ? columns[0].key : null };
        renderSpreadsheet();
        markSheetDirty();
        scheduleSheetSave();
    }

    function removeSheetRow(rowId) {
        if (!state.sheet || state.sheetReadOnly) return;
        var rows = collectSheetRows();
        if (rows.length <= 1) {
            showBanner(T.keepOneRow, 'warn');
            return;
        }
        confirmAction(T.removeRowConfirm, function () {
            state.sheet.rows = rows.filter(function (row) { return row.id !== rowId; });
            state.sheetDirty = true;
            state.sheetFocus = null;
            renderSpreadsheet();
            markSheetDirty();
            scheduleSheetSave();
        });
    }

    function scheduleSheetSave() {
        if (state.sheetReadOnly || !state.sheet || !state.sheetLease) return;
        cancelSheetSave();
        var wait = Math.max(1500, 3000 - (Date.now() - (state.lastSaveAt || 0)));
        state.sheetSaveTimer = window.setTimeout(function () {
            state.sheetSaveTimer = null;
            saveSpreadsheet('AUTO');
        }, wait);
    }

    function cancelSheetSave() {
        if (state.sheetSaveTimer) {
            window.clearTimeout(state.sheetSaveTimer);
            state.sheetSaveTimer = null;
        }
    }

    async function saveSpreadsheet(kind) {
        cancelSheetSave();
        var sheet = state.sheet;
        var manual = kind !== 'AUTO';
        if (!sheet || state.sheetReadOnly) return;
        var titleField = byId('tcSheetTitle');
        var title = (titleField ? titleField.value.trim() : sheet.title) || T.untitled;
        var rows = collectSheetRows();
        var visibility = byId('tcSheetVisibility');
        var requestedVisibility = visibility ? visibility.value : sheet.visibility;
        if (sheet.visibility === 'RESTRICTED' && requestedVisibility === 'TEAM'
            && !hasVisibilityConsent('SPREADSHEET', sheet.id)) {
            requestedVisibility = 'RESTRICTED';
            if (visibility) visibility.value = 'RESTRICTED';
        }
        if (state.saveInFlight > 0) {
            if (manual) scheduleSheetSave();
            return;
        }
        var chip = byId('tcSheetState');
        if (chip) chip.textContent = T.saving;
        state.saveInFlight += 1;
        try {
            var saved = await api('/team/' + teamId + '/spreadsheets/' + sheet.id, {
                method: 'PUT',
                body: JSON.stringify({
                    title: title,
                    columns: sheet.columns,
                    rows: rows,
                    visibility: requestedVisibility,
                    baseVersion: sheet.version,
                    leaseToken: state.sheetLease ? state.sheetLease.leaseToken : null,
                    clientRequestId: uuid(),
                    saveKind: manual ? 'MANUAL' : 'AUTO',
                    changeSummary: manual ? T.manualSave : null
                }),
                silent: true
            });
            state.lastSaveAt = Date.now();
            state.visibilityConsented = false;
            state.sheet = saved;
            state.sheetDirty = false;
            state.sheetReadOnly = !saved.canEdit;
            state.sheetConflict = false;
            // A re-render would drop the caret, so an autosave only refreshes
            // the header line while the user is still typing in a cell.
            var keepGrid = !manual && findSheetCell(state.sheetFocus && state.sheetFocus.rowId,
                state.sheetFocus && state.sheetFocus.colKey) && !state.sheetReadOnly;
            if (keepGrid) {
                updateSheetMeta(saved);
                if (chip) {
                    chip.textContent = T.autosaved;
                    chip.className = 'tc-status tc-status--ok';
                }
            } else {
                renderSpreadsheet();
                if (chip) setSheetSaved(chip);
            }
            await loadSpreadsheets();
            markActiveListItem('sheet', saved.id);
        } catch (error) {
            state.lastSaveAt = Date.now();
            markSheetDirty();
            var code = errorCodeOf(error);
            if (/VERSION|CONFLICT/.test(code)) {
                state.sheetConflict = true;
                showBanner(localizeError(error), 'danger');
                notify(localizeError(error), 'error');
            } else if (/LEASE/.test(code)) {
                state.sheetReadOnly = true;
                showLeaseHeld(T.leaseHeld);
                renderSpreadsheet();
            } else if (manual) {
                showBanner(localizeError(error), 'danger');
            }
        } finally {
            state.saveInFlight = Math.max(0, state.saveInFlight - 1);
        }
    }

    function setSheetSaved(chip) {
        if (!chip) return;
        chip.textContent = T.saved;
        chip.className = 'tc-status tc-status--ok';
    }

    function updateSheetMeta(sheet) {
        var host = byId('tcSheetMain');
        if (!host) return;
        var meta = host.querySelector('.tc-doc__meta');
        if (!meta) return;
        var spans = meta.querySelectorAll('span');
        if (spans[0]) spans[0].textContent = 'v' + sheet.version;
        if (spans[1]) spans[1].textContent = sheet.lastEditorName || '';
        if (spans[2]) spans[2].textContent = formatDate(sheet.updatedAt);
    }

    async function toggleSheetVersions() {
        state.sheetVersionsOpen = !state.sheetVersionsOpen;
        if (!state.sheetVersionsOpen) {
            var host = byId('tcSheetVersions');
            if (host) host.innerHTML = '';
            return;
        }
        await loadSheetVersions();
    }

    async function loadSheetVersions() {
        var host = byId('tcSheetVersions');
        if (!host || !state.sheet) return;
        try {
            var rows = await api('/team/' + teamId + '/spreadsheets/' + state.sheet.id
                + '/versions?limit=10');
            rows = Array.isArray(rows) ? rows : [];
            host.innerHTML = '<div class="tc-versions"><div class="tc-versions__head">'
                + esc(T.sheetHistory) + '</div>' + (rows.length ? rows.map(function (row) {
                    var current = state.sheet.version === row.version;
                    return '<div class="tc-version"><span>v' + esc(row.version) + ' · '
                        + esc(formatDate(row.createdAt)) + ' · ' + esc(row.saveKind || '')
                        + (row.actorName ? ' · ' + esc(row.actorName) : '') + '</span>'
                        + (current || state.sheetReadOnly ? '' :
                            '<button class="tc-btn tc-btn--ghost" type="button" data-restore-sheet="'
                            + esc(row.version) + '">' + esc(T.restore) + '</button>')
                        + '</div>';
                }).join('') : '<div class="tc-empty">' + esc(T.noVersions) + '</div>')
                + '</div>';
            host.querySelectorAll('[data-restore-sheet]').forEach(function (button) {
                button.addEventListener('click', function () {
                    restoreSheetVersion(Number(button.dataset.restoreSheet));
                });
            });
        } catch (error) {
            host.innerHTML = '<div class="tc-empty">' + esc(localizeError(error)) + '</div>';
        }
    }

    async function restoreSheetVersion(version) {
        try {
            var restored = await api('/team/' + teamId + '/spreadsheets/' + state.sheet.id
                + '/versions/' + version + '/restore', {
                method: 'POST',
                body: JSON.stringify({
                    leaseToken: state.sheetLease ? state.sheetLease.leaseToken : null,
                    clientRequestId: uuid()
                })
            });
            state.sheet = restored;
            state.sheetDirty = false;
            state.sheetConflict = false;
            state.sheetVersionsOpen = false;
            renderSpreadsheet();
            await loadSpreadsheets();
            markActiveListItem('sheet', restored.id);
            notify(T.ok);
        } catch (error) {
            showBanner(localizeError(error), 'danger');
        }
    }

    async function deleteSpreadsheet() {
        if (!state.sheet) return;
        confirmAction(T.deleteSheetConfirm, async function () {
            try {
                await api('/team/' + teamId + '/spreadsheets/' + state.sheet.id
                    + '?version=' + encodeURIComponent(state.sheet.version), { method: 'DELETE' });
                state.sheet = null;
                state.sheetLease = null;
                state.sheetVersionsOpen = false;
                byId('tcSheetMain').innerHTML = '<div class="tc-empty tc-empty--center">'
                    + esc(T.emptySheet) + '</div>';
                await loadSpreadsheets();
                notify(T.ok);
            } catch (error) {
                showBanner(localizeError(error), 'danger');
            }
        });
    }

    async function createSpreadsheet() {
        await loadMembers();
        openDialog(T.newSheet,
            '<label class="tc-field">' + esc(T.title)
            + '<input type="text" id="tcNewSheetTitle" maxlength="120" placeholder="'
            + esc(T.untitled) + '"></label>'
            + '<label class="tc-field">' + esc(T.visibility)
            + '<select id="tcNewSheetVisibility"><option value="TEAM">' + esc(T.teamVisible)
            + '</option><option value="RESTRICTED">' + esc(T.restricted)
            + '</option></select></label>',
            [{ label: T.cancel, onClick: closeDialog },
                {
                    label: T.confirm, primary: true, onClick: async function () {
                        var title = byId('tcNewSheetTitle').value.trim();
                        if (!title) { byId('tcNewSheetTitle').focus(); return; }
                        var columns = [
                            { id: 'col_task', key: 'task', label: T.taskColumn, type: 'TEXT', width: 240 },
                            { id: 'col_owner', key: 'owner', label: T.ownerColumn, type: 'TEXT', width: 120 },
                            { id: 'col_status', key: 'status', label: T.statusColumn, type: 'TEXT', width: 110 },
                            { id: 'col_deadline', key: 'deadline', label: T.deadlineColumn, type: 'DATE', width: 130 }
                        ];
                        var created = await api('/team/' + teamId + '/spreadsheets', {
                            method: 'POST',
                            body: JSON.stringify({
                                title: title, columns: columns, rows: [],
                                visibility: byId('tcNewSheetVisibility').value
                            })
                        });
                        closeDialog();
                        await loadSpreadsheets();
                        await openSpreadsheet(created.id);
                    }
                }]);
        byId('tcNewSheetTitle').focus();
    }

    // ==================== 材料矩阵 ====================

    async function loadMatrix() {
        var host = byId('tcMaterial');
        if (!host) return;
        try {
            var query = new URLSearchParams();
            if (state.matrixFilter.status) query.set('status', state.matrixFilter.status);
            if (state.matrixFilter.required) query.set('required', state.matrixFilter.required);
            if (state.matrixFilter.category) query.set('category', state.matrixFilter.category);
            var matrix = await api('/team/' + teamId + '/materials/matrix'
                + (query.toString() ? '?' + query.toString() : ''));
            state.matrix = matrix;
            state.canMaintain = !!(matrix.competition && matrix.competition.canMaintain);
            state.canCreateRequirement = !!(matrix.competition
                && matrix.competition.canCreate && !matrix.competition.readOnly);
            renderMatrix();
            renderHeadActions();
        } catch (error) {
            host.innerHTML = '<div class="tc-empty">' + esc(localizeError(error)) + '</div>';
        }
    }

    function statusChip(status) {
        var map = {
            MET: ['ok', T.statusMet],
            PARTIAL: ['warn', T.statusPartial],
            MISSING: ['danger', T.statusMissing],
            NEEDS_REVIEW: ['info', T.statusReview],
            UNCHECKED: ['', T.statusUnchecked]
        };
        var item = map[status] || map.UNCHECKED;
        return '<span class="tc-chip ' + (item[0] ? 'tc-chip--' + item[0] : '') + '">'
            + esc(item[1]) + '</span>';
    }

    function renderMatrix() {
        var host = byId('tcMaterial');
        var matrix = state.matrix || { requirements: [], counts: {} };
        var rows = Array.isArray(matrix.requirements) ? matrix.requirements : [];
        var counts = matrix.counts || {};
        var filter = '<div class="tc-filter">'
            + '<label><span>' + esc(T.filter) + '</span><select id="tcMatrixStatus">'
            + filterOption('', T.all) + filterOption('UNCHECKED', T.statusUnchecked)
            + filterOption('NEEDS_REVIEW', T.statusReview)
            + filterOption('MET', T.statusMet)
            + filterOption('PARTIAL', T.statusPartial)
            + filterOption('MISSING', T.statusMissing)
            + '</select></label><label><span>' + esc(T.required) + '</span>'
            + '<select id="tcMatrixRequired">' + filterOption('', T.all)
            + filterOption('true', T.required) + filterOption('false', T.optional)
            + '</select></label><label><span>' + esc(T.exportCsv) + '</span>'
            + '<button class="tc-btn tc-btn--ghost" type="button" id="tcMatrixExportBtn">'
            + esc(T.exportCsv) + '</button></label></div>';
        var summary = '<div class="tc-summary">'
            + summaryCell(T.requiredMet, (matrix.requiredMet || 0) + ' / ' + (matrix.requiredTotal || 0))
            + summaryCell(T.metCount, counts.MET || 0)
            + summaryCell(T.reviewCount, counts.NEEDS_REVIEW || 0)
            + summaryCell(T.missingCount, counts.MISSING || 0)
            + summaryCell(T.uncheckedCount, counts.UNCHECKED || 0)
            + '</div>';
        if (!rows.length) {
            host.innerHTML = filter + summary + '<div class="tc-empty tc-empty--center">'
                + esc(T.noRequirement) + '</div>';
            bindMatrixFilters();
            return;
        }
        host.innerHTML = filter + summary + rows.map(renderRequirement).join('');
        bindMatrixFilters();
        bindRequirementActions();
    }

    function filterOption(value, label) {
        var current = value === 'true' ? state.matrixFilter.required === 'true'
            : value === 'false' ? state.matrixFilter.required === 'false'
                : value === state.matrixFilter.status;
        return '<option value="' + esc(value) + '"' + (current ? ' selected' : '') + '>'
            + esc(label) + '</option>';
    }

    function bindMatrixFilters() {
        var status = byId('tcMatrixStatus');
        var required = byId('tcMatrixRequired');
        var exportBtn = byId('tcMatrixExportBtn');
        if (status) status.addEventListener('change', function () {
            state.matrixFilter.status = status.value;
            loadMatrix();
        });
        if (required) required.addEventListener('change', function () {
            state.matrixFilter.required = required.value;
            loadMatrix();
        });
        if (exportBtn) exportBtn.addEventListener('click', function () {
            window.location.href = '/api/team/' + teamId + '/materials/matrix/export.csv';
        });
    }

    function summaryCell(label, value) {
        return '<div class="tc-summary__cell"><span class="tc-summary__label">' + esc(label)
            + '</span><span class="tc-summary__value">' + esc(value) + '</span></div>';
    }

    function renderRequirement(row) {
        var evidence = Array.isArray(row.evidence) ? row.evidence : [];
        return '<article class="tc-req" data-req-id="' + esc(row.id) + '" data-version="'
            + esc(row.version) + '">'
            + '<div class="tc-req__head"><div><div class="tc-req__title">' + esc(row.title) + '</div>'
            + (row.description ? '<div class="tc-req__desc">' + esc(row.description) + '</div>' : '')
            + '</div>' + statusChip(row.reviewStatus) + '</div>'
            + '<div class="tc-req__tags">'
            + '<span class="tc-chip ' + (row.required ? 'tc-chip--warn' : '') + '">'
            + esc(row.required ? T.required : T.optional) + '</span>'
            + (row.category ? '<span class="tc-chip">' + esc(row.category) + '</span>' : '')
            + (row.sourceType === 'AGENT_SUGGESTED' ? '<span class="tc-chip tc-chip--info">'
                + esc(T.agentSuggestion) + '</span>' : '')
            + '</div>'
            + (evidence.length ? '<div class="tc-req__evidence">' + evidence.map(function (item) {
                return '<span class="tc-evidence">' + esc(item.assetType) + ' · '
                    + esc(item.label || ('#' + item.assetId))
                    + (item.deleted ? '（' + esc(T.deletedSource) + '）' : '')
                    + (item.canRemove ? '<button class="tc-evidence__remove" type="button" data-remove-evidence="'
                        + esc(item.id) + '" aria-label="' + esc(T.removeEvidenceAria)
                        + '">×</button>' : '')
                    + '</span>';
            }).join('') + '</div>' : '')
            + '<div class="tc-req__actions">'
            + (state.canCreateRequirement
                ? '<button class="tc-btn tc-btn--ghost" type="button" data-add-evidence="'
                    + esc(row.id) + '">' + esc(T.addEvidence) + '</button>' : '')
            + (row.canReview ? (row.canMaintain
                ? ['MET', 'PARTIAL', 'MISSING', 'NEEDS_REVIEW']
                : ['NEEDS_REVIEW']).map(function (status) {
                return '<button class="tc-btn tc-btn--ghost" type="button" data-review="' + status
                    + '">' + esc(reviewLabel(status)) + '</button>';
            }).join('') : '')
            + (row.canMaintain ? '<button class="tc-btn tc-btn--ghost" type="button" data-edit-requirement="'
                + esc(row.id) + '">' + esc(T.editRequirement) + '</button>'
                + '<button class="tc-btn tc-btn--danger tc-btn--ghost" type="button" data-archive-requirement="'
                + esc(row.id) + '">' + esc(T.archive) + '</button>' : '')
            + '</div></article>';
    }

    function reviewLabel(status) {
        return status === 'MET' ? T.reviewMet : status === 'PARTIAL' ? T.reviewPartial
            : status === 'MISSING' ? T.reviewMissing : T.reviewPending;
    }

    function bindRequirementActions() {
        document.querySelectorAll('#tcMaterial [data-add-evidence]').forEach(function (button) {
            button.addEventListener('click', function () {
                openEvidenceDialog(Number(button.dataset.addEvidence));
            });
        });
        document.querySelectorAll('#tcMaterial [data-review]').forEach(function (button) {
            button.addEventListener('click', function () {
                var card = button.closest('.tc-req');
                reviewRequirement(Number(card.dataset.reqId), button.dataset.review);
            });
        });
        document.querySelectorAll('#tcMaterial [data-remove-evidence]').forEach(function (button) {
            button.addEventListener('click', function () {
                removeEvidence(Number(button.dataset.removeEvidence));
            });
        });
        document.querySelectorAll('#tcMaterial [data-edit-requirement]').forEach(function (button) {
            button.addEventListener('click', function () {
                var card = button.closest('.tc-req');
                var row = (state.matrix.requirements || []).find(function (item) {
                    return String(item.id) === String(card.dataset.reqId);
                });
                if (row) openRequirementDialog(row);
            });
        });
        document.querySelectorAll('#tcMaterial [data-archive-requirement]').forEach(function (button) {
            button.addEventListener('click', function () {
                var card = button.closest('.tc-req');
                archiveRequirement(Number(card.dataset.reqId), Number(card.dataset.version));
            });
        });
    }

    async function reviewRequirement(id, status) {
        var note = null;
        if (status === 'PARTIAL' || status === 'MISSING') {
            var value = await askForNote();
            if (value === null) return;
            note = value;
        }
        try {
            await api('/team/' + teamId + '/materials/requirements/' + id + '/review', {
                method: 'PUT', body: JSON.stringify({ status: status, note: note })
            });
            await loadMatrix();
            notify(T.ok);
        } catch (error) {
            showBanner(localizeError(error), 'danger');
        }
    }

    async function removeEvidence(evidenceId) {
        try {
            await api('/team/' + teamId + '/materials/evidence/' + evidenceId, { method: 'DELETE' });
            await loadMatrix();
        } catch (error) {
            showBanner(localizeError(error), 'danger');
        }
    }

    function askForNote() {
        return new Promise(function (resolve) {
            openDialog(T.noteTitle,
                '<label class="tc-field">' + esc(T.noteTitle)
                + '<textarea id="tcReviewNote" maxlength="1000" placeholder="'
                + esc(T.notePlaceholder) + '"></textarea></label>',
                [{ label: T.cancel, onClick: function () { closeDialog(); resolve(null); } },
                    {
                        label: T.confirm, primary: true, onClick: function () {
                            var value = byId('tcReviewNote').value.trim();
                            closeDialog();
                            resolve(value);
                        }
                    }]);
        });
    }

    function openRequirementDialog(existing) {
        var row = existing || {};
        var editing = !!(existing && existing.id);
        openDialog(editing ? T.editRequirement : T.addRequirement,
            '<label class="tc-field">' + esc(T.reqTitle)
            + '<input type="text" id="tcReqTitle" maxlength="200" value="'
            + esc(row.title || '') + '"></label>'
            + '<label class="tc-field">' + esc(T.reqDescription)
            + '<textarea id="tcReqDesc" maxlength="2000">'
            + esc(row.description || '') + '</textarea></label>'
            + '<label class="tc-field">' + esc(T.reqCategory)
            + '<input type="text" id="tcReqCategory" maxlength="64" placeholder="'
            + esc(T.reqCategoryPlaceholder) + '" value="'
            + esc(row.category || '') + '"></label>'
            + '<label class="tc-field"><span><input type="checkbox" id="tcReqRequired"'
            + (row.required === false ? '' : ' checked') + '> '
            + esc(T.reqRequired) + '</span></label>',
            [{ label: T.cancel, onClick: closeDialog },
                {
                    label: T.confirm, primary: true, onClick: async function () {
                        var title = byId('tcReqTitle').value.trim();
                        if (!title) { byId('tcReqTitle').focus(); return; }
                        var payload = {
                            title: title,
                            description: byId('tcReqDesc').value.trim(),
                            category: byId('tcReqCategory').value.trim(),
                            required: byId('tcReqRequired').checked
                        };
                        if (editing) payload.version = row.version;
                        await api('/team/' + teamId + '/materials/requirements'
                            + (editing ? '/' + row.id : ''), {
                            method: editing ? 'PUT' : 'POST',
                            body: JSON.stringify(payload)
                        });
                        closeDialog();
                        await loadMatrix();
                        notify(T.ok);
                    }
                }]);
        byId('tcReqTitle').focus();
    }

    function archiveRequirement(id, version) {
        confirmAction(T.archiveConfirm, async function () {
            try {
                await api('/team/' + teamId + '/materials/requirements/' + id
                    + '?version=' + encodeURIComponent(version), { method: 'DELETE' });
                await loadMatrix();
                notify(T.ok);
            } catch (error) {
                showBanner(localizeError(error), 'danger');
            }
        });
    }

    async function openAclDialog(type, assetId, title) {
        var members = await loadMembers();
        var path = '/team/' + teamId + '/collaboration/' + type + '/' + assetId + '/acl';
        var grants = [];
        try {
            grants = await api(path);
            grants = Array.isArray(grants) ? grants : [];
        } catch (error) {
            showBanner(localizeError(error), 'danger');
            return;
        }
        var granted = {};
        grants.forEach(function (grant) { granted[String(grant.userId)] = true; });
        var candidates = members.filter(function (member) {
            return !granted[String(member.userId)];
        });
        var grantRows = grants.length ? grants.map(function (grant) {
            return '<div class="tc-acl-row"><span class="tc-acl-row__name">'
                + esc(grant.displayName || T.member) + '</span><select data-acl-permission="'
                + esc(grant.userId) + '">' + permissionOptions(grant.permission) + '</select>'
                + '<button class="tc-btn tc-btn--ghost tc-btn--danger" type="button" data-revoke-acl="'
                + esc(grant.userId) + '">' + esc(T.revoke) + '</button></div>';
        }).join('') : '<div class="tc-empty">' + esc(T.noGrants) + '</div>';
        openDialog(T.authTitle + (title ? ' · ' + title : ''),
            '<div class="tc-acl">' + grantRows
            + (candidates.length ? '<div class="tc-acl__add"><select id="tcAclMember">'
                + candidates.map(function (member) {
                    return '<option value="' + esc(member.userId) + '">'
                        + esc(member.displayName || member.username || T.member) + '</option>';
                }).join('') + '</select><select id="tcAclPermission">'
                + permissionOptions('VIEW') + '</select><button class="tc-btn tc-btn--primary" type="button" id="tcAclGrantBtn">'
                + esc(T.grant) + '</button></div>' : '')
            + '</div>',
            [{ label: T.cancel, onClick: closeDialog },
                {
                    label: T.confirm, primary: true, onClick: async function () {
                        closeDialog();
                        if (state.doc && type === 'DOCUMENT' && String(state.doc.id) === String(assetId)) {
                            await openDocument(assetId);
                        } else if (state.sheet && type === 'SPREADSHEET'
                            && String(state.sheet.id) === String(assetId)) {
                            await openSpreadsheet(assetId);
                        }
                    }
                }]);
        document.querySelectorAll('[data-revoke-acl]').forEach(function (button) {
            button.addEventListener('click', async function () {
                try {
                    await api(path + '/' + button.dataset.revokeAcl, { method: 'DELETE' });
                    closeDialog();
                    await openAclDialog(type, assetId, title);
                    notify(T.ok);
                } catch (error) {
                    notify(localizeError(error), 'error');
                }
            });
        });
        document.querySelectorAll('[data-acl-permission]').forEach(function (select) {
            select.addEventListener('change', async function () {
                try {
                    await api(path, {
                        method: 'POST',
                        body: JSON.stringify({
                            userId: Number(select.dataset.aclPermission),
                            permission: select.value
                        })
                    });
                    notify(T.ok);
                } catch (error) {
                    notify(localizeError(error), 'error');
                }
            });
        });
        var grantBtn = byId('tcAclGrantBtn');
        if (grantBtn) grantBtn.addEventListener('click', async function () {
            try {
                await api(path, {
                    method: 'POST',
                    body: JSON.stringify({
                        userId: Number(byId('tcAclMember').value),
                        permission: byId('tcAclPermission').value
                    })
                });
                closeDialog();
                await openAclDialog(type, assetId, title);
                notify(T.ok);
            } catch (error) {
                notify(localizeError(error), 'error');
            }
        });
    }

    function permissionOptions(current) {
        return [['VIEW', T.view], ['EDIT', T.edit], ['MANAGE', T.manage]].map(function (item) {
            return '<option value="' + item[0] + '"' + (current === item[0] ? ' selected' : '')
                + '>' + esc(item[1]) + '</option>';
        }).join('');
    }

    async function openEvidenceDialog(requirementId) {
        var sources = [
            ['FILE', T.sourceFile], ['DOCUMENT', T.sourceDoc],
            ['SPREADSHEET', T.sourceSheet], ['TASK', T.sourceTask]
        ];
        openDialog(T.pickEvidence,
            '<label class="tc-field">' + esc(T.sourceType) + '<select id="tcEvidenceType">'
            + sources.map(function (item) {
                return '<option value="' + item[0] + '">' + esc(item[1]) + '</option>';
            }).join('') + '</select></label>'
            + '<label class="tc-field">' + esc(T.selectItem)
            + '<select id="tcEvidenceAsset"></select></label>'
            + '<label class="tc-field">' + esc(T.excerpt)
            + '<textarea id="tcEvidenceExcerpt" maxlength="600"></textarea></label>',
            [{ label: T.cancel, onClick: closeDialog },
                {
                    label: T.confirm, primary: true, onClick: async function () {
                        var type = byId('tcEvidenceType').value;
                        var assetId = Number(byId('tcEvidenceAsset').value);
                        if (!assetId) return;
                        await api('/team/' + teamId + '/materials/requirements/' + requirementId
                            + '/evidence', {
                            method: 'POST',
                            body: JSON.stringify({
                                assetType: type,
                                assetId: assetId,
                                evidenceExcerpt: byId('tcEvidenceExcerpt').value.trim()
                            })
                        });
                        closeDialog();
                        await loadMatrix();
                        notify(T.ok);
                    }
                }]);
        var typeSelect = byId('tcEvidenceType');
        typeSelect.addEventListener('change', function () { fillEvidenceAssets(typeSelect.value); });
        fillEvidenceAssets(typeSelect.value);
    }

    async function fillEvidenceAssets(type) {
        var select = byId('tcEvidenceAsset');
        if (!select) return;
        select.innerHTML = '<option value="">' + esc(T.loading) + '</option>';
        try {
            var options = [];
            if (type === 'FILE') {
                var tree = await api('/team/' + teamId + '/library');
                flattenFiles(tree).forEach(function (file) {
                    options.push([file.id, file.name]);
                });
            } else if (type === 'DOCUMENT') {
                (await api('/team/' + teamId + '/documents')).forEach(function (doc) {
                    options.push([doc.id, doc.title]);
                });
            } else if (type === 'SPREADSHEET') {
                (await api('/team/' + teamId + '/spreadsheets')).forEach(function (sheet) {
                    options.push([sheet.id, sheet.title]);
                });
            } else {
                (await api('/team/' + teamId + '/tasks')).forEach(function (task) {
                    options.push([task.id, task.taskTitle || task.title]);
                });
            }
            select.innerHTML = options.length ? options.map(function (item) {
                return '<option value="' + esc(item[0]) + '">' + esc(item[1]) + '</option>';
            }).join('') : '<option value="">' + esc(T.noItems) + '</option>';
        } catch (error) {
            select.innerHTML = '<option value="">' + esc(localizeError(error)) + '</option>';
        }
    }

    function flattenFiles(nodes, out) {
        out = out || [];
        (Array.isArray(nodes) ? nodes : []).forEach(function (node) {
            if (!node) return;
            if (node.isFolder || node.folder) {
                flattenFiles(node.children || node.files, out);
            } else {
                out.push(node);
            }
        });
        return out;
    }

    // ==================== 租约续期 ====================

    function startRenew(type, assetId, leaseGetter, versionGetter) {
        stopRenew();
        state.renewTimer = window.setInterval(function () {
            var lease = leaseGetter();
            if (!lease) return;
            api('/team/' + teamId + '/collaboration/' + type + '/' + assetId + '/lease', {
                method: 'PUT',
                body: JSON.stringify({
                    leaseToken: lease.leaseToken,
                    baseVersion: versionGetter()
                }),
                silent: true
            }).catch(function (error) {
                if (/LEASE/.test(errorCodeOf(error))) {
                    stopRenew();
                    showLeaseHeld(T.leaseHeld);
                }
            });
        }, 30000);
    }

    function stopRenew() {
        if (state.renewTimer) {
            window.clearInterval(state.renewTimer);
            state.renewTimer = null;
        }
    }

    // ==================== 弹窗 ====================

    function openDialog(title, bodyHtml, buttons) {
        byId('tcDialogTitle').textContent = title;
        byId('tcDialogBody').innerHTML = bodyHtml;
        var foot = byId('tcDialogFoot');
        foot.innerHTML = '';
        (buttons || []).forEach(function (item) {
            var button = document.createElement('button');
            button.type = 'button';
            button.className = 'tc-btn' + (item.primary ? ' tc-btn--primary' : '');
            button.textContent = item.label;
            button.addEventListener('click', async function () {
                if (!item.onClick) return;
                button.disabled = true;
                try {
                    await item.onClick();
                } catch (error) {
                    notify(localizeError(error), 'error');
                } finally {
                    button.disabled = false;
                }
            });
            foot.appendChild(button);
        });
        var dialog = byId('tcDialog');
        dialog.hidden = false;
    }

    function closeDialog() {
        var dialog = byId('tcDialog');
        if (dialog) dialog.hidden = true;
    }

    // ==================== 初始化 ====================

    function init() {
        document.querySelectorAll('[data-tc-tab]').forEach(function (button) {
            button.addEventListener('click', function () { switchTab(button.dataset.tcTab); });
        });
        var docSearch = byId('tcDocSearch');
        if (docSearch) {
            docSearch.addEventListener('input', function () {
                state.docQuery = docSearch.value.trim().toLowerCase();
                renderDocumentList();
            });
        }
        var sheetSearch = byId('tcSheetSearch');
        if (sheetSearch) {
            sheetSearch.addEventListener('input', function () {
                state.sheetQuery = sheetSearch.value.trim().toLowerCase();
                renderSpreadsheetList();
            });
        }
        var dialog = byId('tcDialog');
        if (dialog) {
            dialog.addEventListener('click', function (event) {
                if (event.target === dialog) closeDialog();
            });
        }
        // The autosave debounce leaves a short window of unsaved work; warn
        // instead of silently dropping it when the tab closes.
        window.addEventListener('beforeunload', function (event) {
            var pending = state.docDirty || state.sheetDirty;
            if (!pending) return;
            event.preventDefault();
            event.returnValue = '';
        });
    }

    window.teamCollab = {
        closeDialog: closeDialog,
        activeLeaseToken: function (assetType, assetId) {
            if (String(assetType).toUpperCase() === 'DOCUMENT' && state.doc
                && String(state.doc.id) === String(assetId)) {
                return state.docLease ? state.docLease.leaseToken : null;
            }
            if (String(assetType).toUpperCase() === 'SPREADSHEET' && state.sheet
                && String(state.sheet.id) === String(assetId)) {
                return state.sheetLease ? state.sheetLease.leaseToken : null;
            }
            return null;
        },
        remoteChanged: function (event) {
            if (!event) return;
            if (state.saveInFlight > 0) return;
            var currentConflict = state.tab === 'docs' ? state.docConflict
                : state.tab === 'sheets' ? state.sheetConflict : false;
            if (currentConflict) {
                showRemoteConflict();
                return;
            }
            var editing = state.tab === 'docs' ? (state.doc && state.docDirty)
                : state.tab === 'sheets' ? (state.sheet && state.sheetDirty) : false;
            if (editing) {
                showRemoteConflict();
                return;
            }
            if (state.tab === 'docs') loadDocuments();
            else if (state.tab === 'sheets') loadSpreadsheets();
            else loadMatrix();
        },
        reload: function () {
            if (!state.loadedOnce) {
                loadFirstPaint();
                return;
            }
            if (state.tab === 'docs') loadDocuments();
            else if (state.tab === 'sheets') loadSpreadsheets();
            else loadMatrix();
        }
    };

    /**
     * 首屏：先用 bootstrap 拿到团队状态 + 权限 + 两份清单，渲染后再补齐
     * 当前页签需要的额外数据。bootstrap 失败时退回原来的按页签加载，
     * 让页面至少能显示错误原因而不是空白。
     */
    async function loadFirstPaint() {
        if (state.loadedOnce) return;
        state.loadedOnce = true;
        var shell = byId('teamCollab');
        if (shell) shell.setAttribute('aria-busy', 'true');
        try {
            await loadBootstrap();
            byId('tcDocCount').textContent = String(state.documents.length);
            byId('tcSheetCount').textContent = String(state.spreadsheets.length);
            renderDocumentList();
            renderSpreadsheetList();
            var reason = readOnlyReason();
            if (reason) showBanner(reason, 'warn');
        } catch (error) {
            showBanner(localizeError(error), 'danger');
        } finally {
            if (shell) shell.setAttribute('aria-busy', 'false');
        }
        switchTab(state.tab || 'docs');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
