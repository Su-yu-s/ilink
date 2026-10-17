/**
 * 打印候选文案清单（含可疑残留标记），供人工翻译时逐条核对。
 * 用法：node tools/i18n/report-candidates.js [--suspect]
 */
const fs = require('fs');
const path = require('path');
const file = process.argv.find((a) => a.endsWith('.json')) || path.join(process.env.TEMP, 'ilink-i18n', 'candidates.json');
const data = JSON.parse(fs.readFileSync(file, 'utf8'));
const suspectOnly = process.argv.includes('--suspect');
const suspicious = /class=|style=|loading=|<[a-z]|="|">|\son[a-z]+=/;
const list = suspectOnly ? data.entries.filter((e) => suspicious.test(e.text)) : data.entries;
list.forEach((entry) => console.log(entry.text.replace(/\n/g, '\\n') + '\t<<' + entry.files.join(',') + '>>'));
process.stderr.write('listed=' + list.length + ' total=' + data.entries.length + '\n');
