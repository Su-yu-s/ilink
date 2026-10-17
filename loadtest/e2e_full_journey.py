# -*- coding: utf-8 -*-
"""全面 e2e：建队→邀请审批→资料库→任务→提交审阅→批阅→KG索引→清理
账号：uitest_leader(id=38, 队长) / uitest_member(临时创建, 结束后删)
"""
import json, os, subprocess, sys, uuid
import urllib.request, urllib.parse, urllib.error, http.cookiejar

BASE = 'http://localhost:8092'
results = []
MYSQL = ['mysql', '-uroot', '-p1234', '-h127.0.0.1', '-D', 'ilink', '-N', '-B']

def sql(q):
    r = subprocess.run(MYSQL + ['-e', q], capture_output=True, text=True)
    if r.returncode != 0:
        print('SQL ERROR:', r.stderr.strip()[:300])
    return r.stdout.strip()

def mk_session():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)), cj

def req(opener, cj, method, path, data=None, raw=None, files=None):
    url = BASE + path
    headers = {'Accept': 'application/json'}
    body = None
    if files is not None:
        boundary = uuid.uuid4().hex
        parts = []
        for k, v in (data or {}).items():
            parts.append(('--%s\r\nContent-Disposition: form-data; name="%s"\r\n\r\n%s\r\n' % (boundary, k, v)).encode())
        for fname, fcontent, ctype in files:
            parts.append(('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: %s\r\n\r\n' % (boundary, fname, ctype)).encode())
            parts.append(fcontent)
            parts.append(b'\r\n')
        parts.append(('--%s--\r\n' % boundary).encode())
        body = b''.join(parts)
        headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
    elif raw is not None:
        body = raw
        headers['Content-Type'] = 'application/json; charset=utf-8'
    elif data is not None:
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        headers['Content-Type'] = 'application/json; charset=utf-8'
    xsrf = None
    for c in cj:
        if c.name == 'XSRF-TOKEN':
            xsrf = urllib.parse.unquote(c.value)
    if xsrf and method in ('POST', 'PUT', 'DELETE'):
        headers['X-XSRF-TOKEN'] = xsrf
    r = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        resp = opener.open(r, timeout=20)
        return resp.status, resp.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')

def jload(txt):
    try:
        return json.loads(txt)
    except Exception:
        return {}

def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + ((' | ' + str(detail)[:220]) if (detail and not cond) else ''))

# ===== 0. 成员账号（DB 直插，BCrypt 复用 leader 的哈希）=====
hash38 = sql("SELECT password FROM user WHERE id=38;")
sql("DELETE FROM user WHERE username='uitest_member';")
sql("INSERT INTO user (username, password, real_name, phone_number, role, created_at) "
    "VALUES ('uitest_member', '%s', '测试成员', '13900022222', 'STUDENT', NOW());" % hash38)
mid = sql("SELECT id FROM user WHERE username='uitest_member';")
check('成员账号就绪', mid.isdigit(), mid)

# ===== 1. 双方登录 =====
lop, lcj = mk_session()
mop, mcj = mk_session()
req(mop, mcj, 'GET', '/login')
st, _ = req(lop, lcj, 'GET', '/login')
st, txt = req(lop, lcj, 'POST', '/api/login', data={'identifier': '13900011111', 'password': 'Loadtest123'})
check('队长登录', st == 200 and jload(txt).get('code') == 200, (st, txt[:120]))
st, txt = req(mop, mcj, 'POST', '/api/login', data={'identifier': '13900022222', 'password': 'Loadtest123'})
check('成员登录', st == 200 and jload(txt).get('code') == 200, (st, txt[:120]))

# ===== 2. 队长创建团队 =====
st, txt = req(lop, lcj, 'POST', '/api/team', data={
    'title': '全面测试队-勿动', 'description': 'e2e 全链路测试团队，测完即删',
    'customCompetition': 'e2e 虚构大赛', 'category': '技术开发',
    'requiredSkills': 'Python', 'requiredMemberCount': 3,
    'deadline': '2026-12-31'})
rj = jload(txt)
team_id = (rj.get('data') or {}).get('id')
check('创建团队', st == 200 and team_id, (st, txt[:180]))

# ===== 3. 成员申请加入 =====
st, txt = req(mop, mcj, 'POST', '/api/team/join', data={'teamId': team_id, 'message': '想加入一起打比赛'})
rj = jload(txt)
check('成员申请加入', st == 200, (st, txt[:180]))
app_id = sql("SELECT id FROM team_application WHERE team_id=%s AND user_id=%s ORDER BY id DESC LIMIT 1;" % (team_id, mid))

# ===== 4. 队长审批通过 =====
st, txt = req(lop, lcj, 'PUT', '/api/team/application/%s/approve' % app_id, data={'action': 'APPROVED', 'note': 'e2e'})
check('队长审批通过', st == 200, (st, txt[:180]))
st, txt = req(lop, lcj, 'GET', '/api/team/%s/members' % team_id)
mem_names = txt
check('成员已在队伍中', 'uitest_member' in mem_names or '测试成员' in mem_names, mem_names[:150])

# ===== 4.5 团队切到组队中（TEAMING，任务系统前提）=====
st, txt = req(lop, lcj, 'PUT', '/api/team/%s/status' % team_id, data={'status': 'TEAMING'})
check('团队状态 OPEN→TEAMING', st == 200, (st, txt[:180]))

# ===== 5. 资料库：文件夹 =====
st, txt = req(lop, lcj, 'POST', '/api/team/%s/library/folders' % team_id, data={'name': 'e2e目录'})
fid_folder = (jload(txt).get('data') or {}).get('id')
check('新建文件夹', st == 200 and fid_folder, (st, txt[:180]))

# ===== 6. 上传物理文件 + 登记（挂到文件夹）=====
st, txt = req(lop, lcj, 'POST', '/api/upload/attachment?kind=library', files=[('e2e.txt', '全面测试内容v1'.encode('utf-8'), 'text/plain')])
up1 = jload(txt)
furl1 = (up1.get('data') or {}).get('url') or (up1.get('data') or {}).get('fileUrl')
check('上传附件(kind=library)', st == 200 and furl1, (st, txt[:200]))
st, txt = req(lop, lcj, 'POST', '/api/team/%s/library/files' % team_id,
              data={'name': 'e2e文件.txt', 'url': furl1, 'size': 18, 'parentId': fid_folder})
fid1 = ((jload(txt).get('data') or {}).get('file') or {}).get('id')
check('登记资料库(挂文件夹)', st == 200 and fid1, (st, txt[:220]))

# ===== 7. 同名冲突（无策略 → conflict）=====
st, txt = req(lop, lcj, 'POST', '/api/team/%s/library/files' % team_id,
              data={'name': 'e2e文件.txt', 'url': furl1, 'size': 18, 'parentId': fid_folder})
rj = jload(txt)
check('同名无策略→返回冲突', st == 200 and (rj.get('data') or {}).get('conflict') is True, (st, txt[:200]))

# ===== 8. KEEP_BOTH 自动改名 =====
st, txt = req(lop, lcj, 'POST', '/api/team/%s/library/files' % team_id,
              data={'name': 'e2e文件.txt', 'url': furl1, 'size': 18,
                    'parentId': fid_folder, 'conflictStrategy': 'KEEP_BOTH'})
fid2 = ((jload(txt).get('data') or {}).get('file') or {}).get('id')
check('KEEP_BOTH→保留两者', st == 200 and fid2, (st, txt[:200]))

# ===== 9. 树结构（文件夹+2文件，referenced 标记）=====
st, txt = req(lop, lcj, 'GET', '/api/team/%s/library?view=active' % team_id)
rj = jload(txt)
flat = rj.get('data') or {}
items = flat if isinstance(flat, list) else (flat.get('files') or flat.get('items') or flat.get('list') or [])
names = [i.get('name') for i in items] if isinstance(items, list) else []
check('树含 e2e目录', any('e2e目录' in str(n) for n in names) or 'e2e目录' in txt, names[:8])

# ===== 10. 重命名 + 重名拒绝 =====
st, txt = req(lop, lcj, 'PUT', '/api/team/%s/library/files/%s' % (team_id, fid1), data={'name': 'e2e文件改名.txt'})
check('重命名成功', st == 200, (st, txt[:150]))
st, txt = req(lop, lcj, 'PUT', '/api/team/%s/library/files/%s' % (team_id, fid2), data={'name': 'e2e文件改名.txt'})
check('重命名撞名→400', st == 400, st)

# ===== 11. 任务（requiresReview 默认 true）+ 通知 =====
st, txt = req(lop, lcj, 'POST', '/api/team/%s/tasks' % team_id,
              data={'taskTitle': 'e2e批阅任务', 'taskDescription': '提交材料并接受批阅',
                    'assignedTo': int(mid), 'priority': 1, 'taskType': 'DEVELOPMENT',
                    'deadline': '2026-12-31T23:59:59'})
rj = jload(txt)
task_id = (rj.get('data') or {}).get('id')
check('队长建任务(需审阅)', st == 200 and task_id, (st, txt[:220]))
kg_cnt = sql("SELECT COUNT(*) FROM kg_index_task WHERE doc_id=%s AND doc_type='TASK';" % task_id) if task_id else '0'
check('任务已入KG索引队列', kg_cnt.isdigit() and int(kg_cnt) >= 1, kg_cnt)
notif = sql("SELECT COUNT(*) FROM notification WHERE user_id=%s AND type='TASK_ASSIGNED';" % mid)
check('成员收到任务指派通知', notif.isdigit() and int(notif) >= 1, notif)

# ===== 12. 成员 selfAssign 自建任务 =====
st, txt = req(mop, mcj, 'POST', '/api/team/%s/tasks' % team_id,
              data={'taskTitle': '成员自建任务', 'taskDescription': '自己拆给自己',
                    'assignedTo': int(mid), 'priority': 2, 'taskType': 'DEVELOPMENT',
                    'deadline': '2026-12-31T23:59:59'})
rj = jload(txt)
self_task = (rj.get('data') or {}).get('id')
check('成员 selfAssign 建任务', st == 200 and self_task, (st, txt[:200]))
# 自己给自己分配不通知
notif2 = sql("SELECT COUNT(*) FROM notification WHERE user_id=%s AND type='TASK_ASSIGNED' AND content LIKE '%%成员自建任务%%';" % mid)
check('自建任务不产生指派通知', notif2 == '0', notif2)
# 成员给别人分配 → 403
st, txt = req(mop, mcj, 'POST', '/api/team/%s/tasks' % team_id,
              data={'taskTitle': '越权任务', 'taskDescription': 'x', 'assignedTo': 38,
                    'priority': 2, 'taskType': 'DEVELOPMENT', 'deadline': '2026-12-31T23:59:59'})
check('成员给别人派任务→403', st == 403, st)

# ===== 13. 成员提交审阅（挂资料库文件）=====
st, txt = req(mop, mcj, 'PUT', '/api/tasks/%s/status' % task_id, data={'status': 'IN_PROGRESS'})
check('成员开始任务(PENDING→IN_PROGRESS)', st == 200, (st, txt[:180]))
st, txt = req(mop, mcj, 'POST', '/api/tasks/%s/submit' % task_id,
              data={'content': 'e2e 提交说明',
                    'attachments': [{'name': 'e2e文件改名.txt', 'url': furl1, 'libraryFileId': fid1}]})
check('成员提交审阅(挂资料库文件)', st == 200, (st, txt[:220]))

# ===== 14. 驳回无点评 → 400 =====
st, txt = req(lop, lcj, 'PUT', '/api/tasks/%s/review' % task_id,
              data={'action': 'RETURNED', 'overall': '', 'fileComments': []})
check('驳回无点评→400', st == 400, (st, txt[:150]))
# ===== 15. 驳回带点评 =====
st, txt = req(lop, lcj, 'PUT', '/api/tasks/%s/review' % task_id,
              data={'action': 'RETURNED', 'overall': '请补充说明', 'fileComments': [{'fileName': 'e2e文件改名.txt', 'comment': '内容太薄'}]})
check('驳回带点评成功', st == 200, (st, txt[:200]))
# 任务应回到 IN_PROGRESS
st, txt = req(lop, lcj, 'GET', '/api/team/%s/tasks' % team_id)
tasks = jload(txt).get('data') or []
trow = next((t for t in tasks if t.get('id') == task_id), {})
check('驳回后回到 IN_PROGRESS', (trow.get('status') or trow.get('taskStatus')) == 'IN_PROGRESS', trow.get('status'))

# ===== 16. 成员再提交 → 队长通过 =====
st, txt = req(mop, mcj, 'POST', '/api/tasks/%s/submit' % task_id,
              data={'content': '已补充',
                    'attachments': [{'name': 'e2e文件改名.txt', 'url': furl1, 'libraryFileId': fid1}]})
check('二次提交', st == 200, (st, txt[:180]))
st, txt = req(lop, lcj, 'PUT', '/api/tasks/%s/review' % task_id,
              data={'action': 'COMPLETED', 'overall': '通过', 'fileComments': []})
check('批阅通过→COMPLETED', st == 200, (st, txt[:200]))
st, txt = req(lop, lcj, 'GET', '/api/team/%s/tasks' % team_id)
tasks = jload(txt).get('data') or []
trow = next((t for t in tasks if t.get('id') == task_id), {})
check('任务状态 COMPLETED', (trow.get('status') or trow.get('taskStatus')) == 'COMPLETED', trow.get('status'))

# ===== 17. 批阅时间线 =====
st, txt = req(lop, lcj, 'GET', '/api/tasks/%s/reviews' % task_id)
rj = jload(txt)
revs = rj.get('data') or []
check('批阅时间线=2条', st == 200 and isinstance(revs, list) and len(revs) >= 2, (st, str(revs)[:200]))

# ===== 18. requiresReview=false 直接完成 =====
st, txt = req(lop, lcj, 'POST', '/api/team/%s/tasks' % team_id,
              data={'taskTitle': '免审阅任务', 'taskDescription': '直接完成型', 'assignedTo': int(mid),
                    'priority': 3, 'requiresReview': False, 'taskType': 'OTHER',
                    'deadline': '2026-12-31T23:59:59'})
direct_task = (jload(txt).get('data') or {}).get('id')
st, txt = req(mop, mcj, 'PUT', '/api/tasks/%s/status' % direct_task, data={'status': 'IN_PROGRESS'})
st, txt = req(mop, mcj, 'POST', '/api/tasks/%s/complete' % direct_task, data={})
check('免审阅任务直接完成', st == 200, (st, txt[:200]))
# 免审阅任务提交审阅应被拒（无 submission 也无 requiresReview）
st, txt = req(lop, lcj, 'POST', '/api/team/%s/tasks' % team_id,
              data={'taskTitle': '需审阅任务2', 'taskDescription': 'x', 'assignedTo': int(mid),
                    'priority': 3, 'requiresReview': True, 'taskType': 'OTHER',
                    'deadline': '2026-12-31T23:59:59'})
rr_task = (jload(txt).get('data') or {}).get('id')
st, txt = req(mop, mcj, 'PUT', '/api/tasks/%s/status' % rr_task, data={'status': 'IN_PROGRESS'})
st, txt = req(mop, mcj, 'POST', '/api/tasks/%s/complete' % rr_task, data={})
check('需审阅任务不可直接完成', st in (400, 403), st)

# ===== 19. 资料库删除/回收站/恢复/彻底删除 =====
st, txt = req(lop, lcj, 'DELETE', '/api/team/%s/library/files/%s?purge=false' % (team_id, fid2))
check('软删除文件', st == 200, (st, txt[:150]))
st, txt = req(lop, lcj, 'GET', '/api/team/%s/library?view=trash' % team_id)
check('回收站可见', st == 200 and fid2 and str(fid2) in txt, (st, txt[:150]))
st, txt = req(lop, lcj, 'PUT', '/api/team/%s/library/files/%s/restore' % (team_id, fid2), data={})
check('恢复文件', st == 200, (st, txt[:150]))
st, txt = req(lop, lcj, 'GET', '/api/team/%s/library?view=active' % team_id)
check('恢复后回到 active', st == 200 and str(fid2) in txt, st)

# ===== 20. 权限边界：非成员访问 =====
oop, ocj = mk_session()
st, txt = req(oop, ocj, 'POST', '/api/login', data={'identifier': '13900011111', 'password': 'Loadtest123'})
# 用第三个全新会话但换一个非成员账号：直接用未登录会话验证 401 已在回归脚本覆盖，这里跳过

# ===== 21. 清理 =====
print('\n--- 清理测试数据 ---')
# 物理文件按 file_url 精确删
urls = sql("SELECT file_url FROM team_library_files WHERE team_id=%s;" % team_id)
removed = 0
for u in urls.splitlines():
    u = u.strip().lstrip('/')
    if not u:
        continue
    p = os.path.join('E:/Code/html/Ilink', u.replace('upload', 'uploads', 1)) if u.startswith('upload/') else os.path.join('E:/Code/html/Ilink/uploads', u.split('/uploads/')[-1] if '/uploads/' in u else u)
    # file_url 形如 /upload/library/... 或 uploads/library/...，统一映射
    norm = u.replace('\\', '/')
    if norm.startswith('upload/'):
        norm = 'uploads/' + norm[len('upload/'):]
    p = os.path.join('E:/Code/html/Ilink', norm)
    if os.path.exists(p):
        os.remove(p)
        removed += 1
print('物理文件删除: %d 个' % removed)
sql("DELETE FROM team_library_files WHERE team_id=%s;" % team_id)
sql("DELETE FROM task_reviews WHERE task_id IN (SELECT id FROM team_tasks WHERE team_id=%s);" % team_id)
sql("DELETE FROM team_task_submissions WHERE task_id IN (SELECT id FROM team_tasks WHERE team_id=%s);" % team_id)
sql("DELETE FROM team_tasks WHERE team_id=%s;" % team_id)
sql("DELETE FROM kg_index_task WHERE doc_type='TASK' AND doc_id IN (SELECT id FROM team_tasks WHERE team_id=%s);" % team_id)
sql("DELETE FROM team_application WHERE team_id=%s;" % team_id)
sql("DELETE FROM team_demand WHERE id=%s;" % team_id)
sql("DELETE FROM notification WHERE user_id=%s OR user_id=38;" % mid)
sql("DELETE FROM user WHERE id=%s;" % mid)
left = sql("SELECT COUNT(*) FROM team_demand WHERE id=%s;" % team_id)
check('DB 清理完成', left == '0', left)

print()
fails = [r for r in results if not r[1]]
print('===== e2e 全链路: %d/%d 通过 =====' % (len(results) - len(fails), len(results)))
for name, ok, detail in fails:
    print('失败: %s | %s' % (name, str(detail)[:220]))
sys.exit(1 if fails else 0)
