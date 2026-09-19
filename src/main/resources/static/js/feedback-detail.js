/**
 * 反馈详情页：返回、截图放大、一键复制联系方式。
 *
 * 该页在微信内置浏览器打开，无登录态；内容全部由服务端渲染，
 * 本脚本只负责交互增强，禁用它页面依然可读。
 */
(function () {
    'use strict';

    // ---------------------------------------------------------------
    // 返回：优先浏览器历史，无历史时回首页
    // ---------------------------------------------------------------
    var backBtn = document.getElementById('fbkBack');
    if (backBtn) {
        backBtn.addEventListener('click', function () {
            if (window.history.length > 1) {
                window.history.back();
            } else {
                window.location.assign('/index.html');
            }
        });
    }

    // ---------------------------------------------------------------
    // 截图放大
    // ---------------------------------------------------------------
    var lightbox = document.getElementById('fbkLightbox');
    var lightboxImg = document.getElementById('fbkLightboxImg');
    var lightboxClose = document.getElementById('fbkLightboxClose');
    var previousOverflow = '';

    function openLightbox(src) {
        if (!lightbox || !lightboxImg || !src) {
            return;
        }
        lightboxImg.src = src;
        lightbox.classList.add('is-open');
        previousOverflow = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        if (lightboxClose) {
            lightboxClose.focus();
        }
    }

    function closeLightbox() {
        if (!lightbox || !lightboxImg || !lightbox.classList.contains('is-open')) {
            return;
        }
        lightbox.classList.remove('is-open');
        lightboxImg.src = '';
        document.body.style.overflow = previousOverflow;
    }

    Array.prototype.forEach.call(document.querySelectorAll('.fbk-shot'), function (shot) {
        shot.addEventListener('click', function () {
            openLightbox(shot.getAttribute('data-src'));
        });
    });

    if (lightbox) {
        lightbox.addEventListener('click', closeLightbox);
    }
    if (lightboxClose) {
        lightboxClose.addEventListener('click', function (event) {
            event.stopPropagation();
            closeLightbox();
        });
    }
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
            closeLightbox();
        }
    });

    // ---------------------------------------------------------------
    // 一键复制联系方式
    // ---------------------------------------------------------------
    var copyBtn = document.getElementById('fbkCopy');
    var contactValue = document.getElementById('fbkContactValue');
    if (!copyBtn || !contactValue) {
        return;
    }

    var COPY_LABEL = copyBtn.innerHTML;
    var COPIED_LABEL = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"'
        + ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
        + '<path d="m5 12.5 5 5L19 7"/></svg>已复制';
    var copiedTimer = null;

    function markCopied() {
        copyBtn.classList.add('is-copied');
        copyBtn.innerHTML = COPIED_LABEL;
        if (typeof showMessage === 'function') {
            showMessage('已复制到剪贴板', 'success');
        }
        window.clearTimeout(copiedTimer);
        copiedTimer = window.setTimeout(function () {
            copyBtn.classList.remove('is-copied');
            copyBtn.innerHTML = COPY_LABEL;
        }, 2000);
    }

    /** clipboard API 在非 HTTPS 或旧版微信内核下不可用，退回 execCommand */
    function fallbackCopy(text) {
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.top = '-1000px';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        var ok = false;
        try {
            ok = document.execCommand('copy');
        } catch (e) {
            ok = false;
        }
        document.body.removeChild(area);
        return ok;
    }

    copyBtn.addEventListener('click', function () {
        var text = (contactValue.textContent || '').trim();
        if (!text) {
            return;
        }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(markCopied, function () {
                if (fallbackCopy(text)) {
                    markCopied();
                } else if (typeof showMessage === 'function') {
                    showMessage('复制失败，请手动选择文本', 'error');
                }
            });
            return;
        }
        if (fallbackCopy(text)) {
            markCopied();
        } else if (typeof showMessage === 'function') {
            showMessage('复制失败，请手动选择文本', 'error');
        }
    });
})();
