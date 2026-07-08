// 导师招贤页面JavaScript (Editorial)

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

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', function() {
    initApplyModal();

    // 绑定搜索按钮
    var searchBtn = document.getElementById('searchBtn');
    if (searchBtn) {
        searchBtn.addEventListener('click', function() {
            currentPage = 1;
            loadTeacherList();
        });
    }

    // 绑定 Enter 键搜索
    var keywordInput = document.getElementById('keyword');
    if (keywordInput) {
        keywordInput.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') {
                e.preventDefault();
                currentPage = 1;
                loadTeacherList();
            }
        });
    }

    // 筛选变化时自动搜索
    ['major', 'title'].forEach(function(id) {
        var el = document.getElementById(id);
        if (el) {
            el.addEventListener('change', function() {
                currentPage = 1;
                loadTeacherList();
            });
        }
    });

    // 绑定提交申请按钮事件
    var submitApplyBtn = document.getElementById('submitApply');
    if (submitApplyBtn) {
        submitApplyBtn.addEventListener('click', function() {
            submitTeacherApplication();
        });
    }

    // 加载导师列表
    loadTeacherList();
});

// 加载导师列表
async function loadTeacherList() {
    try {
        var keyword = document.getElementById('keyword') ? document.getElementById('keyword').value : '';
        var major = document.getElementById('major') ? document.getElementById('major').value : '';
        var title = document.getElementById('title') ? document.getElementById('title').value : '';

        var params = new URLSearchParams({
            page: currentPage,
            size: pageSize,
            keyword: keyword,
            major: major,
            title: title
        });

        var response = await apiFetch('/api/teacher/list?' + params, { credentials: 'same-origin' });
        var result = await response.json();

        if (result.code === 200) {
            renderTeacherList(result.data);
            var pagination = (result.extra && result.extra.pagination) || result.pagination;
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
    var paginationNav = document.getElementById('pagination');
    if (!paginationNav || !pagination) return;

    var page = pagination.page || 1;
    var size = pagination.size || pageSize;
    var total = pagination.total || 0;
    var totalPages = Math.max(1, Math.ceil(total / size));

    if (totalPages <= 1) {
        paginationNav.classList.add('d-none');
        return;
    }

    var ul = paginationNav.querySelector('ul.pagination') || paginationNav.querySelector('ul');
    if (!ul) return;

    ul.innerHTML = '';
    paginationNav.classList.remove('d-none');

    var makeItem = function(label, targetPage, isActive, isDisabled) {
        isActive = isActive || false;
        isDisabled = isDisabled || false;
        var li = document.createElement('li');
        li.className = 'page-item' + (isActive ? ' active' : '') + (isDisabled ? ' disabled' : '');

        var a = document.createElement('a');
        a.className = 'page-link';
        a.href = '#';
        a.textContent = label;
        a.addEventListener('click', function(e) {
            e.preventDefault();
            if (isDisabled || !targetPage) return;
            currentPage = targetPage;
            loadTeacherList();
        });
        li.appendChild(a);
        return li;
    };

    var prevPage = page - 1;
    ul.appendChild(makeItem('上一页', prevPage >= 1 ? prevPage : null, false, prevPage < 1));

    var start = Math.max(1, page - 2);
    var end = Math.min(totalPages, page + 2);
    if (end - start < 4) {
        if (start === 1) end = Math.min(totalPages, start + 4);
        if (end === totalPages) start = Math.max(1, end - 4);
    }

    if (start > 1) {
        ul.appendChild(makeItem('1', 1, page === 1));
        if (start > 2) {
            var ellipsisLi = document.createElement('li');
            ellipsisLi.className = 'page-item disabled';
            ellipsisLi.innerHTML = '<a class="page-link" href="#">...</a>';
            var ellipsisA = ellipsisLi.querySelector('a');
            if (ellipsisA) ellipsisA.addEventListener('click', function(e) { e.preventDefault(); });
            ul.appendChild(ellipsisLi);
        }
    }

    for (var p = start; p <= end; p++) {
        ul.appendChild(makeItem(String(p), p, p === page));
    }

    if (end < totalPages) {
        if (end < totalPages - 1) {
            var ellipsisLi2 = document.createElement('li');
            ellipsisLi2.className = 'page-item disabled';
            ellipsisLi2.innerHTML = '<a class="page-link" href="#">...</a>';
            var ellipsisA2 = ellipsisLi2.querySelector('a');
            if (ellipsisA2) ellipsisA2.addEventListener('click', function(e) { e.preventDefault(); });
            ul.appendChild(ellipsisLi2);
        }
        ul.appendChild(makeItem(String(totalPages), totalPages, page === totalPages));
    }

    var nextPage = page + 1;
    ul.appendChild(makeItem('下一页', nextPage <= totalPages ? nextPage : null, false, nextPage > totalPages));
}

// 渲染导师列表（Editorial Card Style）
function renderTeacherList(teachers) {
    var container = document.getElementById('teacherList');
    container.innerHTML = '';

    if (!teachers || teachers.length === 0) {
        container.innerHTML =
            '<div class="empty-state">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">' +
                    '<path d="M12 14c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zM20 22v-2c0-2.21-3.58-4-8-4s-8 1.79-8 4v2"/>' +
                '</svg>' +
                '<p>暂无导师信息</p>' +
                '<span>成为第一个申请导师的人吧！</span>' +
            '</div>';
        return;
    }

    teachers.forEach(function(teacher) {
        var shell = document.createElement('div');
        shell.className = 'mentor-card-shell';
        shell.setAttribute('data-teacher-id', teacher.id);

        // 基本信息
        var introduction = teacher.introduction || '暂无简介';
        var prev = teacher.userPreview;
        var name = '导师 #' + teacher.id;
        var char = '导';
        var avatarUrl = null;
        if (prev && prev.id != null) {
            name = (prev.realName && String(prev.realName).trim()) || (prev.username && String(prev.username).trim()) || name;
            char = name.charAt(0).toUpperCase();
            avatarUrl = prev.avatar || null;
        }

        // 解析职称和专业
        var dept = '其他';
        var titleText = '';
        if (teacher.projects) {
            var projectsStr = String(teacher.projects);
            var titleMatch = projectsStr.match(/（(.+?)）/);
            if (titleMatch) {
                titleText = titleMatch[1];
                dept = projectsStr.replace(/（.+?）/, '').trim() || dept;
            } else {
                dept = projectsStr || dept;
            }
        }

        // 解析研究方向标签
        var tags = [];
        if (teacher.researchDirection) {
            tags = String(teacher.researchDirection).split(/[,，;；\s]+/).filter(function(s) { return s.trim(); }).slice(0, 5);
        }

        // 头像 HTML
        var avatarHtml;
        if (avatarUrl) {
            avatarHtml = '<div class="mentor-avatar">' +
                '<img src="' + escapeHtml(avatarUrl) + '" alt="' + escapeHtml(name) + '"' +
                ' onerror="this.style.display=\'none\'; this.parentElement.querySelector(\'.avatar-fallback\').style.display=\'flex\';">' +
                '<span class="avatar-fallback" style="display:none;">' + escapeHtml(char) + '</span>' +
                '</div>';
        } else {
            avatarHtml = '<div class="mentor-avatar">' +
                '<span class="avatar-fallback">' + escapeHtml(char) + '</span>' +
                '</div>';
        }

        // 职称行
        var titleRowHtml = titleText ? '<div class="mentor-title-row">' + escapeHtml(titleText) + '</div>' : '';

        // 详情链接
        var detailUrl = '/teacher-detail.html?id=' + teacher.id;

        // 研究方向标签
        var tagsHtml = tags.length > 0
            ? '<div class="mentor-tags">' +
                tags.map(function(t) { return '<span class="mentor-tag">' + escapeHtml(t.trim()) + '</span>'; }).join('') +
              '</div>'
            : '';

        shell.innerHTML =
            '<!-- Corner Decoration -->' +
            '<div class="mentor-corner">' +
                '<svg viewBox="0 0 32 32"><path d="M8 2 L2 2 L2 8"/><path d="M24 2 L30 2 L30 8"/><path d="M8 30 L2 30 L2 24"/><path d="M24 30 L30 30 L30 24"/></svg>' +
            '</div>' +
            '<div class="mentor-header">' +
                avatarHtml +
                '<div class="mentor-info">' +
                    '<div class="mentor-name">' + escapeHtml(name) + '</div>' +
                    titleRowHtml +
                    '<span class="mentor-dept">' + escapeHtml(dept) + '</span>' +
                '</div>' +
            '</div>' +
            '<div class="mentor-divider"></div>' +
            '<div class="mentor-bio">' + escapeHtml(introduction) + '</div>' +
            tagsHtml +
            '<div class="mentor-footer">' +
                '<div class="mentor-stats">' +
                    '<div class="mentor-stat">' +
                        '<span class="mentor-stat-value">—</span>' +
                        '<span class="mentor-stat-label">指导队伍</span>' +
                    '</div>' +
                    '<div class="mentor-stat">' +
                        '<span class="mentor-stat-value">—</span>' +
                        '<span class="mentor-stat-label">获奖数量</span>' +
                    '</div>' +
                '</div>' +
                '<a href="' + detailUrl + '" class="mentor-btn" onclick="event.stopPropagation();">' +
                    '查看详情' +
                    '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>' +
                '</a>' +
            '</div>';

        // 点击整卡跳转详情
        shell.addEventListener('click', function(e) {
            if (e.target.closest('.mentor-btn') || e.target.closest('a')) return;
            window.location.href = detailUrl;
        });

        container.appendChild(shell);
    });
}

// 提交导师申请
async function submitTeacherApplication() {
    var introduction = document.getElementById('applicationBio');
    var researchDirection = document.getElementById('applicationResearch');
    var projects = document.getElementById('applicationMajor');
    var applicationTitle = document.getElementById('applicationTitle');

    if (!introduction || !researchDirection || !applicationTitle) {
        showMessage('请填写完整信息', 'error');
        return;
    }

    var applicationData = {
        introduction: introduction.value,
        researchDirection: researchDirection.value,
        projects: (function() {
            var majorVal = projects ? projects.value : '';
            var titleVal = applicationTitle ? applicationTitle.value : '';
            if (titleVal) {
                return (majorVal || '') + '（' + titleVal + '）'.trim();
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
        var response = await apiFetch('/api/teacher/apply', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify(applicationData)
        });

        var result = await response.json();

        if (result.code === 200) {
            showMessage('申请已提交，请等待管理员审核', 'success');
            closeApplyModal();
            if (introduction) introduction.value = '';
            if (researchDirection) researchDirection.value = '';
            if (projects) projects.value = '';
            if (applicationTitle) applicationTitle.value = '';
            loadTeacherList();
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(function() { window.location.href = '/login.html'; }, 1500);
        } else {
            showMessage('申请提交失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('提交导师申请异常:', error);
        showMessage('网络错误，请稍后重试', 'error');
    }
}
