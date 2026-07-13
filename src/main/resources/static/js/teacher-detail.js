// 导师详情页面JavaScript

// 获取URL参数
const urlParams = new URLSearchParams(window.location.search);
const teacherIdParam = (urlParams.get('id') || '').trim();
const teacherId = /^\d+$/.test(teacherIdParam) && Number(teacherIdParam) > 0 ? teacherIdParam : null;

function setTeacherDetailState(type, message) {
    const state = document.getElementById('teacherDetailState');
    const content = document.getElementById('teacherDetailContent');
    const title = document.getElementById('teacherDetailStateTitle');
    const messageEl = document.getElementById('teacherDetailStateMessage');
    const retry = document.getElementById('teacherDetailRetryBtn');
    const indicator = state && state.querySelector('.detail-load-state__indicator');
    const success = type === 'success';
    const loading = type === 'loading';

    if (state) {
        state.hidden = success;
        state.classList.toggle('detail-load-state--error', type === 'error');
        state.setAttribute('aria-busy', loading ? 'true' : 'false');
    }
    if (content) content.hidden = !success;
    if (indicator) indicator.hidden = !loading;
    if (retry) retry.hidden = loading || success;
    if (title) title.textContent = loading ? '正在加载导师信息' : '导师信息暂时无法显示';
    if (messageEl) {
        messageEl.textContent = message || (loading ? '正在连接导师服务，请稍候…' : '请检查网络后重新加载。');
    }
}

async function fetchTeacherDetail() {
    const response = await apiFetch(`/api/teacher/${encodeURIComponent(teacherId)}`);
    let result;
    try {
        result = await response.json();
    } catch (error) {
        throw new Error('服务器返回了无法解析的数据，请稍后重试');
    }
    if (!response.ok || !result || (result.code !== 200 && result.code !== 0)) {
        throw new Error((result && result.message) || `导师详情请求失败（${response.status}）`);
    }
    if (!result.data || typeof result.data !== 'object') {
        throw new Error('导师数据为空，请稍后重试');
    }
    return result.data;
}

async function loadTeacherDetail() {
    setTeacherDetailState('loading');
    try {
        const teacher = await fetchTeacherDetail();
        renderTeacherDetail(teacher);
        setTeacherDetailState('success');
        await initProjectApplySection(teacher);
    } catch (error) {
        console.error('获取导师详情异常:', error);
        setTeacherDetailState('error', error && error.message ? error.message : '网络异常，请稍后重试。');
    }
}

// 页面加载完成后获取导师详情
document.addEventListener('DOMContentLoaded', async function() {
    document.getElementById('teacherDetailRetryBtn')?.addEventListener('click', loadTeacherDetail);
    const applyBtn = document.getElementById('projectApplyBtn');
    if (applyBtn) {
        applyBtn.addEventListener('click', () => submitProjectApply(teacherId));
    }

    if (!teacherId) {
        setTeacherDetailState('error', '链接中缺少有效的导师 ID，请返回导师招贤重新选择。');
        return;
    }
    await loadTeacherDetail();
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

    const majorText = teacher.expertise || '未指定';
    const titleText = teacher.professionalTitle || '';

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

    const projectsRow = document.getElementById('teacherProjectsRow');
    const projectsElem = document.getElementById('teacherProjects');
    const projectsText = String(teacher.projects || '').trim();
    if (projectsRow) projectsRow.hidden = !projectsText;
    if (projectsElem) projectsElem.textContent = projectsText;
    
    const timeElem = document.getElementById('applicationTime');
    if (timeElem) timeElem.textContent = formatTime(teacher.createdAt);
}

async function initProjectApplySection(teacher) {
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
        const currentUser = profJson.data || {};
        const isOwner = currentUser.id != null && teacher.userId != null
            && String(currentUser.id) === String(teacher.userId);
        if (isOwner) {
            const applySection = document.getElementById('projectApplySection');
            if (applySection) applySection.hidden = true;
            await loadTeacherPendingApplications();
            return;
        }
        if (hint) hint.textContent = '';

        const st = await apiFetch(`/api/teacher/project-application-status?teacherId=${encodeURIComponent(teacher.id)}`);
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

async function loadTeacherPendingApplications() {
    const section = document.getElementById('teacherPendingSection');
    const list = document.getElementById('teacherPendingList');
    const count = document.getElementById('teacherPendingCount');
    if (!section || !list) return;

    section.hidden = false;
    list.innerHTML = '<p class="teacher-detail__pending-empty">正在加载待处理申请…</p>';
    try {
        const response = await apiFetch('/api/teacher/my/project-applications');
        const result = await response.json();
        if (!response.ok || !result || result.code !== 200) {
            throw new Error((result && result.message) || '待处理申请加载失败');
        }
        const applications = Array.isArray(result.data) ? result.data : [];
        if (count) count.textContent = String(applications.length);
        renderTeacherPendingApplications(applications);
    } catch (error) {
        console.error('loadTeacherPendingApplications', error);
        list.innerHTML = '<p class="teacher-detail__pending-empty">待处理申请暂时无法加载，请刷新页面重试。</p>';
    }
}

function renderTeacherPendingApplications(applications) {
    const list = document.getElementById('teacherPendingList');
    if (!list) return;
    if (!applications.length) {
        list.innerHTML = '<p class="teacher-detail__pending-empty">暂无待处理的合作申请。</p>';
        return;
    }

    list.innerHTML = applications.map(function (application) {
        const preview = application.applicantPreview || {
            id: application.applicantUserId,
            realName: application.applicantName,
            avatar: application.applicantAvatar
        };
        const name = application.applicantName || '学生用户';
        const avatar = typeof publisherAvatarHtml === 'function'
            ? publisherAvatarHtml(preview, 'publisher-avatar--md', { preferRealName: true })
            : '<span class="teacher-detail__pending-fallback">' + escapeHtml(name.charAt(0) || '学') + '</span>';
        return '<article class="teacher-detail__pending-card" data-application-id="' + escapeHtml(application.id) + '">' +
            '<div class="teacher-detail__pending-user">' + avatar +
                '<div><h3>' + escapeHtml(name) + '</h3><time>' + escapeHtml(formatTime(application.createdAt)) + '</time></div>' +
            '</div>' +
            '<p class="teacher-detail__pending-message">' + escapeHtml(application.message || '未填写申请说明') + '</p>' +
            '<div class="teacher-detail__pending-actions">' +
                '<button type="button" class="teacher-detail__decision teacher-detail__decision--reject" data-action="REJECTED" data-id="' + escapeHtml(application.id) + '">拒绝</button>' +
                '<button type="button" class="teacher-detail__decision teacher-detail__decision--approve" data-action="APPROVED" data-id="' + escapeHtml(application.id) + '">通过</button>' +
            '</div>' +
        '</article>';
    }).join('');

    if (!list.dataset.bound) {
        list.dataset.bound = '1';
        list.addEventListener('click', async function (event) {
            const button = event.target.closest('button[data-id][data-action]');
            if (!button || !list.contains(button)) return;
            await decideProjectApplication(button.dataset.id, button.dataset.action, button);
        });
    }
}

async function decideProjectApplication(applicationId, action, sourceButton) {
    const card = sourceButton && sourceButton.closest('.teacher-detail__pending-card');
    const buttons = card ? card.querySelectorAll('button') : [];
    buttons.forEach(function (button) { button.disabled = true; });
    try {
        const response = await apiFetch('/api/teacher/project-application/' + encodeURIComponent(applicationId) + '/approve', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: action })
        });
        const result = await response.json();
        if (!response.ok || !result || result.code !== 200) {
            throw new Error((result && result.message) || '处理申请失败');
        }
        showMessage(action === 'APPROVED' ? '已通过申请' : '已拒绝申请', 'success');
        await loadTeacherPendingApplications();
    } catch (error) {
        console.error('decideProjectApplication', error);
        showMessage(error && error.message ? error.message : '处理申请失败，请稍后重试', 'error');
        buttons.forEach(function (button) { button.disabled = false; });
    }
}
