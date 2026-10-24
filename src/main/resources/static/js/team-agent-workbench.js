(function () {
    'use strict';

    var root = null;
    var teamId = null;
    var initialized = false;
    var loadPromise = null;
    var state = {
        bootstrap: null,
        conversations: [],
        conversationCursor: null,
        currentConversation: null,
        messages: [],
        hasOlder: false,
        oldestSeq: null,
        activeRunId: null,
        lastSeq: 0,
        eventSource: null,
        reconnectTimer: null,
        mode: 'AUTO',
        busy: false,
        traces: [],
        actionGroups: [],
        selectedActions: {}
    };

    function byId(id) {
        return document.getElementById(id);
    }

    function uuid() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID();
        }
        var bytes = new Uint8Array(16);
        if (window.crypto && window.crypto.getRandomValues) {
            window.crypto.getRandomValues(bytes);
        } else {
            for (var i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
        }
        bytes[6] = (bytes[6] & 15) | 64;
        bytes[8] = (bytes[8] & 63) | 128;
        var hex = Array.prototype.map.call(bytes, function (value) {
            return value.toString(16).padStart(2, '0');
        }).join('');
        return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16)
            + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
    }

    function toast(type, title, desc) {
        if (window.Toast && typeof window.Toast[type] === 'function') {
            window.Toast[type](title, desc || '');
        } else if (type === 'error') {
            console.error(title, desc || '');
        }
    }

    async function api(path, options) {
        var opts = Object.assign({}, options || {});
        opts.headers = Object.assign({
            'Accept': 'application/json'
        }, opts.headers || {});
        if (opts.body && typeof opts.body !== 'string') {
            opts.headers['Content-Type'] = 'application/json';
            opts.body = JSON.stringify(opts.body);
        }
        var response = await apiFetch('/api/team/' + teamId + '/agent' + path, opts);
        var payload;
        try {
            payload = await response.json();
        } catch (error) {
            payload = null;
        }
        if (!response.ok || !payload || (payload.code != null && Number(payload.code) !== 200)) {
            var requestError = new Error(payload && payload.message
                ? payload.message : '团队 Agent 请求失败，请稍后重试');
            requestError.status = response.status;
            requestError.code = payload && payload.errorCode
                ? payload.errorCode : payload && payload.code;
            requestError.retryable = !!(payload && payload.retryable);
            throw requestError;
        }
        return payload.data;
    }

    function load() {
        if (loadPromise) return loadPromise;
        root.setAttribute('aria-busy', 'true');
        loadPromise = (async function () {
            try {
                state.bootstrap = await api('/bootstrap');
                renderBootstrap();
                await loadConversations(true);
                root.setAttribute('aria-busy', 'false');
                return true;
            } catch (error) {
                root.setAttribute('aria-busy', 'false');
                renderFatal(error);
                handleError(error);
                return false;
            } finally {
                loadPromise = null;
            }
        })();
        return loadPromise;
    }

    function renderBootstrap() {
        var boot = state.bootstrap || {};
        var team = boot.team || {};
        var competition = boot.competition || {};
        var capabilities = boot.capabilities || {};
        var competitionName = competition.name || competition.customCompetition || '未绑定竞赛';
        var resolution = competition.resolution === 'CUSTOM' ? '自定义竞赛'
            : competition.resolution === 'CATALOG' ? '竞赛目录' : '未绑定';
        byId('tawContextSummary').textContent = team.name + ' · ' + competitionName
            + ' · ' + roleLabel(team.role);

        var statusClass = team.status === 'TEAMING' ? 'is-success'
            : team.status === 'OPEN' ? 'is-warning' : 'is-danger';
        byId('tawContextMeta').innerHTML =
            '<span class="taw-status-chip ' + statusClass + '">' + escapeHtml(teamStatusLabel(team.status)) + '</span>'
            + '<span class="taw-meta-chip">' + escapeHtml(resolution) + '</span>'
            + '<span class="taw-meta-chip">' + escapeHtml(capabilities.readOnly ? '只读' : '可执行 · 需确认') + '</span>'
            + '<span class="taw-meta-chip">知识新鲜度 ' + escapeHtml(freshnessLabel(boot.knowledgeFreshness && boot.knowledgeFreshness.status)) + '</span>';

        byId('tawReadMode').textContent = capabilities.canReadAllTasks
            ? '当前团队全部任务' : '仅我参与或负责的任务';
        byId('tawFreshness').textContent = freshnessText(boot.knowledgeFreshness || {});
        byId('tawScopeNote').textContent =
            '当前团队（' + competitionName + '）+ 相关公开竞赛资料；不读取私聊、通知和 PRIVATE 图谱。';

        var limits = boot.limits || {};
        byId('tawLimits').innerHTML = [
            '单条消息最多 ' + (limits.messageMaxChars || 500) + ' 字；单次回答最多 '
                + (limits.answerMaxChars || 12000) + ' 字。',
            '动作提案默认 ' + Math.floor((limits.actionProposalTtlSeconds || 600) / 60)
                + ' 分钟内有效；单组最多 ' + (limits.maxActionsPerGroup || 10) + ' 个动作。',
            'Agent 不会审核任务、移除成员、解散团队、转让队长或修改团队状态。',
            '多人实时文档、表格和画布属于后续能力，当前工作台不提供。'
        ].map(function (line) { return '<li>' + escapeHtml(line) + '</li>'; }).join('');

        if (!capabilities.canAskKnowledge) {
            byId('tawLiveStatus').hidden = false;
            byId('tawLiveStatusText').textContent =
                'AI 服务未配置；团队统计、风险和周报草稿仍可使用。';
            byId('tawCancelRun').hidden = true;
        }
        if (capabilities.disabledReasons && capabilities.disabledReasons.length) {
            var reason = capabilities.disabledReasons[0];
            if (reason === 'TEAM_CLOSED') {
                byId('tawLiveStatus').hidden = false;
                byId('tawLiveStatusText').textContent =
                    '团队已关闭，因此只能查看历史内容，不能执行任务动作。';
                byId('tawCancelRun').hidden = true;
            }
        }
    }

    async function loadConversations(reset) {
        if (reset) {
            state.conversations = [];
            state.conversationCursor = null;
        }
        var result = await api('/conversations?limit=20'
            + (state.conversationCursor ? '&cursor=' + encodeURIComponent(state.conversationCursor) : ''));
        var rows = result && Array.isArray(result.items) ? result.items : [];
        state.conversations = reset ? rows : state.conversations.concat(rows);
        state.conversationCursor = result ? result.nextCursor : null;
        renderConversations();

        if (!state.currentConversation && state.conversations.length) {
            await selectConversation(state.conversations[0].conversationId);
        } else if (!state.currentConversation && !state.conversations.length) {
            showWelcome();
        }
    }

    function renderConversations() {
        var list = byId('tawConversationList');
        if (!state.conversations.length) {
            list.innerHTML = '<div class="taw-empty-mini">还没有会话<br>从一个问题开始</div>';
            byId('tawMoreConversations').hidden = true;
            return;
        }
        list.innerHTML = state.conversations.map(function (conversation) {
            var active = state.currentConversation
                && Number(state.currentConversation.conversationId) === Number(conversation.conversationId);
            return '<button type="button" class="taw-conversation-item' + (active ? ' is-active' : '')
                + '" data-conversation-id="' + Number(conversation.conversationId) + '">'
                + '<strong>' + escapeHtml(conversation.title || '新会话') + '</strong>'
                + '<small>' + escapeHtml(formatRelativeTime(conversation.lastMessageAt)) + '</small>'
                + '<span class="taw-conversation-delete" role="button" tabindex="0" '
                + 'data-delete-conversation="' + Number(conversation.conversationId) + '" aria-label="删除会话">'
                + closeIcon() + '</span></button>';
        }).join('');
        byId('tawMoreConversations').hidden = !state.conversationCursor;
    }

    async function selectConversation(conversationId) {
        if (state.busy && state.currentConversation
            && Number(state.currentConversation.conversationId) !== Number(conversationId)) {
            toast('warning', '请先等待当前请求完成');
            return;
        }
        closeEventSource();
        var result = await api('/conversations/' + Number(conversationId) + '?limit=50');
        state.currentConversation = result.conversation;
        state.messages = Array.isArray(result.messages) ? result.messages : [];
        state.hasOlder = !!result.hasOlder;
        state.oldestSeq = result.oldestSeq;
        state.activeRunId = result.activeRunId || null;
        state.lastSeq = 0;
        state.traces = [];
        state.selectedActions = {};
        root.classList.remove('is-conversations-open');
        byId('tawConversationToggle').setAttribute('aria-expanded', 'false');
        renderConversations();
        renderMessages();
        clearTrace();
        if (state.activeRunId) {
            setRunning(true, '正在恢复执行状态…');
            openEventStream(state.activeRunId, 0);
        } else {
            setRunning(false, '空闲');
        }
        await loadActions();
    }

    async function loadOlderMessages() {
        if (!state.currentConversation || !state.hasOlder || !state.oldestSeq) return;
        var conversationId = state.currentConversation.conversationId;
        var result = await api('/conversations/' + conversationId + '?limit=50&beforeSeq=' + state.oldestSeq);
        var older = Array.isArray(result.messages) ? result.messages : [];
        state.messages = older.concat(state.messages);
        state.hasOlder = !!result.hasOlder;
        state.oldestSeq = result.oldestSeq;
        renderMessages(true);
    }

    function showWelcome() {
        state.currentConversation = null;
        state.messages = [];
        state.activeRunId = null;
        state.traces = [];
        byId('tawWelcome').hidden = false;
        byId('tawMessageList').innerHTML = '';
        renderConversations();
        clearTrace();
        renderActions([]);
        setRunning(false, '空闲');
    }

    async function createConversation() {
        var conversation = await api('/conversations', {
            method: 'POST',
            body: {clientRequestId: uuid(), title: null}
        });
        state.conversations = [conversation].concat(state.conversations.filter(function (row) {
            return Number(row.conversationId) !== Number(conversation.conversationId);
        }));
        await selectConversation(conversation.conversationId);
        byId('tawInput').focus();
        return conversation;
    }

    async function deleteConversation(conversationId) {
        var item = state.conversations.find(function (row) {
            return Number(row.conversationId) === Number(conversationId);
        });
        var confirmed = await confirmDialog(
            'Delete this conversation?',
            '会话正文、运行记录和待确认动作将从工作台隐藏。已执行动作的最小审计仍会保留。',
            '删除会话', true);
        if (!confirmed) return;
        await api('/conversations/' + Number(conversationId), {method: 'DELETE'});
        state.conversations = state.conversations.filter(function (row) {
            return Number(row.conversationId) !== Number(conversationId);
        });
        if (state.currentConversation
            && Number(state.currentConversation.conversationId) === Number(conversationId)) {
            closeEventSource();
            state.currentConversation = null;
            if (state.conversations.length) {
                await selectConversation(state.conversations[0].conversationId);
            } else {
                showWelcome();
            }
        } else {
            renderConversations();
        }
        toast('success', '会话已删除', item && item.title ? item.title : '');
    }

    function renderMessages(keepScroll) {
        var list = byId('tawMessageList');
        var welcome = byId('tawWelcome');
        welcome.hidden = state.messages.length > 0;
        if (!state.messages.length) {
            list.innerHTML = '';
            return;
        }
        var html = '';
        if (state.hasOlder) {
            html += '<button type="button" class="taw-load-more" id="tawLoadOlder">'
                + '加载更早消息</button>';
        }
        html += state.messages.map(renderMessage).join('');
        list.innerHTML = html;
        list.querySelectorAll('.taw-message a').forEach(function (link) {
            link.setAttribute('target', '_blank');
            link.setAttribute('rel', 'noopener noreferrer nofollow');
        });
        if (!keepScroll) scrollMessagesToBottom();
    }

    function renderMessage(message) {
        var isUser = message.role === 'USER';
        var content = isUser ? '<p>' + escapeHtml(message.content || '') + '</p>'
            : renderMarkdown(message.content || '');
        var pending = message.status && message.status !== 'COMPLETE';
        return '<article class="taw-message ' + (isUser ? 'taw-message--user' : 'taw-message--assistant')
            + (pending ? ' is-pending' : '') + '" data-message-id="' + Number(message.messageId || 0) + '">'
            + '<div class="taw-message__avatar" aria-hidden="true">' + (isUser ? '你' : 'A') + '</div>'
            + '<div class="taw-message__main"><div class="taw-message__head"><strong>'
            + (isUser ? '你' : '团队 Agent') + '</strong><span>'
            + escapeHtml(formatDateTime(message.createdAt)) + '</span>'
            + (pending ? '<span class="taw-tag">' + escapeHtml(message.status) + '</span>' : '')
            + '</div><div class="taw-message__content">' + content + '</div>'
            + renderCitations(message.citations || [])
            + (isUser ? '' : '<div class="taw-message__actions"><button type="button" class="taw-copy-btn" '
                + 'data-copy-message="' + Number(message.messageId || 0) + '">复制回答</button></div>')
            + '</div></article>';
    }

    function renderCitations(citations) {
        if (!Array.isArray(citations) || !citations.length) return '';
        return '<div class="taw-citations">' + citations.slice(0, 12).map(function (citation) {
            var label = citation.label || citation.title || citation.kind
                || citation.docType || '来源';
            return '<span class="taw-citation">' + sourceIcon()
                + escapeHtml(label) + '</span>';
        }).join('') + '</div>';
    }

    async function sendMessage() {
        var input = byId('tawInput');
        var text = input.value.trim();
        if (!text) {
            toast('warning', '请输入问题');
            input.focus();
            return;
        }
        var capabilities = state.bootstrap && state.bootstrap.capabilities || {};
        if (!capabilities.canAskKnowledge && state.mode !== 'AUTO') {
            toast('warning', 'AI 服务未配置', '请使用自动模式查询团队统计与风险。');
            return;
        }
        if (state.busy) return;
        try {
            if (!state.currentConversation) await createConversation();
            state.busy = true;
            byId('tawSend').disabled = true;
            byId('tawWelcome').hidden = true;
            appendOptimisticUserMessage(text);
            input.value = '';
            resizeInput();
            setRunning(true, '正在创建运行…');
            clearTrace();
            addTrace('request', '请求已提交', '正在建立团队作用域运行', 'active');

            var run = await api('/runs', {
                method: 'POST',
                body: {
                    conversationId: state.currentConversation.conversationId,
                    clientRequestId: uuid(),
                    message: {text: text, mode: state.mode},
                    focus: {taskIds: []}
                }
            });
            state.activeRunId = run.runId;
            state.lastSeq = 0;
            openEventStream(run.runId, 0);
        } catch (error) {
            state.busy = false;
            byId('tawSend').disabled = false;
            setRunning(false, '请求创建失败');
            addTrace('request-error', '请求未创建', error.message, 'error');
            handleError(error);
        }
    }

    function appendOptimisticUserMessage(text) {
        state.messages.push({
            messageId: 'local-' + Date.now(),
            role: 'USER',
            content: text,
            status: 'COMPLETE',
            createdAt: new Date().toISOString(),
            citations: []
        });
        renderMessages();
    }

    function openEventStream(runId, afterSeq) {
        closeEventSource();
        if (!runId) return;
        state.activeRunId = runId;
        state.lastSeq = afterSeq || 0;
        setRunning(true, '运行排队中…');
        var source = new EventSource('/api/team/' + teamId + '/agent/runs/'
            + Number(runId) + '/events?afterSeq=' + Number(state.lastSeq));
        state.eventSource = source;

        source.addEventListener('run.started', function (event) {
            if (!isCurrentRun(runId)) return;
            var data = parseEvent(event);
            setRunning(true, '运行已开始');
            addTrace('start', '运行已启动', routeLabel(data.resolvedRoute || data.requestedMode), 'success');
        });
        source.addEventListener('context.ready', function (event) {
            if (!isCurrentRun(runId)) return;
            var data = parseEvent(event);
            setRunning(true, '团队上下文已绑定');
            addTrace('context', '读取当前团队范围',
                (data.teamName || '当前团队') + ' · ' + roleLabel(data.role), 'success');
            if (data.taskReadMode) {
                byId('tawReadMode').textContent = data.taskReadMode === 'ALL'
                    ? '当前团队全部任务' : '仅我参与或负责的任务';
            }
        });
        source.addEventListener('progress', function (event) {
            if (!isCurrentRun(runId)) return;
            var data = parseEvent(event);
            setRunning(true, data.safeText || '处理中…');
            addTrace('progress-' + (data.stage || 'stage'), data.safeText || '处理中',
                data.stage ? routeLabel(data.stage) : '可复核的处理阶段', 'active');
        });
        source.addEventListener('tool.started', function (event) {
            if (!isCurrentRun(runId)) return;
            var data = parseEvent(event);
            setRunning(true, data.displayName || '正在读取证据');
            addTrace('tool-' + (data.callId || Date.now()), data.displayName || '知识检索',
                scopeLabel(data.scope) + ' · ' + (data.tool || '检索工具'), 'active');
        });
        source.addEventListener('action.proposed', function (event) {
            if (!isCurrentRun(runId)) return;
            var data = parseEvent(event);
            addTrace('actions-' + data.actionGroupId, '已生成动作提案',
                (data.actionCount || 0) + ' 个动作，等待你确认', 'success');
            loadActions().catch(handleError);
        });
        source.addEventListener('message.delta', function (event) {
            if (!isCurrentRun(runId)) return;
            var data = parseEvent(event);
            addTrace('answer', '回答与引用已写入', '运行终态提交后可从历史恢复', 'success');
            var current = state.messages.find(function (message) {
                return Number(message.messageId) === Number(data.messageId);
            });
            if (!current && data.delta) {
                state.messages.push({
                    messageId: data.messageId,
                    role: 'ASSISTANT',
                    content: data.delta,
                    status: 'COMPLETE',
                    createdAt: new Date().toISOString(),
                    citations: data.citations || []
                });
                renderMessages();
            }
        });
        source.addEventListener('run.completed', function () {
            if (!isCurrentRun(runId)) return;
            finishRun(runId, true, '运行已完成');
        });
        source.addEventListener('run.failed', function (event) {
            if (!isCurrentRun(runId)) return;
            var data = parseEvent(event);
            addTrace('run-failed', '运行未完成', data.message || data.errorCode, 'error');
            finishRun(runId, false, data.message || '运行失败');
            toast('error', 'Agent 运行失败', data.message || '');
        });
        source.addEventListener('run.cancelled', function (event) {
            if (!isCurrentRun(runId)) return;
            var data = parseEvent(event);
            addTrace('run-cancelled', '运行已停止', data.message || '已按请求停止', 'error');
            finishRun(runId, false, '运行已停止');
        });
        source.onerror = function () {
            if (!isCurrentRun(runId)) return;
            setRunning(true, '连接中断，正在核对运行状态…');
            scheduleFallback(runId);
        };
    }

    function scheduleFallback(runId) {
        if (state.reconnectTimer) clearTimeout(state.reconnectTimer);
        state.reconnectTimer = setTimeout(async function () {
            if (!isCurrentRun(runId)) return;
            try {
                var run = await api('/runs/' + Number(runId));
                if (isTerminal(run.status)) {
                    finishRun(runId, run.status === 'COMPLETED', run.status === 'COMPLETED'
                        ? '运行已完成' : (run.errorCode || '运行已停止'));
                } else if (!state.eventSource || state.eventSource.readyState === EventSource.CLOSED) {
                    openEventStream(runId, state.lastSeq || 0);
                }
            } catch (error) {
                handleError(error);
            }
        }, 1400);
    }

    async function finishRun(runId, success, label) {
        if (!isCurrentRun(runId)) return;
        closeEventSource(false);
        state.activeRunId = null;
        state.busy = false;
        byId('tawSend').disabled = false;
        byId('tawCancelRun').hidden = false;
        setRunning(false, label);
        try {
            if (state.currentConversation) {
                var conversationId = state.currentConversation.conversationId;
                var result = await api('/conversations/' + conversationId + '?limit=50');
                state.currentConversation = result.conversation;
                state.messages = result.messages || [];
                state.hasOlder = !!result.hasOlder;
                state.oldestSeq = result.oldestSeq;
                renderMessages();
                renderConversations();
                await loadActions();
                await refreshBootstrapSummary();
            }
            if (success) toast('success', '团队 Agent 已完成');
        } catch (error) {
            handleError(error);
        }
    }

    async function cancelRun() {
        if (!state.activeRunId) return;
        try {
            setRunning(true, '正在停止…');
            byId('tawCancelRun').disabled = true;
            await api('/runs/' + Number(state.activeRunId) + '/cancel', {
                method: 'POST',
                body: {clientRequestId: uuid()}
            });
            addTrace('cancelling', '已请求停止', '等待执行器在安全检查点结束', 'active');
        } catch (error) {
            byId('tawCancelRun').disabled = false;
            handleError(error);
        }
    }

    async function loadActions() {
        if (!state.currentConversation) {
            renderActions([]);
            return;
        }
        var result = await api('/action-groups?conversationId='
            + Number(state.currentConversation.conversationId) + '&limit=20');
        var summaries = result && Array.isArray(result.items) ? result.items : [];
        var visible = summaries.slice(0, 12);
        var groups = await Promise.all(visible.map(function (summary) {
            return api('/action-groups/' + Number(summary.groupId)).catch(function () { return null; });
        }));
        state.actionGroups = groups.filter(Boolean);
        state.selectedActions = {};
        state.actionGroups.forEach(function (group) {
            if (group.status === 'PENDING') {
                state.selectedActions[group.groupId] = {};
                (group.actions || []).forEach(function (action) {
                    if (action.status === 'PENDING') state.selectedActions[group.groupId][action.actionId] = true;
                });
            }
        });
        renderActions();
        updateActionBadge();
    }

    function renderActions(groupsOverride) {
        var groups = Array.isArray(groupsOverride) ? groupsOverride : state.actionGroups;
        var list = byId('tawActionList');
        if (!groups.length) {
            list.innerHTML = '<div class="taw-empty-mini">暂无动作提案。<br>任务规划结果会在这里等待你确认。</div>';
            updateActionBadge();
            return;
        }
        list.innerHTML = groups.map(renderActionGroup).join('');
        updateActionBadge();
    }

    function renderActionGroup(group) {
        var actions = Array.isArray(group.actions) ? group.actions : [];
        var pending = group.status === 'PENDING';
        var statusTag = groupTag(group.status);
        return '<div class="taw-action-group" data-action-group="' + Number(group.groupId) + '">'
            + '<div class="taw-action-group__head"><div><strong>动作组 #' + Number(group.groupId)
            + '</strong><span>' + statusTag + (pending && group.expiresInSeconds != null
                ? '<span class="taw-tag">剩余 ' + formatRemaining(group.expiresInSeconds) + '</span>' : '')
            + '</span></div><small>' + escapeHtml(formatDateTime(group.createdAt)) + '</small></div>'
            + actions.map(function (action) {
                return renderActionCard(group, action, pending);
            }).join('')
            + (group.errorMessage ? '<p class="taw-action-group__error">'
                + escapeHtml(group.errorMessage) + '</p>' : '')
            + (pending ? '<div class="taw-action-footer">'
                + '<button type="button" class="primary" data-confirm-group="' + Number(group.groupId)
                + '">确认选中动作</button>'
                + '<button type="button" data-dismiss-group="' + Number(group.groupId)
                + '">忽略整组</button>'
                + '<span class="link" data-group-version="' + Number(group.version) + '">v'
                + Number(group.version) + '</span></div>' : '')
            + '</div>';
    }

    function renderActionCard(group, action, groupPending) {
        var selected = state.selectedActions[group.groupId]
            && state.selectedActions[group.groupId][action.actionId];
        var canCheck = groupPending && action.status === 'PENDING';
        var undoAvailable = action.status === 'EXECUTED'
            && action.undoStatus === 'AVAILABLE' && Number(action.undoRemainingSeconds) > 0;
        return '<article class="taw-action-card" data-action-id="' + Number(action.actionId) + '">'
            + '<div class="taw-action-card__head">'
            + (canCheck ? '<input type="checkbox" data-select-action="' + Number(action.actionId)
                + '" data-group-id="' + Number(group.groupId) + '"'
                + (selected ? ' checked' : '') + ' aria-label="选择此动作">' : '')
            + '<div><strong>' + escapeHtml(actionLabel(action.actionType)) + '</strong>'
            + '<small>' + escapeHtml(actionTarget(action)) + ' · ' + escapeHtml(actionStatusLabel(action.status))
            + '</small></div></div>'
            + (action.reason ? '<p class="taw-action-card__reason">' + escapeHtml(action.reason) + '</p>' : '')
            + renderActionDiff(action)
            + '<div class="taw-action-card__meta">' + actionTags(action)
            + (action.undoStatus && action.undoStatus !== 'NOT_SUPPORTED'
                ? '<span class="taw-tag ' + (undoAvailable ? 'taw-tag--success' : '')
                    + '">' + escapeHtml(undoStatusLabel(action.undoStatus)) + '</span>' : '')
            + '</div>'
            + (undoAvailable ? '<div class="taw-action-footer"><button type="button" class="undo" '
                + 'data-undo-action="' + Number(action.actionId) + '" data-action-version="'
                + Number(action.version) + '">撤销此动作 · ' + formatRemaining(action.undoRemainingSeconds)
                + '</button></div>' : '')
            + (action.errorMessage ? '<p class="taw-action-group__error">'
                + escapeHtml(action.errorMessage) + '</p>' : '')
            + '</article>';
    }

    function renderActionDiff(action) {
        var keys = actionKeys(action.actionType);
        if (!keys.length) return '';
        var after = action.after || {};
        var before = action.before || {};
        return '<div class="taw-action-card__diff"><dl>' + keys.map(function (key) {
            return '<div><dt>' + escapeHtml(fieldLabel(key)) + '</dt><dd>'
                + escapeHtml(displayValue(before[key])) + '</dd><dd>'
                + escapeHtml(displayValue(after[key])) + '</dd></div>';
        }).join('') + '</dl></div>';
    }

    function actionTags(action) {
        var tags = ['<span class="taw-tag">' + escapeHtml(action.schemaVersion || '1.0') + '</span>'];
        if (action.targetId) tags.push('<span class="taw-tag">对象 #' + Number(action.targetId) + '</span>');
        if (action.evidence && action.evidence.length) tags.push('<span class="taw-tag">'
            + action.evidence.length + ' 条证据</span>');
        return tags.join('');
    }

    function updateActionBadge() {
        var pendingCount = state.actionGroups.filter(function (group) {
            return group.status === 'PENDING';
        }).length;
        var badge = byId('tawActionBadge');
        badge.hidden = pendingCount === 0;
        badge.textContent = String(Math.min(pendingCount, 99));
        var mobileActionTab = root.querySelector('[data-taw-mobile-tab="actions"]');
        if (mobileActionTab) mobileActionTab.textContent = '动作' + (pendingCount ? ' ' + pendingCount : '');
    }

    async function confirmGroup(groupId) {
        var group = state.actionGroups.find(function (row) {
            return Number(row.groupId) === Number(groupId);
        });
        if (!group) return;
        var selectedActions = (group.actions || []).filter(function (action) {
            return state.selectedActions[groupId]
                && state.selectedActions[groupId][action.actionId];
        });
        var leaseToken = collaborationLeaseToken(selectedActions);
        var selected = Object.keys(state.selectedActions[groupId] || {}).filter(function (key) {
            return state.selectedActions[groupId][key];
        }).map(Number);
        if (!selected.length) {
            toast('warning', '至少选择一个动作');
            return;
        }
        var confirmed = await confirmDialog(
            '确认执行 ' + selected.length + ' 个动作？',
            '服务端会再次校验团队状态、成员权限、任务版本和提案有效期。整组动作要么全部成功，要么全部失败。',
            '确认执行', false);
        if (!confirmed) return;
        try {
            setActionBusy(groupId, true);
            await api('/action-groups/' + Number(groupId) + '/confirm', {
                method: 'POST',
                body: {
                    selectedActionIds: selected,
                    expectedGroupVersion: Number(group.version),
                    clientRequestId: uuid(),
                    existingLeaseToken: leaseToken
                }
            });
            toast('success', '动作已执行', '可撤销动作的撤销窗口已开始计时。');
            await loadActions();
            if (window.teamSpace && typeof window.teamSpace.loadTasks === 'function') {
                window.teamSpace.loadTasks();
            }
        } catch (error) {
            handleError(error);
            await loadActions().catch(function () {});
        } finally {
            setActionBusy(groupId, false);
        }
    }

    async function dismissGroup(groupId) {
        var group = state.actionGroups.find(function (row) {
            return Number(row.groupId) === Number(groupId);
        });
        if (!group) return;
        var confirmed = await confirmDialog(
            '忽略这组动作？',
            '组内所有待确认动作都会取消；该提案不能再次执行。',
            '忽略动作', true);
        if (!confirmed) return;
        try {
            setActionBusy(groupId, true);
            await api('/action-groups/' + Number(groupId) + '/dismiss', {
                method: 'POST',
                body: {
                    expectedGroupVersion: Number(group.version),
                    clientRequestId: uuid()
                }
            });
            toast('success', '动作组已忽略');
            await loadActions();
        } catch (error) {
            handleError(error);
            await loadActions().catch(function () {});
        } finally {
            setActionBusy(groupId, false);
        }
    }

    async function undoAction(actionId, expectedVersion) {
        var action = findAction(actionId);
        var leaseToken = action ? collaborationLeaseToken([action]) : null;
        var confirmed = await confirmDialog(
            '撤销这项动作？',
            '仅在任务没有被继续修改或使用时才可撤销。系统不会覆盖之后的人工修改。',
            '撤销动作', true);
        if (!confirmed) return;
        try {
            await api('/actions/' + Number(actionId) + '/undo', {
                method: 'POST',
                body: {
                    expectedActionVersion: Number(expectedVersion),
                    clientRequestId: uuid(),
                    existingLeaseToken: leaseToken
                }
            });
            toast('success', '动作已撤销', action ? actionLabel(action.actionType) : '');
            await loadActions();
            if (window.teamSpace && typeof window.teamSpace.loadTasks === 'function') {
                window.teamSpace.loadTasks();
            }
        } catch (error) {
            handleError(error);
            await loadActions().catch(function () {});
        }
    }

    function collaborationLeaseToken(actions) {
        if (!Array.isArray(actions) || !actions.length || !window.teamCollab
            || typeof window.teamCollab.activeLeaseToken !== 'function') {
            return null;
        }
        var found = null;
        for (var i = 0; i < actions.length; i += 1) {
            var action = actions[i];
            var assetType = action.targetType === 'COLLAB_DOCUMENT' ? 'DOCUMENT'
                : action.targetType === 'COLLAB_SPREADSHEET' ? 'SPREADSHEET' : null;
            var assetId = action.targetId;
            if (!assetType && action.result) {
                assetId = action.result.documentId || action.result.spreadsheetId || null;
                assetType = action.result.documentId ? 'DOCUMENT'
                    : action.result.spreadsheetId ? 'SPREADSHEET' : null;
            }
            if (!assetType || !assetId) continue;
            var token = window.teamCollab.activeLeaseToken(assetType, assetId);
            if (!token) continue;
            var key = assetType + ':' + assetId;
            if (found && found.key !== key) return null;
            found = {key: key, token: token};
        }
        return found ? found.token : null;
    }

    function setActionBusy(groupId, busy) {
        var group = root.querySelector('[data-action-group="' + Number(groupId) + '"]');
        if (!group) return;
        group.querySelectorAll('button, input').forEach(function (control) {
            control.disabled = busy;
        });
        group.classList.toggle('is-busy', busy);
    }

    function findAction(actionId) {
        for (var i = 0; i < state.actionGroups.length; i += 1) {
            var actions = state.actionGroups[i].actions || [];
            var found = actions.find(function (action) {
                return Number(action.actionId) === Number(actionId);
            });
            if (found) return found;
        }
        return null;
    }

    function addTrace(key, title, detail, status) {
        var existing = state.traces.find(function (row) { return row.key === key; });
        if (existing) {
            existing.title = title;
            existing.detail = detail;
            existing.status = status || existing.status;
        } else {
            state.traces.push({key: key, title: title, detail: detail, status: status || 'active'});
        }
        renderTrace();
    }

    function clearTrace() {
        state.traces = [];
        renderTrace();
    }

    function renderTrace() {
        var list = byId('tawTraceList');
        if (!state.traces.length) {
            list.innerHTML = '<div class="taw-empty-mini">发送问题后，可在这里查看工具、范围和进度。</div>';
            return;
        }
        list.innerHTML = state.traces.map(function (row) {
            return '<div class="taw-trace-item is-' + escapeAttr(row.status || 'active') + '">'
                + '<strong>' + escapeHtml(row.title || '') + '</strong>'
                + '<small>' + escapeHtml(row.detail || '') + '</small></div>';
        }).join('');
    }

    function setRunning(running, label) {
        byId('tawLiveStatus').hidden = !running && !label;
        byId('tawLiveStatusText').textContent = label || (running ? '处理中…' : '空闲');
        byId('tawCancelRun').hidden = !running;
        byId('tawCancelRun').disabled = false;
        byId('tawRunState').textContent = running ? '运行中' : (label || '空闲');
        byId('tawLiveStatus').classList.toggle('is-running', !!running);
        if (running) switchInspectorTab('trace', false);
    }

    function closeEventSource(cancelTimer) {
        if (state.eventSource) {
            state.eventSource.close();
            state.eventSource = null;
        }
        if (cancelTimer !== false && state.reconnectTimer) {
            clearTimeout(state.reconnectTimer);
            state.reconnectTimer = null;
        }
    }

    function parseEvent(event) {
        try {
            return JSON.parse(event.data || '{}');
        } catch (error) {
            return {};
        }
    }

    function isCurrentRun(runId) {
        return Number(state.activeRunId) === Number(runId);
    }

    function isTerminal(status) {
        return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED';
    }

    function handleError(error) {
        var message = error && error.message ? error.message : '请求失败，请稍后重试';
        if (error && (Number(error.status) === 401 || error.code === 'AUTH_REQUIRED')) {
            var redirect = encodeURIComponent(window.location.pathname + window.location.search);
            window.location.href = '/login.html?redirect=' + redirect;
            return;
        }
        if (error && error.code === 'AI_NOT_CONFIGURED') {
            toast('warning', 'AI 服务尚未配置', '团队统计、风险巡检和周报草稿仍可使用。');
        } else if (error && error.code === 'ACTION_VERSION_CONFLICT') {
            toast('warning', '数据已更新', '已重新读取最新状态，请再次确认。');
        } else if (error && error.code === 'ACTION_GROUP_EXPIRED' || error && error.code === 'UNDO_EXPIRED') {
            toast('warning', '操作已过期', message);
        } else {
            toast('error', '操作未完成', message);
        }
    }

    function renderFatal(error) {
        byId('tawContextSummary').textContent = '团队 Agent 暂时不可用';
        byId('tawConversationList').innerHTML = '<div class="taw-empty-mini">'
            + escapeHtml(error && error.message ? error.message : '加载失败，请刷新重试') + '</div>';
        byId('tawMessageList').innerHTML = '<div class="taw-empty-mini">'
            + (Number(error && error.status) === 403
                ? '你当前无权访问该团队工作台。' : '工作台加载失败，请稍后刷新页面。') + '</div>';
        byId('tawWelcome').hidden = true;
    }

    async function refreshBootstrapSummary() {
        try {
            state.bootstrap = await api('/bootstrap');
            renderBootstrap();
        } catch (error) {
            // A stale context strip is preferable to replacing a completed run.
        }
    }

    function switchInspectorTab(tab, openMobile) {
        tab = tab === 'actions' ? 'actions' : 'trace';
        root.querySelectorAll('[data-taw-inspector-tab]').forEach(function (button) {
            var active = button.dataset.tawInspectorTab === tab;
            button.classList.toggle('active', active);
            button.setAttribute('aria-selected', String(active));
        });
        root.querySelectorAll('[data-taw-pane]').forEach(function (pane) {
            pane.classList.toggle('active', pane.dataset.tawPane === tab);
        });
        if (openMobile !== false && window.matchMedia('(max-width: 860px)').matches) {
            root.dataset.mobilePane = tab;
        }
    }

    function confirmDialog(title, text, confirmLabel, destructive) {
        var dialog = byId('tawConfirmDialog');
        if (!dialog || typeof dialog.showModal !== 'function') {
            return Promise.resolve(window.confirm(title + '\n\n' + text));
        }
        byId('tawDialogTitle').textContent = title;
        byId('tawDialogText').textContent = text;
        var confirmButton = byId('tawDialogConfirm');
        confirmButton.textContent = confirmLabel || '确认';
        confirmButton.classList.toggle('taw-btn--danger', !!destructive);
        dialog.returnValue = '';
        dialog.showModal();
        return new Promise(function (resolve) {
            function onClose() {
                dialog.removeEventListener('close', onClose);
                resolve(dialog.returnValue === 'confirm');
            }
            dialog.addEventListener('close', onClose);
        });
    }

    function resizeInput() {
        var input = byId('tawInput');
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 150) + 'px';
    }

    function scrollMessagesToBottom() {
        var messages = byId('tawMessages');
        if (messages) requestAnimationFrame(function () {
            messages.scrollTop = messages.scrollHeight;
        });
    }

    function renderMarkdown(text) {
        if (window.marked && window.DOMPurify) {
            try {
                var html = marked.parse(String(text || ''), {gfm: true, breaks: true});
                return DOMPurify.sanitize(html, {
                    USE_PROFILES: {html: true},
                    FORBID_TAGS: ['img', 'picture', 'source', 'video', 'audio', 'iframe', 'object', 'embed', 'form'],
                    FORBID_ATTR: ['style', 'srcset', 'onerror', 'onclick']
                });
            } catch (error) {
                return '<p>' + escapeHtml(text || '') + '</p>';
            }
        }
        return '<p>' + escapeHtml(text || '').replace(/\n/g, '<br>') + '</p>';
    }

    function replaceElementContent(element, text) {
        if (!element) return;
        element.textContent = text;
    }

    function copyMessage(messageId) {
        var message = state.messages.find(function (row) {
            return Number(row.messageId) === Number(messageId);
        });
        if (!message) return;
        var text = message.content || '';
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(function () {
                toast('success', '已复制回答');
            }).catch(function () {
                toast('error', '复制失败');
            });
        } else {
            var textarea = document.createElement('textarea');
            textarea.value = text;
            document.body.appendChild(textarea);
            textarea.select();
            document.execCommand('copy');
            textarea.remove();
            toast('success', '已复制回答');
        }
    }

    function bindEvents() {
        byId('tawSend').addEventListener('click', sendMessage);
        byId('tawCancelRun').addEventListener('click', cancelRun);
        byId('tawNewConversation').addEventListener('click', function () {
            createConversation().catch(handleError);
        });
        byId('tawMoreConversations').addEventListener('click', function () {
            loadConversations(false).catch(handleError);
        });
        byId('tawConversationToggle').addEventListener('click', function () {
            var open = root.classList.toggle('is-conversations-open');
            this.setAttribute('aria-expanded', String(open));
        });
        byId('tawRefreshActions').addEventListener('click', function () {
            loadActions().catch(handleError);
        });
        byId('tawInput').addEventListener('input', resizeInput);
        byId('tawInput').addEventListener('keydown', function (event) {
            if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
                event.preventDefault();
                sendMessage();
            }
        });
        root.addEventListener('click', function (event) {
            var quick = event.target.closest('[data-taw-prompt]');
            if (quick) {
                byId('tawInput').value = quick.dataset.tawPrompt || '';
                setMode(quick.dataset.tawMode || 'AUTO');
                resizeInput();
                byId('tawInput').focus();
                return;
            }
            var conversation = event.target.closest('[data-conversation-id]');
            if (conversation) {
                var deleteButton = event.target.closest('[data-delete-conversation]');
                if (deleteButton) {
                    event.stopPropagation();
                    deleteConversation(deleteButton.dataset.deleteConversation).catch(handleError);
                    return;
                }
                selectConversation(conversation.dataset.conversationId).catch(handleError);
                return;
            }
            var mode = event.target.closest('[data-taw-mode-option]');
            if (mode) {
                setMode(mode.dataset.tawModeOption);
                return;
            }
            var confirmButton = event.target.closest('[data-confirm-group]');
            if (confirmButton) {
                confirmGroup(confirmButton.dataset.confirmGroup);
                return;
            }
            var dismissButton = event.target.closest('[data-dismiss-group]');
            if (dismissButton) {
                dismissGroup(dismissButton.dataset.dismissGroup);
                return;
            }
            var undoButton = event.target.closest('[data-undo-action]');
            if (undoButton) {
                undoAction(undoButton.dataset.undoAction, undoButton.dataset.actionVersion);
                return;
            }
            var copyButton = event.target.closest('[data-copy-message]');
            if (copyButton) {
                copyMessage(copyButton.dataset.copyMessage);
                return;
            }
            if (event.target.id === 'tawLoadOlder') {
                loadOlderMessages().catch(handleError);
                return;
            }
            var actionTab = event.target.closest('[data-taw-inspector-tab]');
            if (actionTab) {
                switchInspectorTab(actionTab.dataset.tawInspectorTab);
                return;
            }
            var mobileTab = event.target.closest('[data-taw-mobile-tab]');
            if (mobileTab) {
                var pane = mobileTab.dataset.tawMobileTab;
                root.dataset.mobilePane = pane;
                root.querySelectorAll('[data-taw-mobile-tab]').forEach(function (button) {
                    var active = button === mobileTab;
                    button.classList.toggle('active', active);
                    button.setAttribute('aria-selected', String(active));
                });
            }
        });
        root.addEventListener('change', function (event) {
            var checkbox = event.target.closest('[data-select-action]');
            if (!checkbox) return;
            var groupId = checkbox.dataset.groupId;
            state.selectedActions[groupId] = state.selectedActions[groupId] || {};
            state.selectedActions[groupId][checkbox.dataset.selectAction] = checkbox.checked;
        });
        root.addEventListener('keydown', function (event) {
            if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('.taw-conversation-delete')) {
                event.preventDefault();
                deleteConversation(event.target.dataset.deleteConversation).catch(handleError);
            }
        });
        document.addEventListener('click', function (event) {
            if (!root || !root.classList.contains('is-conversations-open')) return;
            if (!root.querySelector('.taw-layout').contains(event.target)) {
                root.classList.remove('is-conversations-open');
            }
        });
    }

    function setMode(mode) {
        state.mode = ['AUTO', 'KNOWLEDGE_QUICK', 'KNOWLEDGE_DEEP', 'ACTION_PLAN'].includes(mode)
            ? mode : 'AUTO';
        root.querySelectorAll('[data-taw-mode-option]').forEach(function (button) {
            button.classList.toggle('active', button.dataset.tawModeOption === state.mode);
        });
    }

    function initialize() {
        if (initialized) return;
        initialized = true;
        bindEvents();
        setMode('AUTO');
    }

    async function activate() {
        if (!root) return false;
        initialize();
        return load();
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function escapeAttr(value) {
        return String(value || '').replace(/[^A-Za-z0-9_-]/g, '');
    }

    function formatDateTime(value) {
        if (!value) return '';
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) return '';
        return new Intl.DateTimeFormat(document.documentElement.lang || 'zh-CN', {
            month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
        }).format(date);
    }

    function formatRelativeTime(value) {
        if (!value) return '刚刚';
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) return '';
        var diff = Math.max(0, Date.now() - date.getTime());
        if (diff < 60000) return '刚刚';
        if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
        if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
        return formatDateTime(value);
    }

    function formatRemaining(seconds) {
        var safe = Math.max(0, Number(seconds) || 0);
        var minutes = Math.floor(safe / 60);
        var remain = safe % 60;
        return minutes + ':' + String(remain).padStart(2, '0');
    }

    function teamStatusLabel(status) {
        return {OPEN: '招募中', TEAMING: '组队中', CLOSED: '已关闭'}[status] || status || '未知状态';
    }

    function roleLabel(role) {
        return {LEADER: '队长', MENTOR: '导师', STUDENT: '学生'}[role] || role || '成员';
    }

    function scopeLabel(scope) {
        return {CURRENT_TEAM: '当前团队范围', GLOBAL: '公开知识范围',
            TEAM: '当前团队范围'}[scope] || '受限数据范围';
    }

    function freshnessLabel(status) {
        return {FRESH: '已同步', UPDATING: '更新中', STALE: '待更新',
            DEGRADED: '有异常'}[status] || '未知';
    }

    function freshnessText(freshness) {
        var label = freshnessLabel(freshness.status);
        var pending = Number(freshness.pendingDocuments || 0);
        var failed = Number(freshness.failedDocuments || 0);
        var suffix = pending ? ' · ' + pending + ' 个文档排队' : failed ? ' · ' + failed + ' 个失败' : '';
        return '知识索引：' + label + suffix;
    }

    function routeLabel(route) {
        return {
            KNOWLEDGE_QUICK: '快速 GraphRAG',
            KNOWLEDGE_DEEP: '多轮 Agentic GraphRAG',
            TEAM_METRICS: '结构化任务统计',
            TEAM_RISK: '任务风险巡检',
            WEEKLY_DRAFT: '确定性周报草稿',
            ACTION_PLAN: '受控任务规划',
            AUTO: '自动路由'
        }[route] || (route || 'Agent 运行');
    }

    function actionLabel(type) {
        return {
            CREATE_TASK: '创建团队任务',
            CREATE_SUBTASK: '创建子任务',
            ASSIGN_TASK: '改派任务负责人',
            SET_TASK_PRIORITY: '调整任务优先级',
            SET_TASK_DEADLINE: '调整任务截止日期',
            SEND_TASK_REMINDER: '发送任务提醒',
            SEND_WEEKLY_REPORT: '发送团队周报'
        }[type] || type || '任务动作';
    }

    function actionTarget(action) {
        if (action.actionType === 'SEND_WEEKLY_REPORT') return '团队群聊';
        if (action.targetId) return '任务 #' + action.targetId;
        return action.actionType && action.actionType.indexOf('CREATE_') === 0 ? '新建对象' : '团队动作';
    }

    function actionStatusLabel(status) {
        return {PENDING: '待确认', EXECUTED: '已执行', FAILED: '失败',
            CANCELLED: '已取消', EXPIRED: '已过期'}[status] || status || '';
    }

    function undoStatusLabel(status) {
        return {AVAILABLE: '可撤销', EXPIRED: '撤销已过期', UNDONE: '已撤销',
            UNDOING: '撤销中', FAILED: '撤销失败', NOT_SUPPORTED: '不可撤销'}[status] || status || '';
    }

    function groupTag(status) {
        var cls = status === 'EXECUTED' ? 'taw-tag--success'
            : status === 'FAILED' ? 'taw-tag--danger'
                : status === 'PENDING' ? 'taw-tag--warning' : '';
        return '<span class="taw-tag ' + cls + '">'
            + escapeHtml(actionStatusLabel(status) === status ? status : actionStatusLabel(status)) + '</span>';
    }

    function actionKeys(type) {
        return {
            CREATE_TASK: ['taskTitle', 'assignedTo', 'priority', 'deadline'],
            CREATE_SUBTASK: ['taskTitle', 'parentTaskId', 'assignedTo', 'deadline'],
            ASSIGN_TASK: ['assignedTo'],
            SET_TASK_PRIORITY: ['priority'],
            SET_TASK_DEADLINE: ['deadline'],
            SEND_TASK_REMINDER: ['reminderText'],
            SEND_WEEKLY_REPORT: ['windowStart', 'windowEnd']
        }[type] || [];
    }

    function fieldLabel(key) {
        return {
            taskTitle: '任务标题', assignedTo: '负责人', priority: '优先级',
            deadline: '截止时间', parentTaskId: '父任务', reminderText: '提醒内容',
            windowStart: '统计开始', windowEnd: '统计结束'
        }[key] || key;
    }

    function displayValue(value) {
        if (value == null || value === '') return '未设置';
        if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(value)) {
            return formatDateTime(value);
        }
        return String(value);
    }

    function closeIcon() {
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">'
            + '<path d="M18 6L6 18M6 6l12 12"/></svg>';
    }

    function sourceIcon() {
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">'
            + '<path d="M10 13a5 5 0 007.5.5l2-2a5 5 0 00-7-7l-1.2 1.2"/>'
            + '<path d="M14 11a5 5 0 00-7.5-.5l-2 2a5 5 0 007 7l1.2-1.2"/></svg>';
    }

    document.addEventListener('DOMContentLoaded', function () {
        root = byId('teamAgentWorkbench');
        if (!root) return;
        var match = window.location.search.match(/[?&]id=(\d+)/);
        teamId = match ? Number(match[1]) : null;
        if (!teamId) {
            renderFatal(new Error('URL 缺少团队标识'));
            return;
        }
        initialize();
    });

    window.teamAgentWorkbench = {
        activate: activate,
        refresh: function () {
            if (state.currentConversation) {
                return selectConversation(state.currentConversation.conversationId);
            }
            return load();
        },
        getState: function () {
            return {
                initialized: initialized,
                conversationId: state.currentConversation && state.currentConversation.conversationId,
                activeRunId: state.activeRunId
            };
        }
    };
})();
