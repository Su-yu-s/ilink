/**
 * 英文模式下逐页抓取「仍然可见的中文」，用于核对国际化覆盖率。
 *
 * 用法：node tools/i18n/crawl-visible.js <登录名|-> <密码> <输出文件>
 * 输出：{ generatedAt, account, pages: { [path]: { title, items: [...], attrItems: [...] } } }
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const base = process.env.ILINK_BASE || 'http://localhost:8090';
const [, , account, password, outFile] = process.argv;

/** 列表页 → 详情页 id 的抓取规则 */
const discovery = {
    '/team-market.html': { pattern: /\/team-detail\.html\?id=(\d+)/g, page: '/team-detail.html?id=', limit: 3 },
    '/community.html': { pattern: /\/community\/article\/(\d+)/g, page: '/community/article/', limit: 3 },
    '/gallery.html': { pattern: /\/asset-detail\.html\?id=(\d+)/g, page: '/asset-detail.html?id=', limit: 3 },
    '/teacher-wall.html': { pattern: /\/teacher-detail\.html\?id=(\d+)/g, page: '/teacher-detail.html?id=', limit: 3 },
    '/competitions.html': { pattern: /\/competition-detail\.html\?id=(\d+)/g, page: '/competition-detail.html?id=', limit: 2 }
};

const corePaths = [
    '/index.html', '/login.html', '/register.html', '/forgot-password.html',
    '/team-market.html', '/teacher-wall.html', '/community.html', '/gallery.html',
    '/competitions.html', '/privacy.html', '/terms.html', '/404.html',
    '/profile.html', '/profile-edit.html', '/profile-posts.html', '/profile-favorites.html',
    '/profile-honors.html', '/profile-password.html', '/profile-delete.html',
    '/team-publish.html', '/team-workspace.html', '/roster.html', '/team-review.html',
    '/user-profile.html?id=38'
];

// 注意：反馈不是独立页面路由，详情页是带令牌的 /feedback/view/{token}，
// 没有 /feedback.html 与 /feedback-detail.html，写进来只会被记成「404 页面的中文」误报。
const adminPaths = ['/admin.html'];

async function capture(page, url) {
    try {
        await page.goto(url, { waitUntil: 'networkidle', timeout: 30000 });
    } catch (_) {
        await page.waitForTimeout(2500);
    }
    await page.waitForTimeout(1500);
    return page.evaluate(() => {
        const skip = '[data-user-content], .il-markdown-content, .community-article-content, '
            + '.community-post-content, .post-content, .team-description, .asset-description, '
            + '.profile-bio, script, style, noscript, .d-none, [hidden]';
        const visible = (el) => {
            if (!el) return false;
            const style = getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
            if (el.closest('.d-none, [hidden], [aria-hidden="true"]')) return false;
            return true;
        };
        const seen = new Set();
        const items = [];
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) {
            const parent = node.parentElement;
            if (!parent || parent.closest(skip)) continue;
            const text = (node.textContent || '').replace(/\s+/g, ' ').trim();
            if (!text || !/[\u3400-\u9fff]/.test(text)) continue;
            if (!visible(parent)) continue;
            if (seen.has(text)) continue;
            seen.add(text);
            const own = parent.tagName.toLowerCase() + (parent.id ? '#' + parent.id : '')
                + (parent.className ? '.' + String(parent.className).split(' ').slice(0, 2).join('.') : '');
            items.push({ text: text.slice(0, 160), el: own });
        }
        const attrItems = [];
        document.querySelectorAll('[placeholder], [title], [aria-label], [alt]').forEach((el) => {
            if (el.closest(skip) || !visible(el)) return;
            ['placeholder', 'title', 'aria-label', 'alt'].forEach((attr) => {
                const value = el.getAttribute(attr);
                if (value && /[\u3400-\u9fff]/.test(value)) {
                    attrItems.push({
                        attr,
                        text: value.slice(0, 120),
                        el: el.tagName.toLowerCase() + (el.id ? '#' + el.id : '')
                    });
                }
            });
        });
        return { title: document.title, items, attrItems };
    });
}

(async () => {
    const browser = await chromium.launch();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    if (account && account !== '-') {
        await page.goto(base + '/login.html', { waitUntil: 'networkidle' });
        await page.fill('#identifier', account);
        await page.fill('#password', password);
        await Promise.all([
            page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
            page.click('#loginBtn')
        ]);
        await page.waitForTimeout(1500);
    }

    const paths = account === '23130320' ? adminPaths : corePaths.slice();
    // 需要额外覆盖的页面（例如详情页 id 不固定时）用逗号分隔传入
    (process.env.ILINK_EXTRA || '').split(',').map((item) => item.trim()).filter(Boolean)
        .forEach((item) => { if (!paths.includes(item)) paths.push(item); });

    // 先抓列表页，从中发现详情页 id，避免硬编码业务数据
    if (account !== '23130320') {
        for (const [listPath, rule] of Object.entries(discovery)) {
            await page.goto(base + listPath, { waitUntil: 'networkidle' }).catch(() => {});
            await page.waitForTimeout(1200);
            const hrefs = await page.$$eval('a[href]', (links) => links.map((a) => a.getAttribute('href')));
            const ids = [];
            hrefs.forEach((href) => {
                const match = new RegExp(rule.pattern.source, 'g').exec(href || '');
                if (match && !ids.includes(match[1])) ids.push(match[1]);
            });
            ids.slice(0, rule.limit).forEach((id) => paths.push(rule.page + id));
        }
        // 团队空间需要成员身份：从「我的团队」入口发现
        await page.goto(base + '/profile.html', { waitUntil: 'networkidle' }).catch(() => {});
        await page.waitForTimeout(1200);
        const teamHrefs = await page.$$eval('a[href]', (links) => links.map((a) => a.getAttribute('href')));
        teamHrefs.forEach((href) => {
            const match = /\/team-(?:space|chat)\.html\?id=(\d+)/.exec(href || '');
            if (!match) return;
            ['/team-detail.html?id=', '/team-space.html?id=', '/team-chat.html?id='].forEach((prefix) => {
                const target = prefix + match[1];
                if (!paths.includes(target)) paths.push(target);
            });
        });
    }

    const pages = {};
    for (const p of paths) {
        const url = base + p + (p.includes('?') ? '&' : '?') + 'lang=en';
        const data = await capture(page, url);
        pages[p] = data;
        console.log(p + ' | text=' + data.items.length + ' attr=' + data.attrItems.length);
    }

    const payload = { generatedAt: new Date().toISOString(), account: account || 'anonymous', pages };
    fs.writeFileSync(outFile || (process.env.TEMP + '/ilink-i18n/visible-' + (account || 'anon') + '.json'),
        JSON.stringify(payload, null, 1), 'utf8');
    await browser.close();
})();
