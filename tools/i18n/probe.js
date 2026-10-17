/**
 * 打开指定页面（英文模式）并对给定选择器取值，用于核对个别控件渲染结果。
 * 用法：node tools/i18n/probe.js <path> <selector> [account] [password]
 */
const { chromium } = require('playwright');

const base = process.env.ILINK_BASE || 'http://localhost:8090';
const [, , target, selector, account, password] = process.argv;

(async () => {
    const browser = await chromium.launch();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    if (account) {
        await page.goto(base + '/login.html', { waitUntil: 'networkidle' });
        await page.fill('#identifier', account);
        await page.fill('#password', password);
        await Promise.all([
            page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
            page.click('#loginBtn')
        ]);
        await page.waitForTimeout(1200);
    }
    const url = base + target + (target.includes('?') ? '&' : '?') + 'lang=en';
    await page.goto(url, { waitUntil: 'networkidle' }).catch(() => {});
    await page.waitForTimeout(2000);
    const result = await page.evaluate((sel) => {
        const out = { url: location.href, lang: document.documentElement.lang, nodes: [] };
        document.querySelectorAll(sel).forEach((el) => {
            const textNodes = [];
            const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
            let n;
            while ((n = walker.nextNode())) textNodes.push(n.textContent);
            out.nodes.push({ outerHTML: el.outerHTML.slice(0, 400), text: el.textContent, textNodes });
        });
        out.copySample = {
            '人': (window.__I18N_UI_COPY_MAP__ || {})['人'] || null,
            '共 1 人': (window.__I18N_UI_COPY_MAP__ || {})['共 1 人'] || null
        };
        return out;
    }, selector);
    console.log(JSON.stringify(result, null, 1));
    await browser.close();
})();
