// 发布组队需求页面JavaScript

// 取消按钮：弹出确认框后返回组队大厅
document.addEventListener('DOMContentLoaded', function() {
    const cancelBtn = document.getElementById('cancelPublishBtn');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', function(e) {
            e.preventDefault();
            if (confirm('确定要放弃发布吗？已填写的内容将不会保存。')) {
                window.location.href = '/team-market.html';
            }
        });
    }
});

// 分类与截止日期都用单选芯片。真正的值写进隐藏域，提交逻辑不必感知控件形态。
document.addEventListener('DOMContentLoaded', function() {
    /** 把一组 .tp-chip 当单选按钮用 */
    function bindRadioGroup(containerId, onPick) {
        const container = document.getElementById(containerId);
        if (!container) return;
        const chips = Array.prototype.slice.call(container.querySelectorAll('.tp-chip'));
        chips.forEach(function(chip) {
            chip.addEventListener('click', function() {
                chips.forEach(function(other) {
                    const active = other === chip;
                    other.classList.toggle('is-active', active);
                    other.setAttribute('aria-checked', active ? 'true' : 'false');
                });
                onPick(chip);
            });
        });
    }

    // --- 分类 ---
    const categoryInput = document.getElementById('category');
    bindRadioGroup('categoryChips', function(chip) {
        if (categoryInput) categoryInput.value = chip.dataset.value || '';
    });

    // --- 截止日期 ---
    const deadlineInput = document.getElementById('deadline');
    const deadlineValue = document.getElementById('deadlineValue');
    const customField = document.getElementById('customDateField');
    const deadlineNote = document.getElementById('deadlineNote');
    const PERIOD = {
        long: { days: null, note: '一直开放招募' },
        week: { days: 7, note: '7 天后自动停止招募' },
        month: { days: 30, note: '30 天后自动停止招募' }
    };
    let period = 'long';

    /** 按本地时区拼 yyyy-MM-dd：toISOString 走 UTC，跨时区会差一天 */
    function toDateString(days) {
        const d = new Date();
        d.setDate(d.getDate() + days);
        const mm = String(d.getMonth() + 1).padStart(2, '0');
        const dd = String(d.getDate()).padStart(2, '0');
        return d.getFullYear() + '-' + mm + '-' + dd;
    }

    function applyPeriod() {
        if (customField) customField.hidden = period !== 'custom';
        if (!deadlineValue) return;
        if (period === 'custom') {
            const picked = deadlineInput ? deadlineInput.value : '';
            deadlineValue.value = picked || '';
            if (deadlineNote) deadlineNote.textContent = picked ? '截止到 ' + picked : '请选择日期';
            return;
        }
        deadlineValue.value = period === 'long' ? '' : toDateString(PERIOD[period].days);
        if (deadlineNote) deadlineNote.textContent = PERIOD[period].note;
    }

    bindRadioGroup('deadlineChips', function(chip) {
        period = chip.dataset.period || 'long';
        applyPeriod();
    });
    if (deadlineInput) deadlineInput.addEventListener('change', applyPeriod);
    // 默认「长期有效」，与后端 deadline 可空的语义一致
    const defaultPeriod = document.querySelector('#deadlineChips .tp-chip[data-period="long"]');
    if (defaultPeriod) defaultPeriod.click();

    // --- 项目描述字数 ---
    const desc = document.getElementById('description');
    const counter = document.getElementById('descCounter');
    if (desc && counter) {
        const max = desc.getAttribute('maxlength') || '500';
        const syncCounter = function() {
            counter.textContent = desc.value.length + ' / ' + max;
        };
        desc.addEventListener('input', syncCounter);
        syncCounter();
    }
});

// 提交表单发布组队需求
document.addEventListener('DOMContentLoaded', function() {
    const publishForm = document.getElementById('publishForm');
    
    if (publishForm) {
        publishForm.addEventListener('submit', async function(e) {
            e.preventDefault();
            
            const categoryValue = document.getElementById('category').value;
            const competitionMap = {
                '技术开发': 1,
                '创意设计': 2,
                '市场营销': 3,
                '学术研究': 4
            };
            const competitionId = competitionMap[categoryValue] || null;

            const teamData = {
                title: document.getElementById('title').value,
                description: document.getElementById('description').value,
                competitionId: competitionId,
                requiredSkills: document.getElementById('skills').value,
                requiredMemberCount: null,
                deadline: null
            };

            const memberCountElem = document.getElementById('memberCount');
            // 截止日期由芯片换算后写进隐藏域：长期有效为空串
            const deadlineElem = document.getElementById('deadlineValue');
            const memberCount = memberCountElem ? memberCountElem.value : '';
            const deadline = deadlineElem ? deadlineElem.value : '';
            teamData.requiredMemberCount = memberCount ? parseInt(memberCount, 10) : null;
            teamData.deadline = deadline || null;
            
            // 验证表单（分类现在是芯片，浏览器不会再替我们拦空值）
            if (!teamData.title || !teamData.description) {
                showMessage('请填写必填字段', 'error');
                return;
            }
            if (!categoryValue) {
                showMessage('请选择分类', 'error');
                return;
            }
            
            // 按钮里还有图标：直接写 textContent 会把 svg 一起抹掉，之后也没法还原，
            // 所以只改文案所在的 span，并将原值提到 try 外面供 finally 复原
            const submitBtn = publishForm.querySelector('button[type="submit"]');
            const submitLabel = submitBtn ? (submitBtn.querySelector('.tp-submit-text') || submitBtn) : null;
            const originalText = submitLabel ? submitLabel.textContent : '发布需求';

            try {
                if (submitLabel) submitLabel.textContent = '发布中...';
                if (submitBtn) submitBtn.disabled = true;

                const response = await apiFetch('/api/team', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(teamData)
                });

                const result = await response.json();

                if (result.code === 200) {
                    showMessage('发布成功！', 'success');
                    setTimeout(() => {
                        window.location.href = '/team-market.html';
                    }, 1500);
                } else if (result.code === 401) {
                    showMessage('请先登录', 'warning');
                    setTimeout(() => {
                        window.location.href = '/login';
                    }, 1500);
                } else {
                    showMessage('发布失败: ' + result.message, 'error');
                }
            } catch (error) {
                console.error('发布组队需求异常:', error);
                showMessage('网络错误，请稍后重试', 'error');
            } finally {
                // C-19: 按钮恢复放在 finally 块，确保无论成功失败都会恢复（复原成原文案，不是截断的「发布」）
                if (submitLabel) submitLabel.textContent = originalText;
                if (submitBtn) submitBtn.disabled = false;
            }
        });
    }
});
