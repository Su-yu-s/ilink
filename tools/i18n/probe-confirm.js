/**
 * 验证原生 confirm 弹窗文案在英文模式下被翻译（uiConfirm → 运行时对照表）。
 * 用法：node tools/i18n/probe-confirm.js <账号> <密码>
 */
const { chromium } = require('playwright');

const base = process.env.ILINK_BASE || 'http://localhost:8090';
const [, , account, password] = process.argv;

const samples = [
    '确定要删除该组队需求？删除后不可恢复。',
    '确定删除该成果？',
    '确定删除选中的 3 条成果？',
    '已存在同名附件“LitePT.pdf”，是否替换？',
    '确认该任务已经完成？'
];

(async () => {
    const browser = await chromium.launch();
    const page = await browser.newPage();
    await page.goto(base + '/login.html', { waitUntil: 'networkidle' });
    await page.fill('#identifier', account);
    await page.fill('#password', password);
    await Promise.all([
        page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
        page.click('#loginBtn')
    ]);
    await page.goto(base + '/profile.html?lang=en', { waitUntil: 'networkidle' });
    await page.waitForTimeout() .catch(() => {});

    for (const sample of samples) {
        const seen = await page.evaluate((message) => new Promise((resolve) => {
            const original = window.confirm;
            window.confirm = function (text) { window.confirm = original; resolve(text); return false; };
            try { uiConfirm(message); } catch (error) { window.confirm = original; resolve('ERROR: ' + error.message); }
        }), sample);
        console.log(JSON.stringify(sample) + '\n   → ' + JSON.stringify(seen));
    }
    await browser.close();
})();
