// 个人中心 · 注销账号（三步：了解后果 → 验证密码 → 输入「注销」确认）

document.addEventListener('DOMContentLoaded', function () {
    const root = document.querySelector('.acct-delete');
    if (!root) return;

    const panels = Array.from(root.querySelectorAll('.acct-delete__panel'));
    const indicators = Array.from(root.querySelectorAll('[data-step-indicator]'));
    const passwordInput = document.getElementById('acctDeletePassword');
    const confirmInput = document.getElementById('acctDeleteConfirm');
    const submitBtn = document.getElementById('acctDeleteSubmit');

    function showStep(step) {
        panels.forEach(function (panel) {
            panel.hidden = Number(panel.dataset.step) !== step;
        });
        indicators.forEach(function (item) {
            const index = Number(item.dataset.stepIndicator);
            item.classList.toggle('is-active', index === step);
            item.classList.toggle('is-done', index < step);
        });
        if (step === 2 && passwordInput) passwordInput.focus();
        if (step === 3 && confirmInput) confirmInput.focus();
    }

    root.querySelectorAll('[data-delete-next]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            const next = Number(btn.dataset.deleteNext);
            if (next === 3 && passwordInput && !passwordInput.value.trim()) {
                passwordInput.focus();
                showMessage(t('account.delete.client.password'), 'warning');
                return;
            }
            showStep(next);
        });
    });

    root.querySelectorAll('[data-delete-back]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            showStep(Number(btn.dataset.deleteBack));
        });
    });

    // 确认框里实时回显状态，避免用户误以为已可以提交
    if (confirmInput && submitBtn) {
        confirmInput.addEventListener('input', function () {
            const ready = confirmInput.value.trim() === '注销';
            submitBtn.classList.toggle('il-btn--danger', ready);
            submitBtn.classList.toggle('il-btn--outline', !ready);
        });
    }

    submitBtn && submitBtn.addEventListener('click', submitDeleteAccount);

    async function submitDeleteAccount() {
        const password = passwordInput ? passwordInput.value : '';
        const confirmText = confirmInput ? confirmInput.value.trim() : '';
        if (!password) {
            showStep(2);
            showMessage(t('account.delete.client.password'), 'warning');
            return;
        }
        if (confirmText !== '注销') {
            confirmInput && confirmInput.focus();
            showMessage(t('account.delete.client.confirm'), 'warning');
            return;
        }

        submitBtn.disabled = true;
        const originalText = submitBtn.textContent;
        submitBtn.textContent = t('account.delete.submitting');

        try {
            const response = await apiFetch('/api/user/delete-account', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ password: password, confirmText: confirmText })
            });
            const result = await response.json();
            if (result.code === 200) {
                showMessage(t('account.delete.success.redirect'), 'success');
                setTimeout(function () {
                    window.location.href = '/index.html';
                }, 1500);
                return;
            }
            showMessage(result.message || t('account.delete.client.failed'), 'error');
            if (result.code === 409) {
                // 创建者阻断：回到第一步让用户看到处理指引
                showStep(1);
            }
        } catch (e) {
            console.error(e);
            showMessage(t('account.delete.client.network'), 'error');
        } finally {
            submitBtn.disabled = false;
            submitBtn.textContent = originalText;
        }
    }
});
