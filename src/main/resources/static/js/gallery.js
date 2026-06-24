// Gallery Page JavaScript - v5.4
// 成果展示页面 — 服务端分页、筛选、发布
class Gallery {
    constructor() {
        this.assets = [];
        this.currentCategory = '';
        this.currentSort = 'latest';
        this.searchQuery = '';
        this.currentPage = 1;
        this.totalPages = 1;
        this.pageSize = 12;
        this.init();
    }

    async init() {
        this.bindEvents();
        await this.loadAssets(1);
    }

    bindEvents() {
        document.querySelectorAll('.category-pill').forEach(pill => {
            pill.addEventListener('click', () => {
                this.filterByCategory(pill.dataset.category);
            });
        });

        const searchInput = document.getElementById('searchInput');
        if (searchInput) {
            let searchTimeout;
            searchInput.addEventListener('input', () => {
                clearTimeout(searchTimeout);
                searchTimeout = setTimeout(() => {
                    this.searchQuery = searchInput.value.toLowerCase();
                    this.currentPage = 1;
                    this.loadAssets(1);
                }, 300);
            });
        }

        const sortSelect = document.getElementById('sortSelect');
        if (sortSelect) {
            sortSelect.addEventListener('change', (e) => {
                this.currentSort = e.target.value;
                this.currentPage = 1;
                this.loadAssets(1);
            });
        }

        const submitBtn = document.getElementById('submitUpload');
        if (submitBtn) {
            submitBtn.addEventListener('click', () => this.handleUpload());
        }

        // C-24: 事件委托处理分页按钮
        const paginationEl = document.getElementById('pagination');
        if (paginationEl) {
            paginationEl.addEventListener('click', (e) => {
                const btn = e.target.closest('.page-btn');
                if (!btn || btn.disabled) return;
                const page = parseInt(btn.dataset.page, 10);
                if (page > 0) {
                    this.loadAssets(page);
                }
            });
        }
    }

    async loadAssets(page) {
        this.currentPage = page || 1;
        try {
            this.showLoading();
            const params = new URLSearchParams({
                page: this.currentPage,
                size: this.pageSize,
                keyword: this.searchQuery,
                category: this.currentCategory,
                sort: this.currentSort
            });
            const response = await apiFetch(`/api/asset/list?${params}`, { credentials: 'same-origin' });
            if (!response.ok) throw new Error('Failed to load assets');
            const result = await response.json();
            if (result.code === 200) {
                this.assets = result.data || [];
                const pagination = (result.extra && result.extra.pagination) || result.pagination;
                this.totalPages = pagination ? Math.ceil(pagination.total / pagination.size) : 1;
                this.render();
                this.renderPagination();
            } else {
                this.showError();
            }
        } catch (error) {
            console.error('Error loading assets:', error);
            this.showError();
        }
    }

    filterByCategory(category) {
        this.currentCategory = category === 'all' ? '' : category;
        this.currentPage = 1;
        document.querySelectorAll('.category-pill').forEach(pill => {
            pill.classList.toggle('active', pill.dataset.category === category);
        });
        this.loadAssets(1);
    }

    showLoading() {
        const grid = document.getElementById('galleryGrid');
        if (!grid) return;
        let html = '';
        for (let i = 0; i < 6; i++) {
            html += `<div class="gallery-card skeleton-card"><div class="card-image skeleton" style="padding-top:${60 + (i % 3) * 15}%"></div><div class="card-content"><div class="skeleton-title skeleton"></div><div class="skeleton-text skeleton"></div></div></div>`;
        }
        grid.innerHTML = html;
        const emptyState = document.getElementById('emptyState');
        if (emptyState) emptyState.style.display = 'none';
    }

    showError() {
        const grid = document.getElementById('galleryGrid');
        if (grid) grid.innerHTML = '';
        const emptyState = document.getElementById('emptyState');
        if (emptyState) emptyState.style.display = 'block';
    }

    render() {
        const grid = document.getElementById('galleryGrid');
        const emptyState = document.getElementById('emptyState');
        if (!grid) return;

        if (!this.assets || this.assets.length === 0) {
            grid.innerHTML = '';
            if (emptyState) emptyState.style.display = 'block';
            return;
        }

        if (emptyState) emptyState.style.display = 'none';
        grid.innerHTML = this.assets.map((asset, index) => this.createCard(asset, index)).join('');
    }

    createCard(asset, index) {
        const title = asset.title || '未命名成果';
        const description = asset.description || '暂无描述';
        const viewCount = asset.viewCount || 0;
        const createdAt = asset.createdAt || new Date().toISOString();
        const authorName = asset.authorName || '匿名用户';
        const authorInitial = authorName.charAt(0).toUpperCase();
        const dateStr = this.formatDate(createdAt);
        // 从描述中提取分类（兼容旧格式 锛堝垎绫伙細XX锛?
        const catMatch = (asset.description || '').match(/（分类：([^）]+)）/);
        const category = asset.category || (catMatch ? catMatch[1] : '');
        const cleanDesc = (asset.description || '').replace(/（分类：[^）]+）/g, '').trim();

        return `
            <div class="gallery-card" role="link" tabindex="0" data-asset-id="${asset.id}" style="animation: fadeInUp 0.5s ease ${index * 0.05}s both;">
                <div class="card-image" style="padding-top: ${60 + (index % 3) * 15}%;">
                    ${category ? `<span class="category-tag">${this.escapeHtml(category)}</span>` : ''}
                    <span class="view-count">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                        ${viewCount}
                    </span>
                    <div class="placeholder-icon">
                        <svg viewBox="0 0 24 24"><path d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"/></svg>
                    </div>
                </div>
                <div class="card-content">
                    <h3 class="card-title">${this.escapeHtml(title)}</h3>
                    <p class="card-description">${this.escapeHtml(cleanDesc)}</p>
                    <div class="card-footer">
                        <div class="card-author">
                            <div class="author-avatar">${authorInitial}</div>
                            <div class="author-info">
                                <span class="author-name">${this.escapeHtml(authorName)}</span>
                                <span class="author-date">${dateStr}</span>
                            </div>
                        </div>
                        <div class="card-stats">
                            <span class="stat-item">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                                ${viewCount}
                            </span>
                        </div>
                    </div>
                </div>
            </div>`;
    }

    renderPagination() {
        const el = document.getElementById('pagination');
        if (!el || this.totalPages <= 1) { if (el) el.innerHTML = ''; return; }
        let h = '<div class="pagination-wrap">';
        // C-24: 使用 data-page 属性 + 事件委托，避免全局 gallery 变量时序依赖
        h += `<button class="page-btn page-prev" data-page="${this.currentPage - 1}"${this.currentPage <= 1 ? ' disabled' : ''}>上一页</button>`;
        h += `<span class="page-info">第 ${this.currentPage} / ${this.totalPages} 页</span>`;
        h += `<button class="page-btn page-next" data-page="${this.currentPage + 1}"${this.currentPage >= this.totalPages ? ' disabled' : ''}>下一页</button>`;
        h += '</div>';
        el.innerHTML = h;
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text || '';
        return div.innerHTML;
    }

    formatDate(dateStr) {
        const date = new Date(dateStr);
        const now = new Date();
        const diff = now - date;
        const days = Math.floor(diff / (1000 * 60 * 60 * 24));
        if (days === 0) return '今天';
        if (days === 1) return '昨天';
        if (days < 7) return `${days}天前`;
        if (days < 30) return `${Math.floor(days / 7)}周前`;
        if (days < 365) return `${Math.floor(days / 30)}个月前`;
        return `${Math.floor(days / 365)}年前`;
    }

    viewAsset(id) {
        if (!id) return;
        const url = `/asset-detail.html?id=${encodeURIComponent(id)}`;
        if (window.ILink && window.ILink.navigate) {
            window.ILink.navigate(url);
        } else {
            window.location.href = url;
        }
    }

    async handleUpload() {
        const title = document.getElementById('uploadTitle');
        const description = document.getElementById('uploadDesc');
        const category = document.getElementById('uploadCategory');
        const file = document.getElementById('uploadFile');

        if (!title || !title.value.trim()) { alert('请输入标题'); return; }
        if (!category || !category.value) { alert('请选择分类'); return; }

        const fd = new FormData();
        fd.append('title', title.value.trim());
        fd.append('description', (description ? description.value.trim() : '') + (category.value ? '（分类：' + category.value + '）' : ''));
        if (file && file.files[0]) fd.append('file', file.files[0]);

        try {
            const response = await apiFetch('/api/asset/upload', { method: 'POST', body: fd, credentials: 'same-origin' });
            const result = await response.json();
            if (result.code === 200) {
                if (window.ILink && window.ILink.showMessage) window.ILink.showMessage('发布成功', 'success');
                const modal = bootstrap.Modal.getInstance(document.getElementById('uploadModal'));
                if (modal) modal.hide();
                title.value = '';
                if (description) description.value = '';
                this.loadAssets(1);
            } else {
                alert(result.message || '上传失败');
            }
        } catch (e) {
            console.error(e);
            alert('网络错误，请稍后重试');
        }
    }
}

// 添加动效样式
if (!document.getElementById('galleryAnimStyles')) {
    const style = document.createElement('style');
    style.id = 'galleryAnimStyles';
    style.textContent = '@keyframes fadeInUp{from{opacity:0;transform:translateY(30px)}to{opacity:1;transform:translateY(0)}}';
    document.head.appendChild(style);
}

let gallery;
document.addEventListener('DOMContentLoaded', () => {
    gallery = new Gallery();
    window.gallery = gallery;
    document.getElementById('galleryGrid')?.addEventListener('click', (event) => {
        if (event.target.closest('button, a, input, select, textarea')) return;
        const card = event.target.closest('.gallery-card[data-asset-id]');
        if (card) gallery.viewAsset(card.dataset.assetId);
    });
    document.getElementById('galleryGrid')?.addEventListener('keydown', (event) => {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        const card = event.target.closest('.gallery-card[data-asset-id]');
        if (!card) return;
        event.preventDefault();
        gallery.viewAsset(card.dataset.assetId);
    });
});