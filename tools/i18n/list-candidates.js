/**
 * 从 js-copy.json 里筛掉代码片段/选择器/URL/专有名称，输出「像界面文案」的中文字面量清单。
 * 用法：node tools/i18n/list-candidates.js [--all]
 */
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..', '..');
const input = process.argv[2] || path.join(process.env.TEMP, 'ilink-i18n', 'js-copy.json');
const payload = JSON.parse(fs.readFileSync(input, 'utf8'));

const known = new Set();
fs.readFileSync(path.join(root, 'src', 'main', 'resources', 'messages.properties'), 'utf8')
    .split(/\r?\n/)
    .forEach((line) => {
        if (!line || line.startsWith('#')) return;
        const index = line.indexOf('=');
        if (index < 0) return;
        known.add(line.slice(index + 1).replace(/\\n/g, '\n').replace(/\s+/g, ' ').trim());
    });

const reasons = {
    selector: (t) => /^[.#\[]/.test(t) || /^[a-z-]+\[[^\]]*\]/.test(t),
    code: (t) => /[{};]|=>|\bfunction\b|\breturn\b|<\/?[a-z]/.test(t),
    url: (t) => /^(https?:)?\/\//.test(t) || /\.(js|css|html|png|jpg|svg)\b/.test(t),
    fragment: (t) => t.length < 2 || /^[，。、；：？！）」』】·]+$/.test(t)
};

const classified = [];
const blocked = new Set();
payload.unique.forEach((entry) => {
    const text = entry.text;
    if (known.has(text)) { blocked.add('known'); entry.verdict = 'known'; return; }
    for (const [name, test] of Object.entries(reasons)) {
        if (test(text)) { entry.verdict = name; blocked.add(name); return; }
    }
    entry.verdict = 'candidate';
    classified.push(entry);
});

if (process.argv.includes('--all')) {
    payload.unique.forEach((e) => console.log('[' + e.verdict + '] ' + e.text.replace(/\n/g, ' ') + '   <<' + e.files.join(',') + '>>'));
} else {
    classified.forEach((e) => console.log(e.text.replace(/\n/g, ' ') + '\t<<' + e.files.join(',') + '>>'));
}
process.stderr.write('candidates=' + classified.length + ' of ' + payload.unique.length
    + ' (skipped: ' + [...blocked].map((k) => k + '=' + payload.unique.filter((e) => e.verdict === k).length).join(', ') + ')\n');
