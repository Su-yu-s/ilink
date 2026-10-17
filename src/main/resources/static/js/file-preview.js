// 文件预览渲染器：团队资料库与任务批阅工作台共用。
// file 节点字段：{ name, fileType(img/pdf/md/text/word/excel/ppt/zip/other), fileSize, fileUrl }
// 渲染策略：图片、PDF、文本、Markdown 原生渲染；Word(.docx) 用 mammoth、Excel 用 SheetJS
// 在浏览器内转换；老式 .doc/.ppt、压缩包、安装包降级为「元信息 + 下载」卡片。
window.ILinkPreview = (function () {

    var TYPE_LABELS = {
        pdf: 'PDF', word: 'Word 文档', excel: 'Excel 表格', ppt: 'PPT 演示',
        img: '图片', zip: '压缩包', md: 'Markdown', text: '文本', other: '文件'
    };

    // 预览组件依赖：优先用站点内置副本（同源、无 DNS/TLS 往返、可长期缓存），
    // 缺失时回落 CDN（CSP 已放行 cdn.jsdelivr.net）。库仅预览时加载，不拖慢首屏。
    var LIB_BASE = '/lib/preview/';
    var LIB_SOURCES = {
        mammoth: [LIB_BASE + 'mammoth.browser.min.js', 'https://cdn.jsdelivr.net/npm/mammoth@1.8.0/mammoth.browser.min.js'],
        xlsx: [LIB_BASE + 'xlsx.full.min.js', 'https://cdn.jsdelivr.net/npm/xlsx@0.18.5/dist/xlsx.full.min.js'],
        dompurify: [LIB_BASE + 'purify.min.js', 'https://cdn.jsdelivr.net/npm/dompurify@3.1.6/dist/purify.min.js']
    };
    var LIB_TIMEOUT_MS = 8000;
    // 浏览器内转换大文件很吃内存，超过这个体积直接给下载卡片
    var INLINE_RENDER_LIMIT = 10 * 1024 * 1024;
    // 画布内联渲染的行/列上限：超大表格全量拼 HTML 会卡住主线程，
    // 超出部分提示下载查看（数据本身仍在原文件里，不丢内容）。
    var MAX_RENDER_ROWS = 400;
    var MAX_RENDER_COLS = 60;
    // 已渲染结果缓存：同一文件再次点开秒开。只存"体积可控"的结果，避免内存膨胀。
    var CACHE_MAX_ENTRIES = 24;
    var CACHE_MAX_BYTES = 2 * 1024 * 1024;
    var htmlCache = new Map();
    var cacheBytes = 0;

    var scriptCache = {};

    function libSources(name) {
        var urls = LIB_SOURCES[name] || [];
        return Object.prototype.toString.call(urls) === '[object Array]' ? urls : [urls];
    }

    function pickFromCache(key) {
        if (!key || !htmlCache.has(key)) return null;
        var entry = htmlCache.get(key);
        // 命中即刷新 LRU 顺序
        htmlCache.delete(key);
        htmlCache.set(key, entry);
        return entry.html;
    }

    function putToCache(key, html) {
        if (!key || typeof html !== 'string') return;
        var size = html.length * 2;
        if (size > CACHE_MAX_BYTES) return;
        if (htmlCache.has(key)) {
            cacheBytes -= htmlCache.get(key).size;
            htmlCache.delete(key);
        }
        htmlCache.set(key, { html: html, size: size });
        cacheBytes += size;
        while (htmlCache.size > CACHE_MAX_ENTRIES || cacheBytes > CACHE_MAX_BYTES * 4) {
            var oldestKey = htmlCache.keys().next().value;
            if (oldestKey === undefined) break;
            cacheBytes -= htmlCache.get(oldestKey).size;
            htmlCache.delete(oldestKey);
        }
    }

    function perfMark(step, ms) {
        if (!window.__ILinkPreviewPerf) window.__ILinkPreviewPerf = [];
        window.__ILinkPreviewPerf.push({ step: step, ms: Math.round(ms) });
        if (window.__ILinkPreviewPerf.length > 60) window.__ILinkPreviewPerf.shift();
    }

    function timed(step, fn) {
        var t0 = (window.performance && performance.now) ? performance.now() : Date.now();
        return Promise.resolve(fn()).then(function (value) {
            perfMark(step, ((window.performance && performance.now) ? performance.now() : Date.now()) - t0);
            return value;
        });
    }

    // 图标按类型分形状（pdf/word 靠颜色区分，其余形状本身可辨），颜色由 CSS 按 ilpv-i--* 给
    var FILE_GLYPH = '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/>';
    var ICONS = {
        folder: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>',
        folderOpen: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 14l1.45-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.55 6a2 2 0 0 1-1.94 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.93a2 2 0 0 1 1.66.9l.82 1.2a2 2 0 0 0 1.66.9H18a2 2 0 0 1 2 2v2"/></svg>',
        chevronRight: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>',
        doc: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' + FILE_GLYPH + '</svg>',
        image: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg>',
        archive: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 8v13H3V8M1 3h22v5H1zM10 12h4"/></svg>',
        code: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 18l6-6-6-6M8 6l-6 6 6 6"/></svg>',
        text: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h16M4 12h16M4 18h10"/></svg>',
        grid: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M3 15h18M9 4v16M15 4v16"/></svg>',
        slides: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="20" height="12" rx="2"/><path d="M12 16v4M8 20h8"/></svg>',
        download: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5M12 15V3"/></svg>'
    };

    /** 带类型 class 的图标，供 CSS 上色：<svg class="ilpv-i ilpv-i--pdf"> */
    function iconWithType(name, type) {
        return ICONS[name].replace('<svg ', '<svg class="ilpv-i ilpv-i--' + escapeHtml(type) + '" ');
    }

    function escapeHtml(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function extOf(name) {
        var i = String(name == null ? '' : name).lastIndexOf('.');
        return i < 0 ? '' : String(name).slice(i + 1).toLowerCase();
    }

    /** 与服务端 TeamLibraryService 的文本类扩展名保持一致 */
    var TEXT_EXT = ['txt', 'csv', 'json', 'xml', 'yml', 'yaml', 'log', 'sql', 'ini', 'conf', 'properties',
        'html', 'htm', 'css', 'js', 'ts', 'java', 'py', 'c', 'cpp', 'h', 'sh', 'bat', 'cmd'];

    /** 统一解析文件类型：老数据 file_type=doc 时用扩展名兜底（服务端新版本已细分 word/excel/ppt/text） */
    function resolveType(type, name) {
        var ext = extOf(name);
        if (type === 'img' || type === 'zip' || type === 'pdf') return type;
        if (ext === 'md' || ext === 'markdown') return 'md';
        if (ext === 'pdf') return 'pdf';
        if (ext === 'xlsx' || ext === 'xls') return 'excel';
        if (ext === 'pptx' || ext === 'ppt') return 'ppt';
        if (ext === 'docx' || ext === 'doc') return 'word';
        if (['zip', 'rar', '7z', 'tar', 'gz'].indexOf(ext) >= 0) return 'zip';
        if (TEXT_EXT.indexOf(ext) >= 0) return 'text';
        return type || 'other';
    }

    /** 按类型取图标（形状区分，颜色由 CSS 的 ilpv-i--* 给） */
    function fileIcon(type, name) {
        var resolved = resolveType(type, name);
        if (resolved === 'img') return iconWithType('image', 'img');
        if (resolved === 'zip') return iconWithType('archive', 'zip');
        if (resolved === 'md') return iconWithType('code', 'md');
        if (resolved === 'excel') return iconWithType('grid', 'excel');
        if (resolved === 'ppt') return iconWithType('slides', 'ppt');
        if (resolved === 'word') return iconWithType('doc', 'word');
        if (resolved === 'pdf') return iconWithType('doc', 'pdf');
        if (resolved === 'text') return iconWithType('text', 'text');
        return iconWithType('doc', 'other');
    }

    function typeLabel(type, name) {
        return TYPE_LABELS[resolveType(type, name)] || TYPE_LABELS.other;
    }

    function formatSize(bytes) {
        if (bytes == null) return '';
        if (window.ILinkFiles && window.ILinkFiles.formatSize) return window.ILinkFiles.formatSize(bytes);
        return bytes + ' B';
    }

    function injectScript(url, globalName, timeoutMs) {
        return new Promise(function (resolve, reject) {
            var el = document.createElement('script');
            var settled = false;
            var timer = setTimeout(function () {
                if (settled) return;
                settled = true;
                reject(new Error('预览组件加载超时'));
            }, timeoutMs);
            function done(ok, err) {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                if (ok) resolve(window[globalName]);
                else reject(err);
            }
            el.src = url;
            el.async = true;
            el.onload = function () {
                if (window[globalName]) done(true);
                else done(false, new Error(globalName + ' 未就绪'));
            };
            el.onerror = function () { done(false, new Error('预览组件加载失败')); };
            document.head.appendChild(el);
        });
    }

    /**
     * 加载一个预览库：按候选顺序（内置副本 → CDN）尝试，成功后缓存 Promise。
     * 内置副本在同源，省掉 CDN 的 DNS+TLS+跨域往返，是预览提速的主要来源。
     */
    function loadLib(name) {
        var globalName = { mammoth: 'mammoth', xlsx: 'XLSX', dompurify: 'DOMPurify' }[name];
        if (globalName && window[globalName]) return Promise.resolve(window[globalName]);
        if (scriptCache[name]) return scriptCache[name];
        var urls = libSources(name);
        var t0 = (window.performance && performance.now) ? performance.now() : Date.now();
        var chain = Promise.reject(new Error('init'));
        urls.forEach(function (url, idx) {
            chain = chain.catch(function () {
                return injectScript(url, globalName, LIB_TIMEOUT_MS).then(function (lib) {
                    var now = (window.performance && performance.now) ? performance.now() : Date.now();
                    perfMark('lib:' + name + (idx === 0 ? '(local)' : '(cdn)'), now - t0);
                    return lib;
                });
            });
        });
        chain = chain.catch(function (err) {
            delete scriptCache[name];
            throw err;
        });
        scriptCache[name] = chain;
        return chain;
    }

    /**
     * 空闲时间预取预览依赖：用户在资料库/任务里点开文件前，把 861KB 的解析库先拉好，
     * 点击后只剩文件本体读取 + 解析。每页只做一次，不阻塞交互。
     */
    var prefetched = false;
    function prefetchLibs() {
        if (prefetched) return;
        prefetched = true;
        var run = function () { loadLib('xlsx').catch(function () {}); loadLib('dompurify').catch(function () {}); };
        if (window.requestIdleCallback) {
            window.requestIdleCallback(run, { timeout: 2500 });
        } else {
            setTimeout(run, 800);
        }
    }

    function fetchBinary(url) {
        return fetch(url, { credentials: 'same-origin' }).then(function (r) {
            if (!r.ok) throw new Error('文件读取失败');
            return r.arrayBuffer();
        });
    }

    /** mammoth / SheetJS 生成的 HTML 统一过一遍 DOMPurify；净化器加载不到就整体降级 */
    function sanitized(html) {
        return loadLib('dompurify').then(function (dp) {
            return dp.sanitize(html, { ADD_ATTR: ['target'] });
        });
    }

    function fallbackBlock(file) {
        var hint = file.fileUrl
            ? '<a class="ilpv-download" href="' + escapeHtml(file.fileUrl) + '" download="' + escapeHtml(file.name) + '">' + ICONS.download + '下载查看</a>'
            : '<span class="ilpv-hinttext">文件加载中…</span>';
        return '<div class="ilpv-fallback">' +
            '<div class="ilpv-fallback__icon">' + fileIcon(file.fileType, file.name) + '</div>' +
            '<div class="ilpv-fallback__name">' + escapeHtml(file.name) + '</div>' +
            '<div class="ilpv-fallback__meta">' + typeLabel(file.fileType, file.name) +
            (file.fileSize ? ' · ' + formatSize(file.fileSize) : '') + '</div>' +
            hint + '</div>';
    }

    /**
     * 轻量 Markdown：标题/列表/表格/围栏代码/分割线/行内样式。
     * 先转义再拼标签，防注入；链接只放行 http(s)/mailto/#/相对路径。
     */
    function renderMarkdown(text) {
        var lines = String(text || '').split(/\r?\n/);
        var html = '';
        var listOpen = false;
        function closeList() { if (listOpen) { html += '</ul>'; listOpen = false; } }
        function inline(s) {
            return escapeHtml(s)
                .replace(/`([^`]+)`/g, '<code>$1</code>')
                .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
                .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (m, label, href) {
                    return /^(https?:|mailto:|#|\/)/i.test(href)
                        ? '<a href="' + href + '" target="_blank" rel="noopener noreferrer">' + label + '</a>'
                        : label;
                });
        }
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            // 围栏代码块：整段原样转义，不再走行内解析
            if (/^\s*```/.test(line)) {
                closeList();
                var code = [];
                i++;
                while (i < lines.length && !/^\s*```/.test(lines[i])) { code.push(lines[i]); i++; }
                html += '<pre class="ilpv-code"><code>' + escapeHtml(code.join('\n')) + '</code></pre>';
                continue;
            }
            // 表格：本行含 |，下一行是分隔行（| --- | --- |）
            if (line.indexOf('|') >= 0 && i + 1 < lines.length
                && /^\s*\|?[\s:|-]*-[\s:|-]*\|[\s:|-]*$/.test(lines[i + 1])) {
                closeList();
                var head = splitTableRow(line);
                i += 2;
                var rows = [];
                while (i < lines.length && lines[i].indexOf('|') >= 0) { rows.push(splitTableRow(lines[i])); i++; }
                i--;
                html += '<table class="ilpv-table"><thead><tr>' +
                    head.map(function (c) { return '<th>' + inline(c) + '</th>'; }).join('') +
                    '</tr></thead><tbody>' + rows.map(function (row) {
                        return '<tr>' + row.map(function (c) { return '<td>' + inline(c) + '</td>'; }).join('') + '</tr>';
                    }).join('') + '</tbody></table>';
                continue;
            }
            var h = line.match(/^(#{1,6})\s+(.*)$/);
            var li = line.match(/^\s*[-*+]\s+(.*)$/);
            if (li) {
                if (!listOpen) { html += '<ul>'; listOpen = true; }
                html += '<li>' + inline(li[1]) + '</li>';
                continue;
            }
            closeList();
            if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
                html += '<hr>';
            } else if (h) {
                var level = Math.min(h[1].length + 1, 6);
                html += '<h' + level + '>' + inline(h[2]) + '</h' + level + '>';
            } else if (line.trim()) {
                html += '<p>' + inline(line) + '</p>';
            }
        }
        closeList();
        return html || '<p class="ilpv-hinttext">（空文档）</p>';
    }

    function splitTableRow(line) {
        return line.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|')
            .map(function (cell) { return cell.trim(); });
    }

    function fetchText(url) {
        return fetch(url, { credentials: 'same-origin' }).then(function (r) {
            // 文件被删/无权访问时后端回的是 JSON 错误体，直接 r.text() 会把一坨 JSON 当正文摊进取景框
            if (!r.ok) throw new Error('文件读取失败');
            return r.text();
        })
            .then(function (text) { return String(text).replace(/^\uFEFF/, ''); })  // 去掉 UTF-8 BOM，否则首个标题会带不可见字符
            .catch(function () { return '文件加载失败，可能已被移除，请下载后查看。'; });
    }

    /** Word：mammoth 把 .docx 转成 HTML（.doc 老格式不支持，走下载卡片） */
    function renderWord(file) {
        if (extOf(file.name) !== 'docx') return fallbackBlock(file);
        if (file.fileSize && file.fileSize > INLINE_RENDER_LIMIT) return fallbackBlock(file);
        return loadLib('mammoth')
            .then(function (mammoth) {
                return fetchBinary(file.fileUrl).then(function (buf) {
                    return mammoth.convertToHtml({ arrayBuffer: buf });
                });
            })
            .then(function (result) {
                return sanitized(result.value).then(function (html) {
                    return '<div class="ilpv-docx">' + html + '</div>';
                });
            })
            .catch(function () { return fallbackBlock(file); });
    }

    /**
     * 把工作表裁剪成"只含前 N 行 / M 列"的浅拷贝。
     * 本版 SheetJS（0.18.5）的 sheet_to_html 不认 r/c/edate 选项，所以必须自己裁剪范围，
     * 否则超大表格会一次性拼出几十万行 HTML，主线程直接卡住。
     */
    function clippedSheet(sheet, XLSX, range) {
        var limited = {
            s: { r: range.s.r, c: range.s.c },
            e: {
                r: Math.min(range.e.r, range.s.r + MAX_RENDER_ROWS - 1),
                c: Math.min(range.e.c, range.s.c + MAX_RENDER_COLS - 1)
            }
        };
        if (limited.e.r === range.e.r && limited.e.c === range.e.c) return sheet;

        var copy = { '!ref': XLSX.utils.encode_range(limited) };
        var merges = [];
        for (var r = limited.s.r; r <= limited.e.r; r++) {
            for (var c = limited.s.c; c <= limited.e.c; c++) {
                var addr = XLSX.utils.encode_cell({ r: r, c: c });
                if (sheet[addr]) copy[addr] = sheet[addr];
            }
        }
        // 合并单元格若跨出裁剪范围，传给 sheet_to_html 会抛错，这里只保留完全落在范围内的
        (sheet['!merges'] || []).forEach(function (m) {
            if (m.s.r >= limited.s.r && m.s.c >= limited.s.c
                && m.e.r <= limited.e.r && m.e.c <= limited.e.c) {
                merges.push(m);
            }
        });
        if (merges.length) copy['!merges'] = merges;
        copy['!cols'] = sheet['!cols'];
        copy['!rows'] = sheet['!rows'];
        return copy;
    }

    /** 生成单个工作表的表格 HTML；超出上限时附一行提示（数据仍在原文件里） */
    function sheetToHtml(XLSX, sheet) {
        if (!sheet || !sheet['!ref']) return '';
        var range;
        try {
            range = XLSX.utils.decode_range(sheet['!ref']);
        } catch (e) {
            return '';
        }
        var rowCount = range.e.r - range.s.r + 1;
        var colCount = range.e.c - range.s.c + 1;
        var truncated = rowCount > MAX_RENDER_ROWS || colCount > MAX_RENDER_COLS;
        var html;
        try {
            html = XLSX.utils.sheet_to_html(clippedSheet(sheet, XLSX, range));
        } catch (e) {
            return '';
        }
        if (truncated) {
            html += '<p class="ilpv-sheet__more">仅预览前 ' + MAX_RENDER_ROWS + ' 行 / ' + MAX_RENDER_COLS
                + ' 列（共 ' + rowCount + ' 行 × ' + colCount + ' 列），完整内容请下载查看</p>';
        }
        return html;
    }

    /**
     * Excel：SheetJS 读工作簿，每个 sheet 一张表。
     *
     * 提速要点：① 解析库走站内副本；② 结果按 URL 缓存，重复点开秒开；
     * ③ 只渲染第一个 sheet，其余展开时才生成（避免"点开一个多表文件却渲染了全部表格"）；
     * ④ 单表超过 MAX_RENDER_ROWS/COLS 先裁剪，避免几万行 HTML 卡死主线程。
     * 细节见 docs/团队资料预览性能优化-2026-10-03.md。
     */
    function renderExcel(file) {
        if (file.fileSize && file.fileSize > INLINE_RENDER_LIMIT) return fallbackBlock(file);
        var cacheKey = 'excel:' + file.fileUrl + '#' + (file.fileSize || 0);
        var cached = pickFromCache(cacheKey);
        if (cached) {
            perfMark('excel:cache-hit', 0);
            return cached;
        }
        return loadLib('xlsx')
            .then(function (XLSX) {
                return timed('excel:fetch', function () { return fetchBinary(file.fileUrl); })
                    .then(function (buf) {
                        return timed('excel:parse', function () {
                            return XLSX.read(new Uint8Array(buf), { type: 'array' });
                        }).then(function (book) { return { XLSX: XLSX, book: book }; });
                    });
            })
            .then(function (ctx) {
                var XLSX = ctx.XLSX;
                var book = ctx.book;
                var names = (book.SheetNames || []).filter(function (name) {
                    var sheet = book.Sheets[name];
                    return sheet && sheet['!ref'];
                });
                if (!names.length) return fallbackBlock(file);

                var html;
                if (names.length === 1) {
                    html = sheetToHtml(XLSX, book.Sheets[names[0]]);
                } else {
                    // 多表：只渲染第一个，其余留空占位，展开时再生成
                    var blocks = names.map(function (name, idx) {
                        var inner = idx === 0 ? sheetToHtml(XLSX, book.Sheets[name]) : '';
                        return '<details class="ilpv-sheet"' + (idx === 0 ? ' open' : '')
                            + ' data-sheet="' + escapeHtml(name) + '">' +
                            '<summary>' + escapeHtml(name) + '</summary>' +
                            '<div class="ilpv-sheet__body">' + inner + '</div>' +
                            '</details>';
                    });
                    html = blocks.join('');
                    pendingSheets = { XLSX: XLSX, book: book };
                }

                return timed('excel:sanitize', function () {
                    return sanitized(html).then(function (clean) {
                        return '<div class="ilpv-xlsx">' + clean + '</div>';
                    });
                });
            })
            .then(function (html) {
                putToCache(cacheKey, html);
                return html;
            })
            .catch(function () { return fallbackBlock(file); });
    }

    /** 最近一次多表工作簿：展开某个 sheet 时才真正生成它的表格 */
    var pendingSheets = null;
    var lazyBoundCanvases = typeof WeakSet === 'function' ? new WeakSet() : null;
    var lazyBoundFlag = false;

    function bindLazySheets(canvasEl) {
        if (!canvasEl || !canvasEl.addEventListener) return;
        if (lazyBoundCanvases) {
            if (lazyBoundCanvases.has(canvasEl)) return;
            lazyBoundCanvases.add(canvasEl);
        } else {
            if (lazyBoundFlag) return;
            lazyBoundFlag = true;
        }
        canvasEl.addEventListener('toggle', function (event) {
            var details = event.target;
            if (!details || details.tagName !== 'DETAILS' || !details.open) return;
            var body = details.querySelector('.ilpv-sheet__body');
            if (!body) return;
            if (body.getAttribute('data-rendered') === '1') return;
            if (!pendingSheets) return;
            var name = details.getAttribute('data-sheet');
            var sheet = name != null ? pendingSheets.book.Sheets[name] : null;
            if (!sheet) return;
            body.setAttribute('data-rendered', '1');
            body.innerHTML = sheetToHtml(pendingSheets.XLSX, sheet);
        }, true);
    }

    /**
     * 生成预览画布内层 HTML。调用方负责把它放进自己的 .pv-canvas 容器。
     * 返回 Promise 时由 renderInto 异步替换内容。
     * 样式类以 ilpv- 前缀，由引入方（team-space / team-detail）的皮肤 CSS 提供外观。
     */
    function buildPreviewHtml(file) {
        if (!file) return '';
        var type = resolveType(file.fileType, file.name);
        if (type === 'img' && file.fileUrl) {
            return '<div class="ilpv-img">' +
                '<img src="' + escapeHtml(file.fileUrl) + '" alt="' + escapeHtml(file.name) + '" loading="lazy">' +
                '<div class="ilpv-img__zoom" title="Ctrl + 滚轮缩放，放大后可拖动查看">' +
                    '<button type="button" data-zoom="out" aria-label="缩小" title="缩小">&minus;</button>' +
                    '<span class="ilpv-img__level">100%</span>' +
                    '<button type="button" data-zoom="in" aria-label="放大" title="放大">+</button>' +
                    '<button type="button" data-zoom="reset" title="重置为适应窗口">重置</button>' +
                '</div>' +
            '</div>';
        }
        if (type === 'pdf' && file.fileUrl) {
            // 直接用 iframe 铺满预览区；页面头部已有「下载」，这里不再重复一条工具条
            return '<div class="ilpv-pdf-frame">' +
                '<div class="ilpv-pdf-body"><iframe src="' + escapeHtml(file.fileUrl) +
                    '" title="' + escapeHtml(file.name) + '"></iframe></div>' +
            '</div>';
        }
        if (type === 'text' && file.fileUrl) {
            return cachedRender('text', file, function () {
                return fetchText(file.fileUrl).then(function (text) {
                    return '<div class="ilpv-text"><pre>' + escapeHtml(text) + '</pre></div>';
                });
            });
        }
        if (type === 'md') {
            if (!file.fileUrl) return '<div class="ilpv-doc"><h2>' + escapeHtml(file.name) + '</h2>' + fallbackBlock(file) + '</div>';
            return cachedRender('md', file, function () {
                return fetchText(file.fileUrl).then(function (text) {
                    return '<div class="ilpv-md">' + renderMarkdown(text) + '</div>';
                });
            });
        }
        // Word / Excel 自身已带缓存与耗时埋点
        if (type === 'word' && file.fileUrl) return renderWord(file);
        if (type === 'excel' && file.fileUrl) return renderExcel(file);
        return fallbackBlock(file);
    }

    /** 文本/Markdown 等轻量渲染的结果缓存：同一文件再次点开直接返回，不再重新请求与解析 */
    function cachedRender(kind, file, produce) {
        var cacheKey = kind + ':' + file.fileUrl + '#' + (file.fileSize || 0);
        var cached = pickFromCache(cacheKey);
        if (cached) {
            perfMark(kind + ':cache-hit', 0);
            return cached;
        }
        return produce().then(function (html) {
            putToCache(cacheKey, html);
            return html;
        });
    }

    /** 渲染超过这个时间才显示"加载中"占位，避免小文件闪一下 */
    var LOADING_DELAY_MS = 140;

    /**
     * 便捷入口：把预览渲染进指定画布容器（异步渲染时用令牌防止旧文件覆盖新文件）。
     * 慢文件先显示加载占位，避免画布长时间停留在上一个文件或空白。
     */
    function renderInto(canvasEl, file) {
        if (!canvasEl) return;
        var token = (canvasEl.__pvToken || 0) + 1;
        canvasEl.__pvToken = token;
        var result = buildPreviewHtml(file);
        if (result && typeof result.then === 'function') {
            var loadingTimer = setTimeout(function () {
                if (canvasEl.__pvToken === token) {
                    canvasEl.innerHTML = '<div class="ilpv-loading"><span class="ilpv-spinner"></span>正在加载预览…</div>';
                }
            }, LOADING_DELAY_MS);
            result.then(function (html) {
                clearTimeout(loadingTimer);
                if (canvasEl.__pvToken !== token) return;
                canvasEl.innerHTML = html;
                bindLazySheets(canvasEl);
            }).catch(function () {
                clearTimeout(loadingTimer);
            });
        } else {
            canvasEl.innerHTML = result;
            bindLazySheets(canvasEl);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', prefetchLibs);
    } else {
        prefetchLibs();
    }

    /* ---------- 图片预览缩放：滚轮连续、按钮步进、放大后拖动平移 ---------- */
    var IMG_MIN_SCALE = 0.25;
    var IMG_MAX_SCALE = 5;
    var IMG_STEP = 0.25;

    function imgScale(box) {
        return parseFloat(box.dataset.scale || '1') || 1;
    }

    function applyImgZoom(box) {
        var img = box.querySelector('img');
        if (!img) return;
        var scale = imgScale(box);
        var tx = parseFloat(box.dataset.tx || '0') || 0;
        var ty = parseFloat(box.dataset.ty || '0') || 0;
        img.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + scale + ')';
        var level = box.querySelector('.ilpv-img__level');
        if (level) level.textContent = Math.round(scale * 100) + '%';
        box.classList.toggle('is-zoomed', scale > 1.001);
    }

    function setImgScale(box, scale) {
        var next = Math.min(IMG_MAX_SCALE, Math.max(IMG_MIN_SCALE, scale));
        box.dataset.scale = next;
        // 缩回适应窗口时把平移一并归零，免得图片停在偏移位置
        if (next <= 1.001) {
            box.dataset.tx = 0;
            box.dataset.ty = 0;
        }
        applyImgZoom(box);
    }

    function imgBoxOf(el) {
        return el && el.closest ? el.closest('.ilpv-img') : null;
    }

    // 事件全部走委托：预览内容是异步替换的，逐个绑定会随替换失效
    document.addEventListener('click', function (event) {
        var btn = event.target && event.target.closest ? event.target.closest('[data-zoom]') : null;
        if (!btn) return;
        var box = imgBoxOf(btn);
        if (!box) return;
        var action = btn.getAttribute('data-zoom');
        if (action === 'in') setImgScale(box, imgScale(box) + IMG_STEP);
        else if (action === 'out') setImgScale(box, imgScale(box) - IMG_STEP);
        else { box.dataset.tx = 0; box.dataset.ty = 0; setImgScale(box, 1); }
    });

    // Ctrl + 滚轮连续缩放（触控板双指捏合同样是这个信号）；
    // 单纯滚轮不拦，否则在预览区想翻页会被缩放打断
    document.addEventListener('wheel', function (event) {
        var box = imgBoxOf(event.target);
        if (!box || !event.ctrlKey) return;
        event.preventDefault();
        setImgScale(box, imgScale(box) * (event.deltaY < 0 ? 1.1 : 1 / 1.1));
    }, { passive: false });

    // 放大之后可以按住拖动看别处
    var imgDrag = null;
    document.addEventListener('mousedown', function (event) {
        var box = imgBoxOf(event.target);
        if (!box || event.target.tagName !== 'IMG' || imgScale(box) <= 1.001) return;
        imgDrag = {
            box: box, x: event.clientX, y: event.clientY,
            tx: parseFloat(box.dataset.tx || '0') || 0,
            ty: parseFloat(box.dataset.ty || '0') || 0
        };
        box.classList.add('is-dragging');
        event.preventDefault();
    });
    document.addEventListener('mousemove', function (event) {
        if (!imgDrag) return;
        imgDrag.box.dataset.tx = imgDrag.tx + (event.clientX - imgDrag.x);
        imgDrag.box.dataset.ty = imgDrag.ty + (event.clientY - imgDrag.y);
        applyImgZoom(imgDrag.box);
    });
    document.addEventListener('mouseup', function () {
        if (!imgDrag) return;
        imgDrag.box.classList.remove('is-dragging');
        imgDrag = null;
    });

    return {
        escapeHtml: escapeHtml,
        fileIcon: fileIcon,
        typeLabel: typeLabel,
        formatSize: formatSize,
        icons: ICONS,
        buildPreviewHtml: buildPreviewHtml,
        renderInto: renderInto,
        prefetchLibs: prefetchLibs
    };
})();
