# iLink 全面测试报告

日期：2026-09-12
测试范围：运行实例（localhost:8090）黑盒测试 + 单元测试套件 + 源码安全审计
测试原则：全程只读，未修改任何项目代码，未写数据库（仅对登录失败计数缓存产生影响，见附录 B）

---

## 一、总体结论

| 维度 | 结果 |
| --- | --- |
| 单元测试套件（H2） | **236 个测试全部通过，0 失败，0 跳过**（BUILD SUCCESS，耗时 21 秒） |
| 公开 API | 全部正常（200） |
| 页面路由 | 19 个公开页面全部 200，需登录页面正确 302 重定向到登录页 |
| 静态资源 | 检查的 JS/CSS 文件全部 200 |
| 安全审计 | 发现 1 个高风险配置问题、2 个中风险设计问题、3 个低风险问题（详见第三节） |

**最重要的发现**：运行实例中所有匿名 POST 请求（包括白名单内的 `/api/login`、`/api/password-reset/request`）返回 403 而非 401，根因是 CSRF 拦截层先于认证层触发了 `AccessDeniedException`，被 `accessDeniedHandler` 统一渲染为 403 JSON。防护效果正确（请求被拒绝），但语义错误（应为 401），且导致登录/找回密码功能在纯 curl 黑盒场景下不可达。

---

## 二、测试明细

### 2.1 单元测试套件（H2 profile，`mvn test`）

- 24 个测试类，共 236 个测试，全部通过。
- 覆盖范围：服务层（TeamInviteService 14、UserServiceImpl 13、TeamOwnershipService 9、NotificationServiceImpl 8 等）、AI 服务（AiAssistantService、WebSearchService）、控制器（AiAssistantControllerWebTest 等）、安全组件（PasswordResetService、RememberMeService、LoginAttemptService 相关）、HTML 净化（HtmlSanitizerTest）。
- 结论：工作区当前 95 个文件的未提交修改（含大量测试文件改动）没有破坏任何现有测试。

### 2.2 黑盒 API 测试（curl，localhost:8090）

**A. 公开 API 健康检查（全部通过）**

| 用例 | 请求 | 结果 |
| --- | --- | --- |
| A1 | `GET /api/team/list` | 200，返回 4 个队伍 |
| A2 | `GET /api/competitions?page=1&size=1` | 200，共 55 个竞赛 |
| A3 | `GET /api/community/posts?page=1&size=1` | 200，共 3 篇文章 |
| A4 | `GET /api/teacher/list` | 200，含 APPROVED 导师 |
| A5 | `GET /api/asset/list` | 200，description 以 `<!--md:` 前缀承载 Base64 markdown |

**B. 未授权访问防护（功能通过，语义问题）**

| 用例 | 请求 | 预期 | 实际 | 判定 |
| --- | --- | --- | --- | --- |
| B1 | `POST /api/team/list` 等写操作（无 CSRF） | 401 JSON | **403** `无权执行此操作` | 拦截有效，语义偏差 |
| B2 | `GET /api/user/profile` | 401 JSON | 401 `未登录或登录已过期` | 通过 |
| B3 | `GET /profile.html` | 302 登录页 | 302 → `/login.html?redirect=%2Fprofile.html` | 通过 |
| B4 | `GET /admin.html` | 重定向 | 302 | 通过 |

带有效 CSRF token 后，上述 POST 端点正确返回 401 JSON（`未登录或登录已过期`），证明 403 来自 CSRF 层而非认证层。

**C. 登录防护**

- 无 CSRF 的匿名 `POST /api/login`：403（CSRF 层拦截）。
- 带 CSRF 的匿名 `POST /api/login`：控制器可达，错误密码返回 401 `用户名或密码错误`；同 IP 累计 5 次失败后返回 401 `登录尝试次数过多，请 15 分钟后再试`——**锁定机制实测有效**。
- 注册校验：弱密码返回 400 `密码须为 8-32 位，且同时包含大写字母、小写字母和数字`（`POST /api/register` 带 CSRF 验证通过）。

**D. 忘记密码（枚举防护）**

- `POST /api/password-reset/request` 对已存在邮箱与不存在邮箱返回**完全相同**的 200 响应（`如果该账号已绑定邮箱，我们会发送一封密码重置邮件…`），不暴露账号存在性。通过。

**E. 页面路由**：19 个公开页面全部 200；`/login`、`/register` 在 SecurityConfig permitAll 白名单中，匿名可访问。

**F. 文件上传**：未登录 `POST /api/files/upload` 被拒绝（无 CSRF 时 403，带 CSRF 时 401），上传入口均有鉴权。

**G. CSRF 防护**：`POST /api/logout` 不带 `X-XSRF-TOKEN` 被 403 拒绝；Cookie 模式为 `XSRF-TOKEN`（非 HttpOnly，前端可读取）+ `JSESSIONID`（HttpOnly + SameSite=Lax），符合 Spring Security Cookie CSRF 标准实践。

**H. 安全响应头（全部存在）**

- CSP：`default-src 'self'`，script/style 含 `unsafe-inline` 及 jsdelivr/cdnjs 白名单
- `X-Content-Type-Options: nosniff`、`X-Frame-Options: SAMEORIGIN`
- `Permissions-Policy`：camera/mic/geo/payment/usb 全部关闭
- `Referrer-Policy: strict-origin-when-cross-origin`
- 动态页面 `Cache-Control: no-store`
- **缺失** `Strict-Transport-Security`（本地 HTTP 环境可接受，公网部署前必须补）

### 2.3 源码安全审计

| 编号 | 文件 | 严重度 | 发现 |
| --- | --- | --- | --- |
| S1 | SecurityConfig.java:139 | **中** | `csrf.ignoringAntMatchers("/ws/**", "/ws-native/**", "/api/upload/**")` 使 `/api/upload/**` 的 POST 上传请求**完全跳过 CSRF 校验**。带登录态的 cookie 会被跨站请求自动携带，攻击者可伪造同源/宽松站点向上传接口发起请求。建议改为仅忽略 `/ws/**`，上传接口要求自定义头鉴别 |
| S2 | LoginAttemptService.java + AuthController.java:362-372 | **中** | 锁定 key = `IP:账号`，IP 取自 `request.getRemoteAddr()` 不剥代理头：部署在 Nginx/LB 后所有客户端 IP 相同，5 次失败即锁住全部用户（可用性风险）；反向地攻击者换出口 IP 可无限尝试。且 Caffeine 纯内存计数，节点重启清零、多实例部署不共享 |
| S3 | SecurityConfig.java:49 | 低 | `antMatchers(GET, "/api/team/*")` 是路径通配，会把 `GET /api/team/apply` 等也放行到控制器层再拒绝；建议显式列出允许公开的路径 |
| S4 | SecurityConfig.java:116-117 | 低 | CSP `script-src` 含 `unsafe-inline`，XSS 纵深防御实际只剩 HtmlSanitizer 单点。建议后续迁移 nonce 方案 |
| S5 | 全部 | 通过 | 文件上传白名单（按 bizType 分档 + 内容嗅探双校验）、路径规范化（`normalize()` + `startsWith(root)` 二次校验 + `..` 段拒绝）、SQL 注入（MyBatis 全部 `#{}` 占位，无 `${}` 拼接）、HTML 净化（Jsoup 白名单而非黑名单）、密码重置（响应不区分账号存在性、token 只存哈希、30 分钟一次性）——均未发现漏洞 |

---

## 三、问题清单（按优先级）

1. **【中】`/api/upload/**` 豁免 CSRF**（SecurityConfig.java:139）——登录态上传接口可被跨站伪造。
2. **【中】登录锁定依赖裸 `getRemoteAddr()`**——代理部署下双向风险（全量误锁 / 换 IP 绕过）。
3. **【低】匿名 POST 统一 403 而非 401**——CSRF 层对匿名请求抛 `AccessDeniedException`，被 accessDeniedHandler 吞掉。功能无损（都拒绝了），但前端和 API 消费者无法区分"未登录"与"无权限"；且纯 curl 黑盒无法直接测登录接口。修复方向：`accessDeniedHandler` 中区分 `AuthenticationException`（→401）与 `AccessDeniedException`（→403）。
4. **【低】`GET /api/team/*` 通配过宽**（SecurityConfig.java:49）。
5. **【低】CSP `unsafe-inline`** + **缺 HSTS 头**。

## 四、附录

**A. 环境快照**：MySQL 5.7（root/1234，库 ilink，18 账号：10 学生/7 教师/1 管理员），8090 服务运行中，team 表 4 行、team_demand 5 行、competition 55 行、community_post 3 行。工作区有 95 个文件未提交修改（含 6130 行新增），全部通过回归。

**B. 测试副作用**：测试过程中 `testuser2` 账号 + 当前 IP 组合的登录失败计数被触发锁定（15 分钟自动解除，Caffeine 缓存，无数据库写入）。这是开发环境测试账号，不影响功能。

**C. 未覆盖项**：需登录态的功能（团队空间、任务看板、聊天、AI 助手、个人中心编辑）无法黑盒测试——数据库中所有账号密码均未知（BCrypt 哈希），且未找到可用凭据。这些路径由 236 个单元测试覆盖（服务层逻辑全绿）；如需端到端验证登录态功能，需要提供一个已知密码的测试账号。
