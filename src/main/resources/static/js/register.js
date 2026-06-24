// 注册页面专用JavaScript函数

const PASSWORD_POLICY_MSG = '密码须为 8-32 位，且同时包含字母和数字';
const PASSWORD_POLICY = /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/;

document.addEventListener('DOMContentLoaded', function() {
    const registerForm = document.getElementById('registerForm');
    const phoneInput = document.getElementById('phone');
    const studentIdInput = document.getElementById('studentId');
    const passwordInput = document.getElementById('password');
    const confirmPasswordInput = document.getElementById('confirmPassword');

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
                showFieldHint('identifierError', type === 'phone' ? '请输入手机号' : '请输入学号/工号', inputId);
            }
            return false;
        }
        if (!isValidIdentifier(value, type)) {
            if (showHint) {
                showFieldHint('identifierError', type === 'phone' ? '手机号格式不正确' : '学号/工号格式不正确', inputId);
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
            if (showHint) showFieldHint('passwordError', '请输入密码', 'password');
            return false;
        }
        if (!PASSWORD_POLICY.test(pwd)) {
            if (showHint) showFieldHint('passwordError', PASSWORD_POLICY_MSG, 'password');
            return false;
        }
        clearFieldHint('passwordError', 'password');
        return true;
    }

    function validateConfirmPassword(showHint) {
        const pwd = passwordInput ? passwordInput.value : '';
        const confirm = confirmPasswordInput ? confirmPasswordInput.value : '';
        if (!confirm) {
            if (showHint) showFieldHint('confirmPasswordError', '请再次输入密码', 'confirmPassword');
            return false;
        }
        if (confirm !== pwd) {
            if (showHint) showFieldHint('confirmPasswordError', '两次输入的密码不一致', 'confirmPassword');
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

    if (registerForm) {
        registerForm.addEventListener('submit', async function(e) {
            e.preventDefault();

            const idOk = validateIdentifier(true);
            const pwdOk = validatePassword(true);
            const confirmOk = validateConfirmPassword(true);
            if (!idOk || !pwdOk || !confirmOk) {
                return;
            }

            const requestData = {
                identifier: getIdentifierValue(),
                password: passwordInput.value,
                role: window.currentIdentity || 'STUDENT'
            };

            const submitButton = registerForm.querySelector('button[type="submit"]');
            const originalText = submitButton ? submitButton.textContent : '注册';

            try {
                if (submitButton) {
                    submitButton.textContent = '注册中...';
                    submitButton.disabled = true;
                }

                const response = await apiFetch('/api/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(requestData)
                });

                const result = await response.json();

                if (response.ok && result.code === 200) {
                    showMessage('注册成功，请登录', 'success');
                    setTimeout(() => {
                        window.location.href = '/login.html';
                    }, 800);
                } else {
                    showMessage(result.message || '注册失败，请稍后重试', 'error');
                }
            } catch (error) {
                console.error('注册请求失败:', error);
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
