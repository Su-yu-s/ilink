import io

# ============ admin.html：通用"删除原因"弹窗 ============
p = 'src/main/resources/templates/admin.html'
s = io.open(p, encoding='utf-8').read()
anchor = '    <script th:src="@{/lib/bootstrap.bundle.min.js}"></script>'
modal_html = '''    <!-- 通用删除原因弹窗：组队/成果/社区帖子等管理删除共用 -->
    <div class="modal fade" id="adminDeleteReasonModal" tabindex="-1" aria-hidden="true">
        <div class="modal-dialog modal-dialog-centered">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title" id="adminDeleteReasonTitle">删除确认</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="关闭"></button>
                </div>
                <div class="modal-body">
                    <p class="text-muted small">删除不可恢复。请填写删除原因，系统将把删除事实与原因通知内容所有者。</p>
                    <textarea class="form-control" id="adminDeleteReasonInput" rows="3" maxlength="200"
                              placeholder="请填写删除原因（必填，200 字以内）"></textarea>
                    <div class="text-danger small mt-2 d-none" id="adminDeleteReasonError">请填写删除原因</div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary btn-sm" data-bs-dismiss="modal">取消</button>
                    <button type="button" class="btn btn-danger btn-sm" id="adminDeleteReasonConfirmBtn">确认删除并通知</button>
                </div>
            </div>
        </div>
    </div>

''' + anchor
assert anchor in s
s = s.replace(anchor, modal_html, 1)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('admin.html modal added')

# ============ admin.js：helper + 三函数改造 ============
p = 'src/main/resources/static/js/admin.js'
s = io.open(p, encoding='utf-8').read()

helper = '''
// ============================================================
// 通用删除原因弹窗：删除不可恢复，原因必填；确认后回调执行真正的删除请求，
// 服务端会把删除事实与原因通知内容所有者。
// ============================================================
let adminDeleteReasonCallback = null;

function openDeleteReasonModal(title, onConfirm) {
    const modalEl = document.getElementById('adminDeleteReasonModal');
    if (!modalEl) return;
    document.getElementById('adminDeleteReasonTitle').textContent = title;
    const input = document.getElementById('adminDeleteReasonInput');
    const errorEl = document.getElementById('adminDeleteReasonError');
    if (input) input.value = '';
    if (errorEl) errorEl.classList.add('d-none');
    adminDeleteReasonCallback = onConfirm;
    const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
    modal.show();
    if (input) input.focus();
}

document.addEventListener('DOMContentLoaded', function () {
    const confirmBtn = document.getElementById('adminDeleteReasonConfirmBtn');
    const input = document.getElementById('adminDeleteReasonInput');
    const errorEl = document.getElementById('adminDeleteReasonError');
    if (!confirmBtn || !input) return;
    confirmBtn.addEventListener('click', function () {
        const reason = (input.value || '').trim();
        if (!reason) {
            if (errorEl) errorEl.classList.remove('d-none');
            return;
        }
        if (reason.length > 200) {
            if (errorEl) { errorEl.textContent = '删除原因不能超过 200 字'; errorEl.classList.remove('d-none'); }
            return;
        }
        if (errorEl) errorEl.classList.add('d-none');
        const modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('adminDeleteReasonModal'));
        modal.hide();
        const cb = adminDeleteReasonCallback;
        adminDeleteReasonCallback = null;
        if (typeof cb === 'function') cb(reason);
    });
});

'''

old_team = '''async function deleteTeam(teamId) {
    if (!confirm('确定要删除该团队吗？')) return;

    try {
        const response = await apiFetch(`/api/admin/team/${encodeURIComponent(String(teamId))}`, {
            method: 'DELETE'
        });
        const result = await response.json();

        if (result.code === 200) {
            showMessage('删除成功', 'success');
            loadTeams();
            loadDashboardData();
        } else {
            showMessage('删除失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('删除团队异常:', error);
        showMessage('删除失败，请稍后重试', 'error');
    }
}'''
new_team = '''async function deleteTeam(teamId) {
    openDeleteReasonModal('删除组队需求', async function (reason) {
        try {
            const response = await apiFetch(`/api/admin/team/${encodeURIComponent(String(teamId))}?reason=` + encodeURIComponent(reason), {
                method: 'DELETE'
            });
            const result = await response.json();

            if (result.code === 200) {
                showMessage('删除成功，已通知创建者', 'success');
                loadTeams();
                loadDashboardData();
            } else {
                showMessage('删除失败: ' + result.message, 'error');
            }
        } catch (error) {
            console.error('删除团队异常:', error);
            showMessage('删除失败，请稍后重试', 'error');
        }
    });
}'''
assert old_team in s, 'deleteTeam'
s = s.replace(old_team, new_team, 1)

old_asset = '''async function deleteAsset(assetId) {
    if (!confirm('确定要删除该成果吗？')) return;

    try {
        const response = await apiFetch(`/api/admin/asset/${encodeURIComponent(String(assetId))}`, {
            method: 'DELETE'
        });
        const result = await response.json();

        if (result.code === 200) {
            showMessage('删除成功', 'success');
            loadAssets();
            loadDashboardData();
        } else {
            showMessage('删除失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('删除成果异常:', error);
        showMessage('删除失败，请稍后重试', 'error');
    }
}'''
new_asset = '''async function deleteAsset(assetId) {
    openDeleteReasonModal('删除成果', async function (reason) {
        try {
            const response = await apiFetch(`/api/admin/asset/${encodeURIComponent(String(assetId))}?reason=` + encodeURIComponent(reason), {
                method: 'DELETE'
            });
            const result = await response.json();

            if (result.code === 200) {
                showMessage('删除成功，已通知所有者', 'success');
                loadAssets();
                loadDashboardData();
            } else {
                showMessage('删除失败: ' + result.message, 'error');
            }
        } catch (error) {
            console.error('删除成果异常:', error);
            showMessage('删除失败，请稍后重试', 'error');
        }
    });
}'''
assert old_asset in s, 'deleteAsset'
s = s.replace(old_asset, new_asset, 1)

old_post = '''async function deleteCommunityPost(postId) {
    if (!confirm('确定要删除该帖子吗？')) return;

    try {
        const response = await apiFetch(`/api/admin/community-post/${encodeURIComponent(String(postId))}`, {
            method: 'DELETE'
        });
        const result = await response.json();

        if (result.code === 200) {
            showMessage('删除成功', 'success');
            loadCommunityPosts();
            loadDashboardData();
        } else {
            showMessage('删除失败: ' + result.message, 'error');
        }
    } catch (error) {
        console.error('删除社区帖子异常:', error);
        showMessage('删除失败，请稍后重试', 'error');
    }
}'''
new_post = '''async function deleteCommunityPost(postId) {
    openDeleteReasonModal('删除社区帖子', async function (reason) {
        try {
            const response = await apiFetch(`/api/admin/community-post/${encodeURIComponent(String(postId))}?reason=` + encodeURIComponent(reason), {
                method: 'DELETE'
            });
            const result = await response.json();

            if (result.code === 200) {
                showMessage('删除成功，已通知作者', 'success');
                loadCommunityPosts();
                loadDashboardData();
            } else {
                showMessage('删除失败: ' + result.message, 'error');
            }
        } catch (error) {
            console.error('删除社区帖子异常:', error);
            showMessage('删除失败，请稍后重试', 'error');
        }
    });
}'''
assert old_post in s, 'deleteCommunityPost'
s = s.replace(old_post, new_post, 1)

# helper 插到 deleteTeam 函数之前
anchor = 'async function deleteTeam(teamId) {'
s = s.replace(anchor, helper + anchor, 1)

io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print('admin.js patched')
