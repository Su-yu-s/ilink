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
            const deadlineElem = document.getElementById('deadline');
            const memberCount = memberCountElem ? memberCountElem.value : '';
            const deadline = deadlineElem ? deadlineElem.value : '';
            teamData.requiredMemberCount = memberCount ? parseInt(memberCount, 10) : null;
            teamData.deadline = deadline || null;
            
            // 验证表单
            if (!teamData.title || !teamData.description) {
                showMessage('请填写必填字段', 'error');
                return;
            }
            
            try {
                // 显示加载状态
                const submitBtn = publishForm.querySelector('button[type="submit"]');
                const originalText = submitBtn.textContent;
                submitBtn.textContent = '发布中...';
                submitBtn.disabled = true;

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
                // C-19: 按钮恢复放在 finally 块，确保无论成功失败都会恢复
                const submitBtn = publishForm.querySelector('button[type="submit"]');
                if (submitBtn) {
                    submitBtn.textContent = submitBtn.textContent === '发布中...' ? '发布' : submitBtn.textContent;
                    submitBtn.disabled = false;
                }
            }
        });
    }
});
