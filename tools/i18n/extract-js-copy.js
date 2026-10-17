/**
 * 扫描 static/js 下的所有脚本，提取仍然是中文字面量的界面文案。
 *
 * 与早期脚本的差别：这里能正确跳过正则字面量（例如 /['"]/g），
 * 不会把正则里的引号误判成字符串起点，因此不会产出「代码汤」条目。
 *
 * 用法：node tools/i18n/extract-js-copy.js > out.json
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const dir = path.join(root, 'src', 'main', 'resources', 'static', 'js');

const hasChinese = (value) => /[\u3400-\u9fff]/.test(value);

function extractLiterals(src) {
    const out = [];
    const push = (value, kind) => {
        const text = value.replace(/\s+/g, ' ').trim();
        if (!text || !hasChinese(text)) return;
        out.push({ text, kind });
    };

    let i = 0;
    const n = src.length;
    // prev 用于判断 '/' 是除法还是正则起点：能直接跟正则的只有运算符/关键字/行首
    let prev = '';
    while (i < n) {
        const c = src[i];
        const d = src[i + 1];

        if (c === '/' && d === '/') { // 行注释
            i += 2;
            while (i < n && src[i] !== '\n') i++;
            continue;
        }
        if (c === '/' && d === '*') { // 块注释
            i += 2;
            while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++;
            i += 2;
            continue;
        }
        if (c === '/' && !/[)\]\w$]/.test(prev)) { // 正则字面量
            let inClass = false;
            i++;
            while (i < n) {
                const ch = src[i];
                if (ch === '\\') { i += 2; continue; }
                if (ch === '[') inClass = true;
                else if (ch === ']') inClass = false;
                else if (ch === '/' && !inClass) { i++; break; }
                else if (ch === '\n') break;
                i++;
            }
            while (i < n && /[a-z]/i.test(src[i])) i++;
            prev = '/';
            continue;
        }
        if (c === '"' || c === "'") { // 普通字符串
            const quote = c;
            let buf = '';
            i++;
            while (i < n && src[i] !== quote) {
                if (src[i] === '\\') { buf += src[i] + src[i + 1]; i += 2; continue; }
                buf += src[i];
                i++;
            }
            i++;
            push(buf, quote === "'" ? 'single' : 'double');
            prev = '"';
            continue;
        }
        if (c === '`') { // 模板字符串：按 ${} 分段，动态段记成 {value}
            let buf = '';
            i++;
            while (i < n && src[i] !== '`') {
                if (src[i] === '\\') { buf += src[i] + src[i + 1]; i += 2; continue; }
                if (src[i] === '$' && src[i + 1] === '{') {
                    let depth = 1;
                    i += 2;
                    let inner = '';
                    while (i < n && depth > 0) {
                        if (src[i] === '{') depth++;
                        else if (src[i] === '}') depth--;
                        if (depth > 0) inner += src[i];
                        i++;
                    }
                    buf += '{value}';
                    continue;
                }
                buf += src[i];
                i++;
            }
            i++;
            push(buf, 'template');
            prev = '"';
            continue;
        }
        if (!/\s/.test(c)) prev = c;
        i++;
    }
    return out;
}

const perFile = {};
const all = new Map();
for (const name of fs.readdirSync(dir).sort()) {
    if (!name.endsWith('.js')) continue;
    const literals = extractLiterals(fs.readFileSync(path.join(dir, name), 'utf8'));
    const uniq = [...new Set(literals.map((l) => l.text))];
    if (!uniq.length) continue;
    perFile[name] = uniq;
    uniq.forEach((text) => {
        if (!all.has(text)) all.set(text, []);
        all.get(text).push(name);
    });
}

const payload = {
    generatedAt: new Date().toISOString(),
    files: perFile,
    unique: [...all.entries()].map(([text, files]) => ({ text, files })).sort((a, b) => a.text.localeCompare(b.text, 'zh'))
};
process.stdout.write(JSON.stringify(payload, null, 1));
process.stderr.write('files=' + Object.keys(perFile).length + ' occurrences=' + Object.values(perFile).reduce((s, a) => s + a.length, 0) + ' unique=' + payload.unique.length + '\n');
