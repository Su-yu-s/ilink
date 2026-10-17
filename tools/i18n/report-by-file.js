/**
 * 按源文件分组打印候选文案，便于按模块批量翻译。
 * 用法：node tools/i18n/report-by-file.js [fileFilter] [skip] [take]
 */
const fs = require('fs');
const path = require('path');
const data = JSON.parse(fs.readFileSync(path.join(process.env.TEMP, 'ilink-i18n', 'candidates.json'), 'utf8'));
const filter = process.argv[2] || '';
const skip = Number(process.argv[3] || 0);
const take = Number(process.argv[4] || 100000);

const rows = [];
Object.entries(data.files)
    .filter(([name]) => name.includes(filter))
    .forEach(([name, list]) => list.forEach((text) => rows.push({ name, text })));

rows.slice(skip, skip + take).forEach((row, index) => {
    console.log((skip + index) + '\t' + row.name + '\t' + row.text.replace(/\n/g, '\\n'));
});
process.stderr.write('rows=' + rows.length + '\n');
