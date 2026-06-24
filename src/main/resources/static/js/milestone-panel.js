// 里程碑管理类
class MilestoneManager {
    constructor() {
        this.teamId = null;
        this.milestones = [];
        this.currentMilestone = null;
        this.createModal = null;
        this.progressModal = null;
    }

    async init(teamId) {
        this.teamId = teamId;
        this.createModal = new bootstrap.Modal(document.getElementById('createMilestoneModal'));
        this.progressModal = new bootstrap.Modal(document.getElementById('progressMilestoneModal'));

        await this.loadMilestones();
        this.renderMilestones();
    }

    async loadMilestones() {
        try {
            const data = await request(`/team/${this.teamId}/milestones`);
            this.milestones = Array.isArray(data) ? data : [];
        } catch (error) {
            console.error('加载里程碑失败:', error);
            this.milestones = [];
            showMessage('加载里程碑失败', 'error');
        }
    }

    renderMilestones() {
        const timeline = document.getElementById('milestoneTimeline');
        const countEl = document.getElementById('milestoneCount');

        if (countEl) countEl.textContent = this.milestones.length;

        if (!timeline) return;

        if (!this.milestones || this.milestones.length === 0) {
            timeline.innerHTML = `
                <div class="milestone-empty" id="milestoneEmpty">
                    <div class="milestone-empty-icon">⏱️</div>
                    <p class="milestone-empty-text">暂无里程碑，点击上方按钮添加</p>
                </div>
            `;
            return;
        }

        const sortedMilestones = [...this.milestones].sort((a, b) => {
            if (a.dueDate && b.dueDate) {
                return new Date(a.dueDate) - new Date(b.dueDate);
            }
            return 0;
        });

        timeline.innerHTML = sortedMilestones.map(m => this.renderMilestoneCard(m)).join('');

        this.bindMilestoneEvents();
    }

    renderMilestoneCard(milestone) {
        const statusClass = this.normalizeStatus(milestone.status);
        const statusText = this.getStatusText(milestone.status);
        const progress = milestone.completionRate || 0;
        const isCompleted = progress >= 100;

        const dueDateText = milestone.dueDate ? this.formatDate(milestone.dueDate) : '未设置';
        const completedDateText = milestone.completedDate ? this.formatDate(milestone.completedDate) : '-';
        const creatorText = milestone.creatorName ? `创建人: ${escapeHtml(milestone.creatorName)}` : '';

        return `
            <div class="milestone-card ${statusClass}" data-milestone-id="${milestone.id}">
                <div class="milestone-card-header">
                    <h4 class="milestone-card-title">${escapeHtml(milestone.milestoneName)}</h4>
                    <span class="milestone-card-status ${statusClass}">${statusText}</span>
                </div>
                ${milestone.milestoneDescription ? `<p class="milestone-card-description">${escapeHtml(milestone.milestoneDescription)}</p>` : ''}
                <div class="milestone-card-dates">
                    <span class="milestone-card-date">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <rect x="3" y="4" width="18" height="18" rx="2" stroke="currentColor" stroke-width="2"/>
                            <path d="M16 2V6M8 2V6M3 10H21" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
                        </svg>
                        截止: ${dueDateText}
                    </span>
                    <span class="milestone-card-date">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <path d="M22 11.08V12C21.9988 14.1564 21.3005 16.2547 20.0093 17.9818C18.7182 19.709 16.9033 20.9725 14.8354 21.5839C12.7674 22.1953 10.5573 22.1219 8.53447 21.3746C6.51168 20.6273 4.78465 19.2461 3.61096 17.4371C2.43727 15.628 1.87979 13.4881 2.02168 11.3363C2.16356 9.18455 2.99721 7.13631 4.39828 5.49706C5.79935 3.85781 7.69279 2.71537 9.79619 2.24013C11.8996 1.7649 14.1003 1.98232 16.07 2.85999" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                        </svg>
                        完成: ${completedDateText}
                    </span>
                </div>
                ${creatorText ? `<div class="milestone-card-creator">${creatorText}</div>` : ''}
                <div class="milestone-progress">
                    <div class="milestone-progress-header">
                        <span class="milestone-progress-label">完成进度</span>
                        <span class="milestone-progress-value">${progress}%</span>
                    </div>
                    <div class="milestone-progress-bar">
                        <div class="milestone-progress-fill ${isCompleted ? 'completed' : ''}" style="width: ${progress}%"></div>
                    </div>
                </div>
                <div class="milestone-card-actions">
                    <button class="milestone-action-btn" onclick="event.stopPropagation(); milestoneManager.openEditModal(${milestone.id})">
                        编辑
                    </button>
                    <button class="milestone-action-btn" onclick="event.stopPropagation(); milestoneManager.openProgressModal(${milestone.id})">
                        更新进度
                    </button>
                    <button class="milestone-action-btn" onclick="event.stopPropagation(); milestoneManager.deleteMilestone(${milestone.id})">
                        删除
                    </button>
                </div>
            </div>
        `;
    }

    normalizeStatus(status) {
        if (!status) return 'pending';
        const s = status.toLowerCase();
        if (s === 'completed' || s === 'done') return 'completed';
        if (s === 'in_progress' || s === 'in progress' || s === 'progress') return 'in-progress';
        if (s === 'delayed') return 'delayed';
        if (s === 'pending') return 'pending';
        return 'pending';
    }

    getStatusText(status) {
        if (!status) return '待处理';
        const s = status.toLowerCase();
        if (s === 'completed' || s === 'done') return '已完成';
        if (s === 'in_progress' || s === 'in progress' || s === 'progress') return '进行中';
        if (s === 'delayed') return '已延期';
        if (s === 'pending') return '待处理';
        return status;
    }

    formatDate(dateString) {
        if (!dateString) return '';
        const date = new Date(dateString);
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
    }

    bindMilestoneEvents() {
        const cards = document.querySelectorAll('.milestone-card');
        cards.forEach(card => {
            card.addEventListener('click', (e) => {
                if (!e.target.closest('.milestone-action-btn')) {
                    const milestoneId = parseInt(card.dataset.milestoneId);
                    this.openEditModal(milestoneId);
                }
            });
        });
    }

    openCreateModal() {
        document.getElementById('milestoneModalTitle').textContent = '创建里程碑';
        document.getElementById('editMilestoneId').value = '';
        document.getElementById('milestoneName').value = '';
        document.getElementById('milestoneDescription').value = '';
        document.getElementById('milestoneDueDate').value = '';
        document.getElementById('milestoneProgress').value = '0';

        this.createModal.show();
    }

    openEditModal(milestoneId) {
        const milestone = this.milestones.find(m => m.id === milestoneId);
        if (!milestone) {
            showMessage('里程碑不存在', 'error');
            return;
        }

        this.currentMilestone = milestone;

        document.getElementById('milestoneModalTitle').textContent = '编辑里程碑';
        document.getElementById('editMilestoneId').value = milestone.id;
        document.getElementById('milestoneName').value = milestone.milestoneName || '';
        document.getElementById('milestoneDescription').value = milestone.milestoneDescription || '';

        if (milestone.dueDate) {
            document.getElementById('milestoneDueDate').value = this.formatDate(milestone.dueDate);
        } else {
            document.getElementById('milestoneDueDate').value = '';
        }

        document.getElementById('milestoneProgress').value = milestone.completionRate || 0;

        this.createModal.show();
    }

    openProgressModal(milestoneId) {
        const milestone = this.milestones.find(m => m.id === milestoneId);
        if (!milestone) {
            showMessage('里程碑不存在', 'error');
            return;
        }

        this.currentMilestone = milestone;

        document.getElementById('progressMilestoneId').value = milestone.id;
        document.getElementById('progressMilestoneTitle').textContent = milestone.milestoneName;
        document.getElementById('progressSlider').value = milestone.completionRate || 0;
        document.getElementById('progressValue').textContent = `${milestone.completionRate || 0}%`;

        this.progressModal.show();
    }

    updateProgressLabel() {
        const slider = document.getElementById('progressSlider');
        document.getElementById('progressValue').textContent = `${slider.value}%`;
    }

    async saveMilestone() {
        const editId = document.getElementById('editMilestoneId').value;
        const name = document.getElementById('milestoneName').value.trim();

        if (!name) {
            showMessage('请输入里程碑名称', 'warning');
            return;
        }

        const milestoneData = {
            milestoneName: name,
            milestoneDescription: document.getElementById('milestoneDescription').value.trim(),
            dueDate: document.getElementById('milestoneDueDate').value || null,
            completionRate: parseInt(document.getElementById('milestoneProgress').value) || 0
        };

        if (milestoneData.dueDate) {
            milestoneData.dueDate = new Date(milestoneData.dueDate).toISOString();
        }

        try {
            if (editId) {
                await request(`/milestones/${editId}`, {
                    method: 'PUT',
                    body: JSON.stringify(milestoneData)
                });
            } else {
                await request(`/team/${this.teamId}/milestones`, {
                    method: 'POST',
                    body: JSON.stringify(milestoneData)
                });
            }
            await this.loadMilestones();
            this.renderMilestones();
            this.createModal.hide();
            showMessage(editId ? '里程碑更新成功' : '里程碑创建成功', 'success');
        } catch (error) {
            console.error('保存里程碑失败:', error);
        }
    }

    async saveProgress() {
        const milestoneId = document.getElementById('progressMilestoneId').value;
        const progress = parseInt(document.getElementById('progressSlider').value);

        if (!milestoneId) {
            showMessage('里程碑ID无效', 'error');
            return;
        }

        try {
            await request(`/milestones/${milestoneId}/progress`, {
                method: 'PUT',
                body: JSON.stringify({ progress: progress })
            });
            await this.loadMilestones();
            this.renderMilestones();
            this.progressModal.hide();
            showMessage('进度更新成功', 'success');
        } catch (error) {
            console.error('更新进度失败:', error);
        }
    }

    async deleteMilestone(milestoneId) {
        if (!confirm('确定要删除这个里程碑吗？')) return;

        try {
            await request(`/milestones/${milestoneId}`, { method: 'DELETE' });
            this.milestones = this.milestones.filter(m => m.id !== milestoneId);
            this.renderMilestones();
            showMessage('里程碑已删除', 'success');
        } catch (error) {
            console.error('删除里程碑失败:', error);
        }
    }

    async refresh() {
        showMessage('正在刷新...', 'info');
        await this.loadMilestones();
        this.renderMilestones();
        showMessage('里程碑已刷新', 'success');
    }
}

let milestoneManager;

document.addEventListener('DOMContentLoaded', function() {
    const urlParams = new URLSearchParams(window.location.search);
    const teamId = urlParams.get('teamId');

    if (teamId) {
        milestoneManager = new MilestoneManager();
        milestoneManager.init(teamId);
    }
});
