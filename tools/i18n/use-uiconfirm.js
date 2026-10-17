/**
 * 把脚本里的阻塞式 confirm(...) 换成可翻译的 uiConfirm(...)。
 * uiConfirm 定义在 common.js：先按运行时对照表翻译提示，再调用原生 confirm。
 *
 * 用法：node tools/i18n/use-uiconfirm.js
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const targets = [
    path.join(root, 'src', 'main', 'resources', 'static', 'js'),
    path.join(root, 'src', 'main', 'resources', 'templates')
];

function walk(dirPath) {
    return fs.readdirSync(dirPath, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dirPath, entry.name);
        if (entry.isDirectory()) return walk(full);
        return /\.(js|html)$/.test(entry.name) ? [full] : [];
    });
}

let changed = 0;
targets.flatMap(walk).forEach((file) => {
    const source = fs.readFileSync(file, 'utf8');
    const next = source
        .replace(/(?<![\w.$])window\.confirm\(/g, 'uiConfirm(')
        .replace(/(?<![\w.$])confirm\(/g, 'uiConfirm(');
    if (next === source) return;
    changed += 1;
    fs.writeFileSync(file, next, 'utf8');
    console.log('已更新 ' + path.relative(root, file));
});
console.log('共更新 ' + changed + ' 个文件');
