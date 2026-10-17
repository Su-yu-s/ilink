/**
 * 用运行时对照表（messages.properties ↔ messages_en.properties 的同键配对）
 * 试翻译 build-candidates 提取出来的脚本字面量，列出翻不出来的。
 *
 * 用法：node tools/i18n/build-candidates.js && node tools/i18n/check-js-literals.js
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const candidatesFile = path.join(process.env.TEMP || '.', 'ilink-i18n', 'candidates.json');

function readProperties(file) {
    const pairs = new Map();
    fs.readFileSync(file, 'utf8').split(/\r?\n/).forEach((line) => {
        if (!line || line.startsWith('#')) return;
        const index = line.indexOf('=');
        if (index <= 0) return;
        pairs.set(line.slice(0, index).trim(),
            line.slice(index + 1).replace(/\\n/g, '\n').replace(/\\:/g, ':').replace(/\\\\/g, '\\').replace(/\s+/g, ' ').trim());
    });
    return pairs;
}

const normalize = (value) => String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
const zh = readProperties(path.join(root, 'src', 'main', 'resources', 'messages.properties'));
const en = readProperties(path.join(root, 'src', 'main', 'resources', 'messages_en.properties'));

const exact = new Map();
const patterns = [];
zh.forEach((value, key) => {
    const original = normalize(value);
    const translated = normalize(en.get(key));
    if (!original || !translated || original === translated || /[\u3400-\u9fff]/.test(translated)) return;
    if (/\{[^{}]+\}/.test(original)) {
        let expression = '^';
        let lastIndex = 0;
        const names = [];
        original.replace(/\{([^{}]+)\}/g, (placeholder, name, offset) => {
            expression += original.slice(lastIndex, offset).replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '(.+?)';
            names.push(name);
            lastIndex = offset + placeholder.length;
            return placeholder;
        });
        expression += original.slice(lastIndex).replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '$';
        patterns.push({ source: original, expression: new RegExp(expression) });
    } else if (!exact.has(original)) {
        exact.set(original, translated);
    }
});
patterns.sort((a, b) => b.source.length - a.source.length);

function translate(value) {
    const text = normalize(value);
    if (exact.has(text)) return exact.get(text);
    for (const pattern of patterns) if (pattern.expression.test(text)) return '(pattern) ' + pattern.source;
    return null;
}

const candidates = JSON.parse(fs.readFileSync(candidatesFile, 'utf8'));
const rows = [];
Object.keys(candidates).forEach((file) => {
    const items = candidates[file];
    (Array.isArray(items) ? items : Object.keys(items)).forEach((item) => {
        const text = typeof item === 'string' ? item : item.text;
        if (!text || !/[\u3400-\u9fff]/.test(text)) return;
        if (translate(text)) return;
        rows.push(path.relative(root, file) + ' :: ' + normalize(text));
    });
});
rows.sort();
rows.forEach((row) => console.log(row));
process.stderr.write('翻不出来的脚本字面量 ' + rows.length + ' 条\n');
