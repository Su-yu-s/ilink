/**
 * 把 static/js 里的中文字面量拆成「真正会显示给用户的字符串」：
 *   · 纯文案字面量 → 直接作为候选
 *   · 含标签的 HTML 片段 → 拆出属性值（title/aria-label/placeholder/alt）与标签之间的文本
 *   · ${...} 动态段统一记成 {value}
 * 并过滤掉选择器、代码片段、URL 与已收录词条。
 *
 * 用法：node tools/i18n/build-candidates.js [输出 json]
 */
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..', '..');
const out = process.argv[2] || path.join(process.env.TEMP, 'ilink-i18n', 'candidates.json');

const { extractLiterals } = require('./lib/extract-literals');

const hasChinese = (value) => /[\u3400-\u9fff]/.test(value);
const ATTRIBUTES = ['title', 'aria-label', 'placeholder', 'alt'];

const known = new Set();
fs.readFileSync(path.join(root, 'src', 'main', 'resources', 'messages.properties'), 'utf8')
    .split(/\r?\n/)
    .forEach((line) => {
        if (!line || line.startsWith('#')) return;
        const index = line.indexOf('=');
        if (index < 0) return;
        known.add(line.slice(index + 1).replace(/\\n/g, '\n').replace(/\\"/g, '"').replace(/\s+/g, ' ').trim());
    });

const isCode = (text) => /[{};]|=>|\bfunction\b|\breturn\b|^\s*[.#\[]/.test(text)
    || /^(https?:)?\/\//.test(text)
    || /\b(var|const|let|this\.)\b/.test(text)
    || !hasChinese(text);

function collect(literal, bucket) {
    const raw = literal.text;
    const isMarkup = /<[a-z!/]/i.test(raw) || raw.includes('">') || /="[^"]*$/.test(raw);
    const attrRe = new RegExp('(?:' + ATTRIBUTES.join('|') + ')="([^"]*)"', 'g');
    let attrMatch;
    while ((attrMatch = attrRe.exec(raw))) {
        bucket.add(attrMatch[1]);
    }
    // 片段在 ${} 处被切断时属性没有闭合引号，这里补一次尾部匹配
    const openAttr = new RegExp('(?:' + ATTRIBUTES.join('|') + ')="([^"]*)$').exec(raw);
    if (openAttr) bucket.add(openAttr[1]);
    if (!isMarkup) {
        bucket.add(raw);
        return;
    }
    // 按标签与 "> 切断，剩下的片段才是真正会渲染出来的文本
    raw.split(/<[^>]*>|">/).forEach((piece) => {
        if (/[="<]/.test(piece)) return;
        bucket.add(piece);
    });
}

const perFile = {};
const all = new Map();

/** 模板内联脚本同样会渲染界面文案，这里按同一个流程抽取 */
function inlineScripts(html) {
    const blocks = [];
    const re = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
    let match;
    while ((match = re.exec(html))) {
        if (/th:inline|type="text\/javascript"/.test(match[0]) || match[1].trim()) blocks.push(match[1]);
    }
    return blocks;
}

const sources = [];
const jsDir = path.join(root, 'src', 'main', 'resources', 'static', 'js');
fs.readdirSync(jsDir).sort().forEach((name) => {
    if (!name.endsWith('.js')) return;
    sources.push({ name, source: fs.readFileSync(path.join(jsDir, name), 'utf8') });
});

const templateDir = path.join(root, 'src', 'main', 'resources', 'templates');
function walkTemplates(dirPath) {
    return fs.readdirSync(dirPath, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dirPath, entry.name);
        if (entry.isDirectory()) return walkTemplates(full);
        return entry.name.endsWith('.html') ? [full] : [];
    });
}
walkTemplates(templateDir).forEach((file) => {
    const html = fs.readFileSync(file, 'utf8');
    const blocks = inlineScripts(html);
    if (!blocks.length) return;
    sources.push({ name: 'templates/' + path.relative(templateDir, file).split(path.sep).join('/'), source: blocks.join('\n;\n') });
});

for (const { name, source } of sources) {
    const bucket = new Set();
    extractLiterals(source).forEach((literal) => collect(literal, bucket));
    const keep = [...bucket]
        .map((text) => text.replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ').trim())
        .filter((text) => text && !isCode(text) && !known.has(text));
    const uniq = [...new Set(keep)];
    if (!uniq.length) continue;
    perFile[name] = uniq;
    uniq.forEach((text) => {
        if (!all.has(text)) all.set(text, new Set());
        all.get(text).add(name);
    });
}

const entries = [...all.entries()]
    .map(([text, files]) => ({ text, files: [...files] }))
    .sort((a, b) => b.text.length - a.text.length || a.text.localeCompare(b.text, 'zh'));

fs.writeFileSync(out, JSON.stringify({ files: perFile, entries }, null, 1), 'utf8');
process.stderr.write('files=' + Object.keys(perFile).length + ' occurrences='
    + Object.values(perFile).reduce((sum, list) => sum + list.length, 0)
    + ' unique=' + entries.length + ' → ' + out + '\n');
