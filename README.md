# iLink — 高校师生竞赛组队与协作平台

iLink 面向高校学生、教师与平台管理员，覆盖从发现赛事和伙伴、组建队伍，到团队协作、成果沉淀与展示的完整流程。项目以服务端渲染的 Web 应用为主，提供清晰的角色权限、实时沟通和可选的 AI 协作能力。

> 适合用于高校创新创业、学科竞赛和项目制学习场景。平台不自动抓取赛事官网；赛事目录及其日期、链接由管理员维护。

## 功能概览

### 公开门户与内容发现

- **组队大厅**：按关键词、类别和状态浏览队伍；学生可申请加入，队长审核后完成组队。
- **导师招贤**：展示已审核且资料完整的教师档案；学生可提交合作/项目申请，教师可处理申请。
- **竞赛目录**：公开分页检索竞赛信息，管理员可在后台维护目录。
- **交流社区**：综合、技术、竞赛、资源等内容交流，支持发布、编辑、评论、点赞、收藏与个人文章管理。
- **成果展示**：公开浏览项目成果；登录用户可以发布、维护、下载和展示自己的作品。
- **公开个人主页**：展示可公开的个人信息、技能、荣誉、文章和成果。

### 团队全流程协作

- **队伍生命周期**：`OPEN`（招募中）→ `TEAMING`（已组队）→ `CLOSED`（已结束）；审核通过的人数达到目标时自动转入协作状态。
- **成员治理**：队长可以邀请成员、审批申请、移除成员、转让创建者，以及解散团队（软删除并保留协作历史）。
- **团队空间**：只有创建者和已批准成员能查看团队资料、成员、概览和统计数据。
- **任务看板**：任务支持 `PENDING`、`IN_PROGRESS`、`REVIEW`、`COMPLETED` 与 `CANCELLED` 状态；包含创建、指派、参与人、评论、提交与审核闭环。
- **里程碑管理**：记录截止时间、交付物和完成率，并根据时间与进度呈现待开始、进行中、已完成或已延期状态。
- **实时沟通与通知**：团队聊天使用 STOMP/WebSocket 推送，REST 接口提供历史记录和发送能力；通知持久化到数据库，并支持未读计数和用户级实时推送。

### AI 协作（可选配置）

- **竞赛答疑**：可使用公开网页搜索补充上下文，支持普通响应与 SSE 流式响应。
- **任务拆解**：根据团队任务生成子任务建议，用户确认后才会保存到数据库。
- **周报聚合**：根据任务看板数据生成团队周报，不依赖外部 AI 调用。
- **用量留痕**：AI 请求次数和 token 使用情况会记录，便于审计。

AI 功能仅在配置有效的 OpenAI 兼容服务凭据后可用。答疑只使用用户问题和公开竞赛信息；团队聊天与成员个人信息不作为外发上下文。

### 账号与管理

| 角色 | 主要权限 |
| --- | --- |
| `STUDENT` | 注册、完善资料、找队伍/导师、参与团队协作、发布社区内容与成果 |
| `TEACHER` | 拥有导师档案，处理学生合作申请，参与团队协作 |
| `ADMIN` | 管理用户、角色、队伍、教师、成果、社区内容和竞赛目录，并记录管理审计日志 |

公开注册只允许学生和教师身份，管理员需由平台侧授予。登录支持手机号、学号/工号、用户名或邮箱；密码要求为 8–32 位，且同时包含大写字母、小写字母和数字。

## 技术架构

| 层级 | 采用技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 2.7.18、Spring MVC、Spring Security |
| 数据访问 | MySQL、MyBatis-Plus 3.5.5、Flyway 7.11 |
| 页面与交互 | Thymeleaf、HTML5、CSS3、原生 JavaScript、Bootstrap 5、GSAP 3 |
| 实时能力 | Spring WebSocket、STOMP、SockJS |
| 缓存与内容 | Caffeine、Jsoup |
| 接口与运维 | SpringDoc OpenAPI、Spring Boot Actuator |
| 测试 | JUnit 5、Mockito、Spring Security Test、H2 |

应用采用经典分层：Controller 处理页面与 HTTP 接口，Service 承担业务规则，Mapper 通过 MyBatis-Plus 访问数据库；Thymeleaf 模板位于服务端，静态样式和脚本由浏览器加载。数据库结构只通过 Flyway 迁移演进。

## 快速开始

### 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本
- MySQL 5.7 或 MySQL 8

### 1. 创建空数据库

```sql
CREATE DATABASE ilink
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

不要先执行 `sql/schema.sql`。应用启动后会由 Flyway 自动执行 `src/main/resources/db/migration/` 中的迁移。

### 2. 配置本地环境

开发环境默认启用 `dev` profile，服务端口默认为 `8090`。至少应提供数据库密码：

```powershell
$env:DB_HOST = "localhost"
$env:DB_PORT = "3306"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your_database_password"
$env:FILE_UPLOAD_DIR = "E:\data\ilink\uploads"
```

也可以复制本地私有配置模板，并只在本机填写 AI 或反馈推送的密钥：

```powershell
Copy-Item src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

`application-local.yml` 已被 Git 忽略，请勿把真实密钥提交到仓库。

### 3. 启动与访问

```bash
mvn spring-boot:run
```

启动完成后访问 [http://localhost:8090](http://localhost:8090)。

### 4. 测试与打包

```bash
mvn test
mvn clean package
java -jar target/iLink-1.0.jar
```

测试自动使用 `test` profile 和 H2 内存数据库，不依赖本地 MySQL。

## 配置说明

常用配置均可通过环境变量注入：

| 变量 | 用途 | 默认/说明 |
| --- | --- | --- |
| `SERVER_PORT` | HTTP 端口 | `8090` |
| `SPRING_PROFILES_ACTIVE` | Spring Profile | `dev` |
| `DB_HOST`、`DB_PORT`、`DB_USERNAME`、`DB_PASSWORD` | MySQL 连接 | 开发时 `DB_PASSWORD` 也可由 `SPRING_DATASOURCE_PASSWORD` 覆盖 |
| `FILE_UPLOAD_DIR` | 上传文件根目录 | 默认 `./uploads` |
| `FILE_ACCESS_URL_PREFIX` | 上传文件 URL 前缀 | 默认 `/uploads/` |
| `APP_PUBLIC_BASE_URL` | 应用对外地址 | 密码重置和反馈详情链接使用；生产必须是 HTTPS |
| `AGNES_API_KEY`、`AGNES_BASE_URL`、`AGNES_MODEL` | OpenAI 兼容 AI 服务 | 未配置密钥时 AI 服务不可用 |
| `AI_ENABLED`、`AI_SEARCH_ENABLED` | AI 与搜索增强开关 | 默认开启 |
| `SERVERCHAN_SENDKEY` | Server 酱反馈推送 | 不配置时反馈仍会保存，只是不推送 |
| `SESSION_COOKIE_SECURE` | 会话 Cookie 的 Secure 属性 | 生产应为 `true` |

生产环境请显式使用 `prod` profile。应用会在启动时校验：非 root 的数据库账号、非默认数据库密码、HTTPS 的公开地址、SMTP、合法发件地址、绝对上传目录和 Secure 会话 Cookie；任一项缺失会拒绝启动。

## 接口与页面

所有 JSON 接口使用 `Result<T>` 统一包装，分页元数据位于 `extra.pagination`：

```json
{
  "code": 200,
  "message": "获取成功",
  "data": {},
  "extra": {},
  "timestamp": 0
}
```

| 接口域 | 路径前缀 | 说明 |
| --- | --- | --- |
| 认证 | `/api/login`、`/api/register`、`/api/logout`、`/api/password-reset/**` | 登录、注册、退出和密码找回 |
| 用户与技能 | `/api/user/**`、`/api/user/skills/**` | 资料、密码、公开主页、内置头像与技能 |
| 组队与成员 | `/api/team/**` | 队伍发布、申请、审批、邀请、成员管理、转让与解散 |
| 团队协作 | `/api/team-space/**`、`/api/tasks/**`、`/api/milestones/**` | 团队概览、任务、提交、评论和里程碑 |
| 聊天与通知 | `/api/team/{teamId}/messages`、`/api/notifications/**` | 聊天历史/发送及通知中心 |
| 社区与成果 | `/api/community/**`、`/api/asset/**` | 文章、互动、评论、成果和下载 |
| 导师、竞赛、推荐 | `/api/teacher/**`、`/api/competitions`、`/api/recommendations/**` | 导师、赛事目录与匹配推荐 |
| AI | `/api/team/{teamId}/ai/task-breakdown`、`/api/ai/competition-qa`、`/api/ai/competition-qa/stream` | 任务建议、普通答疑与 SSE 流式答疑 |
| 反馈与管理 | `/api/feedback`、`/api/admin/**` | 全站反馈、后台治理与审计 |

公开页面包括首页、组队大厅、导师招贤、社区、竞赛目录、成果展示及详情页；个人中心、发布页和后台会依据登录状态及角色进行访问控制。典型入口为：

```text
/index.html             首页
/team-market.html       组队大厅
/teacher-wall.html      导师招贤
/community.html         交流社区
/competitions.html      竞赛目录
/gallery.html           成果展示
/team-space.html        团队空间
/profile.html           个人中心
/admin.html             管理后台
```

WebSocket 端点为 `/ws`（SockJS）和 `/ws-native`；团队聊天发送到 `/app/chat/{teamId}`，订阅 `/topic/team/{teamId}`。入站连接与订阅会检查登录状态及团队成员资格。

## 安全与数据处理

- 使用 Spring Security、BCrypt、Session 认证和会话固定攻击防护。
- 变更类 AJAX 请求使用 Cookie CSRF Token（`X-XSRF-TOKEN`）；前端公共脚本会自动携带同源凭证与请求头。
- 支持持久化“记住我”令牌：selector/validator 分离、使用后轮换，退出或改密后撤销。
- 连续登录失败达到阈值后会临时锁定；密码找回不泄露账号是否存在，令牌仅保存哈希且一次性有效。
- 社区富文本经 Jsoup 白名单净化；上传使用扩展名、大小、文件签名与路径规范化校验。
- 常规上传单文件最大 20 MB、单请求最大 50 MB；不同业务类型另有更小的文件限制。
- 管理员的用户、角色、队伍、导师、成果、社区和竞赛维护操作写入审计日志。
- 响应头包含 CSP、`nosniff`、`Referrer-Policy` 与 `Permissions-Policy` 等基础防护策略。

## 数据库迁移与历史脚本

`src/main/resources/db/migration/` 是当前数据库结构的唯一事实来源，包含 SQL 与 Java 版本化迁移。新增或修复结构时请创建更高版本迁移，不要修改已在共享环境执行过的迁移。

`sql/` 保存历史建表、兼容修复和演示数据脚本，不会被启动过程自动执行。只有在明确了解目标库的 `flyway_schema_history`、完成备份并确认影响范围后，才应人工使用其中脚本；详情见 [sql/README.md](sql/README.md)。

## 项目结构

```text
.
├── src/
│   ├── main/
│   │   ├── java/cn/ilink/
│   │   │   ├── config/       # 安全、缓存、MVC、WebSocket、生产校验
│   │   │   ├── controller/   # 页面路由与 REST 接口
│   │   │   ├── service/      # 业务规则、AI、通知、上传等服务
│   │   │   ├── mapper/       # MyBatis-Plus 数据访问层
│   │   │   ├── entity/       # 持久化实体
│   │   │   ├── dto/、vo/     # 请求与响应模型
│   │   │   ├── security/     # 登录、记住我和 WebSocket 鉴权
│   │   │   └── util/         # 密码、HTML、IP、缓存等工具
│   │   └── resources/
│   │       ├── db/migration/ # Flyway 版本化迁移
│   │       ├── static/       # CSS、JavaScript、图片与前端库
│   │       ├── templates/    # Thymeleaf 页面和 fragments
│   │       └── application*.yml
│   └── test/java/            # 单元、控制器、安全与路径契约测试
├── sql/                      # 历史/人工 SQL，非自动迁移来源
└── iLink-cn/                 # 中文介绍页及其静态资源
```

## 开发约定

- 后端接口请保持 `Result<T>` 返回格式，并在变更请求中保留认证、授权和 CSRF 约束。
- 新增数据库结构通过 Flyway 迁移交付；不要依赖本地手工改表。
- 私钥、数据库密码、上传文件和本地 `application-local.yml` 不应提交。
- 新功能至少补充相应单元或 Web 层测试，并在提交前运行 `mvn test`。
- 运行代码、模板与迁移是功能事实来源；历史入口和 `sql/` 中脚本仅用于兼容与参考。

## 许可证

[Apache License 2.0](LICENSE)
