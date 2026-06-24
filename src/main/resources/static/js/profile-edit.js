class UserSkillManager {
    constructor() {
        this.skills = [];
        this.userId = null;
        this.apiBase = '/api/user/skills';
    }

    init(userId) {
        this.userId = userId || document.body.getAttribute('data-user-id');
        this.bindEvents();
        this.loadSkills();
    }

    bindEvents() {
        const addBtn = document.getElementById('addSkillBtn');
        if (addBtn && !addBtn.dataset.bound) {
            addBtn.dataset.bound = '1';
            addBtn.addEventListener('click', () => this.addSkill());
        }

        const wrapper = document.getElementById('skillsTagsWrapper');
        if (wrapper && !wrapper.dataset.bound) {
            wrapper.dataset.bound = '1';
            wrapper.addEventListener('click', (e) => {
                const deleteBtn = e.target.closest('.il-skill-remove');
                if (deleteBtn) {
                    const skillId = deleteBtn.dataset.skillId;
                    if (skillId) {
                        this.deleteSkill(skillId);
                    }
                }
            });
        }
    }

    async loadSkills() {
        if (!this.userId) {
            console.warn('UserSkillManager: userId not found');
            return;
        }

        try {
            const response = await apiFetch(this.apiBase, {
                credentials: 'same-origin'
            });
            const result = await response.json();

            if (result.code === 200) {
                this.skills = result.data || [];
                this.renderSkills();
            } else {
                console.warn('加载技能失败:', result.message);
            }
        } catch (error) {
            console.error('加载技能异常:', error);
        }
    }

    renderSkills() {
        const wrapper = document.getElementById('skillsTagsWrapper');
        if (!wrapper) return;

        if (this.skills.length === 0) {
            wrapper.innerHTML = '';
            return;
        }

        wrapper.innerHTML = this.skills.map(skill => this.createSkillTag(skill)).join('');
    }

    createSkillTag(skill) {
        const levelLabel = this.getLevelLabel(skill.skillLevel);

        return `
            <span class="il-skill-tag">
                ${this.escapeHtml(skill.skillName)}
                ${levelLabel ? `<span class="il-skill-level">${levelLabel}</span>` : ''}
                <span class="il-skill-remove" data-skill-id="${skill.id}" title="删除">×</span>
            </span>
        `;
    }

    async addSkill() {
        const skillNameInput = document.getElementById('skillName');
        const skillCategorySelect = document.getElementById('skillCategory');
        const skillLevelSelect = document.getElementById('skillLevel');

        const skillName = skillNameInput?.value.trim();

        if (!skillName) {
            this.showMessage('请输入技能名称', 'warning');
            skillNameInput?.focus();
            return;
        }

        if (skillName.length > 64) {
            this.showMessage('技能名称不能超过64个字符', 'warning');
            return;
        }

        const isDuplicate = this.skills.some(
            s => s.skillName.toLowerCase() === skillName.toLowerCase()
        );
        if (isDuplicate) {
            this.showMessage('该技能已存在', 'warning');
            return;
        }

        const skillData = {
            skillName: skillName,
            skillCategory: skillCategorySelect?.value || '',
            skillLevel: skillLevelSelect?.value || ''
        };

        try {
            const response = await apiFetch(this.apiBase, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                credentials: 'same-origin',
                body: JSON.stringify(skillData)
            });

            const result = await response.json();

            if (result.code === 200) {
                this.skills.push(result.data);
                this.renderSkills();
                this.showMessage('技能添加成功', 'success');

                if (skillNameInput) skillNameInput.value = '';
                if (skillCategorySelect) skillCategorySelect.value = '';
                if (skillLevelSelect) skillLevelSelect.value = '';
            } else {
                this.showMessage(result.message || '添加技能失败', 'error');
            }
        } catch (error) {
            console.error('添加技能异常:', error);
            this.showMessage('系统异常，请稍后重试', 'error');
        }
    }

    async deleteSkill(skillId) {
        if (!confirm('确定要删除该技能吗？')) {
            return;
        }

        try {
            const response = await apiFetch(`${this.apiBase}/${encodeURIComponent(skillId)}`, {
                method: 'DELETE',
                credentials: 'same-origin'
            });

            const result = await response.json();

            if (result.code === 200) {
                this.skills = this.skills.filter(s => s.id != skillId);
                this.renderSkills();
                this.showMessage('技能已删除', 'success');
            } else {
                this.showMessage(result.message || '删除技能失败', 'error');
            }
        } catch (error) {
            console.error('删除技能异常:', error);
            this.showMessage('系统异常，请稍后重试', 'error');
        }
    }

    getLevelLabel(level) {
        const labels = {
            'beginner': '入门',
            'elementary': '初级',
            'intermediate': '中级',
            'advanced': '高级',
            'expert': '专家'
        };
        return labels[level] || '';
    }

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    showMessage(message, type = 'info') {
        if (typeof showMessage === 'function') {
            showMessage(message, type);
        } else {
            alert(message);
        }
    }
}

/** 从用户对象或表单字段值取首字（绝不读 label 文案），与顶部导航保持一致：优先用户名 */
function resolveAvatarInitial(userOrFields) {
    const sources = userOrFields
        ? [userOrFields.username, userOrFields.realName, userOrFields.nickname]
        : [];
    for (let i = 0; i < sources.length; i++) {
        const s = sources[i] != null ? String(sources[i]).trim() : '';
        if (s) return s.charAt(0).toUpperCase();
    }
    const realNameEl = document.getElementById('realName');
    const usernameEl = document.getElementById('username');
    const formSources = [
        usernameEl ? usernameEl.value : '',
        realNameEl ? realNameEl.value : ''
    ];
    for (let j = 0; j < formSources.length; j++) {
        const t = String(formSources[j] || '').trim();
        if (t) return t.charAt(0).toUpperCase();
    }
    return '?';
}

class AvatarUploader {
    constructor() {
        this.container = document.getElementById('avatarContainer');
        this.fileInput = document.getElementById('avatarFileInput');
        this.previewImg = document.getElementById('avatarPreviewImg');
        this.fallback = document.getElementById('avatarFallback');
        this.hiddenInput = document.getElementById('avatar');
        this.hint = document.getElementById('avatarUploadHint');
        this._imgLoadHandler = null;
        this.init();
    }

    init() {
        if (!this.container || !this.fileInput) return;

        this.container.addEventListener('click', () => {
            this.fileInput.click();
        });

        this.fileInput.addEventListener('change', (e) => {
            const file = e.target.files?.[0];
            if (file) {
                this.upload(file);
            }
        });

        const realNameEl = document.getElementById('realName');
        const usernameEl = document.getElementById('username');
        const onNameChange = () => {
            if (!this.hasAvatarUrl()) {
                this.showFallback(resolveAvatarInitial(null));
            }
        };
        if (realNameEl) realNameEl.addEventListener('input', onNameChange);
        if (usernameEl) usernameEl.addEventListener('input', onNameChange);

        this.loadUserAvatar();
    }

    hasAvatarUrl() {
        const src = this.previewImg && this.previewImg.getAttribute('src');
        return !!(src && String(src).trim());
    }

    hideFallback() {
        if (!this.fallback) return;
        this.fallback.classList.add('il-avatar-fallback--hidden');
        this.fallback.setAttribute('aria-hidden', 'true');
        this.fallback.textContent = '';
    }

    showFallback(letter) {
        if (this.previewImg) {
            this.previewImg.classList.add('il-avatar-img--hidden');
            this.previewImg.removeAttribute('src');
            if (this._imgLoadHandler) {
                this.previewImg.removeEventListener('load', this._imgLoadHandler);
                this.previewImg.removeEventListener('error', this._imgOnError);
                this._imgLoadHandler = null;
            }
        }
        if (!this.fallback) return;
        const ch = letter != null && String(letter).trim() ? String(letter).trim().charAt(0).toUpperCase() : '?';
        this.fallback.textContent = ch;
        this.fallback.classList.remove('il-avatar-fallback--hidden');
        this.fallback.removeAttribute('aria-hidden');
    }

    showImage(url) {
        if (!this.previewImg || !url) return;
        const u = String(url).trim();
        if (this.hiddenInput) this.hiddenInput.value = u;

        this.hideFallback();

        if (this._imgLoadHandler) {
            this.previewImg.removeEventListener('load', this._imgLoadHandler);
            this.previewImg.removeEventListener('error', this._imgOnError);
        }
        this._imgLoadHandler = () => this.hideFallback();
        this._imgOnError = () => {
            this.previewImg.classList.add('il-avatar-img--hidden');
            this.previewImg.removeAttribute('src');
            if (this.hiddenInput) this.hiddenInput.value = '';
            this.showFallback(resolveAvatarInitial(null));
        };
        this.previewImg.addEventListener('load', this._imgLoadHandler);
        this.previewImg.addEventListener('error', this._imgOnError);

        this.previewImg.classList.remove('il-avatar-img--hidden');
        this.previewImg.src = u;
        if (this.previewImg.complete) {
            this.hideFallback();
        }
    }

    async loadUserAvatar() {
        try {
            const response = await apiFetch('/api/user/profile', {
                credentials: 'same-origin'
            });

            const ct = response.headers.get('content-type') || '';
            if (!ct.includes('application/json')) {
                console.warn('响应非JSON');
                return;
            }

            const result = await response.json();

            if (result.code === 200 && result.data) {
                const user = result.data;
                const avatarUrl = user.avatar;
                const initialLetter = resolveAvatarInitial(user);
                this.setAvatar(avatarUrl, initialLetter);
            }
        } catch (error) {
            console.error('加载用户头像失败:', error);
        }
    }

    setAvatar(url, initialLetter) {
        const trimmed = url != null ? String(url).trim() : '';
        if (trimmed) {
            this.showImage(trimmed);
        } else {
            if (this.hiddenInput) this.hiddenInput.value = '';
            this.showFallback(initialLetter != null ? initialLetter : resolveAvatarInitial(null));
        }
    }

    async upload(file) {
        if (!file.type.startsWith('image/')) {
            this.showHint('请选择图片文件', 'error');
            return;
        }

        if (file.size > 5 * 1024 * 1024) {
            this.showHint('图片大小不能超过5MB', 'error');
            return;
        }

        this.showHint('上传中...', 'info');

        const formData = new FormData();
        formData.append('file', file);
        formData.append('bizType', 'avatars');

        try {
            const response = await apiFetch('/api/files/upload', {
                method: 'POST',
                body: formData,
                credentials: 'same-origin'
            });

            const result = await response.json();

            if (result.code === 200 && result.data) {
                const url = typeof result.data === 'string' ? result.data : result.data.url;
                if (!url) {
                    this.showHint(result.message || '上传失败', 'error');
                    return;
                }
                this.setAvatar(url, resolveAvatarInitial({
                    username: document.getElementById('username')?.value,
                    realName: document.getElementById('realName')?.value
                }));

                if (this.hiddenInput) {
                    this.hiddenInput.value = url;
                }

                this.showHint('上传成功', 'success');

                if (typeof applyAccountMenuFromUser === 'function') {
                    const uEl = document.getElementById('username');
                    const rEl = document.getElementById('realName');
                    applyAccountMenuFromUser({
                        username: uEl ? uEl.value : '',
                        realName: rEl ? rEl.value : '',
                        avatar: url,
                        role: ''
                    });
                }
            } else {
                this.showHint(result.message || '上传失败', 'error');
            }
        } catch (error) {
            console.error('上传头像异常:', error);
            this.showHint('上传异常，请重试', 'error');
        }

        if (this.fileInput) {
            this.fileInput.value = '';
        }
    }

    showHint(text, type) {
        if (!this.hint) return;
        this.hint.textContent = text;
        this.hint.style.color = type === 'error' ? '#ef4444' : type === 'success' ? '#10b981' : '#6b7280';
    }
}

document.addEventListener('DOMContentLoaded', function() {
    const userId = document.body.getAttribute('data-user-id');
    
    if (userId) {
        window.userSkillManager = new UserSkillManager();
        window.userSkillManager.init(userId);
    } else {
        console.warn('profile-edit.js: data-user-id not found on body');
    }

    window.avatarUploader = new AvatarUploader();
});
