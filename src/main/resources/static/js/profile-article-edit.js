// 个人中心 · 编辑社区文章

const CATEGORY_LABELS = {
    general: '综合交流',
    tech: '技术讨论',
    competition: '竞赛经验',
    resource: '资源分享'
};

let editQuill = null;
let pendingAttachments = [];
let postId = null;

document.addEventListener('DOMContentLoaded', function() {
    const params = new URLSearchParams(window.location.search);
    const rawId = params.get('id');
    if (!rawId) {
        showMessage('缺少文章 id', 'error');
        setTimeout(() => { window.location.href = '/profile-posts.html'; }, 1500);
        return;
    }
    postId = rawId;

    fillCategorySelect();
    document.getElementById('editAddAttachmentBtn')?.addEventListener('click', () => {
        document.getElementById('editAttachmentInput')?.click();
    });
    document.getElementById('editAttachmentInput')?.addEventListener('change', async function() {
        const files = this.files;
        this.value = '';
        if (!files || !files.length) return;
        for (let i = 0; i < files.length; i++) {
            if (pendingAttachments.length >= 10) {
                showMessage('附件最多 10 个', 'warning');
                break;
            }
            await uploadCommunityAttachment(files[i]);
        }
    });
    document.getElementById('editSaveBtn')?.addEventListener('click', savePost);

    const preview = document.getElementById('editPreviewLink');
    if (preview) {
        preview.href = `/community/article/${encodeURIComponent(postId)}`;
    }

    initQuill();
    loadPost();
});

function fillCategorySelect() {
    const sel = document.getElementById('editCategory');
    if (!sel) return;
    sel.innerHTML = '';
    ['general', 'tech', 'competition', 'resource'].forEach(key => {
        const opt = document.createElement('option');
        opt.value = key;
        opt.textContent = CATEGORY_LABELS[key];
        sel.appendChild(opt);
    });
}

function initQuill() {
    if (typeof Quill === 'undefined') return;
    const el = document.getElementById('editEditor');
    if (!el) return;
    const toolbarOptions = [
        [{ header: [1, 2, 3, false] }],
        ['bold', 'italic', 'underline', 'strike'],
        ['blockquote', 'code-block'],
        [{ list: 'ordered' }, { list: 'bullet' }],
        ['link', 'image'],
        ['clean']
    ];
    editQuill = new Quill('#editEditor', {
        theme: 'snow',
        modules: {
            toolbar: {
                container: toolbarOptions,
                handlers: {
                    image: function() {
                        uploadImageForQuill(this.quill);
                    }
                }
            }
        },
        placeholder: '正文内容…'
    });
}

function uploadImageForQuill(quill) {
    const input = document.createElement('input');
    input.setAttribute('type', 'file');
    input.setAttribute('accept', 'image/*');
    input.click();
    input.onchange = async function() {
        const file = input.files && input.files[0];
        if (!file) return;
        const fd = new FormData();
        fd.append('file', file);
        try {
            const r = await apiFetch('/api/upload/attachment?kind=avatar', {
                method: 'POST',
                body: fd,
                credentials: 'same-origin'
            });
            const j = await r.json();
            if (j.code !== 200 || !j.data || !j.data.url) {
                showMessage(j.message || '图片上传失败', 'error');
                return;
            }
            const range = quill.getSelection(true);
            const idx = range ? range.index : quill.getLength();
            quill.insertEmbed(idx, 'image', j.data.url);
            quill.setSelection(idx + 1);
        } catch (e) {
            console.error(e);
            showMessage('图片上传失败', 'error');
        }
    };
}

async function uploadCommunityAttachment(file) {
    const fd = new FormData();
    fd.append('file', file);
    try {
        const r = await apiFetch('/api/upload/attachment?kind=community', {
            method: 'POST',
            body: fd,
            credentials: 'same-origin'
        });
        const j = await r.json();
        if (j.code !== 200 || !j.data || !j.data.url) {
            showMessage(j.message || '上传失败', 'error');
            return;
        }
        pendingAttachments.push({ name: file.name, url: j.data.url });
        renderAttachments();
    } catch (e) {
        console.error(e);
        showMessage('上传失败', 'error');
    }
}

function escapeHtml(s) {
    if (s == null) return '';
    const div = document.createElement('div');
    div.textContent = s;
    return div.innerHTML;
}

function renderAttachments() {
    const ul = document.getElementById('editAttachmentList');
    if (!ul) return;
    ul.innerHTML = '';
    pendingAttachments.forEach((item, idx) => {
        const li = document.createElement('li');
        li.className = 'd-flex align-items-center justify-content-between gap-2 py-1 border-bottom';
        li.innerHTML =
            '<span class="text-truncate small flex-grow-1" title="' +
            escapeHtml(item.name) +
            '">' +
            escapeHtml(item.name) +
            '</span>' +
            '<button type="button" class="btn btn-sm btn-link text-danger p-0 flex-shrink-0" data-idx="' +
            idx +
            '">移除</button>';
        li.querySelector('button')?.addEventListener('click', function() {
            const i = parseInt(this.getAttribute('data-idx'), 10);
            if (!isNaN(i)) {
                pendingAttachments.splice(i, 1);
                renderAttachments();
            }
        });
        ul.appendChild(li);
    });
}

async function loadPost() {
    try {
        const response = await apiFetch(
            `/api/community/posts/${encodeURIComponent(postId)}/for-edit`,
            { credentials: 'same-origin' }
        );
        const result = await response.json();
        if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
            return;
        }
        if (result.code === 403 || result.code === 404) {
            showMessage(result.message || '无法加载', 'error');
            setTimeout(() => { window.location.href = '/profile-posts.html'; }, 1500);
            return;
        }
        if (result.code !== 200 || !result.data) {
            showMessage(result.message || '加载失败', 'error');
            return;
        }

        const d = result.data;
        const titleEl = document.getElementById('editTitle');
        const catEl = document.getElementById('editCategory');
        if (titleEl) titleEl.value = d.title || '';
        if (catEl && d.category) catEl.value = d.category;

        pendingAttachments = Array.isArray(d.attachments)
            ? d.attachments.map(a => ({
                name: a && a.name != null ? String(a.name) : '附件',
                url: a && a.url != null ? String(a.url) : ''
            })).filter(a => a.url && a.url.startsWith('/uploads/'))
            : [];
        renderAttachments();

        if (editQuill) {
            editQuill.setContents([]);
            const html = d.content || '';
            if (html) {
                editQuill.clipboard.dangerouslyPasteHTML(0, html);
            }
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}

async function savePost() {
    const category = document.getElementById('editCategory')?.value;
    const title = document.getElementById('editTitle')?.value.trim() || '';
    if (!editQuill) {
        showMessage('编辑器未就绪', 'error');
        return;
    }
    const plain = editQuill.getText().trim();
    if (!title || !plain) {
        showMessage('请填写标题与正文', 'warning');
        return;
    }
    const html = editQuill.root.innerHTML;

    try {
        const response = await apiFetch(`/api/community/posts/${encodeURIComponent(postId)}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin',
            body: JSON.stringify({
                category,
                title,
                content: html,
                attachments: pendingAttachments
            })
        });
        const result = await response.json();
        if (result.code === 200) {
            showMessage('保存成功', 'success');
            setTimeout(() => {
                window.location.href = '/profile-posts.html';
            }, 600);
        } else if (result.code === 401) {
            showMessage('请先登录', 'warning');
            setTimeout(() => { window.location.href = '/login'; }, 1200);
        } else {
            showMessage(result.message || '保存失败', 'error');
        }
    } catch (e) {
        console.error(e);
        showMessage('网络错误', 'error');
    }
}
