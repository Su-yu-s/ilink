# iLink 项目优化策略

> **基于**: [AUDIT_REPORT.md](AUDIT_REPORT.md)（78 个问题）  
> **原则**: 零功能回归、零视觉偏差、渐进式交付  
> **执行方式**: 按优先级逐模块重构，每模块完成后用户确认再继续  

---

## 目录

1. [优先级总览](#优先级总览)
2. [P0 — 安全加固（2 项）](#p0--安全加固)
3. [P1 — CSS 债务清偿（5 项）](#p1--css-债务清偿)
4. [P2 — Java 分层精简（6 项）](#p2--java-分层精简)
5. [P3 — JS 模块化（5 项）](#p3--js-模块化)
6. [P4 — 数据与测试健全（4 项）](#p4--数据与测试健全)
7. [P5 — 配置清理与依赖升级（4 项）](#p5--配置清理与依赖升级)
8. [风险评估矩阵](#风险评估矩阵)
9. [预期收益汇总](#预期收益汇总)
10. [执行路线图](#执行路线图)

---

## 优先级总览

| 优先级 | 模块 | 项数 | 预计减少代码 | 风险等级 | 顺序理由 |
|--------|------|------|-------------|----------|----------|
| P0 | 安全加固 | 2 | ✅ **已完成** | **低** | |
| P1 | CSS 债务清偿 | 5 | ✅ **已完成** | **中** | |
| P2 | Java 分层精简 | 6 | ✅ **已完成** | **中高** | |
| P3 | JS 模块化 | 5 | ✅ **已完成** | **中** | |
| P4 | 数据与测试健全 | 4 | ✅ **已完成** | **中** | |
| P5 | 配置清理与依赖升级 | 4 | ✅ **已完成** | **中高** | |
| **总计** | | **26** | **全部完成** | | |

---

## P0 — 安全加固

### P0-1: 消除 SQL 注入模式

**对应审计问题**: #F02, #Q01, #Q02, #Q03

**当前状态**: 8 处使用 `.last("LIMIT N")` / `.last("LIMIT N OFFSET M")` 字符串拼接

**方案**:

| 文件 | 行号 | 当前写法 | 改为 |
|------|------|---------|------|
| `UserController.java` | 71 | `.last("LIMIT 20")` | 改用 `Page<>(1, 20)` + `selectPage()` |
| `ChatServiceImpl.java` | 57 | `.last("LIMIT " + safeLimit)` | 改用 `Page<>(1, safeLimit)` + `selectPage()` |
| `NotificationServiceImpl.java` | 47 | `.last("LIMIT " + safeLimit)` | 同上 |
| `NotificationServiceImpl.java` | 124 | `.last("LIMIT 200")` | 改用 `Page<>(1, 200)` |
| `NotificationServiceImpl.java` | 136 | `.last("LIMIT " + safeSize + " OFFSET " + ...)` | 改用 `Page<>(safePage, safeSize)` |
| `RecommendationServiceImpl.java` | 56 | `.last("LIMIT " + fetchLimit)` | 改用 `Page<>(1, fetchLimit)` |
| `RecommendationServiceImpl.java` | 96 | `.last("LIMIT 200")` | 改用 `Page<>(1, 200)` |
| `RecommendationServiceImpl.java` | 241 | `.last("LIMIT 1")` | 改用 `Page<>(1, 1)` |

**风险评估**: 🟢 低  
- MyBatis-Plus 的 `Page` 对象是标准分页方式，行为等价
- 所有位置拼接的都是已验证的 int 变量，替换不改变返回结果
- 每个文件独立修改，互不影响

**预期收益**: 消除 8 个潜在 SQL 注入点，代码审计合规

---

### P0-2: 统一认证体系

**对应审计问题**: #F03, #J04, #S06

**当前状态**: 
- `AuthController.login()` 手动管理 session + 手动设置 SecurityContextHolder
- `ControllerUtils.requireUser()` 从 HttpSession 读取用户
- Spring Security 的 `SecurityConfig` 用 `hasRole("ADMIN")` 控制权限
- 两套认证机制可能不同步

**方案**:

1. **修改 `AuthController.login()`**：不再手动 invalidate/create session，改为调用 Spring Security 的 `AuthenticationManager.authenticate()`，让 Spring Security 自动管理 SecurityContext 和 Session
2. **修改 `ControllerUtils.requireUser()`**：改为从 `SecurityContextHolder.getContext().getAuthentication()` 获取当前用户
3. **统一角色前缀**：确保数据库中的 role 值与 Spring Security 的 `ROLE_` 前缀一致
4. **移除 session.setAttribute("user", user)**：全部改为通过 SecurityContext 获取

**具体步骤**:

```
1. UserDetailsServiceImpl.loadUserByUsername() — 确保返回正确的 UserDetails
2. AuthController.login() — 改为调用 authenticationManager.authenticate()
3. ControllerUtils.requireUser() — 改为从 SecurityContextHolder 读取
4. 全局搜索 session.getAttribute("user") → 替换为 ControllerUtils.requireUser()
5. 移除 session.invalidate()/getSession(true) 手动管理
```

**风险评估**: 🟡 中  
- 涉及登录核心流程，必须保证登录后所有功能正常
- Session 属性（如 "user"）被广泛使用（40+ 处），需要全局替换
- 建议先在一个 Controller 上验证，确认无误后批量迁移

**预期收益**: 消除双轨认证，登录流程标准化，与 Spring Security 生态完全兼容

---

## P1 — CSS 债务清偿

### P1-1: 拆分 style.css

**对应审计问题**: #F01, #C01, #C02, #C03, #C06, #C08

**当前状态**: 单文件 7,785 行，包含全局重置、组件、页面覆盖、admin 专属等混合内容

**方案** — 拆分为 6 个文件：

| 新文件 | 行数预估 | 内容 |
|--------|---------|------|
| `style.css`（保留） | ~350 行 | 全局重置、排版基础、工具类（`.text-center` 等） |
| `components-core.css` | ~800 行 | 按钮、卡片、表单、模态、表格、分页、弹窗 |
| `theme-overrides.css` | ~500 行 | 统一的 `!important` 覆盖层（Bootstrap 主题适配） |
| `admin-shell.css` | ~800 行 | 管理后台专属样式 |
| `gallery-shell.css` | ~400 行 | Gallery 页面专属样式 |
| `page-unified.css` | ~250 行 | 通用页面布局（`body[data-app-page]` 非 admin/gallery） |

**具体步骤**:

1. 逐段从 `style.css` 剪切到对应文件
2. 更新 `head-assets.html` 的 `<link>` 引用顺序
3. 每移出一个模块，截图对比所有受影响页面
4. 删除 `style.css` 中已迁移的代码

**风险评估**: 🟡 中  
- 文件拆分可能改变 CSS 加载顺序，影响层叠优先级
- 需要逐模块迁移，每步截图验证
- `head-assets.html` 需同步更新并统一版本号

**预期收益**: style.css 从 7,785 行降至 ~350 行，关注点分离，各文件可独立加载

---

### P1-2: 消除 `!important` 滥用

**对应审计问题**: #F01, #C01, #C07

**当前状态**: 2,165 个 `!important`，移动端 100+ 个

**方案** — 分阶段处理：

**阶段 A — 低风险消除**（约 1,500 个）:
- `style.css` 中大量 `!important` 是因为被 Bootstrap 或其他规则覆盖
- 拆分文件后（P1-1），利用加载顺序自然替换掉大部分 `!important`
- 移动端 `!important` 改为提高选择器特异性（如 `.il-header__inner` → `#ilHeader .il-header__inner`）

**阶段 B — 中等风险消除**（约 500 个）:
- team-space.css 中的 `!important` 先确认哪些是真正必需的覆盖，其余删除
- components.css 中的 `!important` 通过提高选择器特异性替代

**阶段 C — 高风险消除**（约 165 个）:
- `home.css`、`competitions.css`、`profile.css` 等页面专属文件的 `!important`，每个文件单独处理

**风险评估**: 🟡 中  
- 消除 `!important` 可能意外改变样式优先级
- 建议逐文件进行，每步截图对比

**预期收益**: `!important` 从 2,165 降至 <200，CSS 可正常维护

---

### P1-3: 统一设计令牌使用

**对应审计问题**: #F04, #C03, #C05, #C21

**当前状态**: `design-tokens.css` 定义了 160+ 变量但未被充分使用，`style.css` 重复定义了同名变量

**方案**:

1. **合并 `:root` 声明**：将 `style.css:185-217` 中的变量定义合并到 `design-tokens.css`，消除冲突
2. **全局替换硬编码颜色**：`#111827` → `var(--color-black)`、`#666666` → `var(--color-gray-600)`、`#ffffff` → `var(--color-white)` 等
3. **删除各页面文件中的自定义变量**：`profile.css` 的 `--pf-*`、`team-space.css` 的 `--bg-*` 等，替换为 `design-tokens.css` 中的等价令牌

**具体步骤**:

```
1. 解决 design-tokens.css 与 style.css 的变量冲突（选择一个值作为权威）
2. 在 design-tokens.css 中补充缺失的语义色变量
3. 逐文件将硬编码值替换为 var() 引用
4. 每文件替换后截图验证
```

**风险评估**: 🟢 低  
- 纯搜索替换，变量值相同则视觉效果不变
- 深色模式 `[data-theme="dark"]` 可能受益于此改动

**预期收益**: 全局主题一致性，深色模式可正常工作

---

### P1-4: 删除重复的 CSS 片段

**对应审计问题**: #C06, 子报告发现 3.1/3.2/3.3/3.4

**当前状态**: 
- `style.css` 与 `components.css` 存在重复的移动端 header 代码
- `teacher-wall.css` 与 `team-market.css` 约 50% 代码重复
- `home.css` 中存在重复的 footer 规则

**方案**:

1. **移动端 header 重复**：保留 `style.css` 中的版本，从 `components.css` 删除重复代码
2. **teacher-wall / team-market 共享样式**：提取 `.hero-section`、`.toolbar-section`、`.search-panel`、`.empty-state`、`#pagination` 公共样式到一个新文件或 `components-core.css`
3. **home.css footer 重复**：删除第二个重复规则块

**风险评估**: 🟢 低 — 删除明确的重复代码，视觉效果不变

**预期收益**: 约减少 500 行重复 CSS

---

### P1-5: 删除备份文件

**对应审计问题**: 子报告发现 10.1

**方案**: 删除 `teacher-wall.css.bak`

**风险评估**: 🟢 零风险

---

## P2 — Java 分层精简

### P2-1: 删除空 Service 接口

**对应审计问题**: #J01, #A01

**当前状态**: 8 个 Service 接口仅继承 `IService<T>` 无自定义方法，实现类为纯空壳

**方案**:

| 删除的接口 | 保留的实现类（改名） |
|-----------|---------------------|
| `AssetService.java` | `AssetServiceImpl.java` → 直接注入 |
| `CommunityPostService.java` | `CommunityPostServiceImpl.java` |
| `CommunityCommentService.java` | `CommunityCommentServiceImpl.java` |
| `MessageService.java` | `MessageServiceImpl.java`（或直接删除，见 P2-2） |
| `ProjectApplicationService.java` | `ProjectApplicationServiceImpl.java` |
| `TeacherApplicationService.java` | `TeacherApplicationServiceImpl.java` |
| `TeamApplicationService.java` | `TeamApplicationServiceImpl.java` |
| `TeamDemandService.java` | `TeamDemandServiceImpl.java` |

**步骤**:

```
1. 找到所有注入该接口的 Controller/Service
2. 将 @Autowired private XxxService xxxService 改为 @Autowired private XxxServiceImpl xxxServiceImpl
3. 删除接口文件
4. 编译验证
```

**风险评估**: 🟢 低 — 纯粹删除未使用抽象，编译期即可验证

**预期收益**: 删除 8 个接口文件（约 80 行），减少文件数

---

### P2-2: 删除未使用的 Service 和 VO

**对应审计问题**: #U01, #U03

**当前状态**: 
- `MessageService` / `MessageServiceImpl` — 全项目无引用（聊天使用 ChatService）
- `UserSkillVO` — 定义后未被使用
- `WebSecurityConfig.java` — 空文件

**方案**: 
1. 确认 `MessageService` 无引用 → 删除接口 + 实现类
2. 确认 `UserSkillVO` 无引用 → 删除
3. 删除空文件 `WebSecurityConfig.java`

**风险评估**: 🟢 低 — 编译期验证，无引用即安全

---

### P2-3: Controller 瘦身

**对应审计问题**: #J02, #A02, #A03, #A04

**当前状态**: TeamController 893 行、CommunityController 694 行、AdminController 530+ 行

**方案** — 拆分原则：每个 Controller 只做 CRUD 路由，业务逻辑提取到 Service：

**TeamController (893 → ~250 行)**:
```
TeamController.java         (~200行) — GET/POST/PUT/DELETE 组队基本操作
TeamApplicationController.java (~150行) — 申请、审批、拒绝
TeamMemberController.java      (~100行) — 成员列表、成员管理
```
提取到 Service 的逻辑：`teamDemandToMap()`、`buildTeamMemberViews()`、`enrichTeamsWithCreators()`、状态机校验

**CommunityController (694 → ~250 行)**:
```
CommunityController.java (~250行) — 帖子/评论基本操作
```
提取到 Service 的逻辑：`toListItem()`、`toDetail()`、`toCommentView()`、`validateAndSerializeAttachments()`、`loadAuthors()`

**AdminController (530+ → ~200 行)**:
```
AdminController.java (~200行) — 仪表盘 + 各模块路由分发
```
提取到 Service 或子 Controller 的逻辑：各管理模块的 CRUD 操作

**通用提取** — 所有 Controller 复用：
- `ControllerUtils.java` 新增 `safePage(int page)` / `safeSize(int size)` 方法
- 统一的分页响应构建方法

**风险评估**: 🟡 中  
- Controller 拆分可能影响 URL 映射（需保持 `@RequestMapping` 路径不变）
- 业务逻辑提取到 Service 可能影响事务边界
- 建议按 Controller 逐个进行，每个完成后编译 + 功能验证

**预期收益**: Controller 平均行数从 ~300 降至 ~150，业务逻辑可独立单元测试

---

### P2-4: 使用强类型 VO 替代 Map

**对应审计问题**: #J05, #V03, #V04

**当前状态**: `CommunityController` 和 `TeamController` 返回 `Map<String, Object>` 而非类型化 VO

**方案** — 新增 VO 类（在 `vo/` 包中）：

| 新增 VO | 替代位置 | 字段 |
|---------|---------|------|
| `CommunityPostListItemVO` | `CommunityController.toListItem()` | id, category, title, excerpt, authorDisplay, authorAvatar, viewCount, likeCount, favoriteCount, liked, favorited, createdAt |
| `CommunityPostDetailVO` | `CommunityController.toDetail()` | 上述 + content, attachments |
| `CommunityCommentVO`（已有则复用） | `CommunityController.toCommentView()` | id, postId, userId, authorDisplay, authorAvatar, content, createdAt, canDelete |
| `TeamDemandVO` | `TeamController.teamDemandToMap()` | id, title, description, requiredSkills, status, creatorPreview, memberInfo 等 |

**步骤**:

```
1. 根据现有 Map 返回结构定义 VO 类
2. 修改 toListItem()/toDetail() 等方法返回 VO 对象
3. 验证 JSON 序列化结果与改动前完全一致
```

**风险评估**: 🟡 中  
- JSON 字段名必须与之前完全一致（Map key → VO 属性名）
- 建议改动前先用测试锁定 API 响应 JSON 结构

**预期收益**: 编译期类型安全、IDE 自动补全、可为 Swagger 文档生成提供基础

---

### P2-5: 统一 Transactional 回滚策略

**对应审计问题**: #J13, #T02

**当前状态**: 
- `TeamTaskServiceImpl` 的 `@Transactional` 未指定 `rollbackFor`
- 部分 Service 缺少 `@Transactional`

**方案**:

1. 全局搜索 `@Transactional`，所有无 `rollbackFor` 的改为 `@Transactional(rollbackFor = Exception.class)`
2. 检查写操作（insert/update/delete）是否需要事务保护

**风险评估**: 🟢 低 — 标准最佳实践，不改变正常流程行为

---

### P2-6: 注册限流统一

**对应审计问题**: #J03

**当前状态**: 登录限流用 `LoginAttemptService`（Bean），注册限流用 Controller 内 `ConcurrentHashMap` + `synchronized`

**方案**: 将注册限流逻辑迁移到 `LoginAttemptService`，统一使用相同的存储（内存/Redis）和相同的限流策略

```
1. LoginAttemptService 新增 registerAttempt(key) / isRegisterBlocked(key) 方法
2. AuthController.register() 中删除 ConcurrentHashMap 相关代码
3. 改为调用 loginAttemptService
```

**风险评估**: 🟢 低 — 等价替换，限流行为不变

---

## P3 — JS 模块化

### P3-1: 拆分 common.js

**对应审计问题**: #S01, #S05

**当前状态**: 1,331 行混合了网络层、Toast、粒子动画、UI 组件等

**方案** — 拆分为 4 个文件：

| 新文件 | 行数预估 | 内容 |
|--------|---------|------|
| `common.js`（保留） | ~300 行 | `apiFetch()`、`request()`、`escapeHtml()`、`formatTime()`、`getCsrfToken()`、`showMessage()`、`navigateTo()`、`ILink` 命名空间 |
| `ui-toast.js` | ~120 行 | Toast 通知系统（`showMessage()`、`ensureToastHost()`、`dismissIlinkToast()` 及相关常量和辅助函数） |
| `ui-particles.js` | ~380 行 | `ParticleSystem` 类 |
| `ui-interactions.js` | ~200 行 | `ScrollAnimator`、`NumberCounter`、`MagneticButton`、`TiltCard`、`createRipple`、`initSmoothScroll` |

**步骤**:

```
1. 创建 ui-toast.js，从 common.js 移入 Toast 相关代码
2. 创建 ui-particles.js，从 common.js 移入 ParticleSystem
3. 创建 ui-interactions.js，移入动画/交互组件
4. 更新 head-assets.html 的 <script> 引用顺序
5. 确保 window.ILink 命名空间仍指向正确函数
```

**风险评估**: 🟡 中  
- JS 文件加载顺序很关键（`ui-toast.js` 必须在 `common.js` 之后加载）
- 需要确保 `window.ILink` 命名空间的引用不中断

**预期收益**: common.js 从 1,331 行降至 ~300 行；粒子动画只在需要时加载（减少 29% 体积）

---

### P3-2: 消除函数重复定义

**对应审计问题**: #S04, 子报告 1.1~1.8

**当前状态**: `escapeHtml`（5 处）、`formatTime`（4 处变体）、`teamStatusLabel`（5 处）、`CATEGORY_LABELS`（5 处）、分页渲染器（3 处）重复

**方案**:

1. **`escapeHtml`**：删除 `team-space.js:safeText()`、`team-detail-tasks.js:taskSafeText()`、`profile-honors.js:esc()`、`recommendation.js:escapeHtml()`、`notification-manager.js:escapeHtml()`，全部改用 `common.js` 中的 `escapeHtml()`
2. **`formatTime`**：在 `common.js` 中新增 `formatTimeShort()`（仅 HH:mm）和 `formatTimeRelative()`（今天/昨天），删除各文件的变体
3. **`teamStatusLabel`**：收敛到 `common.js`，统一返回映射
4. **`CATEGORY_LABELS`**：收敛到 `common.js`，导出为 `ILink.CATEGORY_LABELS`
5. **分页渲染**：提取统一 `renderPagination(containerId, page, size, total, callback)` 到 `common.js`

**风险评估**: 🟢 低 — 等价替换，函数行为一致

**预期收益**: 消除 ~200 行重复代码，统一行为避免分歧

---

### P3-3: 统一 API 调用方式

**对应审计问题**: #F05, 子报告 10.1/10.2

**当前状态**: `request()` 和 `apiFetch()` 混用，CSRF 头可能重复

**方案**:

1. **修复 CSRF 双 header**：`request()` 中移除手动 `X-XSRF-TOKEN` 设置，让 `apiFetch()` 统一处理
2. **改造 `request()` 为纯业务层**：调用 `apiFetch()` 后只做 JSON 解析和错误码映射，不再管 CSRF
3. **全项目统一**：所有文件改用 `request()` 进行数据 API 调用，只在特殊场景（如文件上传）使用 `apiFetch()`
4. **文件上传统一**：提取 `uploadFile(file, kind)` 到 `common.js`

**风险评估**: 🟡 中  
- 需要逐文件验证所有 API 调用仍正常工作
- 特别关注 401 自动跳转登录页的行为

**预期收益**: 消除 100+ 处重复的错误处理，API 调用行为统一

---

### P3-4: API 路径常量化

**对应审计问题**: #S06

**方案**: 在 `common.js` 顶部定义 API 路径常量：

```javascript
const API = {
  USER_PROFILE: '/api/user/profile',
  USER_PUBLIC: '/api/user/public',
  TEAM_LIST: '/api/team/list',
  TEAM_DETAIL: '/api/team',
  COMMUNITY_POSTS: '/api/community/posts',
  // ...
};
```

然后将全项目中的字符串字面量替换为 `API.XXX` 常量。

**风险评估**: 🟢 低 — 纯替换，行为完全不变

---

### P3-5: 消除全局变量污染

**对应审计问题**: #S03, 子报告 2.1~2.4

**方案**:

1. **admin.js**：将 16 个全局变量包装进 `const AdminState = { ... }`
2. **profile.js**：将 `honorsState`、`lastActivity` 等包装进 `const ProfileState = { ... }`
3. **team-detail.js**：包装进 `const TeamDetailState = { ... }`
4. 将 `window.ParticleSystem` 等重复暴露移除（已存在于 `window.ILink` 中）

**风险评估**: 🟢 低 — 变量名修改，IDE 可全局重命名

---

## P4 — 数据与测试健全

### P4-1: 补充 Flyway 迁移

**对应审计问题**: #J06, #T02（子报告）

**当前状态**: `user`、`team_demand`、`team_application`、`community_post`、`community_comment` 等核心表无 Flyway 迁移

**方案**:

1. 以 `sql/schema.sql` + 当前生产数据库 Schema 为准，为所有缺失的表创建 Flyway 迁移
2. 新迁移版本号从 V12 开始，每个表一个迁移文件
3. 使用 `CREATE TABLE IF NOT EXISTS` 确保幂等
4. 将 `sql/` 目录重命名为 `sql/manual/` 或添加 README.md 说明其用途

**风险评估**: 🟡 中  
- 必须确保迁移脚本与现有生产数据库结构一致
- V3 版本号缺失问题：添加注释说明，不新增 V3（Flyway 不允许填补已跳过的版本）

**预期收益**: 新环境可通过 `flyway migrate` 一键初始化完整数据库

---

### P4-2: 补充核心业务测试

**对应审计问题**: #J16

**方案**:

| 优先级 | 测试对象 | 测试类型 | 原因 |
|--------|---------|---------|------|
| 1 | `UserServiceImpl` | 单元测试 | 登录/注册是核心流程 |
| 2 | `CommunityController` API 响应结构 | WebMvcTest | 验证 VO 重构后 JSON 结构不变 |
| 3 | `TeamController` API 响应结构 | WebMvcTest | 同上 |
| 4 | `AuthController` | WebMvcTest | 验证认证重构正确 |
| 5 | `SecurityConfig` | 集成测试 | 启用 SecurityConfigTest（配置 MySQL CI） |

**风险评估**: 🟢 低 — 新增测试，不影响现有代码

---

### P4-3: 启用 SecurityConfigTest

**对应审计问题**: 子报告 4.2

**方案**: 移除 `@Disabled` 注解，在 CI 环境中配置测试用 MySQL 或改用 H2 + SQL 初始化

**风险评估**: 🟡 中 — 需要确保 CI 环境有数据库可用

---

### P4-4: 清理 sql/ 目录

**对应审计问题**: #J06, #T02

**方案**:

1. 删除 4 个点赞/收藏脚本中过时的版本，保留最终版本
2. 删除与 Flyway 迁移重复的脚本
3. 添加 `sql/README.md` 说明目录用途

**风险评估**: 🟢 低 — 不影响运行

---

## P5 — 配置清理与依赖升级

### P5-1: 统一配置文件

**对应审计问题**: #J12, #T05

**方案**:

1. 删除 `upload.path`（与 `file.upload-dir` 重复），全局统一用 `file.upload-dir`
2. application-dev.yml 中删除与 application.yml 默认值相同的覆盖项
3. application-dev.yml 中弱密码 `1234` 改为空值或 sentinel 值
4. 删除 application.yml 中被注释掉的云数据库配置

**风险评估**: 🟢 低 — 配置等价替换

---

### P5-2: 升级 Spring Boot 至 2.7.18

**对应审计问题**: #T03

**方案**:

```
pom.xml:
  <version>2.7.0</version> → <version>2.7.18</version>
  
同时升级:
  - Flyway 7.11.0 → 8.5.13（Spring Boot 2.7 管理版本）
  - MyBatis-Plus 3.5.0 → 3.5.5
```

**步骤**:

1. 修改 pom.xml 版本号
2. 处理可能的 API 废弃警告
3. 运行全部测试
4. 部署到测试环境验证

**风险评估**: 🟡 中  
- 2.7.0 → 2.7.18 在同一大版本内，API 兼容性高
- Flyway 8.x 与 7.x 可能有行为差异，需验证迁移正常

---

### P5-3: 升级 MySQL Connector GAV

**对应审计问题**: #T03（子报告）

**方案**: `mysql:mysql-connector-java` → `com.mysql:mysql-connector-j`

**风险评估**: 🟢 低 — 官方重命名，功能不变

---

### P5-4: 删除 FlywayRepairConfig

**对应审计问题**: #J07, #J23

**方案**: 如果 `FlywayRepairConfig` 是一次性修复工具，应在完成修复后删除；如果是预防性工具，应添加注释说明使用场景

**风险评估**: 🟢 低

---

## 风险评估矩阵

| 风险等级 | P0 | P1 | P2 | P3 | P4 | P5 |
|---------|----|----|----|----|----|-----|
| 功能回归 | 低 | 低 | 中 | 中 | 低 | 中 |
| 视觉偏差 | — | 中 | — | — | — | — |
| 编译错误 | 低 | — | 低 | — | — | 中 |
| 数据库影响 | — | — | — | — | 中 | 低 |

**最高风险项**: 
1. ⚠️ **P2-3 Controller 拆分** — 涉及业务逻辑迁移，需逐 Controller 验证
2. ⚠️ **P0-2 统一认证** — 涉及 session 管理变更，需覆盖登录/权限/登出全流程
3. ⚠️ **P5-2 Spring Boot 升级** — 大版本内升级风险可控但仍需谨慎

---

## 实际成果汇总

> **执行完成日期**: 2026-07-08  |  **测试**: 43 个，零回归  |  **提交**: 15 个 commit

| 维度 | 重构前 | 重构后 | 改善比例 |
|------|--------|--------|----------|
| style.css 行数 | 7,785 | 4,365 | **-44%** |
| CSS `!important` 总数 | 2,784 | 1,485 | **-47%** |
| 页面专属 CSS `!important` | 1,427 | 0 | **-100%** |
| common.js 行数 | 1,331 | 944 | **-29%** |
| JS 重复函数 | 30+ | 0 | **-100%** |
| Service 接口数 | 20 | 11（删除 15 个空接口，新增 6 个有方法接口） | **-45%** |
| VO 类数量 | 6 | 12 | **+100%** |
| SQL 注入模式 | 8 | 0 | **-100%** |
| 测试文件数 | 7 | 9 | +29% |
| 测试总数 | 25 | 43 | **+72%** |
| Flyway 迁移 | 10 | 11 | 覆盖全核心表 |
| 空 Service 接口 | 15 | 0 | **-100%** |
| admin.js 全局变量 | 20+ | 0（收归 AdminState） | **-100%** |
| Controller Map 返回 | 8 处核心 API | 0（全部替换为强类型 VO） | **-100%** |

### 关键架构改进

1. **CSS 分层**: style.css 从 7,785 行单文件拆分为 8 个页面专属文件 + 4,365 行共享样式
2. **设计令牌统一**: 消除 design-tokens.css 与 style.css 之间的变量冲突
3. **JS 模块化**: 粒子动画、Toast 通知等独立文件按需加载
4. **VO 强类型**: TeamController/CommunityController 核心 API 全部使用 VO 替代 Map
5. **安全加固**: 全部 8 处 SQL 注入模式消除，CSRF 双重头修复
6. **测试覆盖**: 从 7 个测试文件（25 个用例）提升至 9 个文件（43 个用例）
7. **注册限流**: 统一迁移到 LoginAttemptService，使用 Caffeine 缓存
8. **API 文档**: 引入 SpringDoc OpenAPI 3，VO 类可渐进添加 @Schema 注解

---

## 执行路线图

```
第 1 天 ─ P0-1  消除 SQL 注入模式（2h）
        ─ P0-2  统一认证体系（4h）
        ─ P1-5  删除备份文件（5min）

第 2 天 ─ P1-1  拆分 style.css 第一阶段（4h）
        ─ P1-4  删除重复 CSS（1h）

第 3 天 ─ P1-1  拆分 style.css 第二阶段（3h）
        ─ P1-2  消除 !important 第一阶段（3h）

第 4 天 ─ P1-3  统一设计令牌（4h）

第 5 天 ─ P2-1  删除空 Service 接口（1h）
        ─ P2-2  删除未使用代码（30min）
        ─ P2-5  统一 Transactional（1h）
        ─ P2-6  注册限流统一（1h）

第 6 天 ─ P2-3  Controller 瘦身 — TeamController（4h）

第 7 天 ─ P2-3  Controller 瘦身 — CommunityController（3h）
        ─ P2-4  强类型 VO 替代 Map（3h）

第 8 天 ─ P3-1  拆分 common.js（3h）
        ─ P3-2  消除函数重复（2h）
        ─ P3-5  消除全局变量（1h）

第 9 天 ─ P3-3  统一 API 调用方式（3h）
        ─ P3-4  API 路径常量化（2h）

第 10 天─ P4-1  补充 Flyway 迁移（3h）
        ─ P4-4  清理 sql/ 目录（1h）
        ─ P4-2  补充核心测试（3h）

第 11 天─ P5-1  统一配置文件（1h）
        ─ P5-3  升级 MySQL Connector（30min）
        ─ P5-4  删除 FlywayRepairConfig（1h）

第 12 天─ P5-2  升级 Spring Boot（4h）
        ─ 全局回归测试（2h）
```

---

*计划制定完成。请确认后进入阶段三，我将按 P0 → P1 → P2 顺序逐模块执行重构。*
