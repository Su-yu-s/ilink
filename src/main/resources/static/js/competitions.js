// 竞赛目录：数据来自后台维护的竞赛目录 API。
let currentTrack = '';
let searchKeyword = '';
let currentPage = 1;
const PAGE_SIZE = 6;

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
    if (!grid) return;

    // 不再按赛道分章节：分类已经由搜索栏的「赛道」下拉承担，
    // 页面里再列一遍标题等于把同一个信息说两次。
    grid.innerHTML = items.map(renderIndexItem).join('');
}

/**
 * 一条索引：左级别 / 中正文 / 右时间与入口。
 * 正文不截断 —— 赛制细节正是这个页面存在的理由，截断等于删掉最有价值的部分。
 */
function renderIndexItem(item) {
    const levelClass = item.levelClass || '三类';
    const scope = item.scope || '省赛';
    const url = item.officialUrl || '';

    const title = url
        ? '<a class="comp-index-link" href="' + escapeHtml(url) + '" target="_blank" rel="noopener noreferrer">' +
            escapeHtml(item.name || '') + '</a>'
        : escapeHtml(item.name || '');

    // 核心考察点：结构化数据渲染成列表，不是让后台手写 HTML
    const points = Array.isArray(item.highlights) ? item.highlights : [];
    const pointsHtml = points.length
        ? '<ul class="comp-index-points">' + points.map(h =>
            '<li>' + (h.t ? '<strong>' + escapeHtml(h.t) + '：</strong>' : '') +
            escapeHtml(h.d || '') + '</li>'
          ).join('') + '</ul>'
        : '';

    const tagsHtml = (Array.isArray(item.tags) ? item.tags : []).map(tag =>
        '<span class="comp-index-tag">' + escapeHtml(tag) + '</span>'
    ).join('');

    // 赛季字段常见写法是「校赛约 4-6 月，国赛约 9-10 月」，按标点拆成多行更好扫
    const seasonLines = String(item.season || '以赛事官网为准')
        .split(/[，,；;]/).map(s => s.trim()).filter(Boolean)
        .map(line => '<span>' + escapeHtml(line) + '</span>').join('');

    const cta = url
        ? '<span class="comp-visit" aria-hidden="true">访问官网</span>'
        : '<span class="comp-visit comp-visit--off">官网请自行检索</span>';

    return '<article class="comp-index-item" data-ui-lit>' +
        '<div class="comp-badges">' +
            '<span class="comp-badge comp-badge--scope">' + escapeHtml(scope) + '</span>' +
            '<span class="comp-badge comp-badge--class">' + escapeHtml(levelClass) + '</span>' +
        '</div>' +
        '<div class="comp-main">' +
            '<h3 class="comp-index-title">' + title + '</h3>' +
            (item.organizer
                ? '<div class="comp-index-host">主办：' + escapeHtml(item.organizer) + '</div>'
                : '') +
            (item.description
                ? '<p class="comp-index-bio">' + escapeHtml(item.description) + '</p>'
                : '') +
            pointsHtml +
            (tagsHtml ? '<div class="comp-index-tags">' + tagsHtml + '</div>' : '') +
        '</div>' +
        '<div class="comp-action">' +
            '<div class="comp-time">' + seasonLines + '</div>' +
            cta +
        '</div>' +
    '</article>';
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
            hint.textContent = t('ui.competitions.pageCount', {
                page: currentPage,
                totalPages: totalPages,
                count: total
            });
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
    const trackSelect = document.getElementById('compTrackSelect');
    trackSelect?.addEventListener('change', () => {
        currentTrack = trackSelect.value || '';
        currentPage = 1;
        loadCompetitions();
    });
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
