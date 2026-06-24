// 个人中心 · 修改密码

document.addEventListener('DOMContentLoaded', function () {
    const form = document.getElementById('passwordForm');
    if (!form) return;

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        handlePasswordChange();
    });
});

async function handlePasswordChange() {
    const oldPassword = document.getElementById('oldPassword');
    const newPassword = document.getElementById('newPassword');
    const confirmPassword = document.getElementById('confirmPassword');
    const submitBtn = document.getElementById('passwordSubmitBtn');

    // 清除之前的校验状态
    [oldPassword, newPassword, confirmPassword].forEach(function (input) {
        input.classList.remove('is-invalid', 'is-valid');
    });

    // 校验：所有字段必填
    if (!oldPassword.value.trim()) {
        oldPassword.classList.add('is-invalid');
        oldPassword.focus();
        showMessage('请输入旧密码', 'warning');
        return;
    }

    if (!newPassword.value.trim()) {
        newPassword.classList.add('is-invalid');
        newPassword.focus();
        showMessage('请输入新密码', 'warning');
        return;
    }

    const pwdPolicy = /^(?=.*[A-Za-z])(?=.*\d).{8,32}$/;
    if (!pwdPolicy.test(newPassword.value.trim())) {
        newPassword.classList.add('is-invalid');
        newPassword.focus();
        showMessage('密码须为 8-32 位，且同时包含字母和数字', 'warning');
        return;
    }

    if (!confirmPassword.value.trim()) {
        confirmPassword.classList.add('is-invalid');
        confirmPassword.focus();
        showMessage('请确认新密码', 'warning');
        return;
    }

    // 校验：两次密码一致
    if (newPassword.value !== confirmPassword.value) {
        confirmPassword.classList.add('is-invalid');
        confirmPassword.focus();
        showMessage('两次输入的密码不一致', 'error');
        return;
    }

    // 校验：新旧密码不能相同
    if (oldPassword.value === newPassword.value) {
        newPassword.classList.add('is-invalid');
        newPassword.focus();
        showMessage('新密码不能与旧密码相同', 'warning');
        return;
    }

    // 禁用按钮，防止重复提交
    submitBtn.disabled = true;
    submitBtn.textContent = '提交中...';

    try {
        const response = await apiFetch('/api/user/password', {
            method: 'PUT',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                oldPassword: oldPassword.value,
                newPassword: newPassword.value,
                confirmPassword: confirmPassword.value
            })
        });

        const result = await response.json();

        if (result.code === 200) {
            showMessage('密码修改成功，即将跳转到登录页...', 'success');
            // 清空表单
            oldPassword.value = '';
            newPassword.value = '';
            confirmPassword.value = '';
            // 延迟跳转到登录页
            setTimeout(function () {
                window.location.href = '/login.html';
            }, 1500);
        } else {
            showMessage(result.message || '密码修改失败', 'error');
            if (result.message && result.message.includes('旧密码')) {
                oldPassword.classList.add('is-invalid');
                oldPassword.focus();
            }
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误，请稍后重试', 'error');
    } finally {
        submitBtn.disabled = false;
        submitBtn.textContent = '确认修改';
    }
}
