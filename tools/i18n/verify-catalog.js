/**
 * 校验 /i18n/catalog.js 外链字典与页面内联体积。
 * 用法：node tools/i18n/verify-catalog.js [http://localhost:8090]
 */
const base = process.argv[2] || 'http://localhost:8090';

(async () => {
    const en = await fetch(base + '/i18n/catalog.js?lang=en', { headers: { Cookie: 'ilink_lang=en' } });
    const body = await en.text();
    const etag = en.headers.get('etag');
    console.log('catalog(en) status=' + en.status + ' type=' + en.headers.get('content-type') + ' len=' + body.length);
    console.log('  etag=' + etag + ' vary=' + en.headers.get('vary'));
    console.log('  defines=' + /window\.__I18N__ = \{/.test(body) + ' source=' + body.includes('__I18N_SOURCE__'));

    const revalidate = await fetch(base + '/i18n/catalog.js?lang=en', {
        headers: { Cookie: 'ilink_lang=en', 'If-None-Match': etag }
    });
    console.log('revalidate status=' + revalidate.status + ' (expect 304)');

    const zh = await (await fetch(base + '/i18n/catalog.js?lang=zh')).text();
    console.log('catalog(zh) len=' + zh.length + ' differs=' + (zh !== body));

    for (const path of ['/index.html', '/login.html', '/team-market.html', '/community.html', '/profile.html']) {
        const res = await fetch(base + path + '?lang=en');
        const html = await res.text();
        const inlineKeyed = /window\.__I18N__\["/.test(html);
        console.log(path + ' size=' + html.length + ' catalogTag=' + html.includes('/i18n/catalog.js') + ' legacyInline=' + inlineKeyed);
    }
})();
