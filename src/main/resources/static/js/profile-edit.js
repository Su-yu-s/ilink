class UserSkillManager {
    constructor() {
        this.skills = [];
        this.baselineSkills = [];
        this.userId = null;
        this.apiBase = '/api/user/skills';
        this.loaded = false;
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
                this.baselineSkills = this.cloneSkills(this.skills);
                this.loaded = true;
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
            this.emitChange();
            return;
        }

        wrapper.innerHTML = this.skills.map(skill => this.createSkillTag(skill)).join('');
        this.emitChange();
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
            id: `draft-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
            skillName: skillName,
            skillCategory: skillCategorySelect?.value || '',
            skillLevel: parseInt(skillLevelSelect?.value) || null,
            _draft: true
        };

        this.skills.push(skillData);
        this.renderSkills();
        if (skillNameInput) skillNameInput.value = '';
        if (skillCategorySelect) skillCategorySelect.value = '';
        if (skillLevelSelect) skillLevelSelect.value = '';
    }

    async deleteSkill(skillId) {
        if (!confirm('确定要删除该技能吗？')) {
            return;
        }

        this.skills = this.skills.filter(s => String(s.id) !== String(skillId));
        this.renderSkills();
    }

    cloneSkills(skills) {
        return (skills || []).map(skill => ({ ...skill }));
    }

    getItems() {
        return this.cloneSkills(this.skills);
    }

    snapshot() {
        return this.getItems();
    }

    restore(snapshot) {
        this.skills = this.cloneSkills(snapshot || this.baselineSkills);
        this.renderSkills();
    }

    resetToBaseline() {
        this.restore(this.baselineSkills);
    }

    async saveChanges() {
        const baselineIds = new Set(this.baselineSkills.map(skill => String(skill.id)));
        const currentIds = new Set(this.skills.filter(skill => !skill._draft).map(skill => String(skill.id)));
        const additions = this.skills.filter(skill => skill._draft);
        const removals = this.baselineSkills.filter(skill => baselineIds.has(String(skill.id)) && !currentIds.has(String(skill.id)));

        for (const skill of additions) {
            const response = await apiFetch(this.apiBase, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body: JSON.stringify({
                    skillName: skill.skillName,
                    skillCategory: skill.skillCategory || '',
                    skillLevel: skill.skillLevel || null
                })
            });
            const result = await response.json();
            if (!response.ok || Number(result.code) !== 200) {
                throw new Error(result.message || '技能保存失败');
            }
        }

        for (const skill of removals) {
            const response = await apiFetch(`${this.apiBase}/${encodeURIComponent(skill.id)}`, {
                method: 'DELETE',
                credentials: 'same-origin'
            });
            const result = await response.json();
            if (!response.ok || Number(result.code) !== 200) {
                throw new Error(result.message || '技能删除失败');
            }
        }

        await this.loadSkills();
        return this.getItems();
    }

    emitChange() {
        document.dispatchEvent(new CustomEvent('profile:skills-changed', {
            detail: { skills: this.getItems(), loaded: this.loaded }
        }));
    }

    getLevelLabel(level) {
        const labels = { 1: '入门', 2: '初级', 3: '中级', 4: '高级', 5: '专家' };
        const stringLabels = { 'beginner': '入门', 'elementary': '初级', 'intermediate': '中级', 'advanced': '高级', 'expert': '专家' };
        return labels[level] || stringLabels[level] || '';
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
            if (!document.body.classList.contains('profile-details-editing')) return;
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
        this.hint.style.color = type === 'error' ? '#000000' : type === 'success' ? '#333333' : '#6b7280';
    }
}

class ProfileDetailsController {
    constructor() {
        this.user = null;
        this.teacher = null;
        this.mode = 'view';
        this.skillSnapshot = [];
        this.honorSnapshot = [];
        this.readView = document.getElementById('profileReadView');
        this.readContent = document.getElementById('profileReadContent');
        this.loading = document.getElementById('profileReadLoading');
        this.form = document.getElementById('profileForm');
        this.editButton = document.getElementById('profileEditBtn');
        this.cancelButton = document.getElementById('profileCancelBtn');
        this.title = document.getElementById('profileDetailsTitle');
        this.subtitle = document.getElementById('profileDetailsSubtitle');
        this.bindEvents();
    }

    bindEvents() {
        this.editButton?.addEventListener('click', () => this.enterEdit());
        this.cancelButton?.addEventListener('click', () => this.cancelEdit());
        document.addEventListener('profile:skills-changed', () => {
            if (this.mode === 'view' && this.user) this.render();
        });
        document.addEventListener('profile:honors-changed', () => {
            if (this.mode === 'view' && this.user) this.render();
        });
    }

    setProfileData(user, teacher) {
        if (!user) return;
        this.user = this.clone(user);
        this.teacher = teacher ? this.clone(teacher) : null;
        if (this.loading) this.loading.hidden = true;
        if (this.readContent) this.readContent.hidden = false;
        if (this.editButton) this.editButton.disabled = false;
        this.render();
        this.setMode('view', false);
    }

    showLoadError(message) {
        if (this.loading) {
            this.loading.textContent = message || '个人资料暂时无法加载，请稍后重试。';
            this.loading.setAttribute('role', 'alert');
        }
        if (this.readContent) this.readContent.hidden = true;
        if (this.editButton) this.editButton.disabled = true;
    }

    enterEdit() {
        if (!this.user) return;
        this.applyServerDataToForm();
        if (window.userSkillManager) {
            window.userSkillManager.resetToBaseline();
            this.skillSnapshot = window.userSkillManager.snapshot();
        }
        if (window.profileHonorsManager) {
            window.profileHonorsManager.setItems(this.parseHonors(this.user.honors), { persist: false });
            this.honorSnapshot = window.profileHonorsManager.snapshot();
        }
        this.setMode('edit', true);
    }

    cancelEdit() {
        this.applyServerDataToForm();
        window.userSkillManager?.restore(this.skillSnapshot);
        window.profileHonorsManager?.restore(this.honorSnapshot);
        this.setMode('view', true);
    }

    setMode(mode, moveFocus) {
        this.mode = mode === 'edit' ? 'edit' : 'view';
        const editing = this.mode === 'edit';
        document.body.classList.toggle('profile-details-editing', editing);
        if (this.readView) this.readView.hidden = editing;
        if (this.form) this.form.hidden = !editing;
        if (this.editButton) {
            this.editButton.hidden = editing;
            this.editButton.setAttribute('aria-expanded', editing ? 'true' : 'false');
        }
        if (this.subtitle) {
            this.subtitle.textContent = editing
                ? '修改个人资料，保存后生效'
                : '查看和维护你的个人信息';
        }
        if (moveFocus) {
            if (editing) {
                window.requestAnimationFrame(() => document.getElementById('username')?.focus());
            } else {
                window.requestAnimationFrame(() => this.title?.focus());
            }
        }
    }

    applyServerDataToForm() {
        if (!this.user) return;
        if (typeof applyUserToProfileForm === 'function') applyUserToProfileForm(this.user);
        const values = {
            professionalTitle: this.teacher?.professionalTitle,
            teacherResearch: this.teacher?.researchDirection,
            teacherIntroduction: this.teacher?.introduction,
            teacherProjects: this.teacher?.projects
        };
        Object.keys(values).forEach(id => {
            const element = document.getElementById(id);
            if (element) element.value = values[id] || '';
        });
        window.avatarUploader?.setAvatar(this.user.avatar || '', resolveAvatarInitial(this.user));
        const form = this.form;
        form?.classList.remove('was-validated');
    }

    render() {
        const user = this.user || {};
        const teacher = this.teacher;
        const isTeacher = user.role === 'TEACHER';
        const value = input => String(input == null ? '' : input).trim() || '未填写';
        const setText = (id, input) => {
            const element = document.getElementById(id);
            if (element) element.textContent = value(input);
        };

        setText('profileReadName', user.realName || user.username);
        setText('profileReadUsername', user.username);
        setText('profileReadEmail', user.email);
        setText('profileReadRealName', user.realName);
        setText('profileReadSchool', user.school);
        setText('profileReadMajor', user.major);
        setText('profileReadGrade', user.grade);
        setText('profileReadCollege', user.college);
        setText('profileReadBio', user.bio);
        setText('profileReadGender', this.genderLabel(user.gender));

        const role = document.getElementById('profileReadRole');
        if (role) role.textContent = isTeacher ? '教师' : user.role === 'ADMIN' ? '管理员' : '学生';
        const gradeItem = document.getElementById('profileReadGradeItem');
        if (gradeItem) gradeItem.hidden = isTeacher;
        const teacherSection = document.getElementById('profileReadTeacherSection');
        if (teacherSection) teacherSection.hidden = !isTeacher;
        const labels = {
            profileReadSchoolLabel: isTeacher ? '任职单位' : '学校',
            profileReadMajorLabel: isTeacher ? '专业领域' : '专业',
            profileReadCollegeLabel: isTeacher ? '院系 / 部门' : '学院'
        };
        Object.keys(labels).forEach(id => {
            const element = document.getElementById(id);
            if (element) element.textContent = labels[id];
        });

        if (isTeacher) {
            const unavailable = teacher ? null : '暂时无法加载';
            setText('profileReadProfessionalTitle', unavailable || teacher?.professionalTitle);
            setText('profileReadTeacherResearch', unavailable || teacher?.researchDirection);
            setText('profileReadTeacherIntroduction', unavailable || teacher?.introduction);
            setText('profileReadTeacherProjects', unavailable || teacher?.projects);
        }

        this.renderAvatar(user);
        this.renderSkills();
        this.renderHonors(this.parseHonors(user.honors));
    }

    renderAvatar(user) {
        const image = document.getElementById('profileReadAvatarImg');
        const fallback = document.getElementById('profileReadAvatarFallback');
        const avatar = String(user.avatar || '').trim();
        if (fallback) {
            fallback.textContent = resolveAvatarInitial(user);
            fallback.hidden = !!avatar;
        }
        if (image) {
            image.onerror = () => {
                image.hidden = true;
                image.removeAttribute('src');
                if (fallback) fallback.hidden = false;
            };
            image.hidden = !avatar;
            if (avatar) image.src = avatar;
            else image.removeAttribute('src');
        }
    }

    renderSkills() {
        const container = document.getElementById('profileReadSkills');
        if (!container) return;
        const skills = window.userSkillManager?.getItems() || [];
        if (!skills.length) {
            container.innerHTML = '<span class="il-profile-read-empty">未填写</span>';
            return;
        }
        container.innerHTML = skills.map(skill => {
            const level = window.userSkillManager?.getLevelLabel(skill.skillLevel) || '';
            return `<span class="il-profile-read-tag">${this.escape(skill.skillName)}${level ? `<small>${this.escape(level)}</small>` : ''}</span>`;
        }).join('');
    }

    renderHonors(honors) {
        const container = document.getElementById('profileReadHonors');
        if (!container) return;
        if (!honors.length) {
            container.innerHTML = '<span class="il-profile-read-empty">未填写</span>';
            return;
        }
        container.innerHTML = honors.map(honor => {
            const meta = [honor.issuer, honor.period].filter(Boolean).join(' · ');
            const title = String(honor.title || '').trim() || '未填写';
            const level = this.honorLevelInfo(honor.level);
            const proof = this.honorProofHtml(honor.proofUrl, title);
            return `
                <article class="il-profile-read-honor${proof ? ' il-profile-read-honor--with-proof' : ''}">
                    ${proof}
                    <div class="il-profile-read-honor__main">
                        <div class="il-profile-read-honor__heading">
                            <strong>${this.escape(title)}</strong>
                            ${level.label ? `<span class="il-profile-read-honor__level il-profile-read-honor__level--${level.key}">${this.escape(level.label)}</span>` : ''}
                        </div>
                        ${meta ? `<span class="il-profile-read-honor__meta">${this.escape(meta)}</span>` : ''}
                    </div>
                </article>`;
        }).join('');
        this.bindHonorProofFallbacks(container);
    }

    honorLevelInfo(level) {
        const key = String(level || '').trim().toLowerCase();
        const labels = {
            international: '国际级',
            national: '国家级',
            provincial: '省级',
            school: '校级',
            other: '其他'
        };
        return {
            key: Object.prototype.hasOwnProperty.call(labels, key) ? key : 'other',
            label: labels[key] || ''
        };
    }

    honorProofMediaKind(url) {
        const value = String(url || '').trim().toLowerCase();
        if (!value) return 'none';
        const path = value.split('?')[0].split('#')[0];
        if (/\.(jpe?g|png|gif|webp|bmp|svg|avif|jfif|heic|heif)$/i.test(path)) return 'image';
        if (/\.pdf$/i.test(path)) return 'pdf';
        return 'link';
    }

    safeProofUrl(url) {
        const raw = String(url || '').trim();
        if (!raw) return '';
        try {
            const candidate = typeof honorProofSafeUrl === 'function' ? honorProofSafeUrl(raw) : encodeURI(raw);
            const parsed = new URL(candidate, window.location.origin);
            if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return '';
            return parsed.href;
        } catch (_error) {
            return '';
        }
    }

    honorProofHtml(proofUrl, title) {
        const safeUrl = this.safeProofUrl(proofUrl);
        if (!safeUrl) return '';
        const escapedUrl = this.escape(safeUrl);
        const escapedRaw = this.escape(String(proofUrl || '').trim());
        const escapedTitle = this.escape(title);
        const kind = this.honorProofMediaKind(proofUrl);
        const label = `查看${title}的证明材料`;
        if (kind === 'image') {
            return `
                <a class="il-profile-read-honor__proof il-profile-read-honor__proof--image"
                   href="${escapedUrl}" target="_blank" rel="noopener noreferrer"
                   aria-label="${this.escape(label)}">
                    <img src="${escapedUrl}" data-raw-src="${escapedRaw}"
                         alt="${escapedTitle}证明材料缩略图" loading="lazy">
                    <span class="il-profile-read-honor__proof-fallback" hidden aria-hidden="true">证明</span>
                </a>`;
        }
        const badge = kind === 'pdf' ? 'PDF' : '附件';
        return `
            <a class="il-profile-read-honor__proof il-profile-read-honor__proof--${kind}"
               href="${escapedUrl}" target="_blank" rel="noopener noreferrer"
               aria-label="${this.escape(label)}">
                <span aria-hidden="true">${badge}</span>
            </a>`;
    }

    bindHonorProofFallbacks(container) {
        container.querySelectorAll('.il-profile-read-honor__proof--image img').forEach(image => {
            const showFallback = () => {
                if (image.dataset.encodedRetry !== '1') {
                    image.dataset.encodedRetry = '1';
                    const raw = image.dataset.rawSrc || '';
                    const retry = this.safeProofUrl(raw);
                    if (retry && retry !== image.src) {
                        image.src = retry;
                        return;
                    }
                }
                const proof = image.closest('.il-profile-read-honor__proof');
                const fallback = proof?.querySelector('.il-profile-read-honor__proof-fallback');
                image.hidden = true;
                proof?.classList.add('is-broken');
                if (fallback) fallback.hidden = false;
            };
            image.addEventListener('error', showFallback);
            if (image.complete && image.naturalWidth === 0) showFallback();
        });
    }

    parseHonors(raw) {
        if (Array.isArray(raw)) return this.clone(raw);
        if (!raw || !String(raw).trim()) return [];
        try {
            const parsed = JSON.parse(raw);
            return Array.isArray(parsed) ? parsed : [];
        } catch (_error) {
            return [];
        }
    }

    genderLabel(gender) {
        return { MALE: '男', FEMALE: '女', OTHER: '其他' }[gender] || '';
    }

    clone(value) {
        return JSON.parse(JSON.stringify(value == null ? null : value));
    }

    escape(value) {
        const element = document.createElement('div');
        element.textContent = String(value == null ? '' : value);
        return element.innerHTML;
    }
}

document.addEventListener('DOMContentLoaded', function() {
    const userId = document.body.getAttribute('data-user-id');

    window.profileDetailsController = new ProfileDetailsController();
    
    if (userId) {
        window.userSkillManager = new UserSkillManager();
        window.userSkillManager.init(userId);
    } else {
        console.warn('profile-edit.js: data-user-id not found on body');
    }

    window.avatarUploader = new AvatarUploader();
});

