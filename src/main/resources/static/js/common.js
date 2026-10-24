// iLink JavaScript - Core Utilities
// ========================================

// API基础URL
const API_BASE_URL = '/api';

function buildLoginUrl() {
    const returnTo = window.location.pathname + window.location.search + window.location.hash;
    if (!returnTo.startsWith('/') || returnTo.startsWith('//') || returnTo.startsWith('/login')) {
        return '/login.html';
    }
    return '/login.html?redirect=' + encodeURIComponent(returnTo);
}

function redirectToLogin(delayMs) {
    window.setTimeout(function () {
        window.location.assign(buildLoginUrl());
    }, Math.max(0, Number(delayMs) || 0));
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/**
 * Shared helpers for every upload entry point. New upload APIs return metadata;
 * normalizeUploadResult also accepts the former string response during rollout.
 */
window.ILinkFiles = window.ILinkFiles || {
    normalizeUploadResult: function (data, fallbackFile) {
        var raw = data && typeof data === 'object' ? data : { url: data };
        var fallbackName = fallbackFile && fallbackFile.name ? String(fallbackFile.name) : '附件';
        return {
            url: raw && raw.url ? String(raw.url) : '',
            name: raw && (raw.originalName || raw.name)
                ? String(raw.originalName || raw.name)
                : fallbackName,
            size: Number(raw && raw.size != null ? raw.size : (fallbackFile && fallbackFile.size)) || 0,
            contentType: raw && raw.contentType
                ? String(raw.contentType)
                : String((fallbackFile && fallbackFile.type) || 'application/octet-stream')
        };
    },

    formatSize: function (bytes) {
        var size = Number(bytes) || 0;
        if (size <= 0) return '';
        if (size < 1024) return size + ' B';
        if (size < 1024 * 1024) return (size / 1024).toFixed(size < 10 * 1024 ? 1 : 0) + ' KB';
        return (size / (1024 * 1024)).toFixed(size < 10 * 1024 * 1024 ? 1 : 0) + ' MB';
    },

    extension: function (name) {
        var clean = String(name || '').split(/[?#]/)[0];
        var match = clean.match(/\.([^.\\/]+)$/);
        return match ? match[1].slice(0, 5).toUpperCase() : 'FILE';
    },

    comparableName: function (name) {
        var value = String(name || '').trim();
        if (typeof value.normalize === 'function') value = value.normalize('NFC');
        return value.toLocaleLowerCase('zh-CN');
    },

    findDuplicate: function (items, name) {
        var expected = this.comparableName(name);
        if (!expected || !Array.isArray(items)) return null;
        return items.find(function (item) {
            return item && window.ILinkFiles.comparableName(item.name) === expected;
        }) || null;
    },

    friendlyDownloadName: function (name, url, fallbackTitle) {
        var value = String(name || '').trim();
        if (value && value !== '附件') return value;
        var cleanUrl = String(url || '').split(/[?#]/)[0];
        var match = cleanUrl.match(/\.([^.\\/]+)$/);
        var suffix = match ? '.' + match[1].toLowerCase() : '';
        return String(fallbackTitle || '附件').trim() + suffix;
    },

    /** 成果按分类的内置封面图（未上传封面时的兜底） */
    categoryCovers: {
        '竞赛获奖': 'https://images.unsplash.com/photo-1546422904-90eab23c3d7e?w=400&h=280&fit=crop&auto=format',
        '论文发表': 'https://images.unsplash.com/photo-1456513080510-7bf3a84b82f8?w=400&h=280&fit=crop&auto=format',
        '科研项目': 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=400&h=280&fit=crop&auto=format',
        '作品项目': 'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?w=400&h=280&fit=crop&auto=format',
        '作品 / 项目': 'https://images.unsplash.com/photo-1517694712202-14dd9538aa97?w=400&h=280&fit=crop&auto=format',
        '技术创新': 'https://images.unsplash.com/photo-1518770660439-4636190af475?w=400&h=280&fit=crop&auto=format',
        '荣誉称号': 'https://images.unsplash.com/photo-1523580494863-6f3031224c94?w=400&h=280&fit=crop&auto=format',
        '奖学金': 'https://images.unsplash.com/photo-1523050854058-8df90110c9f1?w=400&h=280&fit=crop&auto=format',
        '其他': 'https://images.unsplash.com/photo-1513542789411-b6a5d4f31634?w=400&h=280&fit=crop&auto=format',
        fallback: 'https://images.unsplash.com/photo-1555066931-4365d14bab8c?w=400&h=280&fit=crop&auto=format'
    },

    /**
     * 取成果的分类兜底封面。调用方应优先用成果自身的 coverUrl，
     * 只有没上传过封面时才回退到这里，保证卡片永远有图。
     */
    coverForCategory: function (category) {
        var covers = this.categoryCovers;
        var key = String(category == null ? '' : category).trim();
        return covers[key] || covers.fallback;
    },

    iconMarkup: function (name) {
        return '<span class="il-upload-file-icon" aria-hidden="true">' +
            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' +
            '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>' +
            '<path d="M14 2v6h6"/>' +
            '</svg><small>' + escapeHtml(this.extension(name)) + '</small></span>';
    }
};

let configuredMarkedInstance = null;

/** 编辑预览与公开详情页必须共用同一套 Markdown 解析选项。 */
function configureMarkdownRenderer() {
    if (typeof marked === 'undefined') return false;
    if (configuredMarkedInstance === marked) return true;
    marked.setOptions({ gfm: true, breaks: true, pedantic: false });
    configuredMarkedInstance = marked;
    return true;
}

function encodeMarkdownSource(raw) {
    const text = raw == null ? '' : String(raw);
    try {
        const bytes = new TextEncoder().encode(text);
        let binary = '';
        bytes.forEach(byte => { binary += String.fromCharCode(byte); });
        return btoa(binary);
    } catch (_) {
        return btoa(unescape(encodeURIComponent(text)));
    }
}

function decodeMarkdownSource(payload) {
    const clean = String(payload || '').replace(/[^A-Za-z0-9+/=]/g, '');
    if (!clean) return '';
    try {
        const binary = atob(clean);
        const bytes = Uint8Array.from(binary, char => char.charCodeAt(0));
        return new TextDecoder('utf-8').decode(bytes);
    } catch (_) {
        try { return decodeURIComponent(escape(atob(clean))); } catch (_) { return ''; }
    }
}

/**
 * Markdown 源码以不可见 data 属性保存；该结构能通过服务端白名单清洗。
 * 同时继续识别历史 <!--md:...--> 数据。
 */
function markdownSourcePayload(raw) {
    const text = raw == null ? '' : String(raw);
    const elementMatch = text.match(/<span\b[^>]*\bdata-ilink-markdown=["']([A-Za-z0-9+/=|]+)["'][^>]*><\/span>/i);
    if (elementMatch) return elementMatch[1];
    const commentMatch = text.match(/<!--md:([A-Za-z0-9+/=|]+)-->/);
    return commentMatch ? commentMatch[1] : '';
}

function stripMarkdownSourceMarker(raw) {
    return String(raw == null ? '' : raw)
        .replace(/<span\b[^>]*\bdata-ilink-markdown=["'][A-Za-z0-9+/=|]+["'][^>]*><\/span>/ig, '')
        .replace(/<!--md:[A-Za-z0-9+/=|]+-->/g, '')
        .trim();
}

function markdownSourceMarker(payload) {
    const safe = String(payload || '').replace(/[^A-Za-z0-9+/=|]/g, '');
    return safe
        ? '<span class="ilink-markdown-source" data-ilink-markdown="' + safe + '" hidden aria-hidden="true"></span>'
        : '';
}

function markdownToSafeHtml(raw) {
    const text = raw == null ? '' : String(raw);
    if (!configureMarkdownRenderer()) return escapeHtml(text).replace(/\n/g, '<br>');
    try {
        const html = marked.parse(text);
        return typeof DOMPurify !== 'undefined'
            ? DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
            : escapeHtml(text).replace(/\n/g, '<br>');
    } catch (_) {
        return escapeHtml(text).replace(/\n/g, '<br>');
    }
}

/** 将 Markdown 写入元素；预览页和详情页均从这里渲染。 */
function renderMarkdownSafe(el, raw) {
    if (!el) return;
    el.innerHTML = markdownToSafeHtml(raw);
}

/**
 * 判断头像地址是否值得发起请求：仅站内受管上传目录（相对 /uploads/，
 * 或与本页同源的绝对 /uploads/ 地址）。存量脏数据中的外链假地址不再渲染，
 * 避免产生加载失败的控制台错误与破图。
 */
function isRenderableAvatarUrl(avatar) {
    const v = avatar == null ? '' : String(avatar).trim();
    if (!v) return false;
    if (v.startsWith('/uploads/')) return true;
    if (v.startsWith('/images/avatars/')) return true;
    try {
        const url = new URL(v, window.location.origin);
        return url.origin === window.location.origin
            && (url.pathname.startsWith('/uploads/') || url.pathname.startsWith('/images/avatars/'));
    } catch (e) {
        return false;
    }
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
    const configuredTimeout = Number(options.timeoutMs);
    const timeoutMs = Number.isFinite(configuredTimeout)
        ? Math.max(0, configuredTimeout)
        : 15000;
    const fetchOptions = { ...options };
    delete fetchOptions.timeoutMs;
    if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
        const token = getCsrfToken();
        if (token) {
            headers['X-XSRF-TOKEN'] = token;
        }
    }

    const controller = !fetchOptions.signal && timeoutMs > 0 && typeof AbortController !== 'undefined'
        ? new AbortController()
        : null;
    let timeoutId = null;
    if (controller) {
        timeoutId = window.setTimeout(function () {
            controller.abort();
        }, timeoutMs);
    }

    try {
        return await fetch(url, {
            credentials: 'same-origin',
            ...fetchOptions,
            signal: controller ? controller.signal : fetchOptions.signal,
            headers
        });
    } catch (error) {
        if (controller && controller.signal.aborted) {
            const timeoutError = new Error('请求超时，请稍后重试');
            timeoutError.name = 'TimeoutError';
            throw timeoutError;
        }
        throw error;
    } finally {
        if (timeoutId !== null) window.clearTimeout(timeoutId);
    }
}

/**
 * 统一的业务 API 请求封装。
 *
 * 页面脚本传入的 url 以 /api 为基准，成功时直接返回响应中的 data；
 * 失败时抛出 Error，并在非 silent 模式下给出统一提示。
 */
async function request(url, options = {}) {
    const requestOptions = options || {};
    const silent = requestOptions.silent === true;
    const headers = { ...(requestOptions.headers || {}) };
    const config = { ...requestOptions, headers };
    delete config.silent;

    const hasContentType = Object.keys(headers).some(function (name) {
        return name.toLowerCase() === 'content-type';
    });
    const isFormData = typeof FormData !== 'undefined' && config.body instanceof FormData;
    if (!hasContentType && !isFormData) {
        headers['Content-Type'] = 'application/json';
    }

    const path = String(url == null ? '' : url);
    const requestUrl = path === API_BASE_URL || path.startsWith(API_BASE_URL + '/')
        ? path
        : API_BASE_URL + (path.startsWith('/') ? path : '/' + path);

    try {
        const response = await apiFetch(requestUrl, config);
        const contentType = response.headers.get('content-type') || '';
        let data;

        if (contentType.includes('application/json')) {
            data = await response.json();
        } else {
            const raw = await response.text();
            try {
                data = raw ? JSON.parse(raw) : null;
            } catch (parseError) {
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

                const error = new Error(userMessage);
                error._ilinkHandled = true;
                if (!silent) {
                    showMessage(userMessage, messageType);
                    if (shouldRedirectLogin) {
                        setTimeout(function () {
                            window.location.assign(buildLoginUrl());
                        }, 1200);
                    }
                }
                throw error;
            }
        }

        const code = data && Number(data.code);
        if (!response.ok || code !== 200) {
            let userMessage = (data && data.message) || '请求失败';
            let messageType = 'error';
            let shouldRedirectLogin = false;

            switch (code || response.status) {
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
                    break;
            }

            if (!silent) {
                showMessage(userMessage, messageType);
                if (shouldRedirectLogin) {
                    setTimeout(function () {
                        window.location.assign(buildLoginUrl());
                    }, 1500);
                }
            }

            const error = new Error(userMessage);
            error._ilinkHandled = true;
            error.response = data;
            error.status = response.status;
            error.errorCode = data && ((data.extra && data.extra.errorCode) || data.errorCode);
            throw error;
        }

        return data.data;
    } catch (error) {
        if (error && error._ilinkHandled) {
            throw error;
        }
        if (!silent && error && error.name === 'TypeError' && String(error.message).includes('fetch')) {
            showMessage(t('common.networkError'), 'error');
        } else if (!silent) {
            showMessage((error && error.message) || t('common.error'), 'error');
        }
        throw error;
    }
}

// 获取当前用户信息；未登录或接口异常时返回 null，便于页面安全降级。
async function getCurrentUser() {
    try {
        return await request('/user/profile', { silent: true });
    } catch (error) {
        console.error('获取用户信息失败:', error);
        return null;
    }
}

// 头部渲染时把登录用户写进 data-current-user-id；空值即匿名。
// 用它做前置判断，可以避免匿名访客在每个公共页都发一次必然 401 的资料请求（核实报告 P2-6）。
function currentUserIdFromHeader() {
    const header = document.getElementById('ilHeader');
    if (!header) return null;
    const value = header.dataset ? header.dataset.currentUserId : header.getAttribute('data-current-user-id');
    return value ? String(value) : null;
}

/** 头部是否明确标注为匿名访客（未登录） */
function isAnonymousVisitor() {
    return document.getElementById('ilHeader') !== null && currentUserIdFromHeader() === null;
}

let _currentUserPromise = null;

/**
 * 页面级「取当前用户」入口：登录态下复用同一次请求（团队详情页此前会重复请求两次），
 * 匿名时直接返回 null，不发请求、不产生控制台 401。
 * 与 getCurrentUser() 的区别：本函数不会抛错，适合在页面初始化里 await。
 */
async function loadCurrentUserOnce() {
    if (isAnonymousVisitor()) return null;
    if (!_currentUserPromise) {
        _currentUserPromise = getCurrentUser().catch(function () { return null; });
    }
    return _currentUserPromise;
}

// 用户登出
async function logout() {
    try {
        await apiFetch('/api/logout', { method: 'POST' });
    } catch (error) {
        console.error('登出请求异常:', error);
    } finally {
        window.location.assign('/login.html');
    }
}

// 格式化时间
function formatTime(timestamp) {
    if (!timestamp) return '未知时间';

    const date = new Date(timestamp);
    const language = String(document.documentElement.lang || 'zh-CN').toLowerCase().startsWith('en') ? 'en-US' : 'zh-CN';
    const formatter = new Intl.DateTimeFormat(language, {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false
    });

    return formatter.format(date);
}

const MOBILE_NAV_MEDIA = '(max-width: 767.98px)';
let mobileNavReturnFocus = null;

function isMobileNavigation() {
    return !!(window.matchMedia && window.matchMedia(MOBILE_NAV_MEDIA).matches);
}

function setMobileMenuOpen(open, options) {
    const menu = document.getElementById('ilHeaderNav');
    const button = document.getElementById('menuToggle');
    const overlay = document.getElementById('ilHeaderOverlay');
    if (!menu) return;

    const mobile = isMobileNavigation();
    const nextOpen = mobile && !!open;
    const restoreFocus = !options || options.restoreFocus !== false;

    if (nextOpen) {
        mobileNavReturnFocus = document.activeElement;
    }

    menu.classList.toggle('il-header__nav--open', nextOpen);
    document.body.classList.toggle('nav-open', nextOpen);
    if (mobile) {
        menu.setAttribute('aria-hidden', String(!nextOpen));
        if (nextOpen) menu.removeAttribute('inert');
        else menu.setAttribute('inert', '');
    } else {
        menu.removeAttribute('aria-hidden');
        menu.removeAttribute('inert');
    }
    if (overlay) overlay.setAttribute('tabindex', nextOpen ? '0' : '-1');
    if (button) {
        button.setAttribute('aria-expanded', String(nextOpen));
        button.setAttribute('aria-label', nextOpen ? t('header.drawer.close') : t('header.drawer.open'));
    }

    if (nextOpen) {
        window.requestAnimationFrame(function () {
            const closeButton = document.getElementById('menuClose');
            if (closeButton) closeButton.focus({ preventScroll: true });
        });
    } else if (restoreFocus && mobileNavReturnFocus && typeof mobileNavReturnFocus.focus === 'function') {
        mobileNavReturnFocus.focus({ preventScroll: true });
        mobileNavReturnFocus = null;
    }
}

// 兼容旧页面与当前统一页头的菜单契约。
function toggleMobileMenu() {
    const legacyMenu = document.getElementById('navMenu');
    if (legacyMenu) {
        legacyMenu.classList.toggle('show');
        return;
    }

    const menu = document.getElementById('ilHeaderNav');
    if (!menu) return;
    setMobileMenuOpen(!menu.classList.contains('il-header__nav--open'));
}

function toggleNotifications() {
    const dropdown = document.getElementById('notificationDropdown');
    const button = document.getElementById('notificationBell');
    if (!dropdown) return;
    const open = dropdown.classList.contains('notification-dropdown--hidden');
    if (open) closeAllDropdowns();
    dropdown.classList.toggle('notification-dropdown--hidden', !open);
    dropdown.classList.toggle('notification-dropdown--open', open);
    if (button) button.setAttribute('aria-expanded', String(open));
    if (open && window.notificationManager) window.notificationManager.loadNotifications();
}

function markAllAsRead() {
    if (window.notificationManager) window.notificationManager.markAllAsRead();
}

function toggleUserMenu() {
    const dropdown = document.getElementById('userDropdown');
    if (!dropdown) return;

    const legacyWrap = document.querySelector('.user-menu');
    const legacyButton = document.getElementById('accountMenuBtn');
    if (legacyWrap) {
        if (!dropdown.classList.contains('show')) closeNotifications();
        dropdown.classList.toggle('show');
        const open = dropdown.classList.contains('show');
        legacyWrap.classList.toggle('is-open', open);
        if (legacyButton) legacyButton.setAttribute('aria-expanded', String(open));
        return;
    }

    const button = document.getElementById('userDropdownToggle');
    if (!dropdown.classList.contains('il-header__dropdown--open')) closeNotifications();
    const open = dropdown.classList.toggle('il-header__dropdown--open');
    if (button) button.setAttribute('aria-expanded', String(open));
}

/**
 * 角色占位显示名。与后端 DisplayNameResolver 保持一致：
 * 用户没填姓名时按角色给中性占位，**绝不回退 username**（历史账号的 username 可能是手机号或学号）。
 */
function rolePlaceholderName(role) {
    const value = String(role || '').trim().toUpperCase();
    if (value === 'STUDENT') return '同学';
    if (value === 'TEACHER') return '老师';
    if (value === 'ADMIN') return '管理员';
    return '用户';
}

/** 右上角展示：优先后端解析好的 displayName，其次姓名，最后角色占位。 */
function computeNavbarDisplayName(user) {
    if (!user) return '用户';
    const displayName = String(user.displayName || '').trim();
    if (displayName) return displayName;
    const realName = String(user.realName || '').trim();
    return realName || rolePlaceholderName(user.role);
}

function computeNavbarInitials(displayName) {
    const value = String(displayName || '用户').trim();
    return value ? value.slice(0, 2).toUpperCase() : '用';
}

function accountTriggerTitleAttr(user) {
    if (!user) return '';
    return computeNavbarDisplayName(user);
}

function buildAccountAvatarInnerHtml(user) {
    const displayName = computeNavbarDisplayName(user);
    const initials = escapeHtml(computeNavbarInitials(displayName));
    const avatar = user && user.avatar ? String(user.avatar).trim() : '';
    if (isRenderableAvatarUrl(avatar)) {
        return '<span class="account-trigger__avatar-wrap"><img class="account-trigger__avatar account-trigger__avatar--photo" src="' + escapeHtml(avatar) + '" alt="" referrerpolicy="no-referrer"></span>';
    }
    return '<span class="account-trigger__avatar-wrap"><span class="account-trigger__avatar" aria-hidden="true">' + initials + '</span></span>';
}

function applyUnifiedHeaderUser(user) {
    const userLink = document.querySelector('.il-header__user-link');
    if (!userLink || !user) return;

    const displayName = computeNavbarDisplayName(user);
    const nameElement = userLink.querySelector('.il-header__username');
    if (nameElement) nameElement.textContent = displayName;
    userLink.setAttribute('title', accountTriggerTitleAttr(user));

    const container = userLink.querySelector('.il-header__avatar-container');
    if (!container) return;
    while (container.firstChild) container.removeChild(container.firstChild);

    const fallback = document.createElement('span');
    fallback.className = 'il-header__avatar-fallback';
    fallback.textContent = computeNavbarInitials(displayName).slice(0, 1);

    const avatar = user.avatar ? String(user.avatar).trim() : '';
    if (!isRenderableAvatarUrl(avatar)) {
        container.appendChild(fallback);
        return;
    }

    const image = document.createElement('img');
    image.className = 'il-header__avatar';
    image.src = avatar;
    image.alt = displayName;
    image.referrerPolicy = 'no-referrer';
    fallback.classList.add('il-header__avatar-fallback--hidden');
    fallback.setAttribute('aria-hidden', 'true');
    image.addEventListener('load', function () {
        fallback.classList.add('il-header__avatar-fallback--hidden');
        fallback.setAttribute('aria-hidden', 'true');
    });
    image.addEventListener('error', function () {
        image.style.display = 'none';
        fallback.classList.remove('il-header__avatar-fallback--hidden');
        fallback.removeAttribute('aria-hidden');
    });
    container.appendChild(image);
    container.appendChild(fallback);
}

function applyAccountMenuFromUser(user) {
    if (!user) return;

    const button = document.getElementById('accountMenuBtn');
    if (button) {
        const nameElement = button.querySelector('.account-trigger__name');
        const avatarWrap = button.querySelector('.account-trigger__avatar-wrap');
        const displayName = computeNavbarDisplayName(user);
        if (nameElement) nameElement.textContent = displayName;
        if (avatarWrap) avatarWrap.outerHTML = buildAccountAvatarInnerHtml(user);
        button.setAttribute('title', accountTriggerTitleAttr(user));
    }

    applyUnifiedHeaderUser(user);
}

function closeAllDropdowns() {
    document.querySelectorAll('.dropdown-menu.show').forEach(function (menu) {
        menu.classList.remove('show');
    });
    document.querySelectorAll('.user-menu.is-open').forEach(function (menu) {
        menu.classList.remove('is-open');
    });

    const legacyButton = document.getElementById('accountMenuBtn');
    if (legacyButton) legacyButton.setAttribute('aria-expanded', 'false');

    const dropdown = document.getElementById('userDropdown');
    if (dropdown) dropdown.classList.remove('il-header__dropdown--open');
    const dropdownButton = document.getElementById('userDropdownToggle');
    if (dropdownButton) dropdownButton.setAttribute('aria-expanded', 'false');
}

function closeNotifications() {
    const dropdown = document.getElementById('notificationDropdown');
    const button = document.getElementById('notificationBell');
    if (dropdown) {
        dropdown.classList.remove('notification-dropdown--open');
        dropdown.classList.add('notification-dropdown--hidden');
    }
    if (button) button.setAttribute('aria-expanded', 'false');
}

function initUnifiedHeader() {
    const header = document.getElementById('ilHeader');
    if (!header || header.dataset.interactionsBound === '1') return;
    header.dataset.interactionsBound = '1';

    const menu = document.getElementById('ilHeaderNav');
    const menuToggle = document.getElementById('menuToggle');
    const menuClose = document.getElementById('menuClose');
    const overlay = document.getElementById('ilHeaderOverlay');
    const userDropdown = document.getElementById('userDropdown');
    const userDropdownToggle = document.getElementById('userDropdownToggle');
    const notificationBell = document.getElementById('notificationBell');
    const notificationDropdown = document.getElementById('notificationDropdown');
    const markAllButton = document.getElementById('markAllNotificationsRead');

    const syncNavigationMode = function () {
        setMobileMenuOpen(false, { restoreFocus: false });
    };

    if (menuToggle) {
        menuToggle.addEventListener('click', function (event) {
            event.stopPropagation();
            toggleMobileMenu();
        });
    }
    if (menuClose) menuClose.addEventListener('click', function () { setMobileMenuOpen(false); });
    if (overlay) overlay.addEventListener('click', function () { setMobileMenuOpen(false); });
    if (menu) {
        menu.addEventListener('click', function (event) {
            if (isMobileNavigation() && event.target.closest('a[href]')) {
                setMobileMenuOpen(false, { restoreFocus: false });
            }
        });
    }

    if (userDropdownToggle && userDropdown) {
        userDropdownToggle.addEventListener('click', function (event) {
            event.stopPropagation();
            toggleUserMenu();
        });
    }
    if (notificationBell) {
        notificationBell.addEventListener('click', function (event) {
            event.stopPropagation();
            toggleNotifications();
        });
    }
    if (markAllButton) markAllButton.addEventListener('click', markAllAsRead);

    document.addEventListener('click', function (event) {
        if (userDropdown && !event.target.closest('#userDropdown')) closeAllDropdowns();
        if (notificationDropdown &&
            !event.target.closest('#notificationDropdown') &&
            !event.target.closest('#notificationBell')) {
            closeNotifications();
        }
    });

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            if (menu && menu.classList.contains('il-header__nav--open')) setMobileMenuOpen(false);
            closeAllDropdowns();
            closeNotifications();
            return;
        }

        if (event.key !== 'Tab' || !menu || !menu.classList.contains('il-header__nav--open')) return;
        const focusable = Array.from(menu.querySelectorAll(
            'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])'
        )).filter(function (element) {
            return element.getClientRects().length > 0;
        });
        if (!focusable.length) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    });

    if (window.matchMedia) {
        const media = window.matchMedia(MOBILE_NAV_MEDIA);
        if (typeof media.addEventListener === 'function') media.addEventListener('change', syncNavigationMode);
        else if (typeof media.addListener === 'function') media.addListener(syncNavigationMode);
    }
    syncNavigationMode();

    const currentUserId = header.dataset.currentUserId;
    if (currentUserId && notificationBell) {
        notificationBell.classList.remove('notification-bell--hidden');
        notificationBell.classList.add('notification-bell--visible');
        if (typeof NotificationManager !== 'undefined' && !window.notificationManager) {
            window.notificationManager = new NotificationManager(currentUserId);
            window.notificationManager.init();
        }
    }
}

// 类型归一化：danger 是历史别名，统一映射到 error；缺省或未知类型回落 info
function normalizeToastType(type) {
    return type === 'danger' ? 'error' : (type || 'info');
}

// ========================================
// Toast — 顶部居中悬浮提示（2026-10-03 定稿，基准 demo.html）
// · 五种状态：success / error / warning / info / loading
// · 双行结构：标题必填，描述可选
// · 默认 3 秒自动消失，可手动关闭；loading 不自动关闭，用返回的 close() 结束
// · 进入 .32s 弹性落位，离开 .22s 快速收起
// 用法：Toast.success('登录成功', '正在进入首页…')
//       const close = Toast.loading('上传中…'); close()
// ========================================
const Toast = (function () {
    // 每条提示的展示时长；0 表示不自动关闭
    const DEFAULT_DURATION = 3000;
    // 与 CSS 里 .ilink-toast.hiding 的 0.22s 离场动画保持一致
    const EXIT_MS = 220;
    // 同时最多显示条数：超出时移除最旧的一条，避免连续操作刷屏
    const MAX_STACK = 3;
    // 同一类型 + 同一文案在此时长内重复触发会被忽略
    const DEDUPE_MS = 2200;

    const TYPE_ICONS = {
        success: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M4.5 12.75l6 6 9-13.5"/></svg>',
        error: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg>',
        warning: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 9v4M12 17h.01"/><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/></svg>',
        info: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 16v-4M12 8h.01"/><circle cx="12" cy="12" r="10"/></svg>',
        loading: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 11-6.219-8.56"/></svg>'
    };

    const CLOSE_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6L6 18M6 6l12 12"/></svg>';

    // 防重复刷屏用的「上次提示」状态
    let lastKey = '';
    let lastAt = 0;

    /**
     * 兜底：若只更新到新 common.js、页面仍在用旧版样式表（缓存场景），
     * 就地补上容器定位所需的最小内联样式，避免提示看不见。
     */
    let legacyStylesApplied = false;
    function applyLegacyStyles(host) {
        if (legacyStylesApplied) return;
        legacyStylesApplied = true;
        host.style.cssText += ';position:fixed;left:50%;transform:translateX(-50%);display:flex;'
            + 'flex-direction:column;align-items:center;gap:10px;pointer-events:none;';
        host.style.top = '76px';
        host.style.zIndex = '10050';
        host.style.width = '380px';
        host.style.maxWidth = 'calc(100vw - 32px)';
    }

    function ensureHost() {
        let host = document.getElementById('ilinkToastHost');
        const created = !host;
        if (created) {
            host = document.createElement('div');
            host.id = 'ilinkToastHost';
            host.className = 'ilink-toast-host';
            host.setAttribute('aria-live', 'polite');
            document.body.appendChild(host);
        }
        // 新版样式表缺失时（页面缓存/样式未重新加载）用内联样式兜底
        const styles = window.getComputedStyle ? window.getComputedStyle(host) : null;
        if (!styles || styles.position !== 'fixed') applyLegacyStyles(host);
        return host;
    }

    /**
     * 关闭一条提示。手动关闭、自动关闭、Toast.loading 返回的 close() 都走这里。
     */
    function close(el) {
        if (!el || el.classList.contains('hiding')) return;
        el.classList.add('hiding');
        if (el._toastTimer) {
            clearTimeout(el._toastTimer);
            el._toastTimer = null;
        }
        setTimeout(function () {
            if (el.parentNode) el.parentNode.removeChild(el);
        }, EXIT_MS);
    }

    /** 立即移除（不播离场动画），仅用于栈溢出与"最旧一条"清理 */
    function removeNow(el) {
        if (!el) return;
        if (el._toastTimer) {
            clearTimeout(el._toastTimer);
            el._toastTimer = null;
        }
        if (el.parentNode) el.parentNode.removeChild(el);
    }

    function show(options) {
        const opts = options || {};
        const type = normalizeToastType(opts.type);
        const title = String(opts.title == null ? '' : opts.title).trim();
        const desc = String(opts.desc == null ? '' : opts.desc).trim();
        if (!title) return function () {};

        const duration = typeof opts.duration === 'number' ? opts.duration : DEFAULT_DURATION;

        // 同一类型 + 同一文案短时间内重复触发时不再叠加（批量操作常会连发同一条）
        const key = type + ':' + title;
        const now = Date.now();
        if (key === lastKey && now - lastAt < DEDUPE_MS) return function () {};
        lastKey = key;
        lastAt = now;

        const host = ensureHost();
        while (host.children.length >= MAX_STACK) {
            removeNow(host.firstChild);
        }

        const el = document.createElement('div');
        el.className = 'ilink-toast ilink-toast--' + type;
        el.setAttribute('role', type === 'error' || type === 'warning' ? 'alert' : 'status');
        el.setAttribute('aria-live', type === 'error' || type === 'warning' ? 'assertive' : 'polite');

        const icon = document.createElement('div');
        icon.className = 'ilink-toast__icon';
        icon.setAttribute('aria-hidden', 'true');
        icon.innerHTML = TYPE_ICONS[type] || TYPE_ICONS.info;

        const body = document.createElement('div');
        body.className = 'ilink-toast__body';

        const titleEl = document.createElement('div');
        titleEl.className = 'ilink-toast__title';
        titleEl.textContent = title;
        body.appendChild(titleEl);

        if (desc) {
            const descEl = document.createElement('div');
            descEl.className = 'ilink-toast__desc';
            descEl.textContent = desc;
            body.appendChild(descEl);
        }

        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'ilink-toast__close';
        closeBtn.setAttribute('aria-label', typeof t === 'function' ? t('toast.close') : '关闭提示');
        closeBtn.innerHTML = CLOSE_ICON;
        closeBtn.addEventListener('click', function () {
            close(el);
        });

        el.appendChild(icon);
        el.appendChild(body);
        el.appendChild(closeBtn);
        host.appendChild(el);

        if (duration > 0) {
            el._toastTimer = setTimeout(function () {
                close(el);
            }, duration);
        }

        return function () {
            close(el);
        };
    }

    function api(type, title, desc, duration) {
        return show({ type: type, title: title, desc: desc, duration: duration });
    }

    return {
        show: show,
        close: close,
        success: function (title, desc, duration) { return api('success', title, desc, duration); },
        error: function (title, desc, duration) { return api('error', title, desc, duration); },
        warning: function (title, desc, duration) { return api('warning', title, desc, duration); },
        info: function (title, desc, duration) { return api('info', title, desc, duration); },
        // 加载态不自动关闭，由调用方使用返回的 close()
        loading: function (title, desc) { return api('loading', title, desc, 0); }
    };
})();

/**
 * 全局轻提示（向后兼容入口，勿用于字段级 blur 校验）
 * 消息文本即标题；具体原因放到 Toast.xxx(title, desc) 的 desc 第二行。
 */
function showMessage(message, type, durationMs) {
    const text = String(message != null ? message : '').trim();
    if (!text) return;
    Toast.show({ type: type === undefined ? 'info' : type, title: text, duration: durationMs });
}

/**
 * 表单字段内联提示（登录/注册等），不打断用户输入
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

function getTypeClass(type) {
    switch (normalizeToastType(type)) {
        case 'success': return 'success';
        case 'error': return 'danger';
        case 'warning': return 'warning';
        default: return 'info';
    }
}

function publicProfilePageUrl(userId) {
    if (userId == null || userId === '') return '#';
    return '/user-profile.html?id=' + encodeURIComponent(String(userId));
}

/**
 * 卡片/详情展示名。后端在 userPreview 里已经解析好 displayName，这里优先用它；
 * 老数据没有该字段时按 realName → 角色占位 的顺序回退。**不再回退 username**。
 */
function displayUserName(preview) {
    if (!preview) return '';
    const displayName = preview.displayName ? String(preview.displayName).trim() : '';
    if (displayName) return displayName;
    const realName = preview.realName ? String(preview.realName).trim() : '';
    if (realName) return realName;
    return rolePlaceholderName(preview.role);
}

/** 成果页面展示名；与列表口径一致，避免同一用户在列表和详情显示不同名称。 */
function displayUsername(preview) {
    if (!preview) return '匿名';
    return displayUserName(preview) || '匿名';
}

function galleryAvatarInitial(preview) {
    if (!preview) return '';
    const initial = preview.avatarInitial ? String(preview.avatarInitial).trim() : '';
    if (initial) return initial.charAt(0).toUpperCase();
    const displayName = displayUserName(preview);
    return displayName ? displayName.charAt(0).toUpperCase() : '';
}

function galleryPublisherAvatarHtml(preview, extraClass) {
    if (!preview || preview.id == null) return '';
    const displayName = displayUsername(preview);
    const initials = galleryAvatarInitial(preview);
    const nameEscaped = escapeHtml(displayName);
    const href = escapeHtml(publicProfilePageUrl(preview.id));
    const avatar = preview.avatar ? String(preview.avatar).trim() : '';
    const className = 'il-publisher-avatar' + (extraClass ? ' ' + extraClass : '');
    const wrapStyle = 'width:40px;height:40px;min-width:40px;min-height:40px;max-width:40px;max-height:40px;border-radius:50%;overflow:hidden;display:block;flex-shrink:0;';
    const imageStyle = 'width:100%;height:100%;object-fit:cover;display:block;border-radius:50%;';

    if (avatar) {
        return '<a href="' + href + '" class="' + className + '" style="display:inline-flex;flex-shrink:0;border-radius:50%;overflow:hidden;" title="查看 ' + nameEscaped + ' 的主页" aria-label="查看 TA 的个人主页">' +
            '<div class="il-avatar-wrap" style="' + wrapStyle + '">' +
            '<img class="il-publisher-avatar__img" style="' + imageStyle + '" src="' + escapeHtml(avatar) + '" alt="" referrerpolicy="no-referrer" loading="lazy" width="40" height="40" onerror="this.onerror=null;var fallback=this.nextElementSibling;this.remove();if(fallback){fallback.classList.remove(\'il-avatar-fallback--hidden\');fallback.removeAttribute(\'aria-hidden\');}">' +
            '<span class="il-avatar-fallback il-avatar-fallback--hidden" aria-hidden="true" style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;border-radius:50%;">' + escapeHtml(initials || '?') + '</span>' +
            '</div></a>';
    }

    return '<a href="' + href + '" class="' + className + '" style="display:inline-flex;flex-shrink:0;border-radius:50%;overflow:hidden;" title="查看 ' + nameEscaped + ' 的主页" aria-label="查看 TA 的个人主页">' +
        '<div class="il-avatar-wrap" style="' + wrapStyle + '">' +
        '<span class="il-avatar-fallback" style="width:100%;height:100%;display:flex;align-items:center;justify-content:center;border-radius:50%;">' + escapeHtml(initials || '?') + '</span>' +
        '</div></a>';
}

function hideGalleryPublisherAvatarFallbacks(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll('.il-publisher-avatar img.il-publisher-avatar__img').forEach(function (image) {
        if (!image.getAttribute('src') || image.style.display === 'none') return;
        const wrap = image.closest('.il-avatar-wrap');
        if (!wrap) return;
        wrap.querySelectorAll('.il-avatar-fallback').forEach(function (fallback) {
            fallback.classList.add('il-avatar-fallback--hidden');
            fallback.setAttribute('aria-hidden', 'true');
        });
    });
}

function avatarInitial(preview) {
    const displayName = displayUserName(preview);
    return displayName ? displayName.charAt(0).toUpperCase() : '';
}

function publisherAvatarHtml(preview, extraClass) {
    if (!preview || preview.id == null) return '';
    const displayName = displayUserName(preview);
    const initials = avatarInitial(preview);
    const nameEscaped = escapeHtml(displayName || '用户');
    const href = escapeHtml(publicProfilePageUrl(preview.id));
    const avatar = preview.avatar ? String(preview.avatar).trim() : '';
    const className = 'publisher-avatar' + (extraClass ? ' ' + extraClass : '');
    const profileTitle = escapeHtml(t('ui.common.viewProfileTitle', { name: displayName }));
    const profileLabel = escapeHtml(t('ui.common.viewProfileLabel'));
    const profileLabelAttrs = 'title="' + profileTitle + '" aria-label="' + profileLabel + '"';

    if (avatar) {
        return '<a href="' + href + '" class="' + className + '" ' + profileLabelAttrs + '>' +
            '<img class="publisher-avatar__img" src="' + escapeHtml(avatar) + '" alt="" referrerpolicy="no-referrer" loading="lazy" onerror="this.onerror=null;this.style.display=\'none\';var s=this.nextElementSibling;if(s)s.classList.remove(\'d-none\');">' +
            '<span class="publisher-avatar__fallback d-none" aria-hidden="true">' + escapeHtml(initials || '?') + '</span></a>';
    }

    return '<a href="' + href + '" class="' + className + '" ' + profileLabelAttrs + '>' +
        '<span class="publisher-avatar__fallback" aria-hidden="true">' + escapeHtml(initials || '?') + '</span></a>';
}

function publisherAvatarFromAuthorFields(authorId, authorAvatar, authorDisplay) {
    if (authorId == null) return '';
    return publisherAvatarHtml({
        id: authorId,
        avatar: authorAvatar || '',
        displayName: authorDisplay || '',
        realName: authorDisplay || ''
    });
}

/** Localization helper. Missing keys intentionally fall back to the key itself. */
function t(key, params) {
    const dictionary = window.__I18N__ || {};
    let value = Object.prototype.hasOwnProperty.call(dictionary, key) ? String(dictionary[key]) : String(key);
    if (!Object.prototype.hasOwnProperty.call(dictionary, key) && !window.__I18N_MISSING__) {
        window.__I18N_MISSING__ = {};
    }
    if (!Object.prototype.hasOwnProperty.call(dictionary, key) && !window.__I18N_MISSING__[key]) {
        window.__I18N_MISSING__[key] = true;
        if (window.console && console.warn) console.warn('[i18n] Missing message key:', key);
    }
    if (params && typeof params === 'object') {
        Object.keys(params).forEach(function (name) {
            value = value.split('{' + name + '}').join(String(params[name]));
        });
    }
    return value;
}

/**
 * 翻译脚本里写死的中文界面文案（依赖 buildUiCopyMap 的运行时对照表）。
 * 非英文模式或查不到对照时原样返回，避免把中文界面改坏。
 */
function translateUiCopyText(value) {
    const map = buildUiCopyMap();
    if (!map || value == null) return value;
    return translateUiCopy(normalizedUiCopy(value), map) || value;
}

/** 原生 confirm 弹窗不会被 DOM 翻译覆盖，这里先查对照表再弹。 */
function uiConfirm(message) {
    return window.confirm(translateUiCopyText(message));
}

function normalizedUiCopy(value) {
    return String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
}

function buildUiCopyMap() {
    if (!String(document.documentElement.lang || '').toLowerCase().startsWith('en')) return null;
    if (window.__I18N_UI_COPY_MAP__) return window.__I18N_UI_COPY_MAP__;

    const source = window.__I18N_SOURCE__ || {};
    const localized = window.__I18N__ || {};
    const map = Object.create(null);
    const patterns = [];
    Object.keys(source).forEach(function (key) {
        const original = normalizedUiCopy(source[key]);
        const translated = normalizedUiCopy(localized[key]);
        if (!original || !translated || original === translated || /[\u3400-\u9fff]/.test(translated)) return;
        if (original.indexOf('{') >= 0 && /\{[^{}]+\}/.test(original)) {
            const names = [];
            let lastIndex = 0;
            let expression = '^';
            original.replace(/\{([^{}]+)\}/g, function (placeholder, name, offset) {
                expression += escapeUiCopyRegex(original.slice(lastIndex, offset)) + '(.+?)';
                names.push(name);
                lastIndex = offset + placeholder.length;
                return placeholder;
            });
            expression += escapeUiCopyRegex(original.slice(lastIndex)) + '$';
            patterns.push({ source: original, translated: translated, names: names, expression: new RegExp(expression) });
        } else if (!Object.prototype.hasOwnProperty.call(map, original)) {
            map[original] = translated;
        }
    });
    patterns.sort(function (a, b) { return b.source.length - a.source.length; });
    map.__patterns = patterns;
    window.__I18N_UI_COPY_MAP__ = map;
    return map;
}

function escapeUiCopyRegex(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function translateUiCopy(value, map) {
    if (!map || value == null) return '';
    const exact = map[value];
    if (exact) return exact;
    const patterns = map.__patterns || [];
    for (let i = 0; i < patterns.length; i++) {
        const pattern = patterns[i];
        const match = pattern.expression.exec(value);
        if (!match) continue;
        let translated = pattern.translated;
        pattern.names.forEach(function (name, index) {
            translated = translated.split('{' + name + '}').join(match[index + 1]);
        });
        return translated;
    }
    return '';
}

function translateUiTextNode(node, map) {
    if (!node || node.nodeType !== Node.TEXT_NODE || !map) return;
    const parent = node.parentElement;
    if (!parent || parent.closest('script, style, textarea, [contenteditable="true"], [data-user-content], .il-markdown-content, .community-article-content, .community-post-content, .post-content, .team-description, .asset-description, .profile-bio')) return;
    const raw = node.textContent || '';
    const normalized = normalizedUiCopy(raw);
    const translated = translateUiCopy(normalized, map);
    if (!translated) return;
    const leading = (raw.match(/^\s*/) || [''])[0];
    const trailing = (raw.match(/\s*$/) || [''])[0];
    node.textContent = leading + translated + trailing;
}

/** 属性版翻译：placeholder / title / aria-label / alt 会被脚本在插入之后再赋值，需要单独兜底 */
function translateUiAttribute(element, name, map) {
    if (!map || !element || element.nodeType !== Node.ELEMENT_NODE) return;
    if (element.closest('[data-user-content], .il-markdown-content, .community-article-content, .community-post-content, .post-content, .team-description, .asset-description, .profile-bio')) return;
    const raw = element.getAttribute(name);
    if (!raw) return;
    const translated = translateUiCopy(normalizedUiCopy(raw), map);
    if (translated && translated !== raw) element.setAttribute(name, translated);
}

/** Translate localized message literals and explicitly marked text without changing authored content. */
function translateMarkedContent(root) {
    const scope = root && root.querySelectorAll ? root : document;
    const copyMap = buildUiCopyMap();
    if (copyMap) {
        const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT);
        let textNode;
        while ((textNode = walker.nextNode())) translateUiTextNode(textNode, copyMap);
        const attributes = [];
        if (scope.nodeType === Node.ELEMENT_NODE && scope.matches('[placeholder], [title], [aria-label], [alt]')) attributes.push(scope);
        scope.querySelectorAll('[placeholder], [title], [aria-label], [alt]').forEach(function (element) { attributes.push(element); });
        attributes.forEach(function (element) {
            ['placeholder', 'title', 'aria-label', 'alt'].forEach(function (name) {
                translateUiAttribute(element, name, copyMap);
            });
        });
    }
    const elements = [];
    if (scope.nodeType === 1 && scope.matches('[data-i18n], [data-i18n-html], [data-i18n-lines], [data-i18n-placeholder], [data-i18n-title], [data-i18n-aria-label]')) {
        elements.push(scope);
    }
    scope.querySelectorAll('[data-i18n], [data-i18n-html], [data-i18n-lines], [data-i18n-placeholder], [data-i18n-title], [data-i18n-aria-label]')
        .forEach(function (element) { elements.push(element); });
    elements.forEach(function (element) {
        const directTextNodes = Array.from(element.childNodes).filter(function (node) { return node.nodeType === Node.TEXT_NODE; });
        const textKey = element.getAttribute('data-i18n');
        if (textKey && directTextNodes.length) {
            const translated = t(textKey);
            if (directTextNodes[0].textContent !== translated) directTextNodes[0].textContent = translated;
        }
        const htmlKey = element.getAttribute('data-i18n-html');
        if (htmlKey) {
            const translatedHtml = t(htmlKey);
            if (element.innerHTML !== translatedHtml) element.innerHTML = translatedHtml;
        }
        const linesKey = element.getAttribute('data-i18n-lines');
        if (linesKey) {
            const keys = linesKey.split('|');
            keys.forEach(function (key, index) {
                if (directTextNodes[index]) {
                    const translated = t(key);
                    if (directTextNodes[index].textContent.trim() !== translated) directTextNodes[index].textContent = translated;
                }
            });
        }
        ['placeholder', 'title', 'aria-label'].forEach(function (attribute) {
            const key = element.getAttribute('data-i18n-' + attribute);
            if (key && element.getAttribute(attribute) !== t(key)) element.setAttribute(attribute, t(key));
        });
    });
}

function bindLanguageSwitch() {
    const button = document.getElementById('languageSwitch');
    if (!button || button.dataset.i18nBound === 'true') return;
    button.dataset.i18nBound = 'true';
    button.textContent = t('language.switch');
    button.setAttribute('aria-label', t('language.switchLabel'));
    button.addEventListener('click', function (event) {
        event.preventDefault();
        const url = new URL(window.location.href);
        const current = String(document.documentElement.lang || 'zh-CN').toLowerCase();
        url.searchParams.set('lang', current.startsWith('en') ? 'zh' : 'en');
        window.location.assign(url.toString());
    });
}

(function initializeInternationalization() {
    function apply() {
        translateMarkedContent(document);
        bindLanguageSwitch();
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', apply);
    else apply();
    if (typeof MutationObserver !== 'undefined') {
        const observer = new MutationObserver(function (records) {
            records.forEach(function (record) {
                if (record.type === 'characterData') {
                    translateUiTextNode(record.target, buildUiCopyMap());
                    return;
                }
                if (record.type === 'attributes') {
                    translateUiAttribute(record.target, record.attributeName, buildUiCopyMap());
                    return;
                }
                record.addedNodes.forEach(function (node) {
                    if (node.nodeType === Node.ELEMENT_NODE) translateMarkedContent(node);
                    else if (node.nodeType === Node.TEXT_NODE) translateUiTextNode(node, buildUiCopyMap());
                });
            });
        });
        observer.observe(document.documentElement, {
            childList: true, characterData: true, subtree: true,
            attributes: true, attributeFilter: ['title', 'placeholder', 'aria-label', 'alt']
        });
    }
})();

// Lightweight navigation helper
function navigateTo(href) {
    if (!href) return;
    window.location.assign(href);
}

/**
 * 组队状态标签映射
 */
function teamStatusLabel(status) {
    var map = { OPEN: '招募中', TEAMING: '已组队', CLOSED: '已结束' };
    return map[status] || status || '';
}

/**
 * 社区分区标签映射（公共定义）
 */
var CATEGORY_LABELS = {
    '': '全部',
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

// ========================================
// Initialize All Interactions
// ========================================
document.addEventListener('DOMContentLoaded', function() {
    initUnifiedHeader();

    // Particle system — only if canvas exists (loaded from ui-particles.js)
    if (document.getElementById('particleCanvas') && typeof ParticleSystem !== 'undefined') {
        new ParticleSystem('particleCanvas', window.ILINK_PARTICLE_OPTIONS);
    }

    // 顶栏滚动效果已移除：原来每次滚动都往 header 写 --header-scroll 变量并切 il-header--scrolled 类，
    // 但 CSS 里没有任何规则用到它们（纯无效写入，白白触发样式重算）
});

// Expose public API via namespace
const ilinkPublicApi = {
    apiFetch: apiFetch,
    request: request,
    buildLoginUrl: buildLoginUrl,
    redirectToLogin: redirectToLogin,
    getCurrentUser: getCurrentUser,
    loadCurrentUserOnce: loadCurrentUserOnce,
    isAnonymousVisitor: isAnonymousVisitor,
    logout: logout,
    showMessage: showMessage,
    Toast: Toast,
    showFieldHint: showFieldHint,
    clearFieldHint: clearFieldHint,
    formatTime: formatTime,
    getTypeClass: getTypeClass,
    toggleMobileMenu: toggleMobileMenu,
    setMobileMenuOpen: setMobileMenuOpen,
    toggleUserMenu: toggleUserMenu,
    toggleNotifications: toggleNotifications,
    markAllAsRead: markAllAsRead,
    closeAllDropdowns: closeAllDropdowns,
    computeNavbarDisplayName: computeNavbarDisplayName,
    computeNavbarInitials: computeNavbarInitials,
    accountTriggerTitleAttr: accountTriggerTitleAttr,
    buildAccountAvatarInnerHtml: buildAccountAvatarInnerHtml,
    applyAccountMenuFromUser: applyAccountMenuFromUser,
    publicProfilePageUrl: publicProfilePageUrl,
    displayUserName: displayUserName,
    displayUsername: displayUsername,
    galleryAvatarInitial: galleryAvatarInitial,
    galleryPublisherAvatarHtml: galleryPublisherAvatarHtml,
    hideGalleryPublisherAvatarFallbacks: hideGalleryPublisherAvatarFallbacks,
    avatarInitial: avatarInitial,
    publisherAvatarHtml: publisherAvatarHtml,
    publisherAvatarFromAuthorFields: publisherAvatarFromAuthorFields,
    configureMarkdownRenderer: configureMarkdownRenderer,
    encodeMarkdownSource: encodeMarkdownSource,
    decodeMarkdownSource: decodeMarkdownSource,
    markdownSourcePayload: markdownSourcePayload,
    stripMarkdownSourceMarker: stripMarkdownSourceMarker,
    markdownSourceMarker: markdownSourceMarker,
    markdownToSafeHtml: markdownToSafeHtml,
    renderMarkdownSafe: renderMarkdownSafe,
    teamStatusLabel: teamStatusLabel,
    CATEGORY_LABELS: CATEGORY_LABELS,
    navigate: navigateTo
};

window.ILink = Object.assign(window.ILink || {}, ilinkPublicApi);
Object.assign(window, ilinkPublicApi);


/* ====== Plan A: custom select for unified search bar ====== */
(function () {
  function initCustomSelects() {
    var wraps = document.querySelectorAll('.il-search-bar__select:not(.cs-inited)');
    if (!wraps.length) return;

    var chevronSvg = '<svg class="cs-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';

    function closeAll() {
      var open = document.querySelectorAll('.il-search-bar__select.open');
      for (var i = 0; i < open.length; i++) open[i].classList.remove('open');
    }

    /* 菜单宽度由内容决定（英文选项更长），展开时若会超出视口就整体左移，
       避免出现横向滚动条或把选项挤出屏幕。收起状态下已由 CSS 收窄，不影响布局。 */
    function clampDropdown(dropdown) {
      if (!dropdown) return;
      dropdown.style.marginLeft = '0px';
      var rect = dropdown.getBoundingClientRect();
      var pad = 12;
      var vw = document.documentElement.clientWidth;
      var shift = 0;
      if (rect.right > vw - pad) shift = (vw - pad) - rect.right;
      if (rect.left + shift < pad) shift = pad - rect.left;
      dropdown.style.marginLeft = shift < 0 ? shift + 'px' : '0px';
    }

    var clampOpenDropdowns = function () {
      var open = document.querySelectorAll('.il-search-bar__select.open .cs-dropdown');
      for (var i = 0; i < open.length; i++) clampDropdown(open[i]);
    };
    window.addEventListener('resize', clampOpenDropdowns);

    for (var w = 0; w < wraps.length; w++) {
      (function (wrap) {
        var select = wrap.querySelector('select');
        if (!select) return;
        wrap.classList.add('cs-inited');

        var label = select.getAttribute('aria-label') || '';
        var trigger = document.createElement('div');
        trigger.className = 'cs-trigger';
        trigger.innerHTML =
          (label ? '<span class="cs-label">' + label + '</span>' : '') +
          '<span class="cs-value"></span>' + chevronSvg;

        var dropdown = document.createElement('div');
        dropdown.className = 'cs-dropdown';
        var options = Array.prototype.slice.call(select.options);

        function updateDisplay() {
          var opt = select.options[select.selectedIndex];
          trigger.querySelector('.cs-value').textContent = opt ? opt.textContent : '';
          var items = dropdown.querySelectorAll('.cs-item');
          for (var i = 0; i < items.length; i++) {
            items[i].classList.toggle('active', options[i] && options[i].value === select.value);
          }
        }

        for (var i = 0; i < options.length; i++) {
          (function (opt) {
            var item = document.createElement('div');
            item.className = 'cs-item' + (opt.selected ? ' active' : '');
            item.textContent = opt.textContent;
            item.setAttribute('data-value', opt.value);
            item.addEventListener('click', function (e) {
              e.stopPropagation();
              select.value = opt.value;
              select.dispatchEvent(new Event('change', { bubbles: true }));
              updateDisplay();
              closeAll();
            });
            dropdown.appendChild(item);
          })(options[i]);
        }

        trigger.addEventListener('click', function (e) {
          e.stopPropagation();
          var wasOpen = wrap.classList.contains('open');
          closeAll();
          if (!wasOpen) {
            wrap.classList.add('open');
            clampDropdown(dropdown);
          }
        });

        wrap.appendChild(trigger);
        wrap.appendChild(dropdown);
        updateDisplay();
      })(wraps[w]);
    }

    document.addEventListener('click', closeAll);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initCustomSelects);
  } else {
    initCustomSelects();
  }
})();

/* ====== 搜索框放大镜按钮点击触发搜索 ====== */
(function() {
  function setupIconSearch() {
    var icons = document.querySelectorAll('.il-search-bar .il-search-field__icon, .il-search-field--combo .il-search-field__icon');
    for (var i = 0; i < icons.length; i++) {
      var icon = icons[i];
      if (icon.dataset.iconSearchBound) continue;
      icon.dataset.iconSearchBound = '1';
      if (icon.parentElement && icon.parentElement.classList.contains('il-icon-search-btn')) continue;

      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'il-icon-search-btn';
      btn.setAttribute('aria-label', '搜索');
      icon.parentNode.insertBefore(btn, icon);
      btn.appendChild(icon);

      (function(button) {
        button.addEventListener('click', function(e) {
          e.preventDefault();
          e.stopPropagation();
          var bar = button.closest('.il-search-bar');
          var combo = button.closest('.il-search-field--combo');
          var searchBtn = bar ? bar.querySelector('.il-search-bar__btn--primary') : null;
          if (searchBtn) { searchBtn.click(); return; }
          var actionBtn = combo ? combo.querySelector('.il-search-field__action') : null;
          if (actionBtn) { actionBtn.click(); return; }
          var scope = bar || combo;
          var inp = scope ? scope.querySelector('input') : null;
          if (inp) {
            inp.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
            inp.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));
          }
        });
      })(btn);
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', setupIconSearch);
  } else {
    setupIconSearch();
  }
  setTimeout(setupIconSearch, 300);
  setTimeout(setupIconSearch, 1000);
})();

/* 滚动条按需浮出：滚动时给该滚动容器挂 .is-scrolling，停手 0.9 秒后摘掉。
   平时完全透明（见 layout.css），避免常驻的灰色条一直挂在页面和面板边上。 */
(function setupScrollbarAutoHide() {
  var timers = new WeakMap();

  function schedule(el) {
    el.classList.add('is-scrolling');
    var prev = timers.get(el);
    if (prev) clearTimeout(prev);
    timers.set(el, setTimeout(function () { el.classList.remove('is-scrolling'); }, 900));
  }

  document.addEventListener('scroll', function (event) {
    // 页面级滚动的事件目标是 document。视口滚动条由 <html> 还是 <body> 绘制，
    // 取决于谁的 overflow 传播到了视口：body 只要设了 overflow-x/y（首页就是
    // overflow-x:hidden），被绘制的那根就归 body，只标 html 会让滑块永远透明。
    // 两个都标；不是滚动容器的那一边没有滚动条，标了也没有视觉影响。
    if (event.target === document) {
      schedule(document.documentElement);
      schedule(document.body);
    } else if (event.target && event.target.classList) {
      schedule(event.target);
    }
  }, { capture: true, passive: true });
})();
