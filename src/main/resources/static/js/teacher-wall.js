// 导师招贤页面JavaScript

// 当前页码
let currentPage = 1;
const pageSize = 10;

function openApplyModal() {
    const modal = document.getElementById('applyModal');
    if (!modal) return;
    modal.classList.add('show');
    modal.setAttribute('aria-hidden', 'false');
    document.body.classList.add('teacher-apply-open');
}

function closeApplyModal() {
    const modal = document.getElementById('applyModal');
    if (!modal) return;
    modal.classList.remove('show');
    modal.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('teacher-apply-open');
}

function initApplyModal() {
    const modal = document.getElementById('applyModal');
    if (!modal) return;
    modal.addEventListener('click', function(e) {
        if (e.target === modal) closeApplyModal();
    });
    document.addEventListener('keydown', function(e) {
        if (e.key === 'Escape' && modal.classList.contains('show')) closeApplyModal();
    });
}

function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function getPaginationMetaFromResult(result) {
    if (result && result.extra && result.extra.pagination) return result.extra.pagination;
    if (result && result.pagination) return result.pagination;
    return null;
}

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', function() {
    initApplyModal();

    // 加载导师列表
    loadTeacherList();
    
    // 绑定筛选表单提交事件
    const filterForm = document.getElementById('filterForm');
    if (filterForm) {
        filterForm.addEventListener('submit', function(e) {
            e.preventDefault();
            currentPage = 1;
            loadTeacherList();
        });
    }
    
    // 绑定重置按钮事件
    const resetBtn = document.querySelector('#filterForm button[type="reset"]');
    if (resetBtn) {
        resetBtn.addEventListener('click', function() {
            setTimeout(() => {
                currentPage = 1;
                loadTeacherList();
            }, 100);
        });
    }
    
    // 绑定提交申请按钮事件
    const submitApplyBtn = document.getElementById('submitApply');
    if (submitApplyBtn) {
        submitApplyBtn.addEventListener('click', function() {
            submitTeacherApplication();
        });
    }
});

// 加载导师列表
async function loadTeacherList() {
    try {
        const keyword = document.getElementById('keyword') ? document.getElementById('keyword').value : '';
        const major = document.getElementById('major') ? document.getElementById('major').value : '';
        const title = document.getElementById('title') ? document.getElementById('title').value : '';

        const params = new URLSearchParams({
            page: currentPage,
            size: pageSize,
            keyword: keyword,
            major: major,
            title: title
        });

        const response = await apiFetch(`/api/teacher/list?${params}`, { credentials: 'same-origin' });
        const result = await response.json();
        
        if (result.code === 200) {
            renderTeacherList(result.data);
            const pagination = (result.extra && result.extra.pagination) || result.pagination;
            renderPagination(pagination);
        } else {
            console.error('获取导师列表失败:', result.message);
            showMessage('获取导师列表失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('获取导师列表异常:', error);
        showMessage('网络错误，请稍后重试', 'error');
    }
}

function renderPagination(pagination) {
    const paginationNav = document.getElementById('pagination');
    if (!paginationNav || !pagination) return;

    const { page, size, total } = pagination;
    const safePage = page || 1;
    const safeSize = size || pageSize;
    const safeTotal = total || 0;
    const totalPages = Math.max(1, Math.ceil(safeTotal / safeSize));

    if (totalPages <= 1) {
        paginationNav.classList.add('d-none');
        return;
    }

    const ul = paginationNav.querySelector('ul.pagination') || paginationNav.querySelector('ul');
    if (!ul) return;

    ul.innerHTML = '';
    paginationNav.classList.remove('d-none');

    const makeItem = (label, targetPage, isActive = false, isDisabled = false) => {
        const li = document.createElement('li');
        li.className = `page-item${isActive ? ' active' : ''}${isDisabled ? ' disabled' : ''}`;

        const a = document.createElement('a');
        a.className = 'page-link';
        a.href = '#';
        a.textContent = label;
        a.addEventListener('click', (e) => {
            e.preventDefault();
            if (isDisabled || !targetPage) return;
            currentPage = targetPage;
            loadTeacherList();
        });
        li.appendChild(a);
        return li;
    };

    const prevPage = safePage - 1;
    ul.appendChild(makeItem('上一页', prevPage >= 1 ? prevPage : null, false, prevPage < 1));

    let start = Math.max(1, safePage - 2);
    let end = Math.min(totalPages, safePage + 2);
    if (end - start < 4) {
        if (start === 1) end = Math.min(totalPages, start + 4);
        if (end === totalPages) start = Math.max(1, end - 4);
    }

    if (start > 1) {
        ul.appendChild(makeItem('1', 1, safePage === 1));
        if (start > 2) {
            const ellipsisLi = document.createElement('li');
            ellipsisLi.className = 'page-item disabled';
            ellipsisLi.innerHTML = `<a class="page-link" href="#">...</a>`;
            const ellipsisA = ellipsisLi.querySelector('a');
            if (ellipsisA) ellipsisA.addEventListener('click', (e) => e.preventDefault());
            ul.appendChild(ellipsisLi);
        }
    }

    for (let p = start; p <= end; p++) {
        ul.appendChild(makeItem(String(p), p, p === safePage));
    }

    if (end < totalPages) {
        if (end < totalPages - 1) {
            const ellipsisLi = document.createElement('li');
            ellipsisLi.className = 'page-item disabled';
            ellipsisLi.innerHTML = `<a class="page-link" href="#">...</a>`;
            const ellipsisA = ellipsisLi.querySelector('a');
            if (ellipsisA) ellipsisA.addEventListener('click', (e) => e.preventDefault());
            ul.appendChild(ellipsisLi);
        }
        ul.appendChild(makeItem(String(totalPages), totalPages, safePage === totalPages));
    }

    const nextPage = safePage + 1;
    ul.appendChild(makeItem('下一页', nextPage <= totalPages ? nextPage : null, false, nextPage > totalPages));
}

// 渲染导师列表
function renderTeacherList(teachers) {
    const teacherListContainer = document.getElementById('teacherList');
    teacherListContainer.innerHTML = '';
    
    if (!teachers || teachers.length === 0) {
        teacherListContainer.innerHTML = `
            <div class="il-list-empty" style="text-align: center; padding: 60px 24px; color: #6b7280;">
                <svg width="64" height="64" viewBox="0 0 24 24" fill="none" style="margin: 0 auto 16px; display: block; opacity: 0.5;">
                    <path d="M12 14c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zM20 22v-2c0-2.21-3.58-4-8-4s-8 1.79-8 4v2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
                <p style="font-size: 1rem;">暂无导师信息</p>
                <p style="font-size: 0.875rem; color: #9ca3af;">成为第一个申请导师的人吧！</p>
            </div>
        `;
        return;
    }
    
    teachers.forEach(teacher => {
        const teacherCard = document.createElement('div');
        teacherCard.className = 'il-teacher-card-shell';
        
        const introduction = teacher.introduction || '暂无简介';
        const truncatedIntro = introduction.length > 120 ? introduction.substring(0, 120) + '...' : introduction;
        
        const prev = teacher.userPreview;
        let teacherName = '导师 #' + teacher.id;
        let teacherAvatar = '导';
        let teacherAvatarUrl = null;
        if (prev && prev.id != null) {
            teacherName = (prev.realName && String(prev.realName).trim()) || (prev.username && String(prev.username).trim()) || teacherName;
            teacherAvatar = teacherName.charAt(0).toUpperCase();
            teacherAvatarUrl = prev.avatar || null;
        }

        function buildTeacherAvatarHtml(avatarUrl, fallbackChar, name) {
            if (avatarUrl) {
                return `<div class="il-teacher-avatar">
                    <img src="${escapeHtml(avatarUrl)}" alt="${escapeHtml(name)}"
                         onerror="this.style.display='none'; this.parentElement.querySelector('.il-teacher-avatar-fallback').style.display='flex';">
                    <span class="il-teacher-avatar-fallback" style="display:none;">${escapeHtml(fallbackChar)}</span>
                </div>`;
            }
            return `<div class="il-teacher-avatar"><span class="il-teacher-avatar-fallback">${escapeHtml(fallbackChar)}</span></div>`;
        }
        
        // 解析研究方向标签
        let researchTags = [];
        if (teacher.researchDirection) {
            researchTags = String(teacher.researchDirection).split(/[,，;；\s]+/).filter(s => s.trim()).slice(0, 5);
        }
        
        // 解析职称和专业
        let major = '其他';
        let title = '';
        if (teacher.projects) {
            const projectsStr = String(teacher.projects);
            const titleMatch = projectsStr.match(/（(.+?)）/);
            if (titleMatch) {
                title = titleMatch[1];
                major = projectsStr.replace(/（.+?）/, '').trim() || major;
            } else {
                major = projectsStr || major;
            }
        }
        
        teacherCard.innerHTML = `
            <div class="il-teacher-card" onclick="window.ILink && window.ILink.navigate ? window.ILink.navigate('/teacher-detail.html?id=${teacher.id}') : window.location.href='/teacher-detail.html?id=${teacher.id}'">
                <div class="il-teacher-header">
                    ${buildTeacherAvatarHtml(teacherAvatarUrl, teacherAvatar, teacherName)}
                    <div class="il-teacher-info">
                        <h3 class="il-teacher-name">${escapeHtml(teacherName)}</h3>
                        ${title ? `<p class="il-teacher-title">${escapeHtml(title)}</p>` : ''}
                        <span class="il-teacher-major">${escapeHtml(major)}</span>
                    </div>
                </div>
                <p class="il-teacher-bio">${escapeHtml(truncatedIntro)}</p>
                ${researchTags.length > 0 ? `
                    <div class="il-teacher-research">
                        ${researchTags.map(tag => `<span class="il-research-tag">${escapeHtml(tag.trim())}</span>`).join('')}
                    </div>
                ` : ''}
                <div class="il-teacher-footer">
                    <a href="/teacher-detail.html?id=${teacher.id}" class="il-btn il-btn-primary" style="padding: 8px 16px; font-size: 0.875rem;">查看详情</a>
                </div>
            </div>
        `;
        teacherListContainer.appendChild(teacherCard);
    });
}

// 提交导师申请
async function submitTeacherApplication() {
    const introduction = document.getElementById('applicationBio');
    const researchDirection = document.getElementById('applicationResearch');
    const projects = document.getElementById('applicationMajor');
    const applicationTitle = document.getElementById('applicationTitle');
    
    if (!introduction || !researchDirection || !applicationTitle) {
        showMessage('请填写完整信息', 'error');
        return;
    }
    
    const applicationData = {
        introduction: introduction.value,
        researchDirection: researchDirection.value,
        projects: (function() {
            const majorVal = projects ? projects.value : '';
            const titleVal = applicationTitle ? applicationTitle.value : '';
            if (titleVal) {
                return `${majorVal || ''}（${titleVal}）`.trim();
            }
            return majorVal || '';
        })()
    };
    
    if (!applicationData.introduction || !applicationData.researchDirection) {
        showMessage('请填写必填字段', 'error');
        return;
    }

    if (!applicationData.projects) {
        showMessage('请填写专业领域与职称', 'error');
        return;
    }
    
    try {
        const response = await apiFetch('/api/teacher/apply', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            credentials: 'same-origin',
            body: JSON.stringify(applicationData)
        });
        
        const result = await response.json();
        
        if (result.code === 200) {
            showMessage('申请已提交，请等待管理员审核', 'success');
            // 关闭模态框
            closeApplyModal();
            // 清空表单
            if (introduction) introduction.value = '';
            if (researchDirection) researchDirection.value = '';
            if (projects) projects.value = '';
            if (applicationTitle) applicationTitle.value = '';
            // 重新加载导师列表
            loadTeacherList();
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => {
                window.location.href = '/login.html';
            }, 1500);
        } else {
            showMessage('申请提交失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('提交导师申请异常:', error);
        showMessage('网络错误，请稍后重试', 'error');
    }
}
