// iLink JavaScript - Enhanced Interactions
// Includes particle system, scroll animations, micro-interactions

// API基础URL
const API_BASE_URL = '/api';

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

// CSRF Token 辅助函数
function getCsrfToken() {
    var match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : null;
}

/**
 * 统一 fetch：同源 Cookie + CSRF 头（写操作必需）
 */
async function apiFetch(url, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = { ...options.headers };
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
        const token = getCsrfToken();
        if (token) {
            headers['X-XSRF-TOKEN'] = token;
        }
    }
    return fetch(url, {
        credentials: 'same-origin',
        ...options,
        headers
    });
}

// 统一请求处理
async function request(url, options = {}) {
    const { silent = false, ...requestOptions } = options;
    const config = {
        headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': getCsrfToken(),
            ...requestOptions.headers
        },
        credentials: 'same-origin',
        ...requestOptions
    };

    try {
        const response = await apiFetch(API_BASE_URL + url, config);

        const contentType = response.headers.get('content-type') || '';
        let data;
        if (contentType.includes('application/json')) {
            data = await response.json();
        } else {
            const raw = await response.text();
            try {
                data = JSON.parse(raw);
            } catch {
                let userMessage = '登录已过期，请重新登录';
                let messageType = 'warning';
                let shouldRedirectLogin = true;
                if (response.status >= 500) {
                    userMessage = '服务器暂时无法处理，请稍后重试';
                    messageType = 'error';
                    shouldRedirectLogin = false;
                } else if (response.status === 404) {
                    userMessage = '请求的资源未找到';
                    messageType = 'error';
                    shouldRedirectLogin = false;
                } else if (response.status === 403) {
                    userMessage = '您没有权限访问此资源';
                    messageType = 'error';
                    shouldRedirectLogin = false;
                }
                const err = new Error(userMessage);
                err._ilinkHandled = true;
                if (!silent) {
                    showMessage(userMessage, messageType);
                    if (shouldRedirectLogin) {
                        setTimeout(() => {
                            window.location.href = '/login.html';
                        }, 1200);
                    }
                }
                throw err;
            }
        }

        if (data.code !== 200) {
            let userMessage = data.message || '请求失败';
            let messageType = 'error';
            let shouldRedirectLogin = false;
            switch (data.code) {
                case 401:
                    userMessage = '请先登录';
                    messageType = 'warning';
                    shouldRedirectLogin = true;
                    break;
                case 403:
                    userMessage = '您没有权限访问此资源';
                    break;
                case 404:
                    userMessage = '请求的资源未找到';
                    break;
                case 500:
                    userMessage = '服务器暂时无法处理，请稍后重试';
                    break;
                default:
                    userMessage = data.message || '请求失败';
            }
            if (!silent) {
                showMessage(userMessage, messageType);
                if (shouldRedirectLogin) {
                    setTimeout(() => {
                        window.location.href = '/login.html';
                    }, 1500);
                }
            }
            const err = new Error(userMessage);
            err._ilinkHandled = true;
            err.response = data;
            throw err;
        }

        return data.data;
    } catch (error) {
        if (error && error._ilinkHandled) {
            throw error;
        }
        if (!silent && error.name === 'TypeError' && error.message.includes('fetch')) {
            showMessage('网络连接失败，请检查网络设置', 'error');
        } else if (!silent && error.message !== '请求失败') {
            showMessage(error.message || '请求失败', 'error');
        }
        throw error;
    }
}

// 获取当前用户信息
async function getCurrentUser() {
    try {
        const response = await apiFetch('/api/user/profile');
        const contentType = response.headers.get('content-type') || '';
        let result;
        if (contentType.includes('application/json')) {
            result = await response.json();
        } else {
            const raw = await response.text();
            try {
                result = JSON.parse(raw);
            } catch {
                return null;
            }
        }
        if (Number(result.code) === 200) {
            return result.data;
        }
        return null;
    } catch (error) {
        console.error('获取用户信息失败:', error);
        return null;
    }
}

// 用户登出
async function logout() {
    try {
        const csrfToken = getCsrfToken();
        await apiFetch('/api/logout', {
            method: 'POST',
            headers: { 'X-XSRF-TOKEN': csrfToken },
            credentials: 'same-origin'
        });
    } catch (error) {
        console.error('登出请求异常:', error);
    } finally {
        // 无论成功失败都跳转到登录页
        window.location.href = '/login.html';
    }
}

// 格式化时间
function formatTime(timestamp) {
    if (!timestamp) return '未知时间';

    const date = new Date(timestamp);
    const formatter = new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false
    });

    return formatter.format(date);
}

// 切换移动端菜单
function toggleMobileMenu() {
    const menu = document.getElementById('navMenu');
    if (menu) {
        menu.classList.toggle('show');
    }
}

// 切换用户菜单
function toggleUserMenu() {
    const menu = document.getElementById('userDropdown');
    const wrap = document.querySelector('.user-menu');
    const btn = document.getElementById('accountMenuBtn');
    if (menu && wrap) {
        menu.classList.toggle('show');
        const open = menu.classList.contains('show');
        wrap.classList.toggle('is-open', open);
        if (btn) {
            btn.setAttribute('aria-expanded', open ? 'true' : 'false');
        }
    }
}

/** 右上角展示：优先「用户名」，否则「姓名」 */
function computeNavbarDisplayName(user) {
    if (!user) return '用户';
    const rn = (user.realName || '').trim();
    const un = (user.username || '').trim();
    return un || rn || '用户';
}

function computeNavbarInitials(displayName) {
    const s = String(displayName || '用户').trim();
    if (!s) return '用';
    return s.slice(0, 2).toUpperCase();
}

function accountTriggerTitleAttr(user) {
    if (!user) return '';
    const dn = computeNavbarDisplayName(user);
    const un = (user.username || '').trim();
    if (un && dn !== un) {
        return `${dn}（账号 ${un}）`;
    }
    return dn;
}

function buildAccountAvatarInnerHtml(user) {
    const displayName = computeNavbarDisplayName(user);
    const initials = escapeHtml(computeNavbarInitials(displayName));
    const av = (user && user.avatar ? String(user.avatar) : '').trim();
    if (av) {
        return `<span class="account-trigger__avatar-wrap"><img class="account-trigger__avatar account-trigger__avatar--photo" src="${escapeHtml(av)}" alt="" referrerpolicy="no-referrer"></span>`;
    }
    return `<span class="account-trigger__avatar-wrap"><span class="account-trigger__avatar" aria-hidden="true">${initials}</span></span>`;
}

function applyAccountMenuFromUser(user) {
    const btn = document.getElementById('accountMenuBtn');
    if (!btn || !user) return;
    const nameEl = btn.querySelector('.account-trigger__name');
    const wrap = btn.querySelector('.account-trigger__avatar-wrap');
    const displayName = computeNavbarDisplayName(user);
    if (nameEl) nameEl.textContent = displayName;
    if (wrap) wrap.outerHTML = buildAccountAvatarInnerHtml(user);
    btn.setAttribute('title', accountTriggerTitleAttr(user));
}

// 关闭所有下拉菜单
function closeAllDropdowns() {
    document.querySelectorAll('.dropdown-menu').forEach(menu => {
        menu.classList.remove('show');
    });
    document.querySelectorAll('.user-menu.is-open').forEach((el) => {
        el.classList.remove('is-open');
    });
    const btn = document.getElementById('accountMenuBtn');
    if (btn) {
        btn.setAttribute('aria-expanded', 'false');
    }
}

// Toast 类型处理
function normalizeToastType(type) {
    switch (type) {
        case 'success': return 'success';
        case 'error':
        case 'danger': return 'error';
        case 'warning': return 'warning';
        case 'info':
        default: return 'info';
    }
}

function getToastDurationMs(type, override) {
    if (typeof override === 'number' && override > 0) {
        return override;
    }
    const t = normalizeToastType(type);
    if (t === 'error') return 5200;
    if (t === 'warning') return 4200;
    if (t === 'success') return 3600;
    return 3800;
}

const ILINK_TOAST_LABELS = {
    success: '成功',
    error: '出错了',
    warning: '请注意',
    info: '提示'
};

let _lastToastKey = '';
let _lastToastAt = 0;

const ILINK_TOAST_SVG = {
    success: '<svg class="ilink-toast__svg" width="22" height="22" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"><path d="M12 22a10 10 0 100-20 10 10 0 000 20z" stroke="currentColor" stroke-width="1.6"/><path d="M8.5 12.2l2.2 2.2 5.2-5.2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    error: '<svg class="ilink-toast__svg" width="22" height="22" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"><path d="M12 22a10 10 0 100-20 10 10 0 000 20z" stroke="currentColor" stroke-width="1.6"/><path d="M15 9l-6 6M9 9l6 6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
    warning: '<svg class="ilink-toast__svg" width="22" height="22" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"><path d="M12 3l10 18H2L12 3z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="M12 10v4M12 17h.01" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
    info: '<svg class="ilink-toast__svg" width="22" height="22" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"><path d="M12 22a10 10 0 100-20 10 10 0 000 20z" stroke="currentColor" stroke-width="1.6"/><path d="M12 16v-5h-.5M12 8h.01" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>'
};

function ensureToastHost() {
    let host = document.getElementById('ilinkToastHost');
    if (!host) {
        host = document.createElement('div');
        host.id = 'ilinkToastHost';
        host.className = 'ilink-toast-host';
        host.setAttribute('aria-live', 'polite');
        document.body.appendChild(host);
    }
    return host;
}

function dismissIlinkToast(toast) {
    if (!toast || !toast.parentNode) return;
    if (toast._ilinkTimer) {
        clearTimeout(toast._ilinkTimer);
        toast._ilinkTimer = null;
    }
    toast.classList.remove('ilink-toast--visible');
    toast.classList.add('ilink-toast--out');
    setTimeout(function () {
        if (toast.parentNode) {
            toast.parentNode.removeChild(toast);
        }
    }, 280);
}

/**
 * 表单字段内联提示（登录/注册等），不打断用户输入
 * @param {string} hintId - 如 identifierError
 * @param {string|null} message - 为空则清除
 * @param {string} [inputId] - 关联输入框 id
 */
function showFieldHint(hintId, message, inputId) {
    const hint = document.getElementById(hintId);
    const input = inputId ? document.getElementById(inputId) : null;
    const msg = message != null ? String(message).trim() : '';
    if (hint) {
        hint.textContent = msg;
        hint.classList.add('il-field-hint');
        if (msg) {
            hint.classList.add('is-visible', 'visible');
        } else {
            hint.classList.remove('is-visible', 'visible');
        }
    }
    if (input) {
        if (msg) {
            input.classList.add('il-input-invalid');
            input.setAttribute('aria-invalid', 'true');
        } else {
            input.classList.remove('il-input-invalid');
            input.removeAttribute('aria-invalid');
        }
    }
}

function clearFieldHint(hintId, inputId) {
    showFieldHint(hintId, '', inputId);
}

/**
 * 全局轻提示（右上角 Toast，勿用于字段级 blur 校验）
 */
function showMessage(message, type = 'info', durationMs) {
    const text = String(message != null ? message : '').trim() || ' ';
    const variant = normalizeToastType(type);
    const dedupeKey = variant + ':' + text;
    const now = Date.now();
    if (dedupeKey === _lastToastKey && now - _lastToastAt < 2200) {
        return;
    }
    _lastToastKey = dedupeKey;
    _lastToastAt = now;

    const host = ensureToastHost();
    const maxStack = 4;
    while (host.children.length >= maxStack) {
        const first = host.firstChild;
        if (first && first._ilinkTimer) {
            clearTimeout(first._ilinkTimer);
            first._ilinkTimer = null;
        }
        if (first) {
            host.removeChild(first);
        }
    }

    const toast = document.createElement('div');
    toast.className = 'ilink-toast ilink-toast--' + variant;
    toast.setAttribute('role', variant === 'error' ? 'alert' : 'status');

    const icon = document.createElement('span');
    icon.className = 'ilink-toast__icon';
    icon.innerHTML = ILINK_TOAST_SVG[variant] || ILINK_TOAST_SVG.info;

    const body = document.createElement('div');
    body.className = 'ilink-toast__body';

    const labelEl = document.createElement('span');
    labelEl.className = 'ilink-toast__label';
    labelEl.textContent = ILINK_TOAST_LABELS[variant] || ILINK_TOAST_LABELS.info;

    const textEl = document.createElement('p');
    textEl.className = 'ilink-toast__text';
    textEl.textContent = text;

    const progress = document.createElement('div');
    progress.className = 'ilink-toast__progress';
    progress.setAttribute('aria-hidden', 'true');

    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'ilink-toast__close';
    closeBtn.setAttribute('aria-label', '关闭');
    closeBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
    closeBtn.addEventListener('click', function () {
        dismissIlinkToast(toast);
    });

    body.appendChild(labelEl);
    body.appendChild(textEl);
    toast.appendChild(icon);
    toast.appendChild(body);
    toast.appendChild(closeBtn);
    toast.appendChild(progress);
    host.appendChild(toast);

    const wait = getToastDurationMs(type, durationMs);
    if (window.matchMedia && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        progress.style.transition = 'transform ' + wait + 'ms linear';
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                toast.classList.add('ilink-toast--visible');
                progress.style.transform = 'scaleX(0)';
            });
        });
    } else {
        requestAnimationFrame(function () {
            toast.classList.add('ilink-toast--visible');
        });
    }

    toast._ilinkTimer = setTimeout(function () {
        dismissIlinkToast(toast);
    }, wait);
}

function getTypeClass(type) {
    switch (normalizeToastType(type)) {
        case 'success': return 'success';
        case 'error': return 'danger';
        case 'warning': return 'warning';
        case 'info':
        default: return 'info';
    }
}

function publicProfilePageUrl(userId) {
    if (userId == null || userId === '') return '#';
    return '/user-profile.html?id=' + encodeURIComponent(String(userId));
}

/** 卡片/详情展示名：preferRealName 时 realName → username，否则 username → realName */
function displayUserName(preview, opts) {
    if (!preview) return '';
    const preferRealName = !!(opts && opts.preferRealName);
    const rn = (preview.realName && String(preview.realName).trim()) || '';
    const un = (preview.username && String(preview.username).trim()) || '';
    return preferRealName ? (rn || un) : (un || rn);
}

/** Gallery / 成果详情：仅 username，绝不读 realName */
function displayUsername(preview) {
    if (!preview) return '匿名';
    const un = (preview.username && String(preview.username).trim()) || '';
    if (un) return un;
    if (preview.id != null) return '用户' + preview.id;
    return '匿名';
}

/** Gallery 无头像首字：仅来自 username */
function galleryAvatarInitial(preview) {
    const un = preview && preview.username && String(preview.username).trim();
    return un ? un.charAt(0).toUpperCase() : '';
}

/** Gallery / 成果详情头像：有 URL 仅 img，无 URL 仅 fallback */
function galleryPublisherAvatarHtml(preview, extraClass) {
    if (!preview || preview.id == null) return '';
    const display = displayUsername(preview);
    const initials = galleryAvatarInitial(preview);
    const nameEscaped = escapeHtml(display);
    const href = escapeHtml(publicProfilePageUrl(preview.id));
    const av = preview.avatar && String(preview.avatar).trim();
    const cls = 'il-publisher-avatar' + (extraClass ? ' ' + extraClass : '');
    const wrapStyle =
        'width:40px;height:40px;min-width:40px;min-height:40px;max-width:40px;max-height:40px;border-radius:50%;overflow:hidden;display:block;flex-shrink:0;';
    const imgStyle = 'width:100%;height:100%;object-fit:cover;display:block;border-radius:50%;';
    if (av) {
        const avEsc = escapeHtml(av);
        return (
            '<a href="' + href + '" class="' + cls + '" style="display:inline-flex;flex-shrink:0;border-radius:50%;overflow:hidden;" title="查看 ' + nameEscaped + ' 的主页" aria-label="查看 TA 的个人主页">' +
            '<div class="il-avatar-wrap" style="' + wrapStyle + '">' +
            '<img class="il-publisher-avatar__img" style="' + imgStyle + '" src="' + avEsc + '" alt="" referrerpolicy="no-referrer" loading="lazy" width="40" height="40" onerror="this.onerror=null;this.style.display=\'none\';">' +
            '</div></a>'
        );
    }
    return (
        '<a href="' + href + '" class="' + cls + '" style="display:inline-flex;flex-shrink:0;border-radius:50%;overflow:hidden;" title="查看 ' + nameEscaped + ' 的主页" aria-label="查看 TA 的个人主页">' +
        '<div class="il-avatar-wrap" style="' + wrapStyle + '">' +
        '<span class="il-avatar-fallback" style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;border-radius:50%;">' + escapeHtml(initials || '?') + '</span>' +
        '</div></a>'
    );
}

/** 有 img 时隐藏同 wrap 内 fallback，避免叠在头像上 */
function hideGalleryPublisherAvatarFallbacks(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll('.gallery-card .il-publisher-avatar img, .detail-view__author .il-publisher-avatar img').forEach(function (img) {
        if (!img.getAttribute('src') || img.style.display === 'none') return;
        const wrap = img.closest('.il-avatar-wrap');
        if (!wrap) return;
        wrap.querySelectorAll('.il-avatar-fallback').forEach(function (fb) {
            fb.classList.add('il-avatar-fallback--hidden');
            fb.setAttribute('aria-hidden', 'true');
            fb.textContent = '';
        });
    });
}

/** 无头像时的首字母，来自 displayUserName，不用「用户」占位 */
function avatarInitial(preview, opts) {
    const name = displayUserName(preview, opts);
    const s = String(name || '').trim();
    return s ? s.charAt(0).toUpperCase() : '';
}

function publisherAvatarHtml(preview, extraClass, opts) {
    if (!preview || preview.id == null) return '';
    const preferRealName = !!(opts && opts.preferRealName);
    const display = displayUserName(preview, { preferRealName: preferRealName });
    const initials = avatarInitial(preview, { preferRealName: preferRealName });
    const nameEscaped = escapeHtml(display || '用户');
    const href = escapeHtml(publicProfilePageUrl(preview.id));
    const av = preview.avatar && String(preview.avatar).trim();
    const cls = 'publisher-avatar' + (extraClass ? ' ' + extraClass : '');
    if (av) {
        const avEsc = escapeHtml(av);
        return (
            '<a href="' + href + '" class="' + cls + '" title="查看 ' + nameEscaped + ' 的主页" aria-label="查看 TA 的个人主页">' +
            '<img class="publisher-avatar__img" src="' + avEsc + '" alt="" referrerpolicy="no-referrer" loading="lazy" onerror="this.onerror=null;this.style.display=\'none\';var s=this.nextElementSibling;if(s)s.classList.remove(\'d-none\');">' +
            '<span class="publisher-avatar__fallback d-none" aria-hidden="true">' + escapeHtml(initials || '?') + '</span></a>'
        );
    }
    return (
        '<a href="' + href + '" class="' + cls + '" title="查看 ' + nameEscaped + ' 的主页" aria-label="查看 TA 的个人主页">' +
        '<span class="publisher-avatar__fallback" aria-hidden="true">' + escapeHtml(initials || '?') + '</span></a>'
    );
}

function publisherAvatarFromAuthorFields(authorId, authorAvatar, authorDisplay) {
    if (authorId == null) return '';
    return publisherAvatarHtml({
        id: authorId,
        avatar: authorAvatar || '',
        realName: authorDisplay || '',
        username: ''
    });
}

// ========================================
// Enhanced Particle System with Mouse Interaction
// 支持参数化配置，可适配首页（密集、强连线）与登录/注册页（稀疏、柔和、多色）
// ========================================
class ParticleSystem {
    constructor(canvasId, options = {}) {
        // 仅在明确标记 data-particles="true" 的页面上初始化（首页 / 登录 / 注册）
        if (!document.body || document.body.getAttribute('data-particles') !== 'true') {
            console.debug('[ParticleSystem] 未启用（data-particles 未设置）');
            return;
        }
        if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            console.debug('[ParticleSystem] 已禁用（用户偏好减少动画）');
            return;
        }

        this.canvas = document.getElementById(canvasId);
        if (!this.canvas) {
            console.debug('[ParticleSystem] 未找到 canvas 元素:', canvasId);
            return;
        }

        // 防重复初始化：若同一 canvas 已托管实例，先销毁旧实例，避免多个 RAF 并行
        const existing = ParticleSystem._instances && ParticleSystem._instances.get(this.canvas);
        if (existing) {
            existing.destroy();
        }
        ParticleSystem._instances = ParticleSystem._instances || new Map();
        ParticleSystem._instances.set(this.canvas, this);

        this.ctx = this.canvas.getContext('2d');
        this.particles = [];
        this.mouse = { x: null, y: null };
        this.rafId = null;
        this.time = 0;
        // 限制 DPR 上限，避免 4K 屏渲染负担过重
        this.dpr = Math.min(window.devicePixelRatio || 1, 2);
        this.resizeTimer = null;
        this.themeObserver = null;

        // 配置合并（向后兼容：不传 options 时保持原有视觉表现）
        this.opts = Object.assign({
            // 粒子数量：按视口面积自适应（每像素的粒子数）
            density: 0.00004,          // 1920×1080 ≈ 83 粒子，接近原固定 80
            minParticles: 30,
            maxParticles: 140,
            fixedCount: null,          // 设置后忽略 density，使用固定数量
            // 外观
            sizeRange: [2, 4.5],       // 粒子半径范围 [min, max]
            speedScale: 0.5,           // 基础速度倍率
            opacityRange: [0.3, 0.6],  // 基础透明度范围
            colors: null,              // null => 主题自适应（亮色蓝 / 暗色白）；['r,g,b', ...] => 多色随机
            // 连线
            connectDistance: 160,
            connectOpacity: 0.7,
            lineWidth: 1.2,
            // 鼠标交互
            mouseEnabled: true,
            mouseRadius: 150,
            mouseForce: 2,
            // 呼吸脉动
            pulse: true,
            pulseSpeed: 0.018,
            pulseAmount: 0.12
        }, options);

        this.connectionDistance = this.opts.connectDistance;
        this.connectionDistanceSq = this.connectionDistance * this.connectionDistance;
        this.gridCellSize = Math.ceil(this.connectionDistance * 1.05);
        this.isDarkMode = document.documentElement.getAttribute('data-theme') === 'dark';

        this.init();
        this.bindEvents();
        this.animate();
    }

    /** 解析当前主题下的粒子颜色集合 */
    resolveColors() {
        const colors = this.opts.colors;
        if (Array.isArray(colors) && colors.length) {
            return colors;
        }
        return this.isDarkMode ? ['255, 255, 255'] : ['59, 130, 246'];
    }

    /** 根据配置计算粒子数量（按视口面积自适应，带上下限） */
    computeCount() {
        if (this.opts.fixedCount != null) {
            return Math.max(0, this.opts.fixedCount);
        }
        const w = this.viewWidth || window.innerWidth;
        const h = this.viewHeight || window.innerHeight;
        const raw = Math.round(w * h * this.opts.density);
        return Math.max(this.opts.minParticles, Math.min(this.opts.maxParticles, raw));
    }

    init() {
        this.onResize();
        // onResize 内部已经调了 createParticles(computeCount())，此处不再重复创建
        // 仅做兜底：若 onResize 因尺寸异常（<=0）跳过了粒子创建，则延迟重试
        const w = this.viewWidth || window.innerWidth;
        const h = this.viewHeight || window.innerHeight;
        if (w <= 0 || h <= 0) {
            setTimeout(() => {
                if (this.viewWidth && this.viewWidth > 0) {
                    this.createParticles(this.computeCount());
                }
            }, 50);
        }
    }

    bindEvents() {
        window.addEventListener('resize', () => {
            if (this.resizeTimer) clearTimeout(this.resizeTimer);
            this.resizeTimer = setTimeout(() => this.onResize(), 150);
        });

        if (this.opts.mouseEnabled) {
            document.addEventListener('mousemove', (e) => {
                this.mouse.x = e.clientX;
                this.mouse.y = e.clientY;
            });
            document.addEventListener('mouseleave', () => {
                this.mouse.x = null;
                this.mouse.y = null;
            });
        }

        document.addEventListener('visibilitychange', () => {
            if (!document.hidden) {
                this.resume();
            }
        });

        window.addEventListener('pageshow', () => {
            setTimeout(() => this.resume(), 0);
        });

        // 监听主题切换：未自定义颜色时实时更新粒子配色
        this.themeObserver = new MutationObserver(() => {
            const dark = document.documentElement.getAttribute('data-theme') === 'dark';
            if (dark !== this.isDarkMode) {
                this.isDarkMode = dark;
                if (!Array.isArray(this.opts.colors)) {
                    const colors = this.resolveColors();
                    this.particles.forEach(p => {
                        p.color = colors[Math.floor(Math.random() * colors.length)];
                    });
                }
            }
        });
        this.themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['data-theme']
        });
    }

    // 兼容旧调用名
    resize() {
        this.onResize();
    }

    onResize() {
        if (!this.canvas) return;
        const dpr = this.dpr;
        const w = window.innerWidth;
        const h = window.innerHeight;
        // 高 DPI 适配：物理像素 = 逻辑像素 × DPR，绘制坐标使用逻辑像素
        this.canvas.width = Math.floor(w * dpr);
        this.canvas.height = Math.floor(h * dpr);
        this.canvas.style.width = w + 'px';
        this.canvas.style.height = h + 'px';
        this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        this.viewWidth = w;
        this.viewHeight = h;

        // 按新尺寸增减粒子，保持密度一致
        const target = this.computeCount();
        if (this.particles.length < target) {
            this.createParticles(target - this.particles.length);
        } else if (this.particles.length > target) {
            this.particles.length = target;
        }
    }

    createParticles(count) {
        const colors = this.resolveColors();
        const [minSize, maxSize] = this.opts.sizeRange;
        const [minOp, maxOp] = this.opts.opacityRange;
        const speed = this.opts.speedScale;
        const w = this.viewWidth || window.innerWidth;
        const h = this.viewHeight || window.innerHeight;

        for (let i = 0; i < count; i++) {
            const baseOp = Math.random() * (maxOp - minOp) + minOp;
            this.particles.push({
                x: Math.random() * w,
                y: Math.random() * h,
                vx: (Math.random() - 0.5) * speed,
                vy: (Math.random() - 0.5) * speed,
                radius: Math.random() * (maxSize - minSize) + minSize,
                baseOpacity: baseOp,
                opacity: baseOp,                // 直接以 baseOpacity 可见，避免从透明渐入（看起来像从角落飘出）
                phase: Math.random() * Math.PI * 2,
                color: colors[Math.floor(Math.random() * colors.length)]
            });
        }
    }

    buildGrid() {
        const grid = new Map();
        const cellSize = this.gridCellSize;
        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];
            const col = Math.floor(p.x / cellSize);
            const row = Math.floor(p.y / cellSize);
            const key = col + ',' + row;
            let cell = grid.get(key);
            if (!cell) {
                cell = [];
                grid.set(key, cell);
            }
            cell.push(i);
        }
        return grid;
    }

    update() {
        const w = this.viewWidth;
        const h = this.viewHeight;
        const mr = this.opts.mouseRadius;
        const mrSq = mr * mr;
        const mf = this.opts.mouseForce;
        // 鼠标在 (0,0) 时视为无效（页面刚加载、鼠标未移动），避免粒子被左上角排斥力推开
        const mouseOn = this.opts.mouseEnabled
            && this.mouse.x !== null && this.mouse.y !== null
            && (this.mouse.x > 0 || this.mouse.y > 0);
        const pulse = this.opts.pulse;
        const ps = this.opts.pulseSpeed;
        const pa = this.opts.pulseAmount;
        this.time += 1;

        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];

            // 鼠标排斥 + 高亮增强
            let boost = 0;
            if (mouseOn) {
                const dx = p.x - this.mouse.x;
                const dy = p.y - this.mouse.y;
                const distSq = dx * dx + dy * dy;
                if (distSq < mrSq) {
                    const dist = Math.sqrt(distSq) || 0.0001;
                    const force = (mr - dist) / mr;
                    p.x += (dx / dist) * force * mf;
                    p.y += (dy / dist) * force * mf;
                    boost = force * 0.3;
                }
            }

            // 位移
            p.x += p.vx;
            p.y += p.vy;

            // 边界反弹
            if (p.x < 0 || p.x > w) p.vx *= -1;
            if (p.y < 0 || p.y > h) p.vy *= -1;
            p.x = Math.max(0, Math.min(w, p.x));
            p.y = Math.max(0, Math.min(h, p.y));

            // 透明度 = 基础呼吸脉动 + 鼠标增强
            const pulseOp = pulse
                ? p.baseOpacity + Math.sin(this.time * ps + p.phase) * pa
                : p.baseOpacity;
            p.opacity = Math.max(0, Math.min(0.85, pulseOp + boost));
        }
    }

    draw() {
        if (!this.ctx) return;
        const ctx = this.ctx;
        ctx.clearRect(0, 0, this.viewWidth, this.viewHeight);

        // 绘制粒子
        for (let i = 0; i < this.particles.length; i++) {
            const p = this.particles[i];
            ctx.beginPath();
            ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(${p.color}, ${p.opacity})`;
            ctx.fill();
        }

        // 绘制连线（空间网格加速，避免 O(n²)）
        const grid = this.buildGrid();
        const maxDistSq = this.connectionDistanceSq;
        const maxDist = this.connectionDistance;
        const connOp = this.opts.connectOpacity;
        const lw = this.opts.lineWidth;
        const drawn = new Set();

        grid.forEach((cellIndices, key) => {
            const parts = key.split(',');
            const col = Number(parts[0]);
            const row = Number(parts[1]);

            const neighborIndices = [];
            for (let dc = -1; dc <= 1; dc++) {
                for (let dr = -1; dr <= 1; dr++) {
                    const nCell = grid.get((col + dc) + ',' + (row + dr));
                    if (nCell) {
                        for (let k = 0; k < nCell.length; k++) {
                            neighborIndices.push(nCell[k]);
                        }
                    }
                }
            }

            for (let a = 0; a < cellIndices.length; a++) {
                const i = cellIndices[a];
                const pi = this.particles[i];
                for (let b = 0; b < neighborIndices.length; b++) {
                    const j = neighborIndices[b];
                    if (j <= i) continue;

                    const pairKey = i * 100000 + j;
                    if (drawn.has(pairKey)) continue;
                    drawn.add(pairKey);

                    const pj = this.particles[j];
                    const dx = pi.x - pj.x;
                    const dy = pi.y - pj.y;
                    const distSq = dx * dx + dy * dy;

                    if (distSq < maxDistSq) {
                        const distance = Math.sqrt(distSq);
                        const opacity = (1 - distance / maxDist) * connOp;
                        ctx.beginPath();
                        ctx.moveTo(pi.x, pi.y);
                        ctx.lineTo(pj.x, pj.y);
                        ctx.strokeStyle = `rgba(${pi.color}, ${opacity})`;
                        ctx.lineWidth = lw;
                        ctx.stroke();
                    }
                }
            }
        });
    }

    animate() {
        this.rafId = null;
        if (document.hidden || (document.body && document.body.classList.contains('page-leaving'))) {
            return;
        }
        this.update();
        this.draw();
        this.rafId = requestAnimationFrame(() => this.animate());
    }

    resume() {
        if (!this.rafId) {
            this.animate();
        }
    }

    /** 销毁实例：停止动画、断开监听、清屏，供防重复初始化与页面卸载使用 */
    destroy() {
        if (this.rafId) {
            cancelAnimationFrame(this.rafId);
            this.rafId = null;
        }
        if (this.themeObserver) {
            this.themeObserver.disconnect();
            this.themeObserver = null;
        }
        if (this.resizeTimer) {
            clearTimeout(this.resizeTimer);
            this.resizeTimer = null;
        }
        if (this.ctx) {
            this.ctx.clearRect(0, 0, this.viewWidth || 0, this.viewHeight || 0);
        }
        this.particles = [];
        if (ParticleSystem._instances && this.canvas) {
            ParticleSystem._instances.delete(this.canvas);
        }
    }
}

// ========================================
// Scroll Animations - Intersection Observer
// ========================================
class ScrollAnimator {
    constructor() {
        this.init();
    }

    init() {
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            this.showAllElements();
            return;
        }

        const options = {
            root: null,
            rootMargin: '0px 0px -50px 0px',
            threshold: 0.1
        };

        this.observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    entry.target.classList.add('animate-in');
                    this.observer.unobserve(entry.target);
                }
            });
        }, options);

        this.observeElements();
    }

    observeElements() {
        const elements = document.querySelectorAll('.scroll-animate');
        // C-47: 批量创建单个 Observer 而非每个元素一个，优化性能
        if (elements.length === 0) return;
        const maxDelay = Math.min(elements.length, 20) * 0.05; // 最多 1s 延迟
        elements.forEach((el, index) => {
            el.style.transitionDelay = `${(index / elements.length) * maxDelay}s`;
            this.observer.observe(el);
        });
    }

    showAllElements() {
        const elements = document.querySelectorAll('.scroll-animate');
        elements.forEach(el => el.classList.add('animate-in'));
    }
}

// ========================================
// Number Counter Animation
// ========================================
class NumberCounter {
    constructor(element, target, duration = 2000) {
        this.element = element;
        this.target = target;
        this.duration = duration;
        this.hasRun = false;
    }

    start() {
        if (this.hasRun) return;
        this.hasRun = true;
        
        const startTime = performance.now();
        const startValue = 0;
        
        const animate = (currentTime) => {
            const elapsed = currentTime - startTime;
            const progress = Math.min(elapsed / this.duration, 1);
            const easeProgress = this.easeOutCubic(progress);
            const currentValue = Math.floor(startValue + (this.target - startValue) * easeProgress);
            
            this.element.textContent = currentValue.toLocaleString();
            
            if (progress < 1) {
                requestAnimationFrame(animate);
            }
        };
        
        requestAnimationFrame(animate);
    }

    easeOutCubic(t) {
        return 1 - Math.pow(1 - t, 3);
    }
}

function initCounters() {
    const counters = document.querySelectorAll('[data-counter]');
    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const el = entry.target;
                const target = parseInt(el.dataset.counter, 10);
                const duration = parseInt(el.dataset.duration || '2000', 10);
                new NumberCounter(el, target, duration).start();
                observer.unobserve(el);
            }
        });
    }, { threshold: 0.5 });

    counters.forEach(counter => observer.observe(counter));
}

// ========================================
// Magnetic Button Effect
// ========================================
class MagneticButton {
    constructor(element) {
        this.element = element;
        this.strength = 0.3;
        this.boundary = 100;
        this.init();
    }

    init() {
        this.element.addEventListener('mousemove', (e) => this.onMouseMove(e));
        this.element.addEventListener('mouseleave', () => this.onMouseLeave());
    }

    onMouseMove(e) {
        const rect = this.element.getBoundingClientRect();
        const centerX = rect.left + rect.width / 2;
        const centerY = rect.top + rect.height / 2;
        const deltaX = e.clientX - centerX;
        const deltaY = e.clientY - centerY;
        
        const distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        
        if (distance < this.boundary) {
            const moveX = deltaX * this.strength;
            const moveY = deltaY * this.strength;
            this.element.style.transform = `translate(${moveX}px, ${moveY}px)`;
        }
    }

    onMouseLeave() {
        this.element.style.transform = 'translate(0, 0)';
    }
}

function initMagneticButtons() {
    const buttons = document.querySelectorAll('.magnetic-btn');
    buttons.forEach(btn => new MagneticButton(btn));
}

// ========================================
// Page Transition Effects
// ========================================
class PageTransition {
    constructor() {
        this.duration = 140;
        this.isTransitioning = false;
        this.reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
        this.init();
    }

    init() {
        this.ensureStyles();
        this.markSurfaces();
        this.enter();

        document.addEventListener('click', (event) => {
            const link = event.target.closest('a[href]');
            if (!link || !this.shouldTransition(link, event)) return;
            this.onLinkClick(event, link);
        });

        window.addEventListener('pageshow', () => {
            this.isTransitioning = false;
            document.body.classList.remove('page-leaving');
            this.markSurfaces();
            this.enter();
        });
    }

    ensureStyles() {
        if (document.getElementById('ilinkPageTransitionStyles')) return;

        const style = document.createElement('style');
        style.id = 'ilinkPageTransitionStyles';
        style.textContent = `
            .il-page-transition-surface {
                transition: opacity 180ms cubic-bezier(0.22, 1, 0.36, 1), transform 180ms cubic-bezier(0.22, 1, 0.36, 1);
                will-change: opacity, transform;
            }
            body.page-transition-ready:not(.page-entered) .il-page-transition-surface {
                opacity: 0.01;
                transform: translate3d(0, 8px, 0);
            }
            body.page-transition-ready.page-leaving .il-page-transition-surface {
                opacity: 0.01;
                transform: translate3d(0, -6px, 0);
                transition-duration: 120ms;
                pointer-events: none;
            }
            body.page-leaving {
                cursor: progress;
            }
            body.page-leaving .particle-canvas {
                opacity: 0 !important;
                transition: opacity 120ms ease-out;
            }
            @media (prefers-reduced-motion: reduce) {
                .il-page-transition-surface {
                    transition: none !important;
                    transform: none !important;
                    opacity: 1 !important;
                }
            }
        `;
        document.head.appendChild(style);
    }

    markSurfaces() {
        const selectors = [
            'main',
            '.main-content',
            '.il-page-wrapper',
            '.gallery-hero',
            '.gallery-control-panel',
            '.gallery-container',
            '.profile-layout',
            '.profile-container',
            '.profile-shell',
            '.profile-card',
            '.auth-wrapper',
            '.auth-container',
            '.login-container',
            '.register-container',
            '.admin-layout',
            '.admin-main',
            '.page-container',
            '.detail-page',
            '.team-workspace',
            '.chat-container'
        ];

        const surfaces = new Set();
        selectors.forEach(selector => {
            document.querySelectorAll(selector).forEach(el => surfaces.add(el));
        });

        if (surfaces.size === 0) {
            Array.from(document.body.children).forEach(el => {
                if (['SCRIPT', 'STYLE', 'CANVAS', 'HEADER', 'FOOTER'].includes(el.tagName)) return;
                if (el.classList.contains('modal') || el.classList.contains('modal-backdrop')) return;
                surfaces.add(el);
            });
        }

        surfaces.forEach(el => el.classList.add('il-page-transition-surface'));
    }

    enter() {
        if (this.reducedMotion.matches) {
            document.body.classList.add('page-entered');
            return;
        }

        document.body.classList.remove('page-leaving', 'page-entered');
        document.body.classList.add('page-transition-ready');
        requestAnimationFrame(() => {
            requestAnimationFrame(() => {
                document.body.classList.add('page-entered');
            });
        });
    }

    shouldTransition(link, event) {
        if (this.isTransitioning || event.defaultPrevented) return false;
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return false;

        const href = link.getAttribute('href');
        if (!href || href.startsWith('#') || href.startsWith('javascript') || href.startsWith('mailto') || href.startsWith('tel')) {
            return false;
        }
        if (link.hasAttribute('download')) return false;
        if (link.target && link.target !== '_self') return false;
        if (link.dataset.noTransition === 'true') return false;
        if (link.dataset.bsToggle || link.getAttribute('role') === 'tab') return false;

        let targetUrl;
        try {
            targetUrl = new URL(href, window.location.href);
        } catch {
            return false;
        }

        if (targetUrl.origin !== window.location.origin) return false;
        if (targetUrl.pathname === window.location.pathname && targetUrl.search === window.location.search) return false;

        return true;
    }

    onLinkClick(e, link) {
        const href = link.href || link.getAttribute('href');
        if (!href) return;

        e.preventDefault();
        this.navigateTo(href);
    }

    navigateTo(href) {
        if (this.reducedMotion.matches) {
            window.location.assign(href);
            return;
        }

        this.isTransitioning = true;
        closeAllDropdowns();
        document.body.classList.add('page-exit');
        document.body.classList.add('page-leaving');
        
        setTimeout(() => {
            window.location.assign(href);
        }, this.duration);
    }
}

let ilinkPageTransitionInstance = null;

function navigateWithTransition(href) {
    if (!href) return;
    if (ilinkPageTransitionInstance) {
        ilinkPageTransitionInstance.navigateTo(href);
        return;
    }
    window.location.assign(href);
}

// ========================================
// Tilt Effect for Cards
// ========================================
class TiltCard {
    constructor(element) {
        this.element = element;
        this.glare = element.querySelector('.tilt-glare');
        this.maxTilt = 10;
        this.init();
    }

    init() {
        this.element.addEventListener('mousemove', (e) => this.onMouseMove(e));
        this.element.addEventListener('mouseleave', () => this.onMouseLeave());
    }

    onMouseMove(e) {
        const rect = this.element.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        
        const centerX = rect.width / 2;
        const centerY = rect.height / 2;
        
        const rotateX = (y - centerY) / centerY * this.maxTilt;
        const rotateY = (centerX - x) / centerX * this.maxTilt;
        
        this.element.style.transform = `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale(1.02)`;
        
        if (this.glare) {
            this.glare.style.opacity = '1';
            this.glare.style.transform = `translate(${x - centerX}px, ${y - centerY}px) translate(-50%, -50%)`;
        }
    }

    onMouseLeave() {
        this.element.style.transform = 'perspective(1000px) rotateX(0) rotateY(0) scale(1)';
        if (this.glare) {
            this.glare.style.opacity = '0';
        }
    }
}

function initTiltCards() {
    const cards = document.querySelectorAll('.tilt-card');
    cards.forEach(card => new TiltCard(card));
}

// ========================================
// Ripple Effect
// ========================================
function createRipple(event, element) {
    const circle = document.createElement('span');
    const diameter = Math.max(element.clientWidth, element.clientHeight);
    const radius = diameter / 2;

    const rect = element.getBoundingClientRect();
    circle.style.width = circle.style.height = `${diameter}px`;
    circle.style.left = `${event.clientX - rect.left - radius}px`;
    circle.style.top = `${event.clientY - rect.top - radius}px`;
    circle.classList.add('ripple-effect');

    const ripple = element.querySelector('.ripple-effect');
    if (ripple) ripple.remove();

    element.appendChild(circle);
}

function initRippleButtons() {
    const buttons = document.querySelectorAll('.ripple-btn');
    buttons.forEach(btn => {
        btn.addEventListener('click', (e) => createRipple(e, btn));
    });
}

// ========================================
// Smooth Scroll for Anchor Links
// ========================================
function initSmoothScroll() {
    const links = document.querySelectorAll('a[href^="#"]');
    
    links.forEach(link => {
        link.addEventListener('click', (e) => {
            const href = link.getAttribute('href');
            if (href === '#') return;
            
            const target = document.querySelector(href);
            if (target) {
                e.preventDefault();
                target.scrollIntoView({
                    behavior: 'smooth',
                    block: 'start'
                });
            }
        });
    });
}

// ========================================
// Initialize All Interactions
// ========================================
document.addEventListener('DOMContentLoaded', function() {
    // Particle system
    // 读取页面级配置钩子：auth 页会在 common.js 加载前设置 window.ILINK_PARTICLE_OPTIONS
    // 以实现稀疏柔和的自定义效果，避免双重实例化导致的竞态（粒子偶发消失）
    new ParticleSystem('particleCanvas', window.ILINK_PARTICLE_OPTIONS);
    
    // Scroll animations
    new ScrollAnimator();
    
    // Number counters
    initCounters();
    
    // Magnetic buttons
    initMagneticButtons();
    
    // Page transitions
    ilinkPageTransitionInstance = new PageTransition();
    
    // Tilt cards
    initTiltCards();
    
    // Ripple buttons
    initRippleButtons();
    
    // Smooth scroll
    initSmoothScroll();
    
    // Navbar scroll effect
    const nav = document.querySelector('.glass-nav');
    if (nav) {
        const scrollHandler = () => {
            if (window.scrollY > 20) {
                nav.classList.add('scrolled');
            } else {
                nav.classList.remove('scrolled');
            }
        };
        window.addEventListener('scroll', scrollHandler);
        // 保存引用以便后续清理
        nav._scrollHandler = scrollHandler;
    }
    
    // Close dropdowns on outside click
    document.addEventListener('click', function(event) {
        const isUserMenu = event.target.closest('.user-menu');
        if (!isUserMenu) {
            closeAllDropdowns();
        }
        const navMenu = document.getElementById('navMenu');
        const menuToggle = event.target.closest('.menu-toggle');
        if (navMenu && !event.target.closest('#navMenu') && !menuToggle) {
            navMenu.classList.remove('show');
        }
    });
});

// Expose public API via namespace
window.ILink = {
    apiFetch: apiFetch,
    request: request,
    getCurrentUser: getCurrentUser,
    logout: logout,
    navigate: navigateWithTransition,
    showMessage: showMessage,
    showFieldHint: showFieldHint,
    clearFieldHint: clearFieldHint,
    formatTime: formatTime,
    ParticleSystem: ParticleSystem,
    ScrollAnimator: ScrollAnimator,
    NumberCounter: NumberCounter
};

window.ParticleSystem = ParticleSystem;
window.ScrollAnimator = ScrollAnimator;
window.NumberCounter = NumberCounter;
