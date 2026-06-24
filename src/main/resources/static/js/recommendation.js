// iLink Recommendation Manager
// 智能推荐团队管理

class RecommendationManager {
    constructor() {
        this.userId = null;
        this.container = null;
        this.recommendations = [];
        this.isLoading = false;
    }

    async init(userId) {
        if (!userId) {
            console.warn('RecommendationManager: 未提供用户ID，跳过初始化');
            return;
        }

        this.userId = userId;
        this.container = document.getElementById('recommendationContainer');

        if (!this.container) {
            console.warn('RecommendationManager: 未找到推荐容器，跳过初始化');
            return;
        }

        await this.loadRecommendations();
    }

    async loadRecommendations() {
        if (this.isLoading) return;
        this.isLoading = true;

        this.showLoading();

        try {
            const data = await request('/recommendations/teams?limit=6', { silent: true });
            this.recommendations = Array.isArray(data) ? data : [];
            this.renderRecommendations();
        } catch (error) {
            console.error('RecommendationManager: 加载推荐失败', error);
            this.showError('推荐内容暂时不可用，请稍后再试');
        } finally {
            this.isLoading = false;
        }
    }

    renderRecommendations() {
        if (!this.container) return;

        if (this.recommendations.length === 0) {
            this.showEmpty();
            return;
        }

        const html = `
            <div class="recommendation-grid">
                ${this.recommendations.map(rec => this.createCardHtml(rec)).join('')}
            </div>
        `;

        this.container.innerHTML = html;
        this.bindCardEvents();
    }

    createCardHtml(recommendation) {
        const teamId = Number(recommendation.teamId) || 0;
        const teamName = recommendation.teamName || '未命名团队';
        const description = recommendation.description || '暂无描述';
        const matchScore = Math.round(recommendation.matchScore || 0);
        const matchReasons = recommendation.matchReasons || [];

        const teamInitials = this.getInitials(teamName);

        const reasonsHtml = matchReasons.length > 0
            ? `<div class="recommendation-match-reason">
                <strong>匹配原因：</strong>${matchReasons.slice(0, 2).map(r => this.escapeHtml(r)).join('、')}
               </div>`
            : '';

        return `
            <a href="/team-detail.html?id=${teamId}" class="recommendation-card scroll-animate">
                <div class="recommendation-card-header">
                    <span class="match-score-badge">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/>
                        </svg>
                        ${matchScore}%
                    </span>
                    <span class="team-status-badge recruiting">招募中</span>
                </div>
                <h3>${this.escapeHtml(teamName)}</h3>
                <p class="recommendation-card-description">${this.escapeHtml(description)}</p>
                ${reasonsHtml}
                <div class="recommendation-card-footer">
                    <div class="recommendation-team-info">
                        <div class="recommendation-team-avatar">${teamInitials}</div>
                        <span class="recommendation-team-name">查看详情</span>
                    </div>
                    <span class="recommendation-view-btn">申请加入</span>
                </div>
            </a>
        `;
    }

    bindCardEvents() {
        const cards = this.container.querySelectorAll('.scroll-animate');
        const observer = new IntersectionObserver((entries) => {
            entries.forEach(entry => {
                if (entry.isIntersecting) {
                    entry.target.classList.add('animate-in');
                    observer.unobserve(entry.target);
                }
            });
        }, { threshold: 0.1 });

        cards.forEach((card, index) => {
            card.style.transitionDelay = `${index * 0.1}s`;
            observer.observe(card);
        });
    }

    showLoading() {
        if (!this.container) return;
        this.container.innerHTML = `
            <div class="recommendation-loading">
                <div class="recommendation-spinner"></div>
            </div>
        `;
    }

    showEmpty() {
        if (!this.container) return;
        this.container.innerHTML = `
            <div class="recommendation-empty">
                <div class="recommendation-empty-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
                        <circle cx="9" cy="7" r="4"/>
                        <path d="M23 21v-2a4 4 0 0 0-3-3.87"/>
                        <path d="M16 3.13a4 4 0 0 1 0 7.75"/>
                    </svg>
                </div>
                <h3>暂无推荐团队</h3>
                <p>完善您的个人资料，获取更精准的团队推荐</p>
            </div>
        `;
    }

    showError(message) {
        if (!this.container) return;
        this.container.innerHTML = `
            <div class="recommendation-empty">
                <div class="recommendation-empty-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                        <circle cx="12" cy="12" r="10"/>
                        <line x1="12" y1="8" x2="12" y2="12"/>
                        <line x1="12" y1="16" x2="12.01" y2="16"/>
                    </svg>
                </div>
                <h3>加载失败</h3>
                <p>${this.escapeHtml(message)}</p>
            </div>
        `;
    }

    getInitials(name) {
        if (!name) return '团';
        const trimmed = name.trim();
        if (trimmed.length === 0) return '团';
        return trimmed.charAt(0).toUpperCase();
    }

    escapeHtml(value) {
        if (value == null) return '';
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }
}

const recommendationManager = new RecommendationManager();

window.RecommendationManager = RecommendationManager;
window.recommendationManager = recommendationManager;
