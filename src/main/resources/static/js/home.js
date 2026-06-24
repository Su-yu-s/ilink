// 个人主页JavaScript

function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// 页面加载完成后获取用户信息
document.addEventListener('DOMContentLoaded', async function() {
    try {
        const response = await apiFetch('/api/user/profile');
        const result = await response.json();
        
        if (result.code === 200) {
            renderUserInfo(result.data);
        } else {
            showMessage('获取用户信息失败: ' + result.message, 'error');
            window.location.href = '/login';
        }
    } catch (error) {
        console.error('获取用户信息异常:', error);
        showMessage('系统异常，请稍后重试', 'error');
    }
});

// 渲染用户信息
function renderUserInfo(user) {
    const userInfoContainer = document.getElementById('userInfo');

    const role = user.role || 'STUDENT';
    const roleLabel = getUserRoleDisplayName(role);
    const roleClass = role === 'ADMIN' ? 'admin' : (role === 'TEACHER' ? 'teacher' : 'student');

    const initials = (user.username || 'i')
        .trim()
        .slice(0, 2)
        .toUpperCase();

    const avatarHtml = user.avatar
        ? `<img src="${escapeHtml(user.avatar)}" alt="头像" class="user-avatar-img" onerror="this.style.display='none'; this.parentElement.querySelector('.user-avatar-initial').style.display='flex';">`
        : `<div class="user-avatar-initial">${initials}</div>`;

    userInfoContainer.innerHTML = `
        <div class="home-hero-glass page-transition home-dashboard-hero">
            <div class="d-flex align-items-center gap-3">
                <div class="user-avatar">
                    ${avatarHtml}
                    ${user.avatar ? `<div class="user-avatar-initial d-none">${initials}</div>` : ''}
                </div>
                <div class="home-hero-glass__intro">
                    <div class="d-flex align-items-center gap-2 flex-wrap mb-2">
                        <h2 class="mb-0 home-user-name">${escapeHtml(user.username)}</h2>
                        <span class="role-badge ${roleClass}">${roleLabel}</span>
                    </div>
                    <p class="home-user-meta">真实姓名：${escapeHtml(user.realName || '未设置')}</p>
                    <p class="home-user-meta">邮箱：${escapeHtml(user.email || '未设置')}</p>
                    <p class="home-user-meta">注册时间：${formatTime(user.createdAt)}</p>
                </div>
            </div>

            <div class="stats-grid home-stats-grid">
                <div class="stat-tile">
                    <span class="label">我的组队</span>
                    <span class="value">—</span>
                </div>
                <div class="stat-tile">
                    <span class="label">我的申请</span>
                    <span class="value">—</span>
                </div>
                <div class="stat-tile">
                    <span class="label">我的成果</span>
                    <span class="value">—</span>
                </div>
                <div class="stat-tile">
                    <span class="label">角色身份</span>
                    <span class="value home-role-tile-value">${roleLabel}</span>
                </div>
            </div>
        </div>
    `;

    // 由于当前后端未提供“我的组队/我的申请/我的成果”的列表接口，这里先做友好占位
    const myTeams = document.getElementById('myTeams');
    if (myTeams && myTeams.children.length === 0) {
        myTeams.innerHTML = `
            <div class="empty-state home-empty">
                <div class="home-empty__art" aria-hidden="true">
                    <svg viewBox="0 0 120 100" width="120" height="100" class="home-empty__svg"><circle cx="40" cy="38" r="18" fill="rgba(58,90,169,0.15)"/><circle cx="82" cy="42" r="14" fill="rgba(42,143,134,0.18)"/><path d="M20 88 Q60 62 100 88" stroke="rgba(58,90,169,0.25)" stroke-width="3" fill="none" stroke-linecap="round"/></svg>
                </div>
                <h4>暂无组队数据</h4>
                <p>你发布的组队需求或加入的团队将显示在这里。</p>
            </div>
        `;
    }

    const myApplications = document.getElementById('myApplications');
    if (myApplications && myApplications.children.length === 0) {
        myApplications.innerHTML = `
            <div class="empty-state home-empty">
                <div class="home-empty__art" aria-hidden="true">
                    <svg viewBox="0 0 120 100" width="120" height="100" class="home-empty__svg home-empty__svg--doc"><rect x="36" y="22" width="48" height="62" rx="8" fill="rgba(255,255,255,0.55)" stroke="rgba(58,90,169,0.28)" stroke-width="2"/><path d="M48 40h24M48 52h24M48 64h16" stroke="rgba(71,85,105,0.45)" stroke-width="3" stroke-linecap="round"/></svg>
                </div>
                <h4>暂无申请记录</h4>
                <p>申请加入团队、导师项目等记录将在这里展示。</p>
            </div>
        `;
    }
}

// 获取用户角色显示名称
function getUserRoleDisplayName(role) {
    switch (role) {
        case 'STUDENT':
            return '学生';
        case 'TEACHER':
            return '教师';
        case 'ADMIN':
            return '管理员';
        default:
            return role;
    }
}