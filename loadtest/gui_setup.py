# -*- coding: utf-8 -*-
"""GUI 测试数据准备：建团队+成员+任务(REVIEW中)+资料库文件，保留不清理"""
import json, subprocess, sys, uuid
import urllib.request, urllib.parse, urllib.error, http.cookiejar

BASE = 'http://localhost:8092'
MYSQL = ['mysql', '-uroot', '-p1234', '-h127.0.0.1', '-D', 'ilink', '-N', '-B']

def sql(q):
    r = subprocess.run(MYSQL + ['-e', q], capture_output=True, text=True)
    if r.returncode != 0:
        print('SQL ERROR:', r.stderr.strip()[:200])
    return r.stdout.strip()

def mk_session():
    cj = http.cookiejar.CookieJar()
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj)), cj

def req(opener, cj, method, path, data=None, files=None):
    url = BASE + path
    headers = {'Accept': 'application/json'}
    body = None
    if files is not None:
        boundary = uuid.uuid4().hex
        parts = []
        for fname, fcontent, ctype in files:
            parts.append(('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: %s\r\n\r\n' % (boundary, fname, ctype)).encode())
            parts.append(fcontent)
            parts.append(b'\r\n')
        parts.append(('--%s--\r\n' % boundary).encode())
        body = b''.join(parts)
        headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
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

# 成员账号
hash38 = sql("SELECT password FROM user WHERE id=38;")
sql("DELETE FROM user WHERE username='uitest_member';")
sql("INSERT INTO user (username, password, real_name, phone_number, role, created_at) VALUES ('uitest_member', '%s', '测试成员', '13900022222', 'STUDENT', NOW());" % hash38)
mid = sql("SELECT id FROM user WHERE username='uitest_member';")
print('member id:', mid)

lop, lcj = mk_session()
mop, mcj = mk_session()
req(lop, lcj, 'GET', '/login')
req(mop, mcj, 'GET', '/login')
print('leader login:', req(lop, lcj, 'POST', '/api/login', data={'identifier': '13900011111', 'password': 'Loadtest123'})[0])
print('member login:', req(mop, mcj, 'POST', '/api/login', data={'identifier': '13900022222', 'password': 'Loadtest123'})[0])

# 建队 → 成员加入 → TEAMING
st, txt = req(lop, lcj, 'POST', '/api/team', data={
    'title': 'GUI 测试队', 'description': '浏览器界面测试用团队',
    'customCompetition': '界面测试杯', 'category': '技术开发',
    'requiredSkills': '测试', 'requiredMemberCount': 3, 'deadline': '2026-12-31T23:59:59'})
team_id = (jload(txt).get('data') or {}).get('id')
print('team id:', team_id)
req(mop, mcj, 'POST', '/api/team/join', data={'teamId': team_id, 'message': '加入'})
app_id = sql("SELECT id FROM team_application WHERE team_id=%s AND user_id=%s ORDER BY id DESC LIMIT 1;" % (team_id, mid))
print('approve:', req(lop, lcj, 'PUT', '/api/team/application/%s/approve' % app_id, data={'action': 'APPROVED', 'note': ''})[0])
print('teaming:', req(lop, lcj, 'PUT', '/api/team/%s/status' % team_id, data={'status': 'TEAMING'})[0])

# 队长上传资料库文件（txt + 假PDF 结构不对没关系，用真 pdf 头的极简文件）
st, txt = req(lop, lcj, 'POST', '/api/team/%s/library/folders' % team_id, data={'name': '交付材料'})
folder_id = ((jload(txt).get('data') or {}).get('file') or {}).get('id')
st, txt = req(lop, lcj, 'POST', '/api/upload/attachment?kind=library',
              files=[('开题报告.txt', '这是开题报告的内容：\n1. 研究背景\n2. 技术路线\n3. 预期成果\n'.encode('utf-8'), 'text/plain')])
up = jload(txt)
url1 = (up.get('data') or {}).get('url')
st, txt = req(lop, lcj, 'POST', '/api/team/%s/library/files' % team_id,
              data={'name': '开题报告.txt', 'url': url1, 'size': 60, 'parentId': folder_id})
lib1 = ((jload(txt).get('data') or {}).get('file') or {}).get('id')
print('library file id:', lib1, 'url:', url1)

# 队长再传一个 PDF（真文件头）
pdf_head = b'%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\nxref\n0 4\ntrailer<</Size 4/Root 1 0 R>>\n%%EOF'
st, txt = req(lop, lcj, 'POST', '/api/upload/attachment?kind=library', files=[('技术方案.pdf', pdf_head, 'application/pdf')])
up = jload(txt)
url2 = (up.get('data') or {}).get('url')
st, txt = req(lop, lcj, 'POST', '/api/team/%s/library/files' % team_id,
              data={'name': '技术方案.pdf', 'url': url2, 'size': 300, 'parentId': folder_id})
lib2 = ((jload(txt).get('data') or {}).get('file') or {}).get('id')
print('library file id:', lib2, 'url:', url2)

# 成员建任务(selfAssign) → 开始 → 提交(挂资料库文件) → REVIEW
st, txt = req(mop, mcj, 'POST', '/api/team/%s/tasks' % team_id,
              data={'taskTitle': '撰写开题报告', 'taskDescription': '完成开题报告初稿并提交审阅',
                    'assignedTo': int(mid), 'priority': 1, 'taskType': 'DOCUMENTATION',
                    'deadline': '2026-12-31T23:59:59'})
task_id = (jload(txt).get('data') or {}).get('id')
print('task id:', task_id)
req(mop, mcj, 'PUT', '/api/tasks/%s/status' % task_id, data={'status': 'IN_PROGRESS'})
st, txt = req(mop, mcj, 'POST', '/api/tasks/%s/submit' % task_id,
              data={'content': '开题报告初稿已完成，请老师审阅',
                    'attachments': [{'name': '开题报告.txt', 'url': url1, 'libraryFileId': lib1},
                                    {'name': '技术方案.pdf', 'url': url2, 'libraryFileId': lib2}]})
print('submit:', st)

print()
print('===== GUI 测试数据就绪 =====')
print('team_id=%s task_id=%s' % (team_id, task_id))
print('leader: 13900011111/Loadtest123 (ADMIN)')
print('member: 13900022222/Loadtest123 (STUDENT)')
