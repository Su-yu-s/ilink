document.addEventListener('DOMContentLoaded', function() {
    const loginForm = document.getElementById('loginForm');
    const identifierInput = document.getElementById('identifier');
    const passwordInput = document.getElementById('password');

    function clearLoginFieldErrors() {
        clearFieldHint('identifierError', 'identifier');
        clearFieldHint('passwordError', 'password');
    }

    function validateIdentifier(showHint) {
        const value = identifierInput ? identifierInput.value.trim() : '';
        if (!value) {
            if (showHint) showFieldHint('identifierError', '请输入手机号或学号/工号', 'identifier');
            return false;
        }
        clearFieldHint('identifierError', 'identifier');
        return true;
    }

    function validatePassword(showHint) {
        const value = passwordInput ? passwordInput.value : '';
        if (!value) {
            if (showHint) showFieldHint('passwordError', '请输入密码', 'password');
            return false;
        }
        clearFieldHint('passwordError', 'password');
        return true;
    }

    if (identifierInput) {
        identifierInput.addEventListener('input', () => {
            if (identifierInput.value.trim()) clearFieldHint('identifierError', 'identifier');
        });
        identifierInput.addEventListener('blur', () => validateIdentifier(true));
    }
    if (passwordInput) {
        passwordInput.addEventListener('input', () => {
            if (passwordInput.value) clearFieldHint('passwordError', 'password');
        });
        passwordInput.addEventListener('blur', () => validatePassword(true));
    }

    if (loginForm) {
        loginForm.addEventListener('submit', async function(e) {
            e.preventDefault();
            clearLoginFieldErrors();

            const idOk = validateIdentifier(true);
            const pwdOk = validatePassword(true);
            if (!idOk || !pwdOk) {
                return;
            }

            const requestData = {
                identifier: identifierInput.value.trim(),
                password: passwordInput.value
            };

            const submitButton = loginForm.querySelector('button[type="submit"]');
            const originalText = submitButton ? submitButton.textContent : '登录';

            try {
                if (submitButton) {
                    submitButton.textContent = '登录中...';
                    submitButton.disabled = true;
                }

                const response = await apiFetch('/api/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(requestData)
                });

                const contentType = response.headers.get('content-type') || '';
                if (!contentType.includes('application/json')) {
                    throw new Error('服务器响应不是有效的 JSON 格式');
                }

                const result = await response.json();

                if (response.ok && result.code === 200) {
                    showMessage('登录成功，正在进入首页', 'success');

                    let next = '/index.html';
                    const extra = result.extra || {};
                    const redirect = extra.redirectAfterLogin || result.redirectAfterLogin;
                    if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
                        next = redirect;
                    }
                    setTimeout(() => {
                        window.location.href = next;
                    }, 800);
                } else {
                    const extra = result.extra || {};
                    showMessage(extra.message || result.message || '用户名或密码错误', 'error');
                }
            } catch (error) {
                console.error('登录请求失败:', error);
                showMessage('网络连接失败，请稍后重试', 'error');
            } finally {
                if (submitButton) {
                    submitButton.textContent = originalText;
                    submitButton.disabled = false;
                }
            }
        });
    }
});
