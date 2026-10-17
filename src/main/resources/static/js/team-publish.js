// 发布组队需求页面JavaScript

// 取消按钮：弹出确认框后返回组队大厅
document.addEventListener('DOMContentLoaded', function() {
    const cancelBtn = document.getElementById('cancelPublishBtn');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', function(e) {
            e.preventDefault();
            if (uiConfirm('确定要放弃发布吗？已填写的内容将不会保存。')) {
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

    // --- 参赛竞赛：目录搜索选择 或 自填，二者互斥、必填其一 ---
    const competitionSearch = document.getElementById('competitionSearch');
    const competitionMenu = document.getElementById('competitionMenu');
    const competitionIdInput = document.getElementById('competitionId');
    const customCompetitionInput = document.getElementById('customCompetitionInput');
    const customCompetitionField = document.getElementById('customCompetitionField');
    const toggleBtn = document.getElementById('toggleCustomCompetition');

    if (competitionSearch && competitionMenu) {
        CompetitionPicker.create({
            input: competitionSearch,
            menu: competitionMenu,
            onSelect: function(comp) {
                // 选了目录竞赛：记 id、清掉自填
                if (competitionIdInput) competitionIdInput.value = comp.id;
                if (customCompetitionInput) customCompetitionInput.value = '';
            }
        });
    }

    if (toggleBtn) {
        toggleBtn.addEventListener('click', function() {
            const toCustom = customCompetitionField.hidden;
            customCompetitionField.hidden = !toCustom;
            competitionSearch.style.display = toCustom ? 'none' : '';
            competitionMenu.hidden = true;
            // helper 行（hint + 链接文本）同步切换；SVG 图标保留
            const btnText = document.getElementById('toggleCustomCompetitionText');
            if (btnText) btnText.textContent = toCustom ? '返回目录选择' : '自行填写';
            const hint = document.getElementById('customCompetitionHint');
            if (hint) {
                hint.innerHTML = toCustom
                    ? '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg> 正在填写自定义竞赛'
                    : '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg> 目录里没有？';
            }
            if (toCustom) {
                // 切到自填：清空目录选择
                if (competitionIdInput) competitionIdInput.value = '';
            } else if (customCompetitionInput) {
                // 切回目录：清空自填
                customCompetitionInput.value = '';
            }
        });
    }

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

    // --- 邀请导师（选填，单选）---
    const mentorSearch = document.getElementById('mentorSearch');
    const mentorMenu = document.getElementById('mentorMenu');
    const mentorPicked = document.getElementById('mentorPicked');
    const toggleMyMentors = document.getElementById('toggleMyMentors');

    function renderMentorPicked() {
        const id = document.getElementById('inviteMentorId').value;
        const name = document.getElementById('inviteMentorName').value;
        if (!mentorPicked) return;
        if (!id) {
            mentorPicked.hidden = true;
            mentorPicked.innerHTML = '';
            return;
        }
        mentorPicked.hidden = false;
        mentorPicked.innerHTML =
            '<span class="tp-chip is-active">' +
            '已选导师：' + name +
            '<button type="button" class="tp-mentor-remove" id="removeMentorBtn" aria-label="移除已选导师">×</button>' +
            '</span>';
        const removeBtn = document.getElementById('removeMentorBtn');
        if (removeBtn) {
            removeBtn.addEventListener('click', function() {
                document.getElementById('inviteMentorId').value = '';
                document.getElementById('inviteMentorName').value = '';
                renderMentorPicked();
            });
        }
    }

    if (mentorSearch && mentorMenu) {
        MentorPicker.create({
            input: mentorSearch,
            menu: mentorMenu,
            onPick: function(mentor) {
                document.getElementById('inviteMentorId').value = mentor.userId || '';
                document.getElementById('inviteMentorName').value = mentor.realName || '';
                renderMentorPicked();
            }
        });
    }

    if (toggleMyMentors) {
        toggleMyMentors.addEventListener('click', function() {
            if (!mentorMenu) return;
            mentorMenu.hidden = false;
            mentorSearch.setAttribute('aria-expanded', 'true');
            mentorMenu.innerHTML = '<div class="tp-comp-empty">通讯录加载中…</div>';
            apiFetch('/api/user/roster?side=mentors')
                .then(function(r) { return r.json(); })
                .then(function(res) {
                    const mentors = (res && res.data) || [];
                    // 通讯录行与搜索行同构（含 role 字段），共用同一套渲染
                    if (mentorMenu.innerHTML.indexOf('加载中') === -1) return;
                    if (!mentors.length) {
                        mentorMenu.innerHTML = '<div class="tp-comp-empty">通讯录还是空的——还没有建立指导关系的导师</div>';
                        return;
                    }
                    mentorMenu.innerHTML = mentors.map(function(m) {
                        const title = [m.professionalTitle, m.researchDirection].filter(Boolean).join(' · ') || m.major || '';
                        return '<button type="button" class="tp-comp-item" role="option" data-user-id="' + m.userId + '"' +
                            ' data-name="' + m.realName + '">' +
                            '<span class="tp-comp-name">' + m.realName + '</span>' +
                            (title ? '<span class="tp-comp-org">' + title + '</span>' : '') +
                            '</button>';
                    }).join('');
                })
                .catch(function() {
                    mentorMenu.innerHTML = '<div class="tp-comp-empty">通讯录加载失败，请稍后重试</div>';
                });
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
            const competitionIdRaw = document.getElementById('competitionId').value;
            const competitionId = competitionIdRaw ? parseInt(competitionIdRaw, 10) : null;
            const customCompetition = document.getElementById('customCompetitionInput')
                ? document.getElementById('customCompetitionInput').value.trim() : '';

            const teamData = {
                title: document.getElementById('title').value,
                description: document.getElementById('description').value,
                category: categoryValue,
                competitionId: competitionId,
                customCompetition: competitionId ? '' : customCompetition,
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
            
            // 验证表单（分类/竞赛现在是芯片与搜索选择，浏览器不会再替我们拦空值）
            if (!teamData.title || !teamData.description) {
                showMessage('请填写必填字段', 'error');
                return;
            }
            if (!categoryValue) {
                showMessage('请选择项目方向', 'error');
                return;
            }
            if (!competitionId && !customCompetition) {
                showMessage('请选择参赛竞赛，或点击「目录里没有？自行填写」', 'error');
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
                    // 可选导师邀请：发布成功后向所选导师发送入队邀请。
                    // 邀请失败不阻塞发布结果，只提示差异。
                    const newTeamId = result.data && (result.data.id || result.data.teamId);
                    const mentorId = document.getElementById('inviteMentorId') ? document.getElementById('inviteMentorId').value : '';
                    const mentorName = document.getElementById('inviteMentorName') ? document.getElementById('inviteMentorName').value : '';
                    if (newTeamId && mentorId) {
                        try {
                            const inviteResp = await apiFetch('/api/team/' + encodeURIComponent(newTeamId) + '/invite', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({ userIds: [Number(mentorId)] })
                            });
                            const inviteResult = await inviteResp.json();
                            if (inviteResult.code === 200) {
                                const invited = Array.isArray(inviteResult.data && inviteResult.data.invited)
                                    ? inviteResult.data.invited.length : 0;
                                showMessage(invited > 0
                                    ? '发布成功！已向导师 ' + mentorName + ' 发送邀请'
                                    : '发布成功，但导师邀请未发送（' + mentorName + ' 可能已在队中或已被邀请）', invited > 0 ? 'success' : 'warning');
                            } else {
                                showMessage('发布成功，但导师邀请发送失败：' + (inviteResult.message || '未知原因'), 'warning');
                            }
                        } catch (inviteError) {
                            console.error('导师邀请发送失败:', inviteError);
                            showMessage('发布成功，但导师邀请发送失败，可在团队详情页重新邀请', 'warning');
                        }
                    } else {
                        showMessage('发布成功！', 'success');
                    }
                    setTimeout(() => {
                        window.location.href = '/team-market.html';
                    }, 1800);
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
