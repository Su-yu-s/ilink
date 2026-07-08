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
            rootMargin: '0px 0px -30px 0px',
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
        // Staggered 序列：同容器内按 DOM 顺序依次 delay
        const containers = document.querySelectorAll('[data-stagger]');
        containers.forEach(container => {
            const items = container.querySelectorAll('.scroll-animate');
            items.forEach((el, index) => {
                el.style.transitionDelay = `${index * 0.06}s`;
                this.observer.observe(el);
            });
        });

        // 非 staggered 的独立元素
        document.querySelectorAll('.scroll-animate').forEach(el => {
            if (el.closest('[data-stagger]')) return; // 已处理
            if (el.dataset.animateDelay) {
                el.style.transitionDelay = el.dataset.animateDelay + 's';
            }
            this.observer.observe(el);
        });
    }

    showAllElements() {
        document.querySelectorAll('.scroll-animate').forEach(el => el.classList.add('animate-in'));
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
// Lightweight navigation helper
// ========================================
function navigateTo(href) {
    if (!href) return;
    closeAllDropdowns();
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
    // Particle system — only if canvas exists
    if (document.getElementById('particleCanvas')) {
        new ParticleSystem('particleCanvas', window.ILINK_PARTICLE_OPTIONS);
    }
    
    // Scroll animations
    new ScrollAnimator();
    
    // Number counters
    initCounters();
    
    // Magnetic buttons
    initMagneticButtons();
    // 页面跳转保持原生行为：只标记入场状态，不增加预取或进度条。
    document.body.classList.add('page-entered');
    
    // Tilt cards
    initTiltCards();
    
    // Ripple buttons
    initRippleButtons();
    
    // Smooth scroll
    initSmoothScroll();
    
    // Navbar progressive scroll effect（0→1 进度驱动 CSS 变量）
    const header = document.getElementById('ilHeader');
    if (header) {
        let headerTicking = false;
        const SCROLL_RANGE = 60; // 0～60px 映射到 0～1 进度
        const updateHeader = () => {
            const progress = Math.min(window.scrollY / SCROLL_RANGE, 1);
            header.style.setProperty('--header-scroll', progress.toFixed(3));
            header.classList.toggle('il-header--scrolled', window.scrollY > 10);
            headerTicking = false;
        };
        window.addEventListener('scroll', () => {
            if (!headerTicking) {
                window.requestAnimationFrame(updateHeader);
                headerTicking = true;
            }
        }, { passive: true });
        updateHeader(); // 初始化状态
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
    navigate: navigateTo,
    showMessage: showMessage,
    showFieldHint: showFieldHint,
    clearFieldHint: clearFieldHint,
    formatTime: formatTime,
    teamStatusLabel: teamStatusLabel,
    CATEGORY_LABELS: CATEGORY_LABELS,
    ParticleSystem: ParticleSystem,
    ScrollAnimator: ScrollAnimator,
    NumberCounter: NumberCounter
};

window.ParticleSystem = ParticleSystem;
window.ScrollAnimator = ScrollAnimator;
window.NumberCounter = NumberCounter;
