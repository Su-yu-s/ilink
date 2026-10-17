/**
 * ui-copy-en.json 里同一句原文只应出现一次。手工分批追加时容易写重，
 * 这里保留第一次出现的译文，值不一致时给出提示，方便修完再跑一遍。
 * 用法：node tools/i18n/dedupe-copy-map.js
 */
const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, 'ui-copy-en.json');
const lines = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n').split('\n');
const seen = new Map();
const output = [];
const removed = [];
let header = null;

for (const line of lines) {
    const match = /^\s{2}"((?:[^"\\]|\\.)*)":\s*"(.*)",?$/.exec(line);
    if (!match) {
        if (line.includes('"_comment"')) header = line;
        output.push(line);
        continue;
    }
    const [, key, value] = match;
    if (seen.has(key)) {
        if (seen.get(key) !== value) {
            console.log('值不一致：' + key + '\n  保留: ' + seen.get(key) + '\n  丢弃: ' + value);
        } else {
            removed.push(key);
        }
        continue;
    }
    seen.set(key, value);
    output.push(line);
}

// 保持最后一个条目不带尾逗号
for (let i = output.length - 2; i >= 0; i--) {
    if (/^\s{2}"/.test(output[i])) {
        output[i] = output[i].replace(/,$/, '');
        break;
    }
}

if (removed.length) {
    fs.writeFileSync(file, output.join('\n'), 'utf8');
}
console.log('保留 ' + seen.size + ' 条，删除重复 ' + removed.length + ' 条'
    + (header ? '' : '（注意：_comment 行缺失）'));
