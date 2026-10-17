import io

# ============ team-publish.html：helper-row 结构 + SVG ============
p = 'src/main/resources/templates/team-publish.html'
s = io.open(p, encoding='utf-8').read()

old = '''                        <div class="tp-comp-toggle-row">
                            <button type="button" class="tp-comp-toggle" id="toggleCustomCompetition">目录里没有？自行填写</button>
                        </div>'''
new = '''                        <div class="tp-comp-toggle-row tp-helper-row">
                            <span class="tp-helper-hint" id="customCompetitionHint">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/></svg>
                                目录里没有？
                            </span>
                            <button type="button" class="tp-comp-toggle" id="toggleCustomCompetition">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
                                <span id="toggleCustomCompetitionText">自行填写</span>
                            </button>
                        </div>'''
assert old in s, 'comp toggle'
s = s.replace(old, new, 1)

old = '''                        <div class="tp-comp-toggle-row">
                            <button type="button" class="tp-comp-toggle" id="toggleMyMentors">从我的导师中选择</button>
                        </div>'''
new = '''                        <div class="tp-comp-toggle-row tp-helper-row">
                            <button type="button" class="tp-comp-toggle" id="toggleMyMentors">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/></svg>
                                <span>从我的导师中选择</span>
                            </button>
                        </div>'''
assert old in s, 'mentor toggle'
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('html done')

# ============ team-publish.js：竞赛切换改为同步 hint 与按钮文本（保留 SVG） ============
p = 'src/main/resources/static/js/team-publish.js'
s = io.open(p, encoding='utf-8').read()

old = '''    if (toggleBtn) {
        toggleBtn.addEventListener('click', function() {
            const toCustom = customCompetitionField.hidden;
            customCompetitionField.hidden = !toCustom;
            competitionSearch.style.display = toCustom ? 'none' : '';
            competitionMenu.hidden = true;
            toggleBtn.textContent = toCustom ? '从竞赛目录选择' : '目录里没有？自行填写';
            if (toCustom) {
                // 切到自填：清空目录选择
                if (competitionIdInput) competitionIdInput.value = '';
            } else if (customCompetitionInput) {
                // 切回目录：清空自填
                customCompetitionInput.value = '';
            }
        });
    }'''
new = '''    if (toggleBtn) {
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
    }'''
assert old in s, 'js toggle'
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('js done')

# ============ portal-redesign.css：helper-row / helper-link 风格（覆盖旧 tp-comp-toggle） ============
p = 'src/main/resources/static/css/portal-redesign.css'
s = io.open(p, encoding='utf-8').read()
s += '''
/* ============================================================
   发布页 helper 行（参考 demo）：灰字提示 + 靛蓝链接按钮（带 SVG），
   hover 底边线；替代原先的灰色下划线文字按钮。
   ============================================================ */
html.ilink-app .tp-helper-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.4;
}

html.ilink-app .tp-helper-hint {
  color: #9aa1ab;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

html.ilink-app .tp-helper-hint svg {
  width: 12px;
  height: 12px;
  opacity: 0.7;
}

html.ilink-app .tp-comp-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 0;
  background: transparent;
  padding: 2px 0;
  margin-top: 0;
  font-size: 12px;
  font-family: inherit;
  font-weight: 500;
  color: #6366f1;
  cursor: pointer;
  text-decoration: none;
  border-bottom: 1px solid transparent;
  transition: border-color 0.15s, color 0.15s;
}

html.ilink-app .tp-comp-toggle svg {
  width: 12px;
  height: 12px;
}

html.ilink-app .tp-comp-toggle:hover {
  color: #4f46e5;
  border-bottom-color: #6366f1;
}
'''
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('css done')
