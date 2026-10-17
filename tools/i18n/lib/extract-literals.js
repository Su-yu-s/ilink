/**
 * 从脚本源码里提取字符串 / 模板字面量。
 * 关键点：能识别正则字面量（例如 /['"]/g），避免把正则里的引号当成字符串起点。
 */
function extractLiterals(src) {
    const out = [];
    const push = (value) => {
        const text = value.replace(/\s+/g, ' ').trim();
        if (text) out.push({ text });
    };

    let i = 0;
    const n = src.length;
    let prev = '';
    while (i < n) {
        const c = src[i];
        const d = src[i + 1];

        if (c === '/' && d === '/') {
            i += 2;
            while (i < n && src[i] !== '\n') i++;
            continue;
        }
        if (c === '/' && d === '*') {
            i += 2;
            while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++;
            i += 2;
            continue;
        }
        if (c === '/' && !/[)\]\w$]/.test(prev)) {
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
        if (c === '"' || c === "'") {
            const quote = c;
            let buf = '';
            i++;
            while (i < n && src[i] !== quote) {
                if (src[i] === '\\') { buf += src[i] + src[i + 1]; i += 2; continue; }
                buf += src[i];
                i++;
            }
            i++;
            push(buf);
            prev = '"';
            continue;
        }
        if (c === '`') {
            let buf = '';
            i++;
            while (i < n && src[i] !== '`') {
                if (src[i] === '\\') { buf += src[i] + src[i + 1]; i += 2; continue; }
                if (src[i] === '$' && src[i + 1] === '{') {
                    let depth = 1;
                    i += 2;
                    while (i < n && depth > 0) {
                        if (src[i] === '{') depth++;
                        else if (src[i] === '}') depth--;
                        i++;
                    }
                    buf += '{value}';
                    continue;
                }
                buf += src[i];
                i++;
            }
            i++;
            push(buf);
            prev = '"';
            continue;
        }
        if (!/\s/.test(c)) prev = c;
        i++;
    }
    return out;
}

module.exports = { extractLiterals };
