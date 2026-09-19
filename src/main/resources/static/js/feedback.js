/**
 * 意见反馈 · 关于作者 —— 全站悬浮入口
 *
 * 由 fragments/feedback-widget.html 引入，随 header 出现在 25 个页面，
 * 另外单独出现在登录 / 注册 / 忘记密码三个认证页。
 *
 * 依赖 common.js 的 request / showMessage。本文件用 defer 加载，
 * 保证在所有常规 <script>（含页面底部的 common.js）之后执行；
 * 所有对公共函数的引用也都写在事件回调里，调用时才解析。
 */
(function () {
    'use strict';

    var AUTHOR_URL = 'https://su-yu-s.github.io/portfolio/';
    var MAX_FILES = 5;
    var MAX_FILE_SIZE = 5 * 1024 * 1024;
    var MAX_DESC = 500;
    var WARN_DESC = 450;
    var IDLE_MS = 5000;
    var CORNER_PX = 160;

    var fabs = document.getElementById('fbkFabs');
    var modal = document.getElementById('fbkModal');
    if (!fabs || !modal) {
        return;
    }

    var panel = modal.querySelector('.fbk-modal__panel');
    var form = document.getElementById('fbkForm');
    var descInput = document.getElementById('fbkDesc');
    var counter = document.getElementById('fbkCounter');
    var contactInput = document.getElementById('fbkContact');
    var fileInput = document.getElementById('fbkImages');
    var dropzone = document.getElementById('fbkDropzone');
    var previews = document.getElementById('fbkPreviews');
    var submitBtn = document.getElementById('fbkSubmit');
    var errorBox = document.getElementById('fbkError');
    var typeGroup = document.getElementById('fbkTypes');
    var closeBtn = document.getElementById('fbkModalClose');

    var selectedFiles = [];
    var currentType = 'BUG';
    var lastFocused = null;
    var submitting = false;
    var authorCooldown = false;

    // ---------------------------------------------------------------
    // Modal 开关
    // ---------------------------------------------------------------
    function openModal() {
        lastFocused = document.activeElement;
        // 入口可能在头像下拉菜单里，打开弹窗时顺手收起菜单
        if (typeof closeAllDropdowns === 'function') {
            closeAllDropdowns();
        }
        modal.classList.add('is-open');
        document.body.style.overflow = 'hidden';
        setDim(false);
        if (descInput) {
            descInput.focus();
        }
    }

    function closeModal() {
        var target = lastFocused;
        modal.classList.remove('is-open');
        document.body.style.overflow = '';
        // 先把焦点移出弹窗再隐藏，避免焦点滞留在被隐藏的子树里
        if (target && target !== document.body && typeof target.focus === 'function'
            && document.contains(target)) {
            target.focus();
        }
        if (document.activeElement && document.activeElement !== document.body
            && modal.contains(document.activeElement)) {
            document.activeElement.blur();
        }
        lastFocused = null;
    }

    function isOpen() {
        return modal.classList.contains('is-open');
    }

    document.addEventListener('click', function (event) {
        var trigger = event.target.closest('[data-fbk-open]');
        if (trigger) {
            event.preventDefault();
            openModal();
            return;
        }
        // 点击遮罩关闭（点击面板本身不关）
        if (event.target === modal) {
            closeModal();
        }
    });

    if (closeBtn) {
        closeBtn.addEventListener('click', closeModal);
    }

    document.addEventListener('keydown', function (event) {
        if (event.key !== 'Escape' || !isOpen()) {
            return;
        }
        event.stopPropagation();
        closeModal();
    });

    // 焦点圈定在面板内，避免 Tab 跑到背后的页面
    modal.addEventListener('keydown', function (event) {
        if (event.key !== 'Tab' || !panel) {
            return;
        }
        var focusable = panel.querySelectorAll(
            'button:not([disabled]), [href], input:not([disabled]):not([type="hidden"]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
        if (!focusable.length) {
            return;
        }
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    });

    // ---------------------------------------------------------------
    // 反馈类型
    // ---------------------------------------------------------------
    if (typeGroup) {
        typeGroup.addEventListener('click', function (event) {
            var button = event.target.closest('.fbk-type');
            if (!button) {
                return;
            }
            Array.prototype.forEach.call(typeGroup.querySelectorAll('.fbk-type'), function (item) {
                item.classList.remove('is-active');
                item.setAttribute('aria-pressed', 'false');
            });
            button.classList.add('is-active');
            button.setAttribute('aria-pressed', 'true');
            currentType = button.getAttribute('data-type') || 'OTHER';
        });
    }

    // ---------------------------------------------------------------
    // 详细描述字数
    // ---------------------------------------------------------------
    function syncCounter() {
        if (!descInput || !counter) {
            return;
        }
        var length = descInput.value.length;
        counter.textContent = String(length);
        counter.classList.toggle('fbk-counter--warn', length > WARN_DESC);
    }

    if (descInput) {
        descInput.addEventListener('input', function () {
            if (descInput.value.length > MAX_DESC) {
                descInput.value = descInput.value.slice(0, MAX_DESC);
            }
            syncCounter();
            clearError();
        });
    }

    // ---------------------------------------------------------------
    // 截图选择 / 拖拽 / 预览
    // ---------------------------------------------------------------
    function fileKey(file) {
        return file.name + '::' + file.size + '::' + file.lastModified;
    }

    function renderPreviews() {
        if (!previews) {
            return;
        }
        previews.innerHTML = '';
        selectedFiles.forEach(function (file, index) {
            var item = document.createElement('div');
            item.className = 'fbk-preview';

            var image = document.createElement('img');
            image.alt = file.name;
            var objectUrl = URL.createObjectURL(file);
            image.src = objectUrl;
            image.addEventListener('load', function () {
                URL.revokeObjectURL(objectUrl);
            });
            item.appendChild(image);

            var remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'fbk-preview__remove';
            remove.setAttribute('aria-label', '移除 ' + file.name);
            remove.textContent = '✕';
            remove.addEventListener('click', function () {
                selectedFiles.splice(index, 1);
                renderPreviews();
            });
            item.appendChild(remove);

            previews.appendChild(item);
        });
    }

    function addFiles(fileList) {
        var incoming = Array.prototype.slice.call(fileList || []);
        var accepted = [];
        var rejection = '';
        for (var i = 0; i < incoming.length; i += 1) {
            var file = incoming[i];
            if (!file.type || file.type.indexOf('image/') !== 0) {
                rejection = '「' + file.name + '」不是图片文件';
                continue;
            }
            if (file.size > MAX_FILE_SIZE) {
                rejection = '「' + file.name + '」超过 5MB';
                continue;
            }
            accepted.push(file);
        }
        if (!accepted.length) {
            if (rejection) {
                showError(rejection);
            }
            return;
        }
        var merged = selectedFiles.slice();
        accepted.forEach(function (file) {
            var key = fileKey(file);
            var exists = merged.some(function (item) {
                return fileKey(item) === key;
            });
            if (!exists) {
                merged.push(file);
            }
        });
        if (merged.length > MAX_FILES) {
            showError('最多上传 ' + MAX_FILES + ' 张截图');
            merged = merged.slice(0, MAX_FILES);
        } else if (rejection) {
            // 有文件被拒时不要用 clearError 把原因冲掉
            showError(rejection);
        } else {
            clearError();
        }
        selectedFiles = merged;
        renderPreviews();
    }

    if (dropzone && fileInput) {
        dropzone.addEventListener('click', function () {
            fileInput.click();
        });
        dropzone.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                fileInput.click();
            }
        });
        fileInput.addEventListener('change', function () {
            addFiles(fileInput.files);
            fileInput.value = '';
        });
    }

    ['dragenter', 'dragover'].forEach(function (name) {
        if (!dropzone) {
            return;
        }
        dropzone.addEventListener(name, function (event) {
            event.preventDefault();
            dropzone.classList.add('is-drag');
        });
    });
    ['dragleave', 'drop'].forEach(function (name) {
        if (!dropzone) {
            return;
        }
        dropzone.addEventListener(name, function (event) {
            event.preventDefault();
            dropzone.classList.remove('is-drag');
        });
    });
    if (dropzone) {
        dropzone.addEventListener('drop', function (event) {
            if (event.dataTransfer && event.dataTransfer.files) {
                addFiles(event.dataTransfer.files);
            }
        });
    }
    // 拖到窗口其它位置时不让浏览器直接打开图片
    ['dragover', 'drop'].forEach(function (name) {
        document.addEventListener(name, function (event) {
            if (isOpen()) {
                event.preventDefault();
            }
        });
    });

    // ---------------------------------------------------------------
    // 错误提示
    // ---------------------------------------------------------------
    function showError(message) {
        if (errorBox) {
            errorBox.textContent = message;
        }
    }

    function clearError() {
        if (errorBox) {
            errorBox.textContent = '';
        }
    }

    // ---------------------------------------------------------------
    // 提交
    // ---------------------------------------------------------------
    var SUBMIT_LABEL = submitBtn ? submitBtn.innerHTML : '';

    function setSubmitting(on) {
        submitting = on;
        if (!submitBtn) {
            return;
        }
        submitBtn.disabled = on;
        submitBtn.classList.toggle('fbk-submit--loading', on);
        submitBtn.innerHTML = on
            ? '<svg viewBox="0 0 24 24" fill="none" aria-hidden="true">'
                + '<circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" opacity="0.25"/>'
                + '<path d="M4 12a8 8 0 0 1 8-8V0C5.373 0 0 5.373 0 12h4z" fill="currentColor"/>'
                + '</svg>正在上传并生成页面…'
            : SUBMIT_LABEL;
    }

    function resetForm() {
        selectedFiles = [];
        currentType = 'BUG';
        if (form) {
            form.reset();
        }
        if (descInput) {
            descInput.value = '';
        }
        if (contactInput) {
            contactInput.value = '';
        }
        if (previews) {
            previews.innerHTML = '';
        }
        if (typeGroup) {
            Array.prototype.forEach.call(typeGroup.querySelectorAll('.fbk-type'), function (item) {
                var isDefault = item.getAttribute('data-type') === 'BUG';
                item.classList.toggle('is-active', isDefault);
                item.setAttribute('aria-pressed', isDefault ? 'true' : 'false');
            });
        }
        syncCounter();
        clearError();
    }

    if (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            if (submitting) {
                return;
            }
            var description = descInput ? descInput.value.trim() : '';
            if (!description) {
                showError('请填写详细描述');
                if (descInput) {
                    descInput.focus();
                }
                return;
            }

            var formData = new FormData();
            formData.append('type', currentType);
            formData.append('desc', description);
            formData.append('contact', contactInput ? contactInput.value.trim() : '');
            selectedFiles.forEach(function (file) {
                formData.append('images', file);
            });

            setSubmitting(true);
            clearError();

            request('/api/feedback', { method: 'POST', body: formData, timeoutMs: 60000 })
                .then(function () {
                    resetForm();
                    closeModal();
                    showMessage('已推送至开发者微信并生成记录', 'success');
                })
                .catch(function (error) {
                    // request() 已统一提示；这里额外做表单内联提示，且保留全部输入与已选截图
                    showError((error && error.message) || '提交失败，请稍后重试');
                })
                .then(function () {
                    setSubmitting(false);
                });
        });
    }

    // ---------------------------------------------------------------
    // 关于作者
    // ---------------------------------------------------------------
    var authorBtn = document.getElementById('fbkFabAuthor');
    if (authorBtn) {
        authorBtn.addEventListener('click', function () {
            if (authorCooldown) {
                return;
            }
            authorCooldown = true;
            authorBtn.classList.add('fbk-fab--loading');
            window.open(AUTHOR_URL, '_blank', 'noopener');
            window.setTimeout(function () {
                authorBtn.classList.remove('fbk-fab--loading');
                authorCooldown = false;
            }, 500);
        });
    }

    // ---------------------------------------------------------------
    // 沉浸式避让：仅写文章 / 发布成果等工作台页面
    // ---------------------------------------------------------------
    var idleTimer = null;
    var cornerHover = false;

    function setDim(on) {
        if (!fabs) {
            return;
        }
        fabs.classList.toggle('fbk-fabs--dim', on && !cornerHover && !isOpen());
    }

    function isNearCorner(x, y) {
        return (window.innerHeight - y) < CORNER_PX && (window.innerWidth - x) < CORNER_PX;
    }

    function scheduleDim() {
        window.clearTimeout(idleTimer);
        idleTimer = window.setTimeout(function () {
            setDim(true);
        }, IDLE_MS);
    }

    if (document.body.hasAttribute('data-publishing-workspace')) {
        ['pointermove', 'keydown', 'scroll', 'pointerdown'].forEach(function (name) {
            document.addEventListener(name, function (event) {
                if (name === 'pointermove') {
                    cornerHover = isNearCorner(event.clientX, event.clientY);
                    if (cornerHover) {
                        setDim(false);
                    }
                }
                scheduleDim();
            }, { passive: true });
        });
        scheduleDim();
    }

    syncCounter();
})();
