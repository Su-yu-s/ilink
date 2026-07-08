// 竞赛小队：展示用户已加入的所有团队，可点击进入团队的任务看板

document.addEventListener('DOMContentLoaded', async function () {
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
                '<div class="text-center py-5 text-muted">' +
                    '<p class="mb-2">您还没有加入任何团队</p>' +
                    '<a href="/team-market.html" class="btn btn-sm btn-primary">去组队大厅</a>' +
                '</div>';
            return;
        }
        container.innerHTML = teams.map(function (t) {
            var teamId = Number(t.teamId);
            var title = escapeHtml(t.teamTitle || '未命名');
            var status = t.status || '';
            var statusLabel = teamStatusLabel(status);
            var statusClass = teamStatusBadgeClass(status);
            var joined = t.joinedAt ? formatTime(t.joinedAt) : '';
            var isCreator = !!t.isCreator;
            var badge = isCreator ? ' <span class="meta-chip meta-chip--primary">队长</span>' : '';
            return '<div class="joined-team-card">' +
                '<div class="joined-team-card__header">' +
                    '<a class="joined-team-card__title" href="/team-detail.html?id=' + teamId + '">' + title + badge + '</a>' +
                    '<span class="il-team-card__badge ' + statusClass + '">' + statusLabel + '</span>' +
                '</div>' +
                '<div class="joined-team-card__meta">加入于 ' + joined + '</div>' +
                '<div class="joined-team-card__actions">' +
                    '<a href="/team-detail.html?id=' + teamId + '" class="il-team-card__btn">查看详情</a>' +
                    (status === 'TEAMING' ? '<a href="/team-space.html?id=' + teamId + '" class="il-team-card__btn">进入空间</a>' : '') +
                '</div>' +
            '</div>';
        }).join('');
    } catch (e) {
        console.error('加载竞赛小队失败', e);
        container.innerHTML = '<div class="text-center py-4 text-muted">网络异常，请稍后重试</div>';
    }
});

function teamStatusLabel(status) {
    var map = { OPEN: '招募中', TEAMING: '已组队', CLOSED: '已结束' };
    return map[status] || status || '';
}

function teamStatusBadgeClass(status) {
    var map = {
        OPEN: 'il-team-card__badge--open',
        TEAMING: 'il-team-card__badge--teaming',
        CLOSED: 'il-team-card__badge--closed'
    };
    return map[status] || 'il-team-card__badge--closed';
}
