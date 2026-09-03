# iLink — 高校师生竞赛组队与协作平台

iLink 面向高校学生、教师和平台管理员，串联“找队伍、找导师、找赛事、做协作、沉淀成果”的完整路径。公开门户负责信息发现，登录后的团队空间负责真实协作。

当前项目采用 Spring Boot 2.7、Thymeleaf 和原生 JavaScript 构建，默认运行端口为 `8090`。

## 产品角色

| 角色 | 主要能力 |
| --- | --- |
| 学生 `STUDENT` | 浏览并申请队伍、寻找导师、参与团队任务与沟通、发布文章和成果 |
| 教师 `TEACHER` | 注册后自动拥有导师身份和可编辑档案；资料完整后自动公开，能够处理学生合作申请并参与团队协作 |
| 管理员 `ADMIN` | 管理用户、角色、队伍、导师、成果和社区内容；管理员账号不能通过公开注册创建 |

登录支持手机号、学号/工号、用户名和邮箱。公开注册只允许选择学生或教师身份，且必须提供手机号或学号/工号。密码要求为 8–32 位，同时包含大写字母、小写字母和数字。

## 核心功能

### 公开发现

- 首页：保留诗句、竞赛叙事、区块顺序和桌面动效，是全站视觉中的明确例外。
- 组队大厅：搜索、分类和状态筛选；查看招募详情与成员信息。
- 导师招贤：只展示 `APPROVED` 导师；学生可提交合作申请，教师进入“我的导师主页”。
- 交流社区：综合、技术、竞赛、资源四类内容；支持文章、点赞、收藏和评论。
- 竞赛目录：由后端竞赛实体和公开分页 API 驱动，支持搜索与分类；管理员可在后台增删改。
- 成果展示：公开浏览和检索成果，登录用户可发布、编辑和下载。

### 组队与协作

- 团队状态：`OPEN`（招募中）→ `TEAMING`（已组队）→ `CLOSED`（已结束）。
- 申请流程：申请人只能加入招募中的队伍；队长审批，批准人数达到目标后自动进入 `TEAMING`。
- 团队空间：仅创建者和已批准成员可以访问团队数据。
- 任务看板：`PENDING`、`IN_PROGRESS`、`REVIEW`、`COMPLETED`，另保留 `CANCELLED` 状态。
- 任务闭环：队长创建和分配任务，成员提交后进入审核，队长通过或退回。
- 里程碑：记录截止时间、交付物与完成率，并派生待开始、进行中、已完成、已延期状态。
- 团队聊天：STOMP/WebSocket 实时推送，REST 历史记录和发送接口作为降级路径。
- 在线状态：按 WebSocket 连接计数维护实时在线状态，并持久化最近活跃时间；聊天明确不提供已读回执。
- 通知中心：数据库持久化、未读计数缓存和用户级 WebSocket 推送。

### AI 协作

- 竞赛答疑：团队空间内嵌 AI 助手，接入 Bing 搜索增强回答准确性，支持流式渲染；仅发送问题与公开竞赛信息，不泄露聊天记录。
- 任务拆解：AI 将团队任务拆为子任务建议，用户确认后才落库，token 用量计入配额审计。
- 用量配额：每日调用上限由 `AiQuotaService` 控制，失败时静默降级不影响团队空间正常功能。
- 周报聚合：按任务看板数据生成周报文本，纯本地聚合不调用外部 AI。
- 隐私边界：群聊内容与成员个人信息绝不外发；AI 助手仅可访问用户明确预览过的任务字段与竞赛公开信息。

### 内容与个人中心

- 社区文章提交前通过 Jsoup 清理 HTML；作者或管理员可以编辑、删除。
- 点赞、收藏和各类申请由数据库唯一约束防止重复。
- 学生资料展示学校、学院、专业和年级。
- 教师资料将 `user.school/college/major` 分别解释为任职单位、院系/部门、专业领域，并隐藏年级；职称、研究方向、导师简介和代表项目存放在导师档案中。
- 用户可以维护技能、荣誉、文章、收藏、成果、头像和密码。

## 主要页面

| 场景 | 页面 |
| --- | --- |
| 门户 | `/index.html`、`/team-market.html`、`/teacher-wall.html`、`/community.html`、`/competitions.html`、`/gallery.html` |
| 详情与发布 | `/team-detail.html`、`/team-publish.html`、`/teacher-detail.html`、`/community/article/{id}`、`/asset-detail.html` |
| 团队协作 | `/team-space.html`、`/team-chat.html`、`/team-workspace.html` |
| 个人中心 | `/profile.html`、`/profile-edit.html`、`/profile-honors.html`、`/profile-posts.html`、`/profile-favorites.html`、`/profile-password.html` |
| 认证与管理 | `/login`、`/register`、`/forgot-password.html`、`/admin.html` |

`/home.html` 会跳转到个人中心，`/chat.html` 为历史兼容入口。忘记密码页已接入限流、账号枚举防护、30 分钟一次性令牌和邮件发送；本地未配置 SMTP 时只在开发日志中输出重置链接。

## API 概览

所有 JSON 接口使用 `Result<T>` 统一包装：

```json
{
  "code": 200,
  "message": "获取成功",
  "data": {},
  "extra": {},
  "timestamp": 0
}
```

分页信息位于 `extra.pagination`。浏览器请求通过 Session 识别用户；变更类 AJAX 请求由 `common.js` 自动携带 `X-XSRF-TOKEN` 和同源凭证。

| 接口组 | 代表端点 | 访问规则 |
| --- | --- | --- |
| 认证 | `POST /api/login`、`POST /api/register`、`GET/POST /api/logout`、`/api/password-reset/**` | 登录注册和找回密码公开；支持轮换式“记住我”令牌与安全站内回跳 |
| 用户 | `GET/POST /api/user/profile`、`PUT /api/user/password`、`GET /api/user/public/{userId}` | 私有资料需登录；公开资料可匿名读取 |
| 技能 | `/api/user/skills`、`/api/user/skills/public/{userId}` | 本人维护，公开列表可匿名读取 |
| 队伍 | `/api/team/**` | 列表与详情公开；发布、申请、审批和“我的队伍”需登录 |
| 团队空间 | `/api/team-space/{teamId}/**` | 仅团队参与者 |
| 任务 | `/api/tasks/**`、`/api/team/{teamId}/tasks` | 团队成员；队长拥有管理和审核权限 |
| 里程碑 | `/api/team/{teamId}/milestones`、`/api/milestones/**` | 团队成员 |
| 聊天 | `GET/POST /api/team/{teamId}/messages` | 团队成员 |
| 导师 | `/api/teacher/**` | 列表与详情公开；档案维护和项目申请需登录并校验身份 |
| 社区 | `/api/community/**` | 文章列表与详情公开；写入操作需登录 |
| 成果 | `/api/asset/**` | 列表与详情公开；发布、编辑和下载需登录 |
| 竞赛 | `GET /api/competitions`、`/api/admin/competitions/**` | 公开分页查询；管理员维护目录 |
| 推荐 | `/api/recommendations/**` | 登录用户；组队大厅和团队空间已接入，失败时回退普通列表 |
| AI 协作 | `POST /api/team/{teamId}/ai/task-breakdown`、`POST /api/ai/competition-qa`、`GET /api/team/{teamId}/weekly-report` | 团队成员；受配额限制 |
| 通知 | `/api/notifications/**` | 仅当前用户自己的通知 |
| 管理 | `/api/admin/**` | 仅 `ADMIN` |
| 文件 | `POST /api/files/upload`、`POST /api/upload/attachment` | 需登录 |

WebSocket 入口为 `/ws`（SockJS）和 `/ws-native`。聊天发送目的地为 `/app/chat/{teamId}`，订阅主题为 `/topic/team/{teamId}`；拦截器会校验登录状态和团队成员资格。通知使用当前用户自己的消息主题。

## 权限与安全

- Spring Security + BCrypt；登录成功后同时维护 SecurityContext 和 Session 用户。
- 同一登录键连续失败 5 次后锁定 15 分钟；成功登录会清除失败计数。
- “记住我”使用 selector/validator 分离的持久令牌，Cookie 为 HttpOnly、SameSite=Lax，使用后轮换，退出或改密后撤销。
- 密码重置响应不暴露账号是否存在，令牌只存哈希、30 分钟过期且只能使用一次。
- CSRF Cookie Token 用于常规写接口；WebSocket 握手、连接、发送和订阅另做鉴权。
- 管理页面和 `/api/admin/**` 要求 `ADMIN`，控制器同时执行会话角色校验。
- 安全响应头包含 CSP、Permissions-Policy、Referrer-Policy 和 nosniff；未声明路由默认要求认证，API 未登录统一返回 JSON 401。
- 社区富文本使用 Jsoup 白名单净化。
- 上传文件使用 UUID 命名和规范化路径；`/api/files/upload` 会检查扩展名、大小和文件头签名。
- 管理员的删除、角色变更、导师审核和竞赛维护记录到 `admin_audit_log`。
- `prod` profile 启动时强制校验非 root 数据库账号、非默认密码、HTTPS 公网地址、SMTP、绝对上传目录和 Secure Cookie；仅公开 Actuator 的 `health` 与 `info`。

## 文件上传

`POST /api/files/upload` 接受 `bizType`：

| 业务类型 | 格式 | 单文件上限 |
| --- | --- | --- |
| `avatars` | jpg、jpeg、png、gif、webp | 1 MB |
| `certificates` | jpg、jpeg、png、pdf | 2 MB |
| `images` | jpg、jpeg、png、gif、webp | 2 MB |

`POST /api/upload/attachment` 服务于头像、证明、社区和任务附件，按 `kind` 使用对应扩展名白名单。Spring 全局限制为单文件 20 MB、单请求 50 MB。上传根目录和访问前缀分别由 `FILE_UPLOAD_DIR`、`FILE_ACCESS_URL_PREFIX` 配置，并通过 `/uploads/**` 访问。

## 视觉与交互规范

全站以克制的冷灰中性色为基础：主文字 `#282A2F`，页面背景 `#F7F7F8`，表面 `#FCFCFD`。绿色 `#16A34A` 只表达成功、招募中等正向状态，红色 `#DC2626` 表达错误或危险操作，已组队等稳定状态使用灰色。

- 正文采用 Microsoft YaHei / PingFang SC 系统字体栈，首页诗句可使用中文衬线字体。
- 公共组件通过 `design-tokens.css`、`layout.css`、`components.css`、`effects.css` 和 `search-bar.css` 统一。
- 卡片依靠边框、留白和轻阴影建立层次，避免普遍使用玻璃拟态、渐变和高饱和装饰。
- 搜索输入、筛选和操作按钮使用统一布局；无业务价值的重置按钮不再展示。
- Toast、表单错误、Modal、状态标签和空状态使用统一语义与反馈层级。
- 响应式基线为桌面 `>1024px`、平板 `768–1024px`、移动 `<768px`、小屏 `<480px`。
- 共享移动导航支持焦点管理、遮罩关闭和 Escape；触摸目标至少 44px。
- 首页是受保护的视觉例外：诗句、区块顺序、粒子与叙事动画保留，同时支持 `prefers-reduced-motion`。

更完整的产品边界见 [PRODUCT.md](PRODUCT.md)，已完成的角色与响应式改造记录见 [docs/superpowers/specs](docs/superpowers/specs)。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 2.7.18、Spring MVC、Spring Security 5.7 |
| 数据 | MySQL 8、MyBatis-Plus 3.5.5、Flyway 7.11、Caffeine |
| 模板与前端 | Thymeleaf 3、HTML5、CSS3、原生 JavaScript、Bootstrap 5、GSAP 3 |
| 实时通信 | Spring WebSocket、STOMP、SockJS |
| 内容与接口 | Jsoup 1.17.2、SpringDoc OpenAPI 1.7.0 |
| 测试 | JUnit 5、Mockito、Spring Security Test、H2 |

当前仓库包含 164 个 Java 源文件、39 个运行模板、28 个 CSS 文件和 33 个 JavaScript 文件。当前全量测试为 27 个测试套件、113 个测试，另有 Playwright 发布回归脚本。

## 本地运行

### 环境要求

- JDK 17+
- Maven 3.9+
- MySQL 5.7+ 或 8.0+

### 1. 创建数据库

```sql
CREATE DATABASE ilink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. 配置环境变量

至少配置数据库密码；生产环境不要使用仓库中的开发默认值。

```powershell
$env:DB_HOST = "localhost"
$env:DB_PORT = "3306"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your_password"
$env:FILE_UPLOAD_DIR = "E:\data\ilink\uploads"
```

通用数据源变量为 `DB_HOST`、`DB_PORT`、`DB_USERNAME`、`DB_PASSWORD`；开发 profile 也兼容优先级更高的 `SPRING_DATASOURCE_PASSWORD`。默认 profile 为 `dev`。

### 3. 启动

```bash
mvn spring-boot:run
```

访问 [http://localhost:8090](http://localhost:8090)。启动时 Flyway 会按 `src/main/resources/db/migration/` 自动迁移数据库。

### 4. 测试与打包

```bash
mvn test
mvn clean package
java -jar target/iLink-1.0.jar
```

测试默认启用 `test` profile 和 H2。生产运行必须显式设置 `SPRING_PROFILES_ACTIVE=prod`、最小权限数据库账号、SMTP、`APP_PUBLIC_BASE_URL`、绝对上传目录和安全 Cookie；缺项会拒绝启动。

## 数据库迁移

`src/main/resources/db/migration/` 是数据库结构的唯一事实来源。当前包含 25 个 SQL 迁移（`V0_5` 至 `V24`，版本号 `V3` 由 Java 迁移占用）和 1 个 Java 迁移，共 26 个迁移。迁移链已在现有 MySQL 5.7 数据库升级和全新空库安装两种路径验证。

`sql/` 中的脚本仅用于历史参考、人工修复或演示数据，不会被应用启动时的 Flyway 自动执行。使用前请阅读 [sql/README.md](sql/README.md)，并先确认目标库的 `flyway_schema_history`。

## 项目结构

```text
src/
├── main/
│   ├── java/cn/ilink/
│   │   ├── config/       # Security、WebSocket、缓存、MVC、Flyway
│   │   ├── controller/   # 页面路由与 REST 接口
│   │   ├── service/      # 业务服务及实现
│   │   ├── mapper/       # MyBatis-Plus Mapper
│   │   ├── entity/       # 持久化实体
│   │   ├── dto/          # 请求 DTO
│   │   ├── vo/           # 响应 VO
│   │   ├── security/     # 登录和 WebSocket 安全组件
│   │   ├── service/ai/   # AI 协作（AiAssistantService、AiQuotaService、WebSearchService）
│   │   └── util/         # 密码、HTML、缓存、用户预览工具
│   └── resources/
│       ├── db/migration/ # Flyway 版本化迁移
│       ├── static/       # CSS、JavaScript、图片和前端库
│       ├── templates/    # Thymeleaf 页面与共享 fragments
│       └── application*.yml
└── test/java/cn/ilink/   # 单元、控制器、安全、路径契约测试
```

## 当前边界

- 聊天按产品决定不提供消息已读状态或已读回执，也不提供成员在线/离线状态，这不是待实现功能。
- 竞赛目录由数据库维护，但不会自动同步各主办方官网；日期和链接由管理员负责更新。
- 密码找回依赖可用 SMTP；开发环境可回退到日志链接，生产环境缺少邮件配置会拒绝启动。
- 5 个来源不明确的历史疑似测试账号只进入只读审计报告，未自动删除；仓库明确生成的演示账号已经清理。
- 仓库仍保留少量历史页面入口和 `sql/` 手工脚本；新增功能应以当前 Controller、模板和 Flyway 迁移为准。

## 许可证

Apache License 2.0
