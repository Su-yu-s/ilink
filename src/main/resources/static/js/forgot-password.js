document.addEventListener('DOMContentLoaded', () => {
    const token = new URLSearchParams(window.location.search).get('token') || '';
    const requestPanel = document.getElementById('passwordResetRequestPanel');
    const confirmPanel = document.getElementById('passwordResetConfirmPanel');
    requestPanel.hidden = Boolean(token);
    confirmPanel.hidden = !token;

    document.getElementById('passwordResetRequestForm')?.addEventListener('submit', async event => {
        event.preventDefault();
        const account = document.getElementById('resetAccount')?.value.trim() || '';
        if (!account) {
            showMessage('请输入账号信息', 'warning');
            return;
        }
        await submitResetRequest(event.currentTarget, account);
    });

    document.getElementById('passwordResetConfirmForm')?.addEventListener('submit', async event => {
        event.preventDefault();
        const password = document.getElementById('resetNewPassword')?.value || '';
        const confirmation = document.getElementById('resetConfirmPassword')?.value || '';
        if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,32}$/.test(password)) {
            showMessage('密码须为 8-32 位，且同时包含大写字母、小写字母和数字', 'warning');
            return;
        }
        if (password !== confirmation) {
            showMessage('两次输入的密码不一致', 'warning');
            return;
        }
        await submitNewPassword(event.currentTarget, token, password);
    });
});

async function submitResetRequest(form, account) {
    const button = form.querySelector('button[type="submit"]');
    setSubmitting(button, true, '发送中…');
    try {
        const response = await apiFetch('/api/password-reset/request', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ account })
        });
        const result = await response.json();
        if (!response.ok || result.code !== 200) throw new Error(result.message || '请求失败');
        showMessage(result.message, 'success');
        form.reset();
    } catch (error) {
        showMessage(error.message || '发送失败，请稍后重试', 'error');
    } finally {
        setSubmitting(button, false);
    }
}

async function submitNewPassword(form, token, newPassword) {
    const button = form.querySelector('button[type="submit"]');
    setSubmitting(button, true, '重置中…');
    try {
        const response = await apiFetch('/api/password-reset/confirm', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ token, newPassword })
        });
        const result = await response.json();
        if (!response.ok || result.code !== 200) throw new Error(result.message || '重置失败');
        showMessage(result.message, 'success');
        setTimeout(() => window.location.replace('/login.html'), 1000);
    } catch (error) {
        showMessage(error.message || '重置失败，请重新申请链接', 'error');
    } finally {
        setSubmitting(button, false);
    }
}

function setSubmitting(button, submitting, loadingText) {
    if (!button) return;
    if (!button.dataset.originalText) button.dataset.originalText = button.textContent;
    button.disabled = submitting;
    button.textContent = submitting ? loadingText : button.dataset.originalText;
}
