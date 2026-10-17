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
            if (showHint) showFieldHint('identifierError', t('auth.login.validation.identifier'), 'identifier');
            return false;
        }
        clearFieldHint('identifierError', 'identifier');
        return true;
    }

    function validatePassword(showHint) {
        const value = passwordInput ? passwordInput.value : '';
        if (!value) {
            if (showHint) showFieldHint('passwordError', t('auth.login.validation.password'), 'password');
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
                password: passwordInput.value,
                rememberMe: Boolean(document.getElementById('rememberMe')?.checked),
                returnTo: safeLoginReturnPath(new URLSearchParams(window.location.search).get('redirect'))
            };

            const submitButton = loginForm.querySelector('button[type="submit"]');
            const originalText = submitButton ? submitButton.textContent : t('auth.login.submit');

            try {
                if (submitButton) {
                    submitButton.textContent = t('auth.login.submitting');
                    submitButton.disabled = true;
                }

                const response = await apiFetch('/api/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(requestData)
                });

                const contentType = response.headers.get('content-type') || '';
                if (!contentType.includes('application/json')) {
                    throw new Error(t('common.error'));
                }

                const result = await response.json();

                if (response.ok && result.code === 200) {
                    showMessage(t('auth.login.success'), 'success');

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
                    showMessage(extra.message || result.message || t('auth.login.invalid'), 'error');
                }
            } catch (error) {
                console.error('登录请求失败:', error);
                showMessage(t('auth.networkError'), 'error');
            } finally {
                if (submitButton) {
                    submitButton.textContent = originalText;
                    submitButton.disabled = false;
                }
            }
        });
    }
});

function safeLoginReturnPath(value) {
    if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//') || value.includes('\\')) {
        return null;
    }
    try {
        const url = new URL(value, window.location.origin);
        if (url.origin !== window.location.origin || url.pathname.startsWith('/login')
                || url.pathname.startsWith('/api/login') || url.pathname.startsWith('/api/logout')) {
            return null;
        }
        return url.pathname + url.search + url.hash;
    } catch (error) {
        return null;
    }
}
