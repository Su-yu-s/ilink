/**
 * 在对照表里按子串搜索词条（源文或译文命中都算）。
 * 用法：node tools/i18n/find-copy.js "共" "邀请"
 */
const fs = require('fs');
const path = require('path');

const map = JSON.parse(fs.readFileSync(path.resolve(__dirname, 'ui-copy-en.json'), 'utf8'));
const needles = process.argv.slice(2);
let hits = 0;
Object.keys(map).forEach((key) => {
    if (key.startsWith('_')) return;
    const value = map[key];
    if (needles.some((n) => key.includes(n) || String(value).includes(n))) {
        hits += 1;
        console.log(JSON.stringify(key) + '  ->  ' + JSON.stringify(value));
    }
});
process.stderr.write('hits=' + hits + '\n');
