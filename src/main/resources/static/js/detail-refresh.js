/* ============================================================
   detail-refresh.js — 组队详情/导师详情 视觉增强（不改业务逻辑）
   1) #teamStatus（业务 JS 写入）同步到标题右侧 #teamHeaderStatus 呼吸 pill
   2) #teamMembers 成员数量同步到 #teamMembersCount
   3) 导师申请 textarea 字数统计
   ============================================================ */
(function () {
    'use strict';

    /* ---------- 组队详情：状态 pill ---------- */
    function mapStatus(text, cls) {
        var t = (text || '').trim();
        if (cls.indexOf('is-open') > -1 || t.indexOf('招募') > -1) {
            return { label: t || '招募中', cls: 'td-status-pill is-open' };
        }
        if (t.indexOf('组队') > -1 || t.indexOf('进行') > -1) {
            return { label: t || '已组队', cls: 'td-status-pill is-teaming' };
        }
        return { label: t || '已结束', cls: 'td-status-pill is-closed' };
    }
    function syncStatusPill() {
        var src = document.getElementById('teamStatus');
        var pill = document.getElementById('teamHeaderStatus');
        if (!src || !pill) return;
        var m = mapStatus(src.textContent, src.className || '');
        pill.textContent = m.label;
        pill.className = m.cls;
        pill.hidden = false;
    }

    /* ---------- 组队详情：成员计数 ---------- */
    function syncMemberCount() {
        var box = document.getElementById('teamMembers');
        var out = document.getElementById('teamMembersCount');
        if (!box || !out) return;
        var n = box.querySelectorAll('.team-member').length;
        out.textContent = n ? ('共 ' + n + ' 人') : '';
    }

    /* ---------- 导师详情：字数统计 ---------- */
    function bindCharCount() {
        var ta = document.getElementById('projectApplyMessage');
        var out = document.getElementById('tdCharCount');
        if (!ta || !out || ta.__tdBound) return;
        ta.__tdBound = true;
        var wrap = out.closest ? out.closest('.td-char-count') : out.parentElement;
        function update() {
            var len = (ta.value || '').length;
            out.textContent = String(len);
            if (wrap) wrap.classList.toggle('is-near', len > 450);
        }
        ta.addEventListener('input', update);
        update();
    }

    function init() {
        var src = document.getElementById('teamStatus');
        var box = document.getElementById('teamMembers');
        if (src) {
            syncStatusPill();
            new MutationObserver(syncStatusPill).observe(src, { childList: true, characterData: true, subtree: true, attributes: true });
        }
        if (box) {
            syncMemberCount();
            new MutationObserver(syncMemberCount).observe(box, { childList: true, subtree: true });
        }
        bindCharCount();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
    // 业务数据异步返回较晚时再兜底一次
    setTimeout(function () { try { init(); } catch (e) {} }, 800);
    setTimeout(function () { try { init(); } catch (e) {} }, 2000);
})();
