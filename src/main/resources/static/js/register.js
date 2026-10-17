// 注册页面专用JavaScript函数

const PASSWORD_POLICY_MSG = '密码须为 8-32 位，且同时包含字母和数字';
const PASSWORD_POLICY = /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/;

document.addEventListener('DOMContentLoaded', function() {
    const registerForm = document.getElementById('registerForm');
    const phoneInput = document.getElementById('phone');
    const studentIdInput = document.getElementById('studentId');
    const passwordInput = document.getElementById('password');
    const confirmPasswordInput = document.getElementById('confirmPassword');
    const emailInput = document.getElementById('email');
    const emailCodeInput = document.getElementById('emailCode');

    function getActiveIdentifierType() {
        const studentWrapper = document.getElementById('studentIdWrapper');
        if (studentWrapper && studentWrapper.style.display !== 'none') {
            return 'studentId';
        }
        return 'phone';
    }

    function getIdentifierValue() {
        const type = getActiveIdentifierType();
        if (type === 'studentId') {
            return studentIdInput ? studentIdInput.value.trim() : '';
        }
        return phoneInput ? phoneInput.value.trim() : '';
    }

    function isValidIdentifier(value, type) {
        if (type === 'phone') {
            return /^1[3-9]\d{9}$/.test(value);
        }
        return /^\d{5,15}$/.test(value);
    }

    function validateIdentifier(showHint) {
        const type = getActiveIdentifierType();
        const value = getIdentifierValue();
        const inputId = type === 'phone' ? 'phone' : 'studentId';
        if (!value) {
            if (showHint) {
                const identityLabel = window.currentIdentity === 'TEACHER' ? t('auth.register.staffId') : t('auth.register.studentIdLabel');
                showFieldHint('identifierError', type === 'phone' ? t('auth.register.validation.phone') : t('auth.register.validation.identifier', { label: identityLabel }), inputId);
            }
            return false;
        }
        if (!isValidIdentifier(value, type)) {
            if (showHint) {
                const identityLabel = window.currentIdentity === 'TEACHER' ? t('auth.register.staffId') : t('auth.register.studentIdLabel');
                showFieldHint('identifierError', type === 'phone' ? t('auth.register.validation.phoneFormat') : t('auth.register.validation.identifierFormat', { label: identityLabel }), inputId);
            }
            return false;
        }
        clearFieldHint('identifierError', inputId);
        if (type === 'phone' && studentIdInput) clearFieldHint('identifierError', 'studentId');
        else if (phoneInput) clearFieldHint('identifierError', 'phone');
        return true;
    }

    function validatePassword(showHint) {
        const pwd = passwordInput ? passwordInput.value : '';
        if (!pwd) {
            if (showHint) showFieldHint('passwordError', t('auth.register.validation.password'), 'password');
            return false;
        }
        if (!PASSWORD_POLICY.test(pwd)) {
            if (showHint) showFieldHint('passwordError', t('auth.register.validation.passwordPolicy'), 'password');
            return false;
        }
        clearFieldHint('passwordError', 'password');
        return true;
    }

    function validateConfirmPassword(showHint) {
        const pwd = passwordInput ? passwordInput.value : '';
        const confirm = confirmPasswordInput ? confirmPasswordInput.value : '';
        if (!confirm) {
            if (showHint) showFieldHint('confirmPasswordError', t('auth.register.validation.confirmPassword'), 'confirmPassword');
            return false;
        }
        if (confirm !== pwd) {
            if (showHint) showFieldHint('confirmPasswordError', t('auth.register.validation.passwordMismatch'), 'confirmPassword');
            return false;
        }
        clearFieldHint('confirmPasswordError', 'confirmPassword');
        return true;
    }

    [phoneInput, studentIdInput].forEach(function (input) {
        if (!input) return;
        input.addEventListener('input', () => clearFieldHint('identifierError', input.id));
        input.addEventListener('blur', () => validateIdentifier(true));
    });
    if (passwordInput) {
        passwordInput.addEventListener('input', () => {
            clearFieldHint('passwordError', 'password');
            if (confirmPasswordInput && confirmPasswordInput.value) validateConfirmPassword(true);
        });
        passwordInput.addEventListener('blur', () => validatePassword(true));
    }
    if (confirmPasswordInput) {
        confirmPasswordInput.addEventListener('input', () => clearFieldHint('confirmPasswordError', 'confirmPassword'));
        confirmPasswordInput.addEventListener('blur', () => validateConfirmPassword(true));
    }

    // 邮箱验证码：发码后 60 秒倒计时，与后端冷却窗口一致
    const sendCodeBtn = document.getElementById('sendEmailCodeBtn');
    let codeCountdown = null;

    function startCodeCountdown(seconds) {
        let left = seconds;
        sendCodeBtn.textContent = t('auth.register.countdown', { seconds: left });
        if (codeCountdown) clearInterval(codeCountdown);
        codeCountdown = setInterval(function () {
            left -= 1;
            if (left <= 0) {
                clearInterval(codeCountdown);
                codeCountdown = null;
                sendCodeBtn.disabled = false;
                sendCodeBtn.textContent = t('auth.register.sendCode');
                return;
            }
            sendCodeBtn.textContent = t('auth.register.countdown', { seconds: left });
        }, 1000);
    }

    if (sendCodeBtn) {
        sendCodeBtn.addEventListener('click', async function () {
            const email = emailInput ? emailInput.value.trim() : '';
            if (!/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email)) {
                showMessage(t('auth.register.validation.email'), 'warning');
                if (emailInput) emailInput.focus();
                return;
            }
            sendCodeBtn.disabled = true;
            try {
                const response = await apiFetch('/api/register/email-code', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email: email })
                });
                const result = await response.json();
                if (response.ok && result.code === 200) {
                    showMessage(t('auth.register.emailSent'), 'success');
                    startCodeCountdown(60);
                } else {
                    // 邮箱已被占用等业务拒绝：放开按钮让用户改邮箱，不进入倒计时
                    showMessage(result.message || t('auth.register.emailSendFailed'), 'error');
                    sendCodeBtn.disabled = false;
                }
            } catch (error) {
                console.error('发送验证码失败:', error);
                showMessage(t('auth.networkError'), 'error');
                sendCodeBtn.disabled = false;
            }
        });
    }

    if (registerForm) {
        registerForm.addEventListener('submit', async function(e) {
            e.preventDefault();

            const idOk = validateIdentifier(true);
            const pwdOk = validatePassword(true);
            const confirmOk = validateConfirmPassword(true);
            if (!idOk || !pwdOk || !confirmOk) {
                return;
            }

            const emailValue = emailInput ? emailInput.value.trim() : '';
            const emailCodeValue = emailCodeInput ? emailCodeInput.value.trim() : '';
            if (!/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(emailValue)) {
                showMessage(t('auth.register.validation.email'), 'error');
                if (emailInput) emailInput.focus();
                return;
            }
            if (!/^\d{6}$/.test(emailCodeValue)) {
                showMessage(t('auth.register.validation.emailCode'), 'error');
                if (emailCodeInput) emailCodeInput.focus();
                return;
            }

            const requestData = {
                identifier: getIdentifierValue(),
                password: passwordInput.value,
                role: window.currentIdentity || 'STUDENT',
                email: emailValue,
                emailCode: emailCodeValue
            };

            const submitButton = registerForm.querySelector('button[type="submit"]');
            const originalText = submitButton ? submitButton.textContent : t('auth.register.submit');

            try {
                if (submitButton) {
                    submitButton.textContent = t('auth.register.submitting');
                    submitButton.disabled = true;
                }

                const response = await apiFetch('/api/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(requestData)
                });

                const result = await response.json();

                if (response.ok && result.code === 200) {
                    const isTeacher = requestData.role === 'TEACHER';
                    showMessage(isTeacher ? t('auth.register.success.teacher') : t('auth.register.success.student'), 'success');
                    setTimeout(() => {
                        window.location.href = '/login.html';
                    }, 800);
                } else {
                    showMessage(result.message || t('auth.register.failed'), 'error');
                }
            } catch (error) {
                console.error('注册请求失败:', error);
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
