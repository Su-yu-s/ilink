/** 检查 ui-copy-en.json 的重复键与格式问题（JSON.parse 会静默保留最后一个同名键）。 */
const fs = require('fs');
const path = require('path');
const file = path.join(__dirname, 'ui-copy-en.json');
const raw = fs.readFileSync(file, 'utf8');
const seen = new Map();
const duplicates = [];
const keyRe = /^\s{2}"((?:[^"\\]|\\.)*)":/gm;
let match;
while ((match = keyRe.exec(raw))) {
    const key = match[1];
    if (key === '_comment') continue;
    if (seen.has(key)) duplicates.push(key);
    seen.set(key, true);
}
const data = JSON.parse(raw);
const entries = Object.entries(data).filter(([key]) => key !== '_comment');
const noChinese = entries.filter(([zh]) => !/[\u3400-\u9fff]/.test(zh));
const badEnglish = entries.filter(([, en]) => !en || /[\u3400-\u9fff]/.test(en));
const placeholderMismatch = entries.filter(([zh, en]) => {
    const count = (value) => (value.match(/\{value\}/g) || []).length;
    return count(zh) !== count(en);
});
console.log('entries=' + entries.length + ' duplicates=' + duplicates.length);
duplicates.forEach((key) => console.log('  重复键: ' + key));
noChinese.forEach(([zh]) => console.log('  原文无中文: ' + zh));
badEnglish.forEach(([zh, en]) => console.log('  译文异常: ' + zh + ' → ' + en));
placeholderMismatch.forEach(([zh, en]) => console.log('  占位符不一致: ' + zh + ' → ' + en));
