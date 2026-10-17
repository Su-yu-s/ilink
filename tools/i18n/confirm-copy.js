/**
 * 列出 JS / 模板内联脚本里的 confirm(...) 字面量，并检查是否已在对照表里。
 * 用法：node tools/i18n/confirm-copy.js
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const dirs = [path.join(root, 'src', 'main', 'resources', 'static', 'js'),
    path.join(root, 'src', 'main', 'resources', 'templates')];
const map = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-copy-en.json'), 'utf8'));

function walk(dirPath) {
    return fs.readdirSync(dirPath, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dirPath, entry.name);
        if (entry.isDirectory()) return walk(full);
        return /\.(js|html)$/.test(entry.name) ? [full] : [];
    });
}

const found = new Map();
dirs.flatMap(walk).forEach((file) => {
    const source = fs.readFileSync(file, 'utf8');
    const re = /(?:\w+\.)?confirm\(([^)]{0,300}?)\)/g;
    let match;
    while ((match = re.exec(source))) {
        const argument = match[1].trim();
        if (!/[\u3400-\u9fff]/.test(argument)) continue;
        const key = path.relative(root, file) + ' :: ' + argument;
        if (!found.has(key)) found.set(key, argument);
    }
});

let missing = 0;
[...found.entries()].sort().forEach(([where, argument]) => {
    // 单引号字面量直接查表；模板串里的 ${...} 用 {value} 占位再查
    const literal = argument.replace(/^['`]|['`]$/g, '').trim();
    const exact = map[literal];
    const pattern = map[literal.replace(/\$\{[^}]*\}/g, '{value}')];
    const hit = exact || pattern;
    if (!hit) missing += 1;
    console.log((hit ? 'OK   ' : 'MISS ') + where);
    if (!hit) console.log('       -> ' + literal);
});
process.stderr.write('confirm 总数 ' + found.size + '，缺译文 ' + missing + '\n');
