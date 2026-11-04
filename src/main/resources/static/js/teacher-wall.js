// 导师招贤页面JavaScript (Editorial)

let currentPage = 1;
// 导师招贤每页 12 张卡片（与组队大厅保持一致）
const pageSize = 12;

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
    initTeacherProfileShortcut();
    checkTeacherProfileBanner();

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

async function initTeacherProfileShortcut() {
    var link = document.getElementById('teacherMyProfileLink');
    if (!link) return;
    try {
        var response = await apiFetch('/api/teacher/me', { credentials: 'same-origin' });
        var result = await response.json();
        if (response.ok && result && result.code === 200 && result.data && result.data.id) {
            link.href = '/teacher-detail.html?id=' + encodeURIComponent(String(result.data.id));
            return;
        }
    } catch (error) {
        console.warn('Unable to load current teacher profile:', error);
    }
    link.href = '/profile.html';
}

// 导师档案未完善时显示提示条：动态列出还缺哪些资料，补全后自动公开展示
async function checkTeacherProfileBanner() {
    var banner = document.getElementById('teacherIncompleteBanner');
    if (!banner) return;
    var titleEl = document.getElementById('teacherIncompleteTitle');
    var textEl = document.getElementById('teacherIncompleteText');
    try {
        var response = await apiFetch('/api/teacher/me', { credentials: 'same-origin' });
        var result = await response.json();
        if (!response.ok || !result || result.code !== 200 || !result.data) return; // 无档案等场景保持隐藏
        var t = result.data;
        if (t.profileComplete === true) return; // 资料完整，已在公开展示
        if (t.status === 'PENDING') {
            if (titleEl) titleEl.textContent = '导师申请审核中';
            if (textEl) textEl.textContent = '你的导师申请已提交，管理员审核通过后将自动公开展示在导师招贤。';
        } else if (t.status === 'REJECTED') {
            if (titleEl) titleEl.textContent = '导师申请未通过审核';
            if (textEl) textEl.textContent = '申请已被驳回，请完善资料后重新申请，或联系管理员了解原因。';
        } else {
            if (titleEl) titleEl.textContent = '完善导师资料，即可公开展示';
            var missing = [];
            var prev = t.userPreview || {};
            if (!(prev.realName && String(prev.realName).trim())) missing.push('姓名');
            if (!(t.institution && String(t.institution).trim())) missing.push('任职单位');
            if (!(t.expertise && String(t.expertise).trim())) missing.push('专业领域');
            if (!(t.professionalTitle && String(t.professionalTitle).trim())) missing.push('职称');
            if (!(t.researchDirection && String(t.researchDirection).trim())) missing.push('研究方向');
            if (!(t.introduction && String(t.introduction).trim())) missing.push('导师简介');
            if (textEl) textEl.textContent = missing.length
                ? '还差：' + missing.join('、') + '。补全后，你的导师主页将自动公开展示在导师招贤，无需管理员审批。'
                : '请补全导师资料（姓名、任职单位、专业领域、职称、研究方向、导师简介），补全后自动公开展示。';
        }
        banner.hidden = false;
    } catch (e) {
        console.warn('检查导师资料提示失败:', e);
    }
}

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
            var pagination = (result.extra && result.extra.pagination) || result.pagination;
            // 空页回退：当前页超出总页数（如数据被删、每页条数调整）时自动回退到末页重载，避免空白页
            var p = pagination || {};
            var totalPages = Math.max(1, Math.ceil((p.total || 0) / (p.size || pageSize)));
            if (currentPage > totalPages && currentPage > 1) {
                currentPage = totalPages;
                return loadTeacherList();
            }
            renderTeacherList(result.data);
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
        // 无边框杂志排版：整条即一个条目，不再套卡片容器
        shell.className = 'editorial-item';
        // 指针即光源：追光灯跟着指针照亮这一条
        shell.setAttribute('data-ui-lit', '');
        shell.setAttribute('data-teacher-id', teacher.id);
        shell.setAttribute('role', 'link');
        shell.setAttribute('tabindex', '0');

        // 基本信息
        var introduction = teacher.introduction || '暂无简介';
        var prev = teacher.userPreview;
        var name = '导师 #' + teacher.id;
        var char = '导';
        var avatarUrl = null;
        if (prev && prev.id != null) {
            name = (prev.displayName && String(prev.displayName).trim()) || (prev.realName && String(prev.realName).trim()) || name;
            char = name.charAt(0).toUpperCase();
            avatarUrl = prev.avatar || null;
        }

        // 专业领域与职称由导师档案明确提供；旧数据仍由后端兼容转换。
        var dept = teacher.expertise || teacher.department || '未设置专业领域';
        var titleText = teacher.professionalTitle || '';

        // 解析研究方向标签
        var tags = [];
        if (teacher.researchDirection) {
            // 顿号也是分隔符：数据里「软件工程、分布式系统」是一条字符串
            tags = String(teacher.researchDirection).split(/[,，;；、/|]+/).map(function(s) { return s.trim(); }).filter(Boolean).slice(0, 5);
        }

        // 详情链接
        var detailUrl = '/teacher-detail.html?id=' + encodeURIComponent(String(teacher.id));

        // 数据用两位数，像档案编号 —— 视觉上比裸数字更像"科研数据"
        function statHtml(value, label) {
            var n = Number(value || 0);
            var padded = n < 10 ? '0' + n : String(n);
            return '<div class="stat-item">' +
                '<span class="stat-num">' + padded + '</span>' +
                '<span class="stat-label">' + label + '</span>' +
            '</div>';
        }

        var posterHtml = avatarUrl
            ? '<img src="' + escapeHtml(avatarUrl) + '" alt="' + escapeHtml(name) + '" loading="lazy"' +
              ' onerror="this.style.display=\'none\'; var f=this.parentElement.querySelector(\'.poster-fallback\'); if(f){f.style.display=\'grid\';}">' +
              '<span class="poster-fallback" style="display:none">' + escapeHtml(char) + '</span>'
            : '<span class="poster-fallback">' + escapeHtml(char) + '</span>';

        shell.innerHTML =
            '<div class="profile-poster">' +
                posterHtml +
                '<div class="floating-major">' + escapeHtml(dept) + '</div>' +
            '</div>' +
            '<div class="profile-info">' +
                '<div class="info-header">' +
                    '<h2 class="info-name">' + escapeHtml(name) + '</h2>' +
                    (titleText ? '<span class="info-title">' + escapeHtml(titleText) + '</span>' : '') +
                '</div>' +
                '<p class="info-bio">' + escapeHtml(introduction) + '</p>' +
                (tags.length > 0
                    ? '<div class="info-tags">' +
                        tags.map(function (t) { return '<span class="info-tag">' + escapeHtml(t.trim()) + '</span>'; }).join('') +
                      '</div>'
                    : '') +
                '<div class="info-footer">' +
                    '<div class="stats">' +
                        statHtml(teacher.approvedProjectCount, '已通过合作') +
                        statHtml(teacher.publicAssetCount, '公开成果') +
                    '</div>' +
                    '<a href="' + detailUrl + '" class="btn-detail" onclick="event.stopPropagation();">查看学术档案</a>' +
                '</div>' +
            '</div>';

        // 点击整卡跳转详情
        shell.addEventListener('click', function(e) {
            if (e.target.closest('.btn-detail') || e.target.closest('a')) return;
            window.location.href = detailUrl;
        });
        shell.addEventListener('keydown', function(e) {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            if (e.target.closest('a, button, input, select, textarea')) return;
            e.preventDefault();
            window.location.href = detailUrl;
        });

        container.appendChild(shell);
    });
}

// 提交导师申请
async function submitTeacherApplication() {
    var introduction = document.getElementById('applicationBio');
    var researchDirection = document.getElementById('applicationResearch');
    var expertise = document.getElementById('applicationMajor');
    var applicationTitle = document.getElementById('applicationTitle');
    var projects = document.getElementById('applicationProjects');

    if (!introduction || !researchDirection || !applicationTitle || !expertise) {
        showMessage('请填写完整信息', 'error');
        return;
    }

    var applicationData = {
        introduction: introduction.value,
        researchDirection: researchDirection.value,
        professionalTitle: applicationTitle.value,
        expertise: expertise.value,
        projects: projects ? projects.value : ''
    };

    if (!applicationData.introduction || !applicationData.researchDirection
        || !applicationData.professionalTitle || !applicationData.expertise) {
        showMessage('请填写必填字段', 'error');
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
            if (expertise) expertise.value = '';
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
