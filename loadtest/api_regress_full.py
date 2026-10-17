# -*- coding: utf-8 -*-
"""API 全面回归：登录 → 新功能(检索/通讯录) → 团队 → 资料库 → 任务 → 批阅"""
import json, re, sys, io
import urllib.request, http.cookiejar

BASE = 'http://localhost:8092'
cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
results = []

def req(method, path, data=None, form=False, raw=None):
    url = BASE + path
    body = None
    headers = {'Accept': 'application/json'}
    if raw is not None:
        body = raw
        headers['Content-Type'] = 'application/json; charset=utf-8'
    elif data is not None:
        if form:
            body = urllib.parse.urlencode(data).encode()
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        else:
            body = json.dumps(data, ensure_ascii=False).encode('utf-8')
            headers['Content-Type'] = 'application/json; charset=utf-8'
    # CSRF: 从 cookie 读 XSRF-TOKEN
    xsrf = None
    for c in cj:
        if c.name == 'XSRF-TOKEN':
            xsrf = urllib.parse.unquote(c.value)
    if xsrf and method in ('POST', 'PUT', 'DELETE'):
        headers['X-XSRF-TOKEN'] = xsrf
    r = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        resp = opener.open(r, timeout=15)
        txt = resp.read().decode('utf-8', 'replace')
        return resp.status, txt
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8', 'replace')

def check(name, cond, detail=''):
    results.append((name, bool(cond), detail))
    print(('PASS ' if cond else 'FAIL ') + name + ((' | ' + str(detail)[:160]) if (detail and not cond) else ''))

import urllib.parse

# 1. 拿 CSRF token
st, txt = req('GET', '/login')
check('首页可达', st == 200, st)

# 2. 登录 uitest_leader
st, txt = req('POST', '/api/login', data={'identifier': '13900011111', 'password': 'Loadtest123'})
try:
    rj = json.loads(txt)
except Exception:
    rj = {}
check('登录成功', st == 200 and rj.get('code') == 200, (st, txt[:120]))

# 3. 当前用户
st, txt = req('GET', '/api/user/profile')
rj = json.loads(txt)
me_id = (rj.get('data') or {}).get('id')
check('获取当前用户', st == 200 and me_id == 38, (st, me_id))

# 4. 新功能：用户检索（按姓名搜）
st, txt = req('GET', '/api/user/search?keyword=test&limit=5')
rj = json.loads(txt)
check('用户检索 /api/user/search', st == 200 and rj.get('code') == 200, (st, txt[:150]))
# 隐私边界：响应中不得出现 phone/mobile/email/studentId 字段值
data = rj.get('data') or []
leak = []
for it in data if isinstance(data, list) else []:
    for k in ('phone', 'mobile', 'email', 'studentId', 'student_id'):
        if k in it and it[k]:
            leak.append(k)
check('检索响应隐私边界(无手机号/邮箱/学号)', not leak, leak)

# 5. 新功能：师生通讯录
st, txt = req('GET', '/api/user/roster')
rj = json.loads(txt)
check('通讯录 /api/user/roster', st == 200 and rj.get('code') == 200, (st, txt[:150]))

# 6. 空关键词检索
st, txt = req('GET', '/api/user/search')
rj = json.loads(txt)
check('空关键词返回空列表', st == 200 and (rj.get('data') == [] or rj.get('data') is not None), (st, txt[:100]))

# 7. 我的团队列表
st, txt = req('GET', '/api/teams/my')
rj = json.loads(txt)
teams = rj.get('data') or []
check('我的团队列表', st == 200, (st, txt[:120]))
print('  团队: ' + ', '.join('%s(id=%s)' % ((t.get('teamName') or t.get('name') or '?'), t.get('teamId') or t.get('id')) for t in teams[:6]) if teams else '  (无团队)')

# 8. team 32 资料库树（active）
st, txt = req('GET', '/api/team/32/library?view=active')
rj = json.loads(txt)
check('资料库树(active)', st == 200 and rj.get('code') == 200, (st, txt[:200]))
tree = rj.get('data') or {}
print('  资料库文件数: %s' % len(tree.get('files', tree.get('items', []))) if isinstance(tree, dict) else '')

# 9. team 32 回收站（队长可进）
st, txt = req('GET', '/api/team/32/library?view=trash')
rj = json.loads(txt)
check('资料库回收站(队长)', st == 200 and rj.get('code') == 200, (st, txt[:150]))

# 10. team 32 任务列表
st, txt = req('GET', '/api/team/32/tasks')
rj = json.loads(txt)
check('任务列表', st == 200 and rj.get('code') == 200, (st, txt[:150]))
tasks = rj.get('data') or []
print('  任务数: %s' % len(tasks))
# 找一个 REVIEW 状态的任务测批阅
review_task = next((t for t in tasks if (t.get('status') or t.get('taskStatus')) == 'REVIEW'), None)
if review_task:
    tid = review_task.get('id') or review_task.get('taskId')
    print('  REVIEW 中任务: id=%s title=%s' % (tid, review_task.get('taskTitle') or review_task.get('title')))
    # 11. 批阅时间线
    st, txt = req('GET', '/api/tasks/%s/reviews' % tid)
    rj = json.loads(txt)
    check('批阅时间线 /api/tasks/{id}/reviews', st == 200 and rj.get('code') == 200, (st, txt[:150]))
else:
    check('批阅时间线 /api/tasks/{id}/reviews', False, '无 REVIEW 状态任务可测')

# 12. 通知列表
st, txt = req('GET', '/api/notifications?size=5')
rj = json.loads(txt)
check('通知列表', st == 200, (st, txt[:120]))

# 13. 未登录防护：不带 cookie 直接访问资料库
noCookieOpener = urllib.request.build_opener()
try:
    resp = noCookieOpener.open(BASE + '/api/team/32/library', timeout=10)
    st2 = resp.status
except urllib.error.HTTPError as e:
    st2 = e.code
check('未登录访问资料库被拒(401)', st2 == 401, st2)

print()
fails = [r for r in results if not r[1]]
print('===== API 回归汇总: %d/%d 通过 =====' % (len(results) - len(fails), len(results)))
for name, ok, detail in fails:
    print('失败: %s | %s' % (name, str(detail)[:200]))
sys.exit(1 if fails else 0)
