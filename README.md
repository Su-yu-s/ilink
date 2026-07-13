# iLink — 高校竞赛组队与成果展示平台

> 基于 Spring Boot + Thymeleaf 的智能组队协作平台，打造 **"找队友 — 找项目 — 找导师"** 完整生态。

## 设计风格

暖白金系 · 黑灰白极简美学 — 柔和、克制、专业：
- 主色调 `#282A2F`，灰阶 `#52555C` ~ `#F7F7F8`
- 毛玻璃卡片 `backdrop-filter: blur(16px)`，柔和阴影与微光泽
- 流畅过渡动画 `cubic-bezier(0.2, 0.8, 0.2, 1)`
- 粒子背景点缀，字体 Microsoft YaHei / PingFang SC

---

## 功能模块

### 1. 用户系统（认证、注册、资料）

多因素登录（手机号 / 学号 / 用户名），渐进式限流防护。注册强制身份选择（学生 / 教师 / 管理员），密码强度校验，重复检测。支持邮箱验证找回密码。

**实体:** `User` — id, username, studentId, phoneNumber, password, email, role (STUDENT/TEACHER/ADMIN), avatar, realName, gender, grade, major, school, college, bio, honors, createdAt

<img width="1209" height="945" alt="登录页面" src="https://github.com/user-attachments/assets/1d5b1d97-4c6d-4cb3-bb2e-e9ae5070b2fc" />
<img width="998" height="1135" alt="注册页面" src="https://github.com/user-attachments/assets/32a6ac55-f7cf-4706-a002-ce75d991ead7" />

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/login` | POST | 多模式登录（失败锁定） |
| `/api/register` | POST | 注册（限流 3次/分钟） |
| `/api/logout` | GET/POST | 登出 |
| `/api/user/profile` | GET | 获取当前用户资料 |
| `/api/user/profile` | POST | 更新资料（姓名、头像、年级、专业、学校、简介、荣誉） |
| `/api/user/password` | PUT | 修改密码 |
| `/api/user/public/{userId}` | GET | 公开用户概览（含最新 20 条帖子） |
| `/api/forgot-password` | POST | 发送密码重置邮件 |
| `/api/reset-password` | POST | 重置密码 |

**页面:** `login.html`, `register.html`, `forgot-password.html`, `profile.html`, `profile-edit.html`, `profile-password.html`, `profile-honors.html`, `profile-posts.html`, `profile-favorites.html`, `profile-article-edit.html`, `profile-asset-edit.html`, `user-profile.html`

---

### 2. 组队市场（招募 & 匹配）

全生命周期组队管理：发布需求 → 浏览市场 → 申请加入 → 审核批准 → 成团。支持关键词搜索、分类筛选、状态追踪。

<img width="1942" height="810" alt="组队市场" src="https://github.com/user-attachments/assets/edc84ad6-f0c3-40aa-b062-2206191b2b18" />

**状态流转:** `OPEN`（招募中） → `TEAMING`（组队中） → `CLOSED`（已结束）

**实体:**
- `TeamDemand` — id, title, description, competitionId, requiredSkills, requiredMemberCount, deadline, status, creatorId
- `TeamApplication` — id, teamId, userId, status (PENDING/APPROVED/REJECTED), message

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/team/list` | GET | 团队列表（分页、搜索、分类/状态筛选） |
| `/api/team/{id}` | GET | 团队详情（创建者信息、成员、申请数） |
| `/api/team` | POST | 发布招募需求 |
| `/api/team/{id}` | PUT | 编辑需求（仅创建者，OPEN 状态） |
| `/api/team/{id}` | DELETE | 删除需求（仅创建者，无申请时） |
| `/api/team/{id}/status` | PUT | 更新状态（OPEN→TEAMING / →CLOSED） |
| `/api/team/join` | POST | 申请加入团队 |
| `/api/team/application-status` | GET | 查询用户申请状态 |
| `/api/team/application/{id}/approve` | PUT | 批准/拒绝申请（满员自动关闭） |
| `/api/team/my/published` | GET | 我发布的需求 |
| `/api/team/my/applications` | GET | 我提交的申请 |
| `/api/team/my/pending-applications` | GET | 待审批申请（创建者视角） |
| `/api/team/{id}/members` | GET | 团队成员列表 |

---

### 3. 团队工作区 & 任务协作

四列看板（**待办 → 进行中 → 审核 → 已完成**），支持拖拽。任务分配、提交、审核工作流。支持嵌套评论与文件附件。

<img width="1302" height="792" alt="团队工作区" src="https://github.com/user-attachments/assets/e966c064-1321-4072-b44e-67f00c929893" />

**实体:**
- `TeamTask` — id, teamId, taskTitle, taskDescription, taskType (DEVELOPMENT/DESIGN/TESTING/DOCUMENTATION/OTHER), priority (LOW/MEDIUM/HIGH/URGENT), status (PENDING/IN_PROGRESS/REVIEW/COMPLETED/CANCELLED), estimatedHours, actualHours, deadline, assignedTo, createdBy
- `TaskParticipant` — taskId, userId, role (OWNER/LEAD/MEMBER/REVIEWER), contributionHours, contributionRate
- `TaskSubmission` — taskId, submitterId, content, attachments
- `TaskComment` — taskId, parentId（支持嵌套回复）, userId, content, commentType, attachments, likeCount

| 端点 | 方法 | 说明 |
|------|------|------|
| `GET /api/tasks?teamId=` | GET | 获取团队任务（按角色筛选） |
| `GET /api/tasks/{taskId}` | GET | 获取任务详情 |
| `POST /api/team/{teamId}/tasks` | POST | 创建任务（仅队长） |
| `PUT /api/tasks/{taskId}` | PUT | 更新任务 |
| `PUT /api/tasks/{taskId}/status` | PUT | 更新任务状态 |
| `DELETE /api/tasks/{taskId}` | DELETE | 删除任务（仅队长） |
| `PUT /api/tasks/{taskId}/assign` | PUT | 分配任务给成员 |
| `GET /api/tasks/{taskId}/participants` | GET | 获取任务参与者 |
| `POST /api/tasks/{taskId}/participants` | POST | 添加参与者 |
| `DELETE /api/tasks/{taskId}/participants/{userId}` | DELETE | 移除参与者 |
| `GET /api/tasks/{taskId}/comments` | GET | 获取任务评论（嵌套结构） |
| `POST /api/tasks/{taskId}/comments` | POST | 添加评论（支持 parentId 回复） |
| `PUT /api/tasks/{taskId}/review` | PUT | 审核提交（通过→COMPLETED / 驳回→IN_PROGRESS） |
| `GET /api/tasks/{taskId}/submissions` | GET | 获取提交记录（按角色筛选） |
| `POST /api/tasks/{taskId}/submit` | POST | 提交任务（自动切换至 REVIEW 状态） |

---

### 4. 项目里程碑

追踪团队项目进度：命名里程碑、完成率、交付物、状态跟踪。

**实体:** `ProjectMilestone` — id, teamId, milestoneName, milestoneDescription, dueDate, completedDate, completionRate, deliverables, status (PENDING/IN_PROGRESS/COMPLETED/DELAYED)

| 端点 | 方法 | 说明 |
|------|------|------|
| `GET /api/team/{teamId}/milestones` | GET | 获取团队里程碑 |
| `GET /api/milestones/{id}` | GET | 获取单个里程碑 |
| `POST /api/team/{teamId}/milestones` | POST | 创建里程碑（团队成员） |
| `PUT /api/milestones/{id}` | PUT | 更新里程碑 |
| `PUT /api/milestones/{id}/progress` | PUT | 更新完成进度 |
| `DELETE /api/milestones/{id}` | DELETE | 删除里程碑 |

---

### 5. 团队实时聊天（WebSocket）

STOMP over WebSocket 实时消息。消息持久化到数据库，支持历史检索。WebSocket 连接与订阅均强制鉴权。

| 端点/主题 | 方法 | 说明 |
|-----------|------|------|
| `GET /api/team/{teamId}/messages` | GET | 获取聊天历史（REST 降级） |
| `POST /api/team/{teamId}/messages` | POST | 发送消息（REST 降级） |
| `/ws` | WebSocket | SockJS STOMP 端点 |
| `/app/chat/{teamId}` | STOMP SEND | 发送消息 → 广播 |
| `/topic/team/{teamId}` | STOMP SUBSCRIBE | 接收团队消息 |

---

### 6. 交流社区

全功能论坛，四大分类（**综合 / 技术 / 竞赛 / 资源**）。富文本编辑器 + HTML 净化（Jsoup + 自定义白名单）。点赞、收藏、嵌套评论。

<img width="1905" height="903" alt="交流社区" src="https://github.com/user-attachments/assets/ab7ff117-1a5f-48cf-9614-6e22d13083d7" />

**实体:**
- `CommunityPost` — id, authorId, category, title, content, attachments (JSON), viewCount, likeCount, favoriteCount
- `CommunityComment` — id, postId, userId, content
- `CommunityPostLike` — postId, userId
- `CommunityPostFavorite` — postId, userId

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/community/posts` | GET | 帖子列表（分页、搜索、分类筛选）— 公开 |
| `/api/community/posts/{id}` | GET | 帖子详情（自动增加浏览量） |
| `/api/community/posts` | POST | 发帖（HTML 净化） |
| `/api/community/posts/{id}` | PUT | 编辑帖子（作者或管理员） |
| `/api/community/posts/{id}` | DELETE | 删除帖子（作者或管理员） |
| `/api/community/posts/{id}/like` | POST | 切换点赞 |
| `/api/community/posts/{id}/favorite` | POST | 切换收藏 |
| `/api/community/posts/{postId}/comments` | GET | 获取评论列表 |
| `/api/community/posts/{postId}/comments` | POST | 添加评论 |
| `/api/community/comments/{commentId}` | DELETE | 删除评论 |
| `/api/community/my-posts` | GET | 我的帖子 |
| `/api/community/my-favorites` | GET | 我的收藏 |

---

### 7. 成果展示 & 竞赛列表

项目成果、研究成果、竞赛奖项数字化展示。支持文件上传（MIME 校验），公开画廊可搜索、排序（最新 / 最热），Caffeine 缓存。

<img width="1818" height="1032" alt="成果展示" src="https://github.com/user-attachments/assets/c17a581c-06a3-45e4-99e7-490a0437e976" />
<img width="1864" height="1122" alt="竞赛列表" src="https://github.com/user-attachments/assets/7bef811a-101b-49a0-9b33-cdc12e77a6c7" />

**实体:** `Asset` — id, title, description, fileUrl, userId, viewCount, downloadCount, createdAt

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/asset/list` | GET | 成果列表（分页、搜索、分类筛选、排序） |
| `/api/asset/{id}` | GET | 成果详情（含发布者信息，30分钟缓存） |
| `/api/asset/upload` | POST | 上传成果（标题 + 描述 + 可选文件） |
| `/api/asset/{id}` | PUT | 编辑成果（替换旧文件） |
| `/api/asset/download/{id}` | GET | 下载成果文件（增加下载计数） |

---

### 8. 导师匹配

双向师生匹配系统。教师申请成为导师（研究方向、项目），学生浏览导师资料并申请加入项目，导师审批。

<img width="1857" height="776" alt="导师匹配" src="https://github.com/user-attachments/assets/f743a79d-7832-4feb-80ab-0598c09e424b" />

**实体:**
- `TeacherApplication` — id, userId, introduction, researchDirection, professionalTitle, projects, status (PENDING/APPROVED)
- `ProjectApplication` — id, teacherId, userId, status (PENDING/APPROVED/REJECTED), message

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/teacher/list` | GET | 导师列表（仅 APPROVED，分页、搜索、筛选） |
| `/api/teacher/{id}` | GET | 导师详情（含关联用户信息，缓存） |
| `/api/teacher/apply` | POST | 申请成为导师（每人限一次） |
| `/api/teacher/project-apply` | POST | 学生申请加入导师项目 |
| `/api/teacher/project-application-status` | GET | 查询学生申请状态 |
| `/api/teacher/my/project-applications` | GET | 导师的待处理项目申请 |
| `/api/teacher/project-application/{id}/approve` | PUT | 导师审批项目申请 |

---

### 9. 用户技能标签

细粒度技能管理，提升匹配精度。每条技能包含等级、分类、认证、经验年限与作品集链接。

**实体:** `UserSkill` — id, userId, skillName, skillLevel, skillCategory, certification, yearsExperience, portfolioUrl

| 端点 | 方法 | 说明 |
|------|------|------|
| `GET /api/user/skills` | GET | 获取当前用户技能列表 |
| `POST /api/user/skills` | POST | 添加技能（防重复） |
| `DELETE /api/user/skills/{skillId}` | DELETE | 删除技能（所有权校验） |

---

### 10. 通知中心

实时通知系统，按类型归类。支持未读角标、批量已读、按角色投递。

**实体:** `Notification` — id, userId, senderId, type (TEAM_INVITE/TASK_ASSIGNED/TASK_COMPLETED/MILESTONE_UPDATE/RECOMMENDATION/SYSTEM), title, content, isRead, relatedId, relatedType

| 端点 | 方法 | 说明 |
|------|------|------|
| `GET /api/notifications` | GET | 获取通知列表（所有权校验） |
| `GET /api/notifications/unread-count` | GET | 获取未读数 |
| `PUT /api/notifications/{id}/read` | PUT | 标记单条已读 |
| `PUT /api/notifications/read-all` | PUT | 全部标记已读 |
| `POST /api/notifications` | POST | 创建通知 |

---

### 11. 管理后台

综合管理仪表盘。用户、团队、导师、成果、社区帖子的完整 CRUD。角色管理含自降级保护。删除用户时级联清理关联数据。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/admin/dashboard` | GET | 仪表盘统计 |
| `/api/admin/users` | GET | 用户列表 |
| `/api/admin/teams` | GET | 团队列表 |
| `/api/admin/teachers` | GET | 导师列表 |
| `/api/admin/assets` | GET | 成果列表 |
| `/api/admin/community-posts` | GET | 帖子列表 |
| `/api/admin/user/{id}` | PUT | 编辑用户 |
| `/api/admin/user/{id}/role` | PUT | 修改角色（防自降级） |
| `/api/admin/user/{id}` | DELETE | 删除用户（级联清理） |
| `/api/admin/team/{id}` | DELETE | 删除团队 |
| `/api/admin/teacher/{id}/approve` | PUT | 审批导师申请 |
| `/api/admin/teacher/{id}` | DELETE | 删除导师 |
| `/api/admin/asset/{id}` | DELETE | 删除成果 |
| `/api/admin/community-post/{id}` | DELETE | 删除帖子 |

---

### 12. 文件与附件上传

统一文件管理，头像、成果附件、富文本图片上传。MIME 白名单校验，SHA-256 去重存储。

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/file/upload` | POST | 通用文件上传 |
| `/api/file/{filename}` | GET | 文件下载/预览 |
| `/api/attachment/upload` | POST | 富文本编辑器附件上传 |

---

### 13. 安全与基础设施

- **登录限流** — `LoginAttemptService` 渐进式锁定（5分钟内 5 次失败 → 锁定 15 分钟）
- **密码策略** — 最少 8 位，含大小写字母与数字
- **HTML 净化** — Jsoup 自定义白名单，防范 XSS
- **统一响应** — `Result<T>` 包装器（code + message + data）
- **全局异常处理** — `@ControllerAdvice` 拦截校验异常、认证异常、运行时异常
- **CSRF 防护** — Spring Security 内置，AJAX 自动携带 Token
- **CORS 配置** — 跨域策略可配置

---

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端框架 | Spring Boot | 2.7.18 |
| 安全框架 | Spring Security + BCrypt + CSRF | 5.7.11 |
| 数据库 | MySQL | 8.0+ |
| ORM | MyBatis-Plus | 3.5.5 |
| 实时通信 | WebSocket + STOMP (SockJS) | - |
| 前端 | HTML5 + CSS3 + JavaScript (ES6+) | - |
| UI 框架 | Bootstrap 5 + GSAP 动画 | 5.x / 3.x |
| 模板引擎 | Thymeleaf | 3.0.15 |
| 缓存 | Caffeine | 2.9.3 |
| 数据库迁移 | Flyway | 7.11.0 |
| API 文档 | SpringDoc OpenAPI | 1.7.0 |
| HTML 净化 | Jsoup | 1.17.2 |
| 测试 | JUnit 5 + Mockito + H2 | - |

---

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.0+
- Maven 3.9+

### 数据库设置

```sql
CREATE DATABASE ilink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

编辑 `src/main/resources/application-dev.yml` 配置数据库连接：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ilink?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: your_username
    password: your_password
```

Flyway 会在启动时自动执行数据库迁移脚本（`src/main/resources/db/migration/`）。

### 构建 & 运行

```bash
# 编译
mvn clean compile -DskipTests

# 运行
mvn spring-boot:run

# 或打包运行
mvn clean package -DskipTests
java -jar target/iLink-1.0.jar
```

访问 **http://localhost:8090**

### 测试

```bash
# 运行全部测试（使用 H2 内存数据库）
mvn test
```

---

## 项目结构

```
src/
├── main/
│   ├── java/cn/ilink/
│   │   ├── config/              # 配置类（Security, WebSocket, Cache, MVC, MyBatis-Plus, Flyway）
│   │   ├── controller/          # REST 控制器 + 页面路由（19 个文件）
│   │   ├── service/             # 业务接口（13 个接口）
│   │   │   └── impl/            # 业务实现（16 个实现类）
│   │   ├── mapper/              # MyBatis-Plus Mapper 接口（19 个）
│   │   ├── entity/              # 实体类（20 个）
│   │   ├── dto/                 # 请求体 DTO（18 个）
│   │   ├── vo/                  # 响应体 VO（16 个）
│   │   ├── common/              # 通用组件（Result 统一响应、ControllerUtils 控制器工具）
│   │   ├── exception/           # 全局异常处理（GlobalExceptionHandler）
│   │   ├── security/            # 安全组件（LoginAttemptService 登录限流）
│   │   └── util/                # 工具类（HtmlSanitizer、PasswordPolicy、CacheEvictUtils、UserPreviewHelper）
│   └── resources/
│       ├── static/
│       │   ├── css/             # 样式（31 个文件）
│       │   ├── js/              # 脚本（34 个文件）
│       │   ├── lib/             # 第三方库（Bootstrap 5, GSAP）
│       │   └── images/          # 图片资源
│       ├── templates/           # Thymeleaf 模板（29 个页面 + 7 个片段）
│       │   └── fragments/       # 共享片段（header、footer、sidebar 等）
│       ├── db/migration/        # Flyway 数据库迁移脚本（16 个 SQL）
│       └── application*.yml     # 多环境配置（dev / test / prod）
└── test/
    └── java/cn/ilink/           # 测试代码（17 个测试类，JUnit 5 + Mockito + H2）
        ├── config/              # 安全与 WebSocket 测试
        ├── controller/          # 控制器集成测试
        ├── service/             # 服务层单元测试
        └── mapper/              # Mapper 测试
```

---

## 设计系统

### CSS 设计令牌（design-tokens.css v7.4）

```css
:root {
  /* 品牌主色 — 暖灰黑 */
  --primary: #282A2F;
  --primary-dark: #181A1E;
  --primary-light: #ECEDEF;
  --accent: #73757A;

  /* 灰阶 */
  --color-gray-700: #52555C;
  --color-gray-600: #63666D;
  --color-gray-500: #73757A;
  --color-gray-400: #A1A3A8;
  --color-gray-300: #D7D8DB;
  --color-gray-200: #E5E5E7;
  --color-gray-100: #F1F1F2;
  --color-gray-50: #F7F7F8;
  --color-white: #FCFCFD;

  /* 语义色 */
  --success: #16A34A;
  --warning: #DC2626;
  --error: #DC2626;

  /* 毛玻璃效果 */
  --glass-blur: 16px;
  --glass-bg: rgba(252, 252, 253, 0.98);
  --glass-border: rgba(40, 42, 47, 0.08);
  --glass-shadow: 0 4px 12px rgba(40, 42, 47, 0.08);

  /* 圆角 */
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;
  --radius-xl: 16px;
  --radius-2xl: 20px;

  /* 阴影 */
  --shadow-sm: 0 1px 2px rgba(40, 42, 47, 0.06);
  --shadow-md: 0 4px 12px rgba(40, 42, 47, 0.08);
  --shadow-lg: 0 12px 24px rgba(40, 42, 47, 0.10);

  /* 字体 */
  --font-family: "Microsoft YaHei", "微软雅黑", "PingFang SC", sans-serif;
  --font-serif: "Noto Serif SC", "Songti SC", "STSong", "SimSun", serif;
  --font-mono: "SF Mono", "Fira Code", "Consolas", monospace;

  /* 过渡 */
  --transition-fast: 120ms cubic-bezier(0.2, 0.8, 0.2, 1);
  --transition-normal: 240ms cubic-bezier(0.2, 0.8, 0.2, 1);
  --transition-slow: 320ms cubic-bezier(0.2, 0.8, 0.2, 1);
}
```

---

## 许可证

Apache License 2.0
