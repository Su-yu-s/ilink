/**
 * 扫描 Thymeleaf 模板里仍然是中文、且没有走词条键的界面文案。
 * 只报告「文本节点」和 title/placeholder/aria-label/alt 这类可见属性，
 * 含 ${...} / #{...} 的片段会先被去掉，避免把动态表达式当成文案。
 *
 * 用法：node tools/i18n/templates-pending.js
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const dir = path.join(root, 'src', 'main', 'resources', 'templates');

const known = new Set();
['messages.properties', 'messages_en.properties'].forEach((name) => {
    fs.readFileSync(path.join(root, 'src', 'main', 'resources', name), 'utf8').split(/\r?\n/).forEach((line) => {
        const index = line.indexOf('=');
        if (index < 0 || line.startsWith('#')) return;
        known.add(line.slice(index + 1).replace(/\\n/g, '\n').replace(/\\:/g, ':').replace(/\\/g, '').replace(/\s+/g, ' ').trim());
    });
});

const hasChinese = (value) => /[\u3400-\u9fff]/.test(value);
const ATTRIBUTES = ['title', 'placeholder', 'aria-label', 'alt'];

function clean(value) {
    return value
        .replace(/\$\{[^}]*\}/g, ' ')
        .replace(/#\{[^}]*\}/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}

function walk(dirPath) {
    return fs.readdirSync(dirPath, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dirPath, entry.name);
        if (entry.isDirectory()) return walk(full);
        return entry.name.endsWith('.html') ? [full] : [];
    });
}

const report = new Map();
walk(dir).forEach((file) => {
    let source = fs.readFileSync(file, 'utf8');
    // 注释里的中文不是界面文案
    source = source.replace(/<!--[\s\S]*?-->/g, ' ');
    // 内联脚本 / 样式里的中文由 build-candidates + 运行时对照表负责，这里只看静态标记
    source = source.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, ' ')
        .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, ' ');
    const relative = path.relative(dir, file);
    const add = (text) => {
        const value = clean(text);
        if (!value || !hasChinese(value) || known.has(value)) return;
        if (!report.has(relative)) report.set(relative, new Set());
        report.get(relative).add(value);
    };
    // 文本节点
    source.split(/<[^>]*>/).forEach(add);
    // 可见属性
    const attrRe = new RegExp('(?:' + ATTRIBUTES.join('|') + ')="([^"]*)"', 'g');
    let match;
    while ((match = attrRe.exec(source))) add(match[1]);
});

let total = 0;
[...report.entries()].sort().forEach(([file, set]) => {
    const list = [...set].sort();
    total += list.length;
    console.log('== ' + file);
    list.forEach((text) => console.log('   ' + text));
});
process.stderr.write('待处理模板文案 ' + total + ' 条\n');
