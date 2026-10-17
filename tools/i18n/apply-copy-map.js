/**
 * 把 tools/i18n/ui-copy-en.json 里的「中文原文 → 英文译文」写入两份词条文件：
 *   src/main/resources/messages.properties      中文原文（同时充当运行时对照表的原文侧）
 *   src/main/resources/messages_en.properties   英文译文
 *
 * 这两份 properties 是页面模板直接引用的资源，脚本只维护自己那段带标记的区块，
 * 其余内容原样保留，因此可以反复执行。
 *
 * 用法：node tools/i18n/apply-copy-map.js [--check]
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const mapFile = path.join(__dirname, 'ui-copy-en.json');
const zhFile = path.join(root, 'src', 'main', 'resources', 'messages.properties');
const enFile = path.join(root, 'src', 'main', 'resources', 'messages_en.properties');

const MARK_START = '# >>> ui-copy: 脚本生成，请修改 tools/i18n/ui-copy-en.json 后重新运行 tools/i18n/apply-copy-map.js';
const MARK_END = '# <<< ui-copy';
const hasChinese = (value) => /[\u3400-\u9fff]/.test(value);
const unescapeValue = (value) => value.replace(/\\n/g, '\n');
const escapeValue = (value) => String(value)
    .replace(/\\/g, '\\\\')
    .replace(/\r?\n/g, '\\n')
    // 前导空格在 properties 里会被吃掉，需要转义
    .replace(/^(\s)/, '\\$1');

const raw = fs.readFileSync(mapFile, 'utf8');
const map = JSON.parse(raw);
delete map._comment;

const entries = Object.entries(map);
const problems = [];
entries.forEach(([zh, en]) => {
    if (!hasChinese(zh)) problems.push('原文不含中文：' + zh);
    if (!en || hasChinese(en)) problems.push('译文缺失或仍含中文：' + zh + ' → ' + en);
});

function existingKeys(file) {
    const keys = new Set();
    fs.readFileSync(file, 'utf8').split(/\r?\n/).forEach((line) => {
        const index = line.indexOf('=');
        if (index > 0 && !line.startsWith('#')) keys.add(line.slice(0, index).trim());
    });
    return keys;
}

// 已经收录在其它键下的原文不必再加一份，避免同一句出现两个键（模板词条优先级更高）
function collectExistingValues(file) {
    const source = fs.readFileSync(file, 'utf8');
    const blockStart = source.indexOf(MARK_START);
    // 只看脚本生成区块以外的词条，否则第二次运行时会把上次生成的内容当成「已存在」
    const outside = blockStart >= 0 ? source.slice(0, blockStart) : source;
    const values = new Set();
    outside.split(/\r?\n/).forEach((line) => {
        if (!line || line.startsWith('#')) return;
        const index = line.indexOf('=');
        if (index < 0) return;
        values.add(unescapeValue(line.slice(index + 1)).replace(/\s+/g, ' ').trim());
    });
    return values;
}

function writeBlock(file, lines, kept) {
    const source = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
    const start = source.indexOf(MARK_START);
    const header = start >= 0 ? source.slice(0, start) : source.replace(/\n+$/, '\n');
    const end = source.indexOf(MARK_END, start);
    const tail = start >= 0 && end >= 0 ? source.slice(end + MARK_END.length).replace(/^\n+/, '') : '';
    const body = header.replace(/\n+$/, '\n\n') + MARK_START + '\n'
        + '# 这些词条不直接在模板里引用，而是给脚本动态渲染的界面文案做运行时对照（common.js 的 buildUiCopyMap）。\n'
        + lines.join('\n') + '\n' + MARK_END + '\n';
    const next = tail ? body + '\n' + tail : body;
    if (next === source) {
        console.log('未变化：' + path.relative(root, file));
        return;
    }
    if (process.argv.includes('--check')) {
        console.log('需要更新：' + path.relative(root, file));
        return;
    }
    fs.writeFileSync(file, next, 'utf8');
    console.log('已写入 ' + path.relative(root, file) + '（' + kept + ' 条）');
}

const zhValues = collectExistingValues(zhFile);
// 同一句在别的键下已经翻译过就不再重复收录（例如模板里已经用 data-i18n 处理的文案）
const duplicates = entries.filter(([zh]) => zhValues.has(zh.replace(/\s+/g, ' ').trim()));
const kept = entries.filter(([zh]) => !zhValues.has(zh.replace(/\s+/g, ' ').trim()));

const zhLines = kept.map(([zh], index) => 'ui.copy.' + String(index + 1).padStart(4, '0') + '=' + escapeValue(zh));
const enLines = kept.map(([, en], index) => 'ui.copy.' + String(index + 1).padStart(4, '0') + '=' + escapeValue(en));

const zhKeys = existingKeys(zhFile);
const enKeys = existingKeys(enFile);
const missingEn = [...zhKeys].filter((key) => key.startsWith('ui.copy.') && !enKeys.has(key));
const missingZh = [...enKeys].filter((key) => key.startsWith('ui.copy.') && !zhKeys.has(key));

writeBlock(zhFile, zhLines, kept.length);
writeBlock(enFile, enLines, kept.length);

console.log('对照表条目 ' + entries.length + '，写入 ' + kept.length
    + '，因已有同义词条跳过 ' + duplicates.length);
if (duplicates.length) {
    duplicates.slice(0, 20).forEach(([zh]) => console.log('  跳过（已存在）：' + zh.slice(0, 40)));
}
if (missingEn.length || missingZh.length) {
    console.log('键位不一致：缺英文 ' + missingEn.length + ' / 缺中文 ' + missingZh.length);
}
if (problems.length) {
    console.log('需要注意 ' + problems.length + ' 条：');
    problems.forEach((line) => console.log('  ' + line));
}
