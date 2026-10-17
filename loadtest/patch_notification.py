import io

p = 'src/main/resources/static/js/notification-manager.js'
s = io.open(p, encoding='utf-8').read()

# 1) 图标映射补齐三个删除类型（trash / file-x 线性风格，与现有图标一致）
old = "            'COMMENT': '<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\"><path d=\"M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z\"/></svg>'"
new = """            'COMMENT': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
            'TEAM_DELETED': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>',
            'ASSET_DELETED': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><circle cx="12" cy="13" r="3"/><path d="M9.5 10.5l5 5M14.5 10.5l-5 5"/></svg>',
            'POST_DELETED': '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M4 19.5V5a2 2 0 0 1 2-2h9l5 5v11.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19.5Z"/><path d="M14 3v6h6"/><path d="m9.5 12.5 5 5M14.5 12.5l-5 5"/></svg>'"""
assert old in s, 'comment icon'
s = s.replace(old, new, 1)

# 2) handleNotificationClick：删除类通知打开详情弹窗；未知类型不再乱跳
old = """function handleNotificationClick(id, notificationType, relatedId) {
    if (window.notificationManager) {
        window.notificationManager.markAsRead(id);
    }

    if (!notificationType || !relatedId) {
        return;
    }
"""
new = """function handleNotificationClick(id, notificationType, relatedId) {
    if (window.notificationManager) {
        window.notificationManager.markAsRead(id);
    }

    // 删除类通知：相关内容已被删除，跳转会 404——点击打开完整详情弹窗
    if (notificationType === 'TEAM_DELETED' || notificationType === 'ASSET_DELETED' || notificationType === 'POST_DELETED') {
        const item = document.querySelector('.notification-item[data-id="' + id + '"]');
        const title = item ? (item.querySelector('.notification-item__title') || {}).textContent || '' : '';
        const content = item ? (item.querySelector('.notification-item__text') || {}).textContent || '' : '';
        const time = item ? (item.querySelector('.notification-item__time') || {}).textContent || '' : '';
        const icon = item ? (item.querySelector('.notification-item__icon') || {}).innerHTML || '' : '';
        showNotificationDetailModal(title, content, time, icon);
        return;
    }

    if (!notificationType || !relatedId) {
        return;
    }
"""
assert old in s, 'click entry'
s = s.replace(old, new, 1)

# 3) default 分支：未知类型不再猜跳转
old = """        default:
            // 兜底：跳转到团队详情页
            route = '/team-detail.html?id=' + relatedId;
            break;
    }"""
new = """        default:
            // 未知类型不做猜测跳转（跳错页面比不跳更糟）
            route = '';
            break;
    }"""
assert old in s, 'default branch'
s = s.replace(old, new, 1)

# 4) 通知详情弹窗（动态创建，任何引入本脚本的页面都可用）
old_tail = s.rstrip()
appendix = """

// ============================================================
// 通知详情弹窗：展示完整标题/内容/时间（下拉列表里长文本被截断，
// 点击删除类等无跳转目标的通知时在这里看全文）。动态注入，随用随建。
// ============================================================
function showNotificationDetailModal(title, content, time, iconSvg) {
    let overlay = document.getElementById('notificationDetailOverlay');
    if (!overlay) {
        overlay = document.createElement('div');
        overlay.id = 'notificationDetailOverlay';
        overlay.className = 'modal fade';
        overlay.tabIndex = -1;
        overlay.innerHTML =
            '<div class="modal-dialog modal-dialog-centered">' +
                '<div class="modal-content">' +
                    '<div class="modal-header">' +
                        '<h5 class="modal-title">通知详情</h5>' +
                        '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="关闭"></button>' +
                    '</div>' +
                    '<div class="modal-body">' +
                        '<div class="d-flex align-items-center gap-2 mb-3">' +
                            '<span class="notification-detail-icon"></span>' +
                            '<h6 class="notification-detail-title mb-0"></h6>' +
                        '</div>' +
                        '<p class="notification-detail-content mb-3" style="white-space: pre-wrap; word-break: break-word;"></p>' +
                        '<div class="notification-detail-time text-muted small"></div>' +
                    '</div>' +
                    '<div class="modal-footer">' +
                        '<button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">关闭</button>' +
                    '</div>' +
                '</div>' +
            '</div>';
        document.body.appendChild(overlay);
        const style = document.createElement('style');
        style.textContent =
            '#notificationDetailOverlay .notification-detail-icon { display: inline-flex; width: 28px; height: 28px; align-items: center; justify-content: center; border-radius: 8px; background: #eef2ff; color: #4f46e5; }' +
            '#notificationDetailOverlay .notification-detail-title { font-weight: 700; }' +
            '#notificationDetailOverlay .notification-detail-content { font-size: 15px; line-height: 1.7; color: #1f2937; }';
        document.head.appendChild(style);
    }
    overlay.querySelector('.notification-detail-icon').innerHTML = iconSvg || '';
    overlay.querySelector('.notification-detail-title').textContent = title || '通知';
    overlay.querySelector('.notification-detail-content').textContent = content || '';
    overlay.querySelector('.notification-detail-time').textContent = time || '';
    if (typeof bootstrap !== 'undefined' && bootstrap.Modal) {
        bootstrap.Modal.getOrCreateInstance(overlay).show();
    } else {
        overlay.classList.add('show');
        overlay.style.display = 'block';
    }
}
"""
s = old_tail + appendix
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('notification-manager patched')
