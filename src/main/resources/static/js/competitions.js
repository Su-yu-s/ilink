// 竞赛目录：数据来自后台维护的竞赛目录 API。
const COMP_TRACKS = [
    { key: '', label: '全部' },
    { key: 'cs', label: '计算机软件' },
    { key: 'ee', label: '电子信息' },
    { key: 'innovation', label: '创新创业' },
    { key: 'stem', label: '数理建模' },
    { key: 'robot', label: '机器人 / 智能车' },
    { key: 'general', label: '综合 / 语言' }
];

let currentTrack = '';
let searchKeyword = '';
let currentPage = 1;
const PAGE_SIZE = 6;

function buildTrackTabs() {
    const nav = document.getElementById('competitionTrackTabs');
    if (!nav) return;
    nav.innerHTML = COMP_TRACKS.map(track =>
        '<button type="button" class="btn btn-sm competition-track-tab' +
        (track.key === currentTrack ? ' active' : '') +
        '" data-track="' + escapeHtml(track.key) + '">' +
        escapeHtml(track.label) + '</button>'
    ).join('');
    nav.querySelectorAll('[data-track]').forEach(btn => {
        btn.addEventListener('click', () => {
            currentTrack = btn.dataset.track || '';
            currentPage = 1;
            buildTrackTabs();
            loadCompetitions();
        });
    });
}

function renderPager(totalItems) {
    const pager = document.getElementById('competitionPager');
    const inner = document.getElementById('competitionPagerInner');
    if (!pager || !inner) return;
    const totalPages = Math.max(1, Math.ceil(totalItems / PAGE_SIZE));
    if (totalItems <= PAGE_SIZE) {
        pager.classList.add('d-none');
        inner.innerHTML = '';
        return;
    }
    const makeItem = (label, page, disabled, active) =>
        '<li class="page-item' + (disabled ? ' disabled' : '') + (active ? ' active' : '') + '">' +
        '<button class="page-link" type="button" data-page="' + page + '"' +
        (disabled ? ' disabled' : '') + (active ? ' aria-current="page"' : '') +
        '>' + label + '</button></li>';

    const pieces = [makeItem('上一页', currentPage - 1, currentPage <= 1, false)];
    let start = Math.max(1, currentPage - 2);
    let end = Math.min(totalPages, currentPage + 2);
    if (end - start < 4) {
        if (start === 1) end = Math.min(totalPages, start + 4);
        if (end === totalPages) start = Math.max(1, end - 4);
    }
    for (let page = start; page <= end; page++) {
        pieces.push(makeItem(String(page), page, false, page === currentPage));
    }
    pieces.push(makeItem('下一页', currentPage + 1, currentPage >= totalPages, false));
    inner.innerHTML = pieces.join('');
    pager.classList.remove('d-none');
    inner.querySelectorAll('button[data-page]').forEach(button => {
        button.addEventListener('click', () => {
            const page = Number(button.dataset.page || 1);
            if (page < 1 || page > totalPages || page === currentPage) return;
            currentPage = page;
            loadCompetitions();
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });
    });
}

function renderCompetitionCards(items) {
    const grid = document.getElementById('competitionGrid');
    grid.innerHTML = items.map(item => {
        const levelClass = item.levelClass || '三类';
        const scope = item.scope || '省赛';
        const scopeClass = /国赛|国际/.test(scope) ? 'scope--national'
            : /省赛/.test(scope) ? 'scope--province'
            : /校赛/.test(scope) ? 'scope--school' : 'scope--default';
        const levelCss = levelClass.includes('一类A') ? '1a'
            : levelClass.includes('一类B') ? '1b'
            : levelClass.includes('二类A') ? '2a'
            : levelClass.includes('二类B') ? '2b'
            : levelClass.includes('三类') ? '3' : 'default';
        const tags = (Array.isArray(item.tags) ? item.tags : []).map((tag, index) =>
            '<span class="meta-chip contest-tag' + (index === 0 ? ' primary' : '') + '">' +
            escapeHtml(tag) + '</span>'
        ).join('');
        const link = item.officialUrl
            ? '<a href="' + escapeHtml(item.officialUrl) +
              '" class="competition-card__link contest-card__link" target="_blank" rel="noopener noreferrer">访问官网<span aria-hidden="true">→</span></a>'
            : '<span class="competition-card__link competition-card__link--disabled">官网请自行检索</span>';
        return '<article class="contest-card competition-card h-100' +
            (levelClass.includes('一类A') ? ' featured' : '') + '">' +
            '<div class="competition-card__header"><h2 class="competition-card__title mb-0">' +
            escapeHtml(item.name || '') + '</h2><div class="competition-card__levels">' +
            '<span class="competition-card__level competition-card__level--scope ' + scopeClass + '">' +
            escapeHtml(scope) + '</span><span class="competition-card__level competition-card__level--class level--' +
            levelCss + '">' + escapeHtml(levelClass) + '</span></div></div>' +
            '<div class="competition-card__meta">' +
            '<div class="competition-card__meta-line"><strong>主办：</strong><span>' +
            escapeHtml(item.organizer || '') + '</span></div>' +
            '<div class="competition-card__meta-line"><strong>赛季参考：</strong><span>' +
            escapeHtml(item.season || '') + '</span></div></div>' +
            '<div class="competition-card__tags">' + tags + '</div>' +
            '<p class="competition-card__desc">' + escapeHtml(item.description || '') + '</p>' +
            '<div class="card-divider competition-card__divider"></div>' +
            '<div class="competition-card__foot"><span class="competition-card__season">' +
            '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2"/><path d="M12 7v5l3 2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>' +
            escapeHtml(item.season || '以赛事官网为准') + '</span>' + link + '</div></article>';
    }).join('');
}

async function loadCompetitions() {
    const grid = document.getElementById('competitionGrid');
    const empty = document.getElementById('competitionEmpty');
    const hint = document.getElementById('competitionCountHint');
    if (!grid) return;
    grid.setAttribute('aria-busy', 'true');
    grid.innerHTML = '<div class="col-12 text-center text-muted py-5">正在加载竞赛目录…</div>';
    try {
        const params = new URLSearchParams({ page: currentPage, size: PAGE_SIZE });
        if (currentTrack) params.set('track', currentTrack);
        if (searchKeyword) params.set('keyword', searchKeyword);
        const response = await apiFetch('/api/competitions?' + params.toString());
        const result = await response.json();
        if (!response.ok || result.code !== 200) {
            throw new Error(result.message || '获取竞赛目录失败');
        }
        const rows = Array.isArray(result.data) ? result.data : [];
        const pagination = result.extra?.pagination || {};
        const total = Number(pagination.total || 0);
        const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
        if (currentPage > totalPages) {
            currentPage = totalPages;
            return loadCompetitions();
        }
        renderCompetitionCards(rows);
        empty?.classList.toggle('d-none', rows.length > 0);
        if (rows.length === 0) grid.innerHTML = '';
        if (hint) {
            hint.textContent = '当前第 ' + currentPage + ' / ' + totalPages +
                ' 页 · 共收录 ' + total + ' 项（由管理员持续维护）';
        }
        renderPager(total);
    } catch (error) {
        grid.innerHTML = '<div class="col-12"><div class="alert alert-danger text-center mb-0">' +
            escapeHtml(error.message || '竞赛目录暂时无法加载，请稍后重试') + '</div></div>';
        empty?.classList.add('d-none');
        if (hint) hint.textContent = '';
        renderPager(0);
    } finally {
        grid.removeAttribute('aria-busy');
    }
}

document.addEventListener('DOMContentLoaded', () => {
    buildTrackTabs();
    const input = document.getElementById('compSearchInput');
    const button = document.getElementById('compSearchBtn');
    const applySearch = () => {
        searchKeyword = input ? input.value.trim() : '';
        currentPage = 1;
        loadCompetitions();
    };
    button?.addEventListener('click', applySearch);
    input?.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            event.preventDefault();
            applySearch();
        }
    });
    loadCompetitions();
});
