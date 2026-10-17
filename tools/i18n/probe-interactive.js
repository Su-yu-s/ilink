/**
 * 交互态国际化巡检：打开英文模式下的弹窗 / 二级面板 / 原生 confirm，
 * 检查这些「默认截图里看不到」的区域还有没有中文界面文案。
 *
 * 用法：node tools/i18n/probe-interactive.js <账号> <密码>
 */
const { chromium } = require('playwright');

const base = process.env.ILINK_BASE || 'http://localhost:8090';
const [, , account, password] = process.argv;

const visibleChinese = () => {
    const skip = '[data-user-content], .il-markdown-content, .community-article-content, '
        + '.community-post-content, .post-content, .team-description, .asset-description, '
        + '.profile-bio, script, style, noscript, [hidden]';
    const isVisible = (el) => {
        if (!el || !el.getClientRects().length) return false;
        const style = getComputedStyle(el);
        return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
    };
    const out = [];
    const seen = new Set();
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
        const parent = node.parentElement;
        if (!parent || parent.closest(skip)) continue;
        const text = (node.textContent || '').replace(/\s+/g, ' ').trim();
        if (!text || !/[\u3400-\u9fff]/.test(text) || seen.has(text)) continue;
        if (!isVisible(parent)) continue;
        seen.add(text);
        out.push(text.slice(0, 120));
    }
    document.querySelectorAll('[placeholder], [title], [aria-label]').forEach((el) => {
        if (el.closest(skip) || !isVisible(el)) return;
        ['placeholder', 'title', 'aria-label'].forEach((attr) => {
            const value = el.getAttribute(attr);
            if (value && /[\u3400-\u9fff]/.test(value) && !seen.has(value)) {
                seen.add(value);
                out.push('<' + attr + '> ' + value.slice(0, 120));
            }
        });
    });
    return out;
};

async function report(page, label) {
    const items = await page.evaluate(visibleChinese);
    console.log('== ' + label + ' → ' + items.length + ' 条');
    items.forEach((item) => console.log('   ' + item));
}

/** 把页面里的每个弹窗依次显示出来再扫描，覆盖默认隐藏的分支 */
async function reportModals(page, label) {
    const count = await page.evaluate(() => document.querySelectorAll('.modal').length);
    for (let index = 0; index < count; index += 1) {
        const name = await page.evaluate((i) => {
            const modal = document.querySelectorAll('.modal')[i];
            modal.dataset.probePrev = modal.style.display || '';
            modal.style.display = 'block';
            modal.classList.add('show');
            return modal.id || ('modal#' + i);
        }, index);
        await page.waitForTimeout(300);
        const items = await page.evaluate(visibleChinese);
        const title = await page.evaluate((i) => {
            const modal = document.querySelectorAll('.modal')[i];
            const heading = modal.querySelector('.modal-title, h5');
            return heading ? heading.textContent.trim() : '';
        }, index);
        console.log('== ' + label + ' / ' + name + (title ? ' 「' + title + '」' : '') + ' → ' + items.length + ' 条');
        items.forEach((item) => console.log('   ' + item));
        await page.evaluate((i) => {
            const modal = document.querySelectorAll('.modal')[i];
            modal.style.display = modal.dataset.probePrev || '';
            modal.classList.remove('show');
        }, index);
    }
}

(async () => {
    const browser = await chromium.launch();
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    const dialogs = [];
    page.on('dialog', async (dialog) => {
        dialogs.push(dialog.message());
        await dialog.dismiss();
    });

    await page.goto(base + '/login.html', { waitUntil: 'networkidle' });
    await page.fill('#identifier', account);
    await page.fill('#password', password);
    await Promise.all([
        page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
        page.click('#loginBtn')
    ]);

    const isAdmin = account === '23130320';
    if (!isAdmin) {
        await page.goto(base + '/team-space.html?id=51&lang=en', { waitUntil: 'networkidle' });
        await page.waitForTimeout(2500);
        await report(page, 'team-space 默认视图');

        await page.evaluate(() => {
            if (window.TeamInvite && window.TeamInvite.open) window.TeamInvite.open(51, 'GUI 测试队');
        });
        await page.waitForTimeout(1200);
        await report(page, '邀请成员弹窗');
        await page.evaluate(() => {
            const modal = document.getElementById('teamInviteModal');
            if (modal) { modal.classList.remove('show'); modal.style.display = ''; }
            document.querySelectorAll('.modal-backdrop').forEach((el) => el.remove());
            document.body.classList.remove('modal-open');
        });
        await page.waitForTimeout(300);
        await reportModals(page, 'student 弹窗巡检');
    } else {
        await page.goto(base + '/admin.html?lang=en', { waitUntil: 'networkidle' });
        await page.waitForTimeout(2500);
        await report(page, 'admin 默认视图');

        const editButtons = page.locator('#userTableBody button, #teamTableBody button').filter({ hasText: /编辑|Edit/i });
        if (await editButtons.count()) {
            await editButtons.first().click({ force: true }).catch(() => {});
            await page.waitForTimeout(1200);
            await report(page, 'admin 编辑弹窗');
            await page.keyboard.press('Escape');
            await page.waitForTimeout(500);
        }
        await page.evaluate(() => {
            if (typeof openDeleteReasonModal === 'function') openDeleteReasonModal('删除组队需求', function () {});
        });
        await page.waitForTimeout(900);
        await report(page, 'admin 删除原因弹窗');
        await page.evaluate(() => {
            document.querySelectorAll('.modal').forEach((modal) => {
                modal.classList.remove('show');
                modal.style.display = '';
            });
            document.querySelectorAll('.modal-backdrop').forEach((el) => el.remove());
            document.body.classList.remove('modal-open');
        });
        await page.waitForTimeout(300);
        await reportModals(page, 'admin 弹窗巡检');
    }

    console.log('== 原生 confirm 文案 → ' + dialogs.length + ' 条');
    dialogs.forEach((message) => console.log('   ' + message));
    await browser.close();
})();
