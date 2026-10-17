/**
 * 检查一批中文串能否被运行时对照表翻译。
 * 对照表来源与 common.js 的 buildUiCopyMap 一致：messages.properties（原文）与
 * messages_en.properties（译文）按同一个键配对，外加 ui-copy-en.json 里的脚本文案。
 * 用法：node tools/i18n/has-copy.js "串1" "串2" ...
 * 或者：node tools/i18n/has-copy.js @file.txt（每行一条）
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const sourceMap = new Map();

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

const zh = readProperties(path.join(root, 'src', 'main', 'resources', 'messages.properties'));
const en = readProperties(path.join(root, 'src', 'main', 'resources', 'messages_en.properties'));
zh.forEach((value, key) => {
    const translated = en.get(key);
    if (value && translated && value !== translated && !/[\u3400-\u9fff]/.test(translated) && !sourceMap.has(value)) {
        sourceMap.set(value, translated + '  [' + key + ']');
    }
});

const json = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-copy-en.json'), 'utf8'));
Object.keys(json).forEach((key) => {
    if (key.startsWith('_')) return;
    if (!sourceMap.has(key)) sourceMap.set(key, json[key] + '  [json]');
});

const args = process.argv.slice(2);
const list = args[0] && args[0].startsWith('@')
    ? fs.readFileSync(args[0].slice(1), 'utf8').split(/\r?\n/).filter(Boolean)
    : args;

list.forEach((item) => {
    const hit = sourceMap.has(item);
    console.log((hit ? 'OK   ' : 'MISS ') + JSON.stringify(item) + (hit ? ' -> ' + JSON.stringify(sourceMap.get(item)) : ''));
});
