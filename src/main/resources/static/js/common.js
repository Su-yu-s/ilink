// iLink JavaScript - Core Utilities
// ========================================

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
                            window.location.assign('/login.html');
                        }, 1200);
                    }
                }
                throw error;
            }
        }

        const code = data && Number(data.code);
        if (code !== 200) {
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
                        window.location.assign('/login.html');
                    }, 1500);
                }
            }

            const error = new Error(userMessage);
            error._ilinkHandled = true;
            error.response = data;
            error.status = response.status;
            throw error;
        }

        return data.data;
    } catch (error) {
        if (error && error._ilinkHandled) {
            throw error;
        }
        if (!silent && error && error.name === 'TypeError' && String(error.message).includes('fetch')) {
            showMessage('网络连接失败，请检查网络设置', 'error');
        } else if (!silent) {
            showMessage((error && error.message) || '请求失败', 'error');
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

// 兼容旧页面与当前统一页头的菜单契约。
function toggleMobileMenu() {
    const legacyMenu = document.getElementById('navMenu');
    if (legacyMenu) {
        legacyMenu.classList.toggle('show');
        return;
    }

    const menu = document.getElementById('ilHeaderNav');
    const button = document.getElementById('menuToggle');
    if (!menu) return;
    const open = menu.classList.toggle('il-header__nav--open');
    document.body.classList.toggle('nav-open', open);
    if (button) {
        button.setAttribute('aria-expanded', String(open));
        button.setAttribute('aria-label', open ? '关闭菜单' : '打开菜单');
    }
}

function toggleUserMenu() {
    const dropdown = document.getElementById('userDropdown');
    if (!dropdown) return;

    const legacyWrap = document.querySelector('.user-menu');
    const legacyButton = document.getElementById('accountMenuBtn');
    if (legacyWrap) {
        dropdown.classList.toggle('show');
        const open = dropdown.classList.contains('show');
        legacyWrap.classList.toggle('is-open', open);
        if (legacyButton) legacyButton.setAttribute('aria-expanded', String(open));
        return;
    }

    const button = document.getElementById('userDropdownToggle');
    const open = dropdown.classList.toggle('il-header__dropdown--open');
    if (button) button.setAttribute('aria-expanded', String(open));
}

/** 右上角展示：优先用户名，否则姓名。 */
function computeNavbarDisplayName(user) {
    if (!user) return '用户';
    const realName = String(user.realName || '').trim();
    const username = String(user.username || '').trim();
    return username || realName || '用户';
}

function computeNavbarInitials(displayName) {
    const value = String(displayName || '用户').trim();
    return value ? value.slice(0, 2).toUpperCase() : '用';
}

function accountTriggerTitleAttr(user) {
    if (!user) return '';
    const displayName = computeNavbarDisplayName(user);
    const username = String(user.username || '').trim();
    return username && displayName !== username
        ? displayName + '（账号 ' + username + '）'
        : displayName;
}

function buildAccountAvatarInnerHtml(user) {
    const displayName = computeNavbarDisplayName(user);
    const initials = escapeHtml(computeNavbarInitials(displayName));
    const avatar = user && user.avatar ? String(user.avatar).trim() : '';
    if (avatar) {
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
    if (!avatar) {
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

// Toast 类型处理 — compact
function normalizeToastType(type) {
    return type === 'danger' ? 'error' : (type || 'info');
}

function getToastDurationMs(type, override) {
    if (typeof override === 'number' && override > 0) return override;
    const t = normalizeToastType(type);
    if (t === 'error') return 4800;
    if (t === 'warning') return 4000;
    if (t === 'success') return 2800;
    return 3200;
}

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

function removeIlinkToast(toast) {
    if (!toast) return;
    if (toast._ilinkTimer) {
        clearTimeout(toast._ilinkTimer);
        toast._ilinkTimer = null;
    }
    if (toast.parentNode) toast.parentNode.removeChild(toast);
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

/**
 * 全局轻提示（右上角 Toast，勿用于字段级 blur 校验）
 */
function showMessage(message, type, durationMs) {
    if (type === undefined) type = 'info';
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

    // “处理中”类信息被后续结果替换，避免连续操作遮挡页面内容。
    Array.from(host.children).forEach(function (item) {
        if (item.classList.contains('ilink-toast--info')) removeIlinkToast(item);
    });

    const maxStack = 3;
    while (host.children.length >= maxStack) {
        removeIlinkToast(host.firstChild);
    }

    const labels = { success: '操作完成', error: '操作失败', warning: '请注意', info: '提示' };

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
    labelEl.textContent = labels[variant] || labels.info;

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
        default: return 'info';
    }
}

function publicProfilePageUrl(userId) {
    if (userId == null || userId === '') return '#';
    return '/user-profile.html?id=' + encodeURIComponent(String(userId));
}

/** 卡片/详情展示名：preferRealName 时 realName → username，否则 username → realName。 */
function displayUserName(preview, opts) {
    if (!preview) return '';
    const preferRealName = !!(opts && opts.preferRealName);
    const realName = preview.realName ? String(preview.realName).trim() : '';
    const username = preview.username ? String(preview.username).trim() : '';
    return preferRealName ? (realName || username) : (username || realName);
}

/** 成果页面只展示 username，避免同一用户在列表和详情显示不同名称。 */
function displayUsername(preview) {
    if (!preview) return '匿名';
    const username = preview.username ? String(preview.username).trim() : '';
    if (username) return username;
    if (preview.id != null) return '用户' + preview.id;
    return '匿名';
}

function galleryAvatarInitial(preview) {
    const username = preview && preview.username ? String(preview.username).trim() : '';
    return username ? username.charAt(0).toUpperCase() : '';
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

function avatarInitial(preview, opts) {
    const displayName = displayUserName(preview, opts);
    return displayName ? displayName.charAt(0).toUpperCase() : '';
}

function publisherAvatarHtml(preview, extraClass, opts) {
    if (!preview || preview.id == null) return '';
    const preferRealName = !!(opts && opts.preferRealName);
    const displayName = displayUserName(preview, { preferRealName: preferRealName });
    const initials = avatarInitial(preview, { preferRealName: preferRealName });
    const nameEscaped = escapeHtml(displayName || '用户');
    const href = escapeHtml(publicProfilePageUrl(preview.id));
    const avatar = preview.avatar ? String(preview.avatar).trim() : '';
    const className = 'publisher-avatar' + (extraClass ? ' ' + extraClass : '');

    if (avatar) {
        return '<a href="' + href + '" class="' + className + '" title="查看 ' + nameEscaped + ' 的主页" aria-label="查看 TA 的个人主页">' +
            '<img class="publisher-avatar__img" src="' + escapeHtml(avatar) + '" alt="" referrerpolicy="no-referrer" loading="lazy" onerror="this.onerror=null;this.style.display=\'none\';var s=this.nextElementSibling;if(s)s.classList.remove(\'d-none\');">' +
            '<span class="publisher-avatar__fallback d-none" aria-hidden="true">' + escapeHtml(initials || '?') + '</span></a>';
    }

    return '<a href="' + href + '" class="' + className + '" title="查看 ' + nameEscaped + ' 的主页" aria-label="查看 TA 的个人主页">' +
        '<span class="publisher-avatar__fallback" aria-hidden="true">' + escapeHtml(initials || '?') + '</span></a>';
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
    // Particle system — only if canvas exists (loaded from ui-particles.js)
    if (document.getElementById('particleCanvas') && typeof ParticleSystem !== 'undefined') {
        new ParticleSystem('particleCanvas', window.ILINK_PARTICLE_OPTIONS);
    }

    // Navbar progressive scroll effect（0→1 进度驱动 CSS 变量）
    const header = document.getElementById('ilHeader');
    if (header) {
        let headerTicking = false;
        const SCROLL_RANGE = 60;
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
        updateHeader();
    }
});

// Expose public API via namespace
const ilinkPublicApi = {
    apiFetch: apiFetch,
    request: request,
    getCurrentUser: getCurrentUser,
    logout: logout,
    showMessage: showMessage,
    showFieldHint: showFieldHint,
    clearFieldHint: clearFieldHint,
    formatTime: formatTime,
    getTypeClass: getTypeClass,
    toggleMobileMenu: toggleMobileMenu,
    toggleUserMenu: toggleUserMenu,
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
    teamStatusLabel: teamStatusLabel,
    CATEGORY_LABELS: CATEGORY_LABELS,
    navigate: navigateTo
};

window.ILink = Object.assign(window.ILink || {}, ilinkPublicApi);
Object.assign(window, ilinkPublicApi);
