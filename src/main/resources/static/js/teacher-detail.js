// 导师详情页面JavaScript

// 获取URL参数
const urlParams = new URLSearchParams(window.location.search);
const teacherId = urlParams.get('id');

// 页面加载完成后获取导师详情
document.addEventListener('DOMContentLoaded', async function() {
    if (!teacherId) {
        showMessage('缺少导师ID参数', 'error');
        setTimeout(() => {
            window.location.href = '/teacher-wall.html';
        }, 1000);
        return;
    }

    try {
        const response = await apiFetch(`/api/teacher/${teacherId}`);
        const result = await response.json();

        if (result.code === 200) {
            renderTeacherDetail(result.data);
            await initProjectApplySection(teacherId);
        } else {
            showMessage('获取导师详情失败: ' + result.message, 'error');
            setTimeout(() => {
                window.location.href = '/teacher-wall.html';
            }, 1000);
        }
    } catch (error) {
        console.error('获取导师详情异常:', error);
        showMessage('系统异常，请稍后重试', 'error');
        setTimeout(() => {
            window.location.href = '/teacher-wall.html';
        }, 1000);
    }
    
    const applyBtn = document.getElementById('projectApplyBtn');
    if (applyBtn) {
        applyBtn.addEventListener('click', () => submitProjectApply(teacherId));
    }
});

// 渲染导师详情
function renderTeacherDetail(teacher) {
    const avSlot = document.getElementById('teacherHeaderAvatar');
    if (avSlot) {
        if (typeof publisherAvatarHtml === 'function' && teacher.userPreview && teacher.userPreview.id != null) {
            avSlot.innerHTML = publisherAvatarHtml(teacher.userPreview, 'publisher-avatar--lg', { preferRealName: true });
        } else {
            avSlot.innerHTML = '';
        }
    }

    const pv = teacher.userPreview;
    const display =
        pv &&
        ((pv.username && String(pv.username).trim()) || (pv.realName && String(pv.realName).trim()))
            ? (pv.username && String(pv.username).trim()) || pv.realName
            : '';
    document.getElementById('teacherName').textContent = display ? display + ' · 导师' : `导师 #${teacher.id}`;
    
    // 更新各个字段
    const majorElem = document.getElementById('teacherMajor');

    let majorText = teacher.projects || '未指定';
    let titleText = '';

    // teacher.projects 由前端存为：专业领域（职称），这里拆分出来用于更准确展示
    const titleMatch = majorText.match(/（([^）]+)）/);
    if (titleMatch && titleMatch[1]) {
        titleText = titleMatch[1].trim();
        majorText = majorText.replace(titleMatch[0], '').trim();
    }

    if (majorElem) majorElem.textContent = majorText || '未指定';

    const titleElem = document.getElementById('teacherTitle');
    if (titleElem) {
        if (titleText) {
            titleElem.textContent = titleText;
        } else {
            titleElem.textContent = teacher.status === 'APPROVED' ? '已认证导师' : '待审核';
        }
    }
    
    const bioElem = document.getElementById('teacherBio');
    if (bioElem) bioElem.textContent = teacher.introduction || '暂无简介';
    
    const researchElem = document.getElementById('teacherResearch');
    if (researchElem) researchElem.textContent = teacher.researchDirection || '未指定';
    
    const timeElem = document.getElementById('applicationTime');
    if (timeElem) timeElem.textContent = formatTime(teacher.createdAt);
}

async function initProjectApplySection(teacherId) {
    const hint = document.getElementById('projectApplyHint');
    const statusEl = document.getElementById('projectApplyStatus');
    const textarea = document.getElementById('projectApplyMessage');
    const btn = document.getElementById('projectApplyBtn');

    try {
        const prof = await apiFetch('/api/user/profile');
        const profJson = await prof.json();
        if (profJson.code !== 200) {
            if (hint) hint.textContent = '请先登录后再提交申请。';
            if (textarea) textarea.disabled = true;
            if (btn) btn.disabled = true;
            return;
        }
        if (hint) hint.textContent = '';

        const st = await apiFetch(`/api/teacher/project-application-status?teacherId=${encodeURIComponent(teacherId)}`);
        const stJson = await st.json();
        if (stJson.code !== 200) {
            return;
        }
        const s = stJson.data && stJson.data.status;
        if (s === 'NOT_APPLIED') {
            if (btn) btn.disabled = false;
            if (textarea) textarea.disabled = false;
            if (statusEl) statusEl.textContent = '';
            return;
        }
        if (textarea) textarea.disabled = true;
        if (btn) btn.disabled = true;
        const label = s === 'PENDING' ? '申请已提交，等待导师审核。'
            : s === 'APPROVED' ? '导师已通过您的申请。'
                : s === 'REJECTED' ? '该申请未通过审核。' : `当前状态：${s}`;
        if (statusEl) statusEl.textContent = label;
    } catch (e) {
        console.error('initProjectApplySection', e);
    }
}

async function submitProjectApply(teacherId) {
    const textarea = document.getElementById('projectApplyMessage');
    const statusEl = document.getElementById('projectApplyStatus');
    const msg = textarea ? textarea.value.trim() : '';

    try {
        const response = await apiFetch('/api/teacher/project-apply', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                teacherId: Number(teacherId),
                message: msg || ''
            })
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('申请已提交', 'success');
            if (statusEl) statusEl.textContent = '申请已提交，等待导师审核。';
            if (textarea) textarea.disabled = true;
            const btn = document.getElementById('projectApplyBtn');
            if (btn) btn.disabled = true;
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
        } else {
            showMessage(result.message || '提交失败', 'error');
        }
    } catch (e) {
        console.error('submitProjectApply', e);
        showMessage('网络错误，请稍后重试', 'error');
    }
}