/**
 * 列出还没有英文译文的候选文案（对照 tools/i18n/ui-copy-en.json）。
 * 用法：node tools/i18n/pending-copy.js [文件名过滤]
 */
const fs = require('fs');
const path = require('path');
const candidates = JSON.parse(fs.readFileSync(path.join(process.env.TEMP, 'ilink-i18n', 'candidates.json'), 'utf8'));
const map = JSON.parse(fs.readFileSync(path.join(__dirname, 'ui-copy-en.json'), 'utf8'));
delete map._comment;

const filter = process.argv[2] || '';
const pending = [];
Object.entries(candidates.files)
    .filter(([name]) => name.includes(filter))
    .forEach(([name, list]) => list.forEach((text) => {
        if (!map[text]) pending.push(name + '\t' + text.replace(/\n/g, '\\n'));
    }));

pending.forEach((line) => console.log(line));
process.stderr.write('pending=' + pending.length + '\n');
