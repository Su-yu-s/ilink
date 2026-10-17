/**
 * Agentic GraphRAG 前端组件（v1.0 方案；数据来自真实后端）
 * 同一套交互逻辑支持两种挂载：
 *   1) mountFab()        —— 全站悬浮入口（fragments/agentic-drawer.html，header 统一引入）
 *   2) mountInline(root) —— 页面内嵌实例（如 team-space 中央「AI 智能答疑」卡片）
 * 内嵌实例挂载后悬浮入口自动隐藏，避免双入口重复。
 *
 * 元素通过 root 内 data-agc-ref 属性定位；跨实例共享：实体浮层（#agcEntityPop）与全局快捷键。
 */
(function () {
    'use strict';

    var WELCOME_TEXTS = {
        fast: '你好，我是团队的 AI 助手。选中上方竞赛可针对该赛事提问，留空则按通用问题处理。可以问我报名条件、赛程节点、评审规则、材料准备等问题。',
        deep: '你好，我会在知识图谱里逐层探索，每一步推理过程都会展示给你看。选中上方竞赛可从该赛事开始探索。'
    };
    var PLACEHOLDERS = {
        fast: '向 AI 提问竞赛相关问题…',
        deep: '描述你的复杂问题，我会深度探索…'
    };
    var HINTS = {
        fast: '一次性检索后直接生成答案，速度更快',
        deep: 'Agent 会自主探索知识图谱，多轮检索后给出可追溯的答案'
    };
    var FOOT_MODES = { fast: '快速答疑 · 即时响应', deep: '深度搜索 · 多轮探索' };

    var COLORS = {
        question:    { dot: '#d1d5db', bg: '#f3f4f6', border: '#d1d5db', text: '#374151', label: '用户问题', icon: 'kgd-i-thought' },
        thought:     { dot: '#d1d5db', bg: '#f3f4f6', border: '#d1d5db', text: '#374151', label: '思考', icon: 'kgd-i-thought' },
        action:      { dot: '#60a5fa', bg: '#eff6ff', border: '#bfdbfe', text: '#1d4ed8', label: '检索', icon: 'kgd-i-db' },
        observation: { dot: '#fbbf24', bg: '#fffbeb', border: '#fde68a', text: '#b45309', label: '观察', icon: 'kgd-i-eye' },
        supplement:  { dot: '#a78bfa', bg: '#f5f3ff', border: '#ddd6fe', text: '#6d28d9', label: '补充', icon: 'kgd-i-doc' },
        final:       { dot: '#4ade80', bg: '#f0fdf4', border: '#bbf7d0', text: '#15803d', label: '产出', icon: 'kgd-i-check' }
    };

    var instances = [];      // 所有实例（fab / inline），全局快捷键据此路由
    var fabInstance = null;  // 悬浮实例单例
    var competitionsLoaded = false;

    // ==================== 工具 ====================

    function escapeHtml(text) {
        return String(text == null ? '' : text)
            .replace(/[&<>"']/g, function (c) {
                return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
            });
    }

    function readCookie(name) {
        var match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
        return match ? decodeURIComponent(match[1]) : '';
    }

    function doFetch(url, options) {
        options = options || {};
        options.headers = options.headers || {};
        if (!options.headers['X-XSRF-TOKEN']) {
            options.headers['X-XSRF-TOKEN'] = readCookie('XSRF-TOKEN');
        }
        options.credentials = options.credentials || 'same-origin';
        return fetch(url, options);
    }

    function abbreviate(text, max) {
        text = String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
        return text.length <= max ? text : text.slice(0, max) + '…';
    }

    function icon(name, size) {
        return '<svg fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="width:' + (size || 12) + 'px;height:' + (size || 12) + 'px;flex-shrink:0;"><use href="#' + name + '"/></svg>';
    }

    function avatarHtml(small) {
        return '<div class="kgd-avatar' + (small ? ' kgd-avatar-sm' : '') + '">' +
            '<svg fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><use href="#kgd-i-spark"/></svg></div>';
    }

    // ==================== 实体浮层（全局唯一） ====================

    function positionPop(pop, anchor) {
        var rect = anchor.getBoundingClientRect();
        var left = rect.left;
        var top = rect.bottom + 8;
        if (left + 288 > window.innerWidth - 20) left = window.innerWidth - 308;
        if (left < 16) left = 16;
        if (top + 320 > window.innerHeight - 20) top = Math.max(20, rect.top - 320);
        pop.style.left = left + 'px';
        pop.style.top = top + 'px';
    }

    function renderPopError(body, message) {
        body.innerHTML = '<div style="font-size:12px;color:#dc2626;">' + escapeHtml(message) + '</div>';
    }

    function showEntityPop(entityId, anchor) {
        var pop = document.getElementById('agcEntityPop');
        if (!pop) return;
        var body = document.getElementById('agcEntityPopBody');
        body.innerHTML = '<div style="display:flex;align-items:center;gap:8px;font-size:12px;color:#6b7280;">' +
            '<span class="kgd-spin"></span> 正在加载实体详情…</div>';
        positionPop(pop, anchor);
        pop.classList.add('kgd-show');

        doFetch('/api/ai/graph-rag/entity/' + entityId, { method: 'GET' })
            .then(function (resp) { return resp.json(); })
            .then(function (res) {
                if (!res || res.code !== 200) {
                    renderPopError(body, (res && res.message) || '实体详情加载失败');
                    return;
                }
                var d = res.data;
                var moreRows = '';
                (d.sources || []).forEach(function (s) {
                    moreRows += '<div class="kgd-pop-morerow"><span class="kgd-k">来源文档</span>' +
                        '<span class="kgd-v">' + escapeHtml(s.ref) + '</span></div>';
                });
                moreRows += '<div class="kgd-pop-morerow"><span class="kgd-k">可见范围</span>' +
                    '<span class="kgd-v">' + escapeHtml(d.scope || 'GLOBAL') + '</span></div>';
                body.innerHTML =
                    '<div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;">' +
                      '<span class="kgd-pop-type">' + escapeHtml(d.entityType || 'ENTITY') + '</span>' +
                      '<span class="kgd-pop-name">' + escapeHtml(d.name || ('#' + d.entityId)) + '</span>' +
                    '</div>' +
                    '<div class="kgd-pop-desc">' + escapeHtml(d.description || '暂无描述') + '</div>' +
                    '<div class="kgd-pop-more" id="agcPopMore"><div style="padding-top:4px;">' + moreRows + '</div></div>' +
                    '<div class="kgd-pop-src">' + icon('kgd-i-doc', 12) +
                      '来源：' + ((d.sources && d.sources.length)
                        ? escapeHtml(d.sources.map(function (s) { return s.ref; }).join('、'))
                        : '暂无关联文档') +
                    '</div>' +
                    '<button type="button" class="kgd-pop-morebtn" onclick="agenticChat.togglePopMore(this)">查看实体详情</button>';
            })
            .catch(function () { renderPopError(body, '网络异常，实体详情加载失败'); });
    }

    function hideEntityPop() {
        var pop = document.getElementById('agcEntityPop');
        if (pop) pop.classList.remove('kgd-show');
    }

    document.addEventListener('click', function (e) {
        if (e.target.closest('#agcEntityPop') || e.target.closest('.kgd-cite')) return;
        hideEntityPop();
    });

    // ==================== 组件实例 ====================

    function ChatInstance(root, opts) {
        this.root = root;
        this.opts = opts || {};
        this.mode = 'deep';
        this.running = false;
        this.controller = null;
        this.aborted = false;
        this.unread = 0;
        this.lastTrace = [];
        this.agentCard = null;
        this.traceBody = null;
        this.competitions = [];

        var self = this;
        this.el = {};
        ['modeFast', 'modeDeep', 'context', 'hint', 'chat', 'welcome', 'welcomeText',
         'scrollbadge', 'badgeCount', 'error', 'input', 'send', 'tplmenu', 'footMode',
         'fab', 'mask', 'drawer'].forEach(function (ref) {
            self.el[ref] = root.querySelector('[data-agc-ref="' + ref + '"]');
        });

        this.bindEvents();
    }

    ChatInstance.prototype.$ = function (sel) {
        return this.root.querySelector(sel);
    };

    ChatInstance.prototype.bindEvents = function () {
        var self = this;
        if (this.el.modeFast) {
            this.el.modeFast.addEventListener('click', function () { self.switchMode('fast'); });
        }
        if (this.el.modeDeep) {
            this.el.modeDeep.addEventListener('click', function () { self.switchMode('deep'); });
        }
        if (this.el.fab) {
            this.el.fab.addEventListener('click', function () { self.open(); });
        }
        if (this.el.mask) {
            this.el.mask.addEventListener('click', function () { self.close(); });
        }
        this.root.querySelectorAll('[data-agc-action="close"]').forEach(function (btn) {
            btn.addEventListener('click', function () { self.close(); });
        });
        if (this.el.scrollbadge) {
            this.el.scrollbadge.addEventListener('click', function () { self.jumpToBottom(); });
        }
        if (this.el.input) {
            this.el.input.addEventListener('input', function () { self.onInput(); });
            this.el.input.addEventListener('keydown', function (e) { self.handleKey(e); });
        }
        if (this.el.send) {
            this.el.send.addEventListener('click', function () { self.submit(); });
        }
        if (this.el.tplmenu) {
            this.el.tplmenu.querySelectorAll('[data-agc-tpl]').forEach(function (item) {
                item.addEventListener('click', function () { self.applyTpl(item.getAttribute('data-agc-tpl')); });
            });
        }
        // 悬浮实例自带聊天滚动区；内嵌实例的滚动区也在 root 内
        if (this.el.chat) {
            this.el.chat.addEventListener('scroll', function () {
                if (self.nearBottom()) {
                    self.unread = 0;
                    if (self.el.scrollbadge) self.el.scrollbadge.classList.remove('kgd-show');
                }
            });
        }
    };

    // ---------- 悬浮实例专属 ----------

    ChatInstance.prototype.open = function () {
        this.populateContextOptions();
        if (this.el.drawer) this.el.drawer.classList.add('kgd-open');
        if (this.el.mask) this.el.mask.classList.add('kgd-open');
        if (this.el.input) this.el.input.focus();
    };

    ChatInstance.prototype.close = function () {
        if (this.el.drawer) this.el.drawer.classList.remove('kgd-open');
        if (this.el.mask) this.el.mask.classList.remove('kgd-open');
        hideEntityPop();
        if (this.el.tplmenu) this.el.tplmenu.classList.remove('kgd-show');
        // 关闭抽屉后恢复悬浮入口（页面存在内嵌 AI 答疑实例时才保持隐藏）
        if (this.el.fab && !document.querySelector('[data-agc-instance="inline"]')) {
            this.el.fab.classList.remove('kgd-hidden');
        }
    };

    // ---------- 上下文 ----------

    ChatInstance.prototype.populateContextOptions = function () {
        var select = this.el.context;
        if (!select) return;
        var self = this;
        // ① 优先镜像页面已有的竞赛下拉（如 team-space 的 aiCompetitionSelect，含队伍相关上下文）
        var pageSelect = this.opts.externalContextId
            ? document.getElementById(this.opts.externalContextId) : null;
        if (pageSelect && pageSelect.options.length > 1) {
            this.syncFromExternal(pageSelect);
            if (!pageSelect.dataset.agcBound) {
                pageSelect.dataset.agcBound = '1';
                pageSelect.addEventListener('change', function () { self.syncFromExternal(pageSelect); });
            }
            return;
        }
        // ② 否则自行拉取公开竞赛目录
        if (this.competitions.length || competitionsLoaded) return;
        competitionsLoaded = true;
        doFetch('/api/competitions?size=100', { method: 'GET' })
            .then(function (resp) { return resp.json(); })
            .then(function (res) {
                var list = res && res.code === 200 ? res.data : res;
                if (!Array.isArray(list)) return;
                self.competitions = list;
                list.forEach(function (c) {
                    if (!c || !c.id) return;
                    var name = c.name || c.title || ('竞赛 ' + c.id);
                    var option = document.createElement('option');
                    option.value = c.id;
                    option.textContent = name;
                    select.appendChild(option);
                });
            })
            .catch(function () { /* 竞赛列表拉取失败不阻塞，保持通用提问 */ });
    };

    ChatInstance.prototype.syncFromExternal = function (pageSelect) {
        var select = this.el.context;
        if (!select || !pageSelect.value) return;
        for (var i = 0; i < select.options.length; i++) {
            if (select.options[i].value === pageSelect.value) {
                select.value = pageSelect.value;
                return;
            }
        }
        // 页面有、面板没有的选项：动态补一条
        var text = pageSelect.options[pageSelect.selectedIndex];
        if (text && text.textContent) {
            var option = document.createElement('option');
            option.value = pageSelect.value;
            option.textContent = text.textContent;
            select.appendChild(option);
            select.value = pageSelect.value;
        }
    };

    ChatInstance.prototype.contextId = function () {
        return this.el.context && this.el.context.value ? this.el.context.value : null;
    };

    // ---------- 模式 ----------

    ChatInstance.prototype.switchMode = function (mode) {
        this.mode = mode;
        if (this.el.modeFast) this.el.modeFast.classList.toggle('kgd-active', mode === 'fast');
        if (this.el.modeDeep) this.el.modeDeep.classList.toggle('kgd-active', mode === 'deep');
        if (this.el.welcomeText) this.el.welcomeText.textContent = WELCOME_TEXTS[mode];
        if (this.el.input) this.el.input.placeholder = PLACEHOLDERS[mode];
        if (this.el.hint) this.el.hint.textContent = HINTS[mode];
        if (this.el.footMode) this.el.footMode.textContent = FOOT_MODES[mode];
    };

    // ---------- 输入 / 模板 / 快捷键 ----------

    ChatInstance.prototype.onInput = function () {
        var input = this.el.input;
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 96) + 'px';
        if (this.el.tplmenu) {
            this.el.tplmenu.classList.toggle('kgd-show', input.value.trim() === '/');
        }
    };

    ChatInstance.prototype.applyTpl = function (text) {
        var input = this.el.input;
        input.value = text;
        if (this.el.tplmenu) this.el.tplmenu.classList.remove('kgd-show');
        input.focus();
        this.onInput();
    };

    ChatInstance.prototype.handleKey = function (e) {
        if (e.key === 'Escape') {
            if (this.el.tplmenu && this.el.tplmenu.classList.contains('kgd-show')) {
                this.el.tplmenu.classList.remove('kgd-show');
                return;
            }
            hideEntityPop();
            e.target.blur();
            return;
        }
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
            e.preventDefault();
            this.submit();
        }
    };

    ChatInstance.prototype.submit = function () {
        if (this.running || !this.el.input) return;
        var question = this.el.input.value.trim();
        if (!question) { this.showError('请先输入问题'); return; }
        this.clearError();
        hideEntityPop();
        if (this.el.tplmenu) this.el.tplmenu.classList.remove('kgd-show');
        this.el.input.value = '';
        this.el.input.style.height = 'auto';

        this.appendUserBubble(question);
        if (this.mode === 'deep') {
            this.startTraceCard();
            this.streamAgentic(question);
        } else {
            this.startSimpleCard();
            this.fastQuery(question);
        }
    };

    // ---------- 消息渲染 ----------

    ChatInstance.prototype.nearBottom = function () {
        var chat = this.el.chat;
        if (!chat) return true;
        return chat.scrollHeight - chat.scrollTop - chat.clientHeight < 80;
    };

    ChatInstance.prototype.scrollToBottom = function (smooth) {
        if (!this.el.chat) return;
        this.el.chat.scrollTo({ top: this.el.chat.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    };

    ChatInstance.prototype.onNewContent = function () {
        if (this.nearBottom()) {
            this.scrollToBottom(false);
        } else {
            this.unread++;
            if (this.el.scrollbadge) {
                this.el.badgeCount.textContent = this.unread > 99 ? '99+' : this.unread;
                this.el.scrollbadge.classList.add('kgd-show');
            }
        }
    };

    ChatInstance.prototype.jumpToBottom = function () {
        this.unread = 0;
        if (this.el.scrollbadge) this.el.scrollbadge.classList.remove('kgd-show');
        this.scrollToBottom(true);
    };

    ChatInstance.prototype.showError = function (message) {
        if (this.el.error) { this.el.error.textContent = message; this.el.error.classList.add('kgd-show'); }
    };

    ChatInstance.prototype.clearError = function () {
        if (this.el.error) this.el.error.classList.remove('kgd-show');
    };

    ChatInstance.prototype.setBusy = function (busy) {
        this.running = busy;
        if (this.el.send) this.el.send.disabled = busy;
    };

    ChatInstance.prototype.appendUserBubble = function (question) {
        if (this.el.welcome) this.el.welcome.style.display = 'none';
        var row = document.createElement('div');
        row.className = 'kgd-row kgd-row-user kgd-enter';
        var bubble = document.createElement('div');
        bubble.className = 'kgd-msg-user';
        bubble.textContent = question;
        row.appendChild(bubble);
        this.el.chat.appendChild(row);
        this.onNewContent();
    };

    ChatInstance.prototype.appendAgentRow = function (innerHtml) {
        var row = document.createElement('div');
        row.className = 'kgd-row kgd-enter';
        row.innerHTML = avatarHtml(true) +
            '<div class="kgd-agent-bubble kgd-bubble kgd-bubble-white">' + innerHtml + '</div>';
        this.el.chat.appendChild(row);
        this.agentCard = row;
        this.onNewContent();
        return row;
    };

    ChatInstance.prototype.startTraceCard = function () {
        this.aborted = false;
        this.controller = new AbortController();
        var row = this.appendAgentRow(
            '<div class="kgd-card-title" data-agc-ref="agentTitle">' +
              '<span class="kgd-title-text">深度探索中</span>' +
              '<span class="kgd-dots"><span class="kgd-dot"></span><span class="kgd-dot"></span><span class="kgd-dot"></span></span>' +
              '<button type="button" class="kgd-stop" data-agc-action="stop">' +
                '<svg fill="currentColor" stroke="none"><use href="#kgd-i-stop"/></svg>停止' +
              '</button>' +
            '</div>' +
            '<div class="kgd-trace" data-agc-ref="trace"></div>');
        this.traceBody = row.querySelector('[data-agc-ref="trace"]');
        var self = this;
        var stopBtn = row.querySelector('[data-agc-action="stop"]');
        if (stopBtn) stopBtn.addEventListener('click', function () { self.stop(); });
        return row;
    };

    ChatInstance.prototype.startSimpleCard = function () {
        this.appendAgentRow(
            '<div class="kgd-card-title" data-agc-ref="agentTitle">' +
              '<span class="kgd-title-text">检索中</span><span class="kgd-spin"></span>' +
            '</div>');
        this.traceBody = null;
    };

    ChatInstance.prototype.finishCard = function (rounds, toolCalls, fallback, stopped) {
        var title = this.agentCard && this.agentCard.querySelector('[data-agc-ref="agentTitle"]');
        if (!title) return;
        var dots = title.querySelector('.kgd-dots');
        var spin = title.querySelector('.kgd-spin');
        var stopBtn = title.querySelector('.kgd-stop');
        if (dots) {
            dots.outerHTML = stopped
                ? '<span class="kgd-badge-fb">已停止</span>'
                : (fallback ? '<span class="kgd-badge-fb">已降级快速检索</span>'
                            : '<span class="kgd-badge-done">探索完成</span>');
        }
        if (spin) spin.remove();
        if (stopBtn) stopBtn.remove();
        var text = title.querySelector('.kgd-title-text');
        if (text) {
            text.textContent = stopped ? '推理过程（已停止）'
                : fallback ? '推理过程（降级）'
                : '推理过程（' + (rounds || '-') + ' 轮 · ' + (toolCalls || 0) + ' 次工具调用）';
        }
        // 探索结束后推理区默认折叠（点击标题/箭头展开），答案卡不被长轨迹挤压
        if (this.traceBody && !stopped) {
            this.traceBody.classList.add('kgd-collapsed');
            title.classList.add('kgd-open');
            var chevron = document.createElement('button');
            chevron.type = 'button';
            chevron.className = 'kgd-chevron';
            chevron.setAttribute('aria-label', '展开/收起推理过程');
            chevron.innerHTML = '<svg fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><use href="#kgd-i-down"/></svg>';
            title.appendChild(chevron);
            var self = this;
            title.addEventListener('click', function () { self.toggleTrace(title); });
            title.style.cursor = 'pointer';
        }
    };

    ChatInstance.prototype.toggleTrace = function (titleEl) {
        var trace = titleEl.parentElement && titleEl.parentElement.querySelector('.kgd-trace');
        if (!trace) return;
        var collapsed = trace.classList.toggle('kgd-collapsed');
        titleEl.classList.toggle('kgd-open', !collapsed);
        if (!collapsed) this.onNewContent();
    };

    ChatInstance.prototype.stop = function () {
        if (!this.running || !this.controller) return;
        this.aborted = true;
        this.controller.abort();
    };

    ChatInstance.prototype.renderStopped = function () {
        if (this.traceBody) {
            var line = document.createElement('div');
            line.className = 'kgd-line kgd-enter';
            line.innerHTML = '<div class="kgd-line-info">' + icon('kgd-i-stop') + '<span>已手动停止探索</span></div>';
            this.traceBody.appendChild(line);
        }
        this.finishCard(null, 0, false, true);
        this.setBusy(false);
        this.controller = null;
    };

    ChatInstance.prototype.appendThought = function (round, text) {
        if (!this.traceBody) return;
        var line = document.createElement('div');
        line.className = 'kgd-line kgd-enter';
        line.innerHTML = '<div class="kgd-line-info">' + icon('kgd-i-thought') + '<span>' + escapeHtml(text) + '</span></div>';
        this.traceBody.appendChild(line);
        this.onNewContent();
    };

    ChatInstance.prototype.appendAction = function (round, tool, paramsJson) {
        if (!this.traceBody) return;
        var line = document.createElement('div');
        line.className = 'kgd-line kgd-enter';
        line.innerHTML =
            '<div class="kgd-line-action">' +
              '<span style="color:#3b82f6;display:inline-flex;margin-top:1px;">' + icon('kgd-i-wrench') + '</span>' +
              '<div style="flex:1;min-width:0;">' +
                '<div style="color:#2563eb;font-weight:600;">调用工具 <span class="kgd-tool-chip" title="' + escapeHtml(tool) + '">' + escapeHtml(tool) + '</span></div>' +
                '<div class="kgd-params" title="' + escapeHtml(paramsJson || '') + '">' + escapeHtml(paramsJson || '') + '</div>' +
              '</div>' +
            '</div>';
        this.traceBody.appendChild(line);
        this.onNewContent();
    };

    ChatInstance.prototype.appendObservation = function (round, text) {
        if (!this.traceBody) return;
        var line = document.createElement('div');
        line.className = 'kgd-line kgd-enter';
        line.innerHTML = '<div class="kgd-line-obs">' +
            '<span style="color:#9ca3af;display:inline-flex;margin-top:1px;">' + icon('kgd-i-eye') + '</span>' +
            '<span>' + escapeHtml(abbreviate(text, 220)) + '</span></div>';
        this.traceBody.appendChild(line);
        this.onNewContent();
    };

    /** 答案文本 → 安全 HTML：marked 解析 Markdown + DOMPurify 净化；库未就绪时回退纯文本 */
    function renderMarkdownAnswer(text) {
        if (window.marked && window.DOMPurify) {
            try {
                return DOMPurify.sanitize(marked.parse(String(text || '')));
            } catch (e) { /* 库异常时回退纯文本 */ }
        }
        return escapeHtml(text || '');
    }

    ChatInstance.prototype.renderAnswer = function (answerText, citations, trace, fallback) {
        var hasTrace = trace && trace.length > 0;
        var row = document.createElement('div');
        row.className = 'kgd-row kgd-enter';
        row.innerHTML = avatarHtml(true) +
            '<div class="kgd-agent-bubble kgd-bubble">' +
              '<div class="kgd-answer-head">' + icon('kgd-i-check', 14) +
                (fallback ? '最终答案（深度搜索不可用，已降级）' : '最终答案') +
              '</div>' +
              '<div class="kgd-answer-text kgd-md">' + renderMarkdownAnswer(answerText) + '</div>' +
              '<div class="kgd-cites"></div>' +
              (hasTrace
                  ? '<button type="button" class="kgd-toggle">' + icon('kgd-i-clock', 14) +
                    '<span class="kgd-visual-toggle-text">查看完整推理链路</span></button>' +
                    '<div class="kgd-visual" style="display:none;"></div>'
                  : '') +
            '</div>';
        this.el.chat.appendChild(row);

        var self = this;
        var citeBox = row.querySelector('.kgd-cites');
        (citations || []).slice(0, 12).forEach(function (cite) {
            var chip = document.createElement('span');
            chip.className = 'kgd-cite';
            chip.innerHTML = icon('kgd-i-clip', 10) + '<span>#' + cite.entityId +
                (cite.name ? ' ' + escapeHtml(cite.name) : '') + '</span>';
            chip.addEventListener('click', function (e) {
                e.stopPropagation();
                showEntityPop(cite.entityId, chip);
            });
            citeBox.appendChild(chip);
        });

        var toggle = row.querySelector('.kgd-toggle');
        if (toggle) {
            toggle.addEventListener('click', function () { self.toggleVisual(toggle, trace); });
        }
        this.onNewContent();
    };

    ChatInstance.prototype.toggleVisual = function (btn, trace) {
        var wrap = btn.nextElementSibling;
        var label = btn.querySelector('.kgd-visual-toggle-text');
        if (wrap.style.display === 'none') {
            wrap.style.display = 'block';
            wrap.innerHTML = this.buildTimeline(trace);
            label.textContent = '收起推理链路';
        } else {
            wrap.style.display = 'none';
            label.textContent = '查看完整推理链路';
        }
    };

    ChatInstance.prototype.buildTimeline = function (trace) {
        var items = [{ kind: 'question', text: '用户提问' }];
        (trace || []).forEach(function (entry) {
            if (entry.thought) items.push({ kind: 'thought', round: entry.round, text: entry.thought });
            if (entry.action) items.push({ kind: 'action', round: entry.round,
                text: entry.action + '  ' + (entry.actionInput || '') });
            if (entry.observation) items.push({ kind: 'observation', round: entry.round, text: entry.observation });
        });
        items.push({ kind: 'final', text: '最终答案' });

        var html = '<div class="kgd-vline">';
        items.forEach(function (item) {
            var c = COLORS[item.kind];
            html +=
                '<div class="kgd-vnode">' +
                  '<span class="kgd-vnode-dot" style="border-color:' + c.dot + ';background:' + c.bg + ';"></span>' +
                  '<span class="kgd-vnode-chip" style="background:' + c.bg + ';border-color:' + c.border + ';color:' + c.text + ';">' +
                    icon(c.icon, 13) + c.label + (item.round ? ' #' + item.round : '') +
                    '<span style="font-weight:400;opacity:.85;">' + escapeHtml(abbreviate(item.text, 40)) + '</span>' +
                  '</span>' +
                  '<div class="kgd-vnode-detail">' + escapeHtml(item.text) + '</div>' +
                '</div>';
        });
        html += '</div>' +
            '<div class="kgd-legend">' +
              '<span><i style="background:#60a5fa;"></i>检索</span>' +
              '<span><i style="background:#9ca3af;"></i>推理</span>' +
              '<span><i style="background:#fbbf24;"></i>观察</span>' +
              '<span><i style="background:#a78bfa;"></i>补充</span>' +
              '<span><i style="background:#4ade80;"></i>产出</span>' +
            '</div>';
        return html;
    };

    // ---------- 后端调用 ----------

    ChatInstance.prototype.fastQuery = function (question) {
        var self = this;
        doFetch('/api/ai/graph-rag/query', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question: question, competitionId: this.contextId() })
        }).then(function (resp) { return resp.json(); }).then(function (body) {
            self.finishCard(null, 0, false, false);
            self.setBusy(false);
            if (!body || body.code !== 200) {
                self.showError((body && body.message) || '回答失败，请稍后重试');
                return;
            }
            self.renderAnswer(body.data.answer, body.data.citations, null, false);
        }).catch(function () {
            self.finishCard(null, 0, false, false);
            self.setBusy(false);
            self.showError('网络异常，请稍后重试');
        });
    };

    ChatInstance.prototype.streamAgentic = function (question) {
        var self = this;
        this.lastTrace = [];
        doFetch('/api/ai/graph-rag/agentic-query/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ question: question, competitionId: this.contextId() }),
            signal: this.controller.signal
        }).then(function (resp) {
            var contentType = resp.headers.get('content-type') || '';
            if (contentType.indexOf('text/event-stream') < 0) {
                return resp.json().then(function (body) {
                    self.finishCard(null, 0, false, false);
                    self.setBusy(false);
                    self.showError((body && body.message) || '回答失败，请稍后重试');
                });
            }
            var reader = resp.body.getReader();
            var decoder = new TextDecoder();
            var buffer = '';
            function pump() {
                return reader.read().then(function (chunk) {
                    if (chunk.done) { self.setBusy(false); return; }
                    buffer += decoder.decode(chunk.value, { stream: true });
                    var frames = buffer.split('\n\n');
                    buffer = frames.pop();
                    frames.forEach(function (frame) { self.handleFrame(frame); });
                    return pump();
                });
            }
            return pump();
        }).catch(function (err) {
            if (self.aborted || (err && err.name === 'AbortError')) {
                self.renderStopped();
                return;
            }
            self.finishCard(null, 0, true, false);
            self.setBusy(false);
            self.showError('网络异常，推理被中断');
        });
    };

    ChatInstance.prototype.handleFrame = function (frame) {
        var eventName = '', dataText = '';
        frame.split('\n').forEach(function (line) {
            if (line.indexOf('event:') === 0) eventName = line.slice(6).trim();
            if (line.indexOf('data:') === 0) dataText += line.slice(5).trim();
        });
        if (!eventName) return;
        var data = null;
        try { data = JSON.parse(dataText); } catch (e) { data = null; }
        if (!data) return;

        if (eventName === 'thought' && data.text) {
            this.appendThought(data.round, data.text);
            this.lastTrace.push({ round: data.round, thought: data.text });
        } else if (eventName === 'action') {
            this.appendAction(data.round, data.tool, data.params);
            this.lastTrace.push({ round: data.round, action: data.tool, actionInput: data.params });
        } else if (eventName === 'observation' && data.text) {
            this.appendObservation(data.round, data.text);
            this.lastTrace.push({ round: data.round, observation: data.text });
        } else if (eventName === 'final') {
            this.renderAnswer(data.answer, data.citations, data.trace || this.lastTrace, false);
        } else if (eventName === 'done') {
            this.finishCard(data.rounds, data.toolCalls, data.fallback, false);
            this.setBusy(false);
        } else if (eventName === 'error') {
            this.finishCard(null, 0, true, false);
            this.setBusy(false);
            this.showError(data.message || '回答失败，请稍后重试');
        }
    };

    // ==================== 挂载与全局快捷键 ====================

    function mountFab() {
        if (fabInstance) return fabInstance;
        var root = document.getElementById('agcFabRoot');
        if (!root) return null;
        fabInstance = new ChatInstance(root, { inline: false });
        instances.push(fabInstance);
        // 页面存在内嵌实例（中央卡片已提供完整交互）时隐藏悬浮按钮，避免双入口
        if (document.querySelector('[data-agc-instance="inline"]')) {
            fabInstance.el.fab.classList.add('kgd-hidden');
        }
        return fabInstance;
    }

    function mountInline(root) {
        if (!root) return null;
        var instance = new ChatInstance(root, { inline: true });
        instances.push(instance);
        // 有内嵌实例时隐藏悬浮入口
        if (fabInstance && fabInstance.el.fab) {
            fabInstance.el.fab.classList.add('kgd-hidden');
        }
        return instance;
    }

    // 全局快捷键只注册一次：存在内嵌实例时路由到内嵌输入框，否则唤起悬浮面板
    var globalKeyBound = false;
    function bindGlobalKeys() {
        if (globalKeyBound) return;
        globalKeyBound = true;
        document.addEventListener('keydown', function (e) {
            if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
                e.preventDefault();
                var inline = instances.filter(function (i) { return i.opts.inline; })[0];
                if (inline) {
                    var input = inline.el.input;
                    if (!input) return;
                    if (e.shiftKey && !input.value.trim()) input.value = '有哪些偏算法的竞赛？';
                    input.focus();
                    if (e.shiftKey) inline.submit();
                } else if (fabInstance) {
                    fabInstance.open();
                    if (e.shiftKey && fabInstance.el.input) {
                        if (!fabInstance.el.input.value.trim()) {
                            fabInstance.el.input.value = '有哪些偏算法的竞赛？';
                        }
                        fabInstance.submit();
                    }
                }
            }
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        bindGlobalKeys();
        // 悬浮 fragment 的 DOMContentLoaded 挂载由其内联脚本调用 mountFab；这里兜底：
        if (!fabInstance && document.getElementById('agcFabRoot')) mountFab();
    });

    window.agenticChat = {
        mountFab: mountFab,
        mountInline: mountInline,
        togglePopMore: function (btn) {
            var more = document.getElementById('agcPopMore');
            if (!more) return;
            var open = more.classList.toggle('kgd-open');
            btn.textContent = open ? '收起详情' : '查看实体详情';
        }
    };
})();
