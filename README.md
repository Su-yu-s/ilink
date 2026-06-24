# iLink 高校竞赛组队与成果展示平台

> 基于 Spring Boot + Thymeleaf 的智能组队与协作平台

## 项目简介

iLink 是一个面向高校师生的竞赛服务一体化平台，打造"找人 - 找项目 - 找导师"的完整生态链。

### 核心功能

- **智能组队**：基于技能标签的智能匹配，快速组建高效团队
- **任务看板**：四列看板（待办/进行中/待审核/已完成）支持拖拽操作
- **实时协作**：WebSocket 团队聊天，即时沟通推进项目
- **成果展示**：数字化展厅，记录每一次突破与成长
- **导师对接**：双向选择，获取专业指导
- **个人中心**：成果展示、资料编辑、我的文章、我的收藏
- **交流社区**：帖子发布、点赞、收藏、评论互动

### 设计风格

macOS 液态玻璃效果 + 黑白灰极简风格
- 玻璃态卡片：`backdrop-filter: blur(20px) saturate(1.8)`
- 统一配色：主色 #111827、灰色 #6b7280
- 流畅动画：cubic-bezier(0.4, 0, 0.2, 1)
- 粒子背景效果与卡片毛玻璃交相辉映

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 后端 | Spring Boot | 2.7.0 |
| 数据库 | MySQL | 5.7+ |
| ORM | MyBatis-Plus | 3.5.x |
| 实时通讯 | WebSocket + STOMP | - |
| 前端 | HTML5 + CSS3 + JavaScript | ES6+ |
| UI框架 | Bootstrap | 5.x |
| 模板引擎 | Thymeleaf | 3.0.x |
| 缓存 | Caffeine | 3.x |
| 数据迁移 | Flyway | 7.x |

## 快速开始

### 环境要求

- JDK 17+
- MySQL 5.7+
- Maven 3.9+

### 配置数据库

```sql
CREATE DATABASE ilink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

修改 `src/main/resources/application-dev.yml` 中的数据库配置：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/ilink?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: your_username
    password: your_password
```

### 编译运行

```bash
# 使用项目自带脚本（推荐）
start.bat

# 或手动编译
mvn clean compile -DskipTests
mvn spring-boot:run

# 或打包运行
mvn clean package -DskipTests
java -jar target/ilink-*.jar
```

访问 http://localhost:8090

## 主要页面

| 页面 | 路径 | 说明 |
|------|------|------|
| 首页 | `/` | 展示推荐团队和用户 |
| 用户登录 | `/login` | 支持手机号/学号登录 |
| 用户注册 | `/register` | 手机号或学号注册 |
| 个人中心概览 | `/profile.html` | 个人信息总览 |
| 资料编辑 | `/profile-edit.html` | 管理技能标签、个人信息 |
| 成果展示 | `/profile-honors.html` | 管理个人荣誉、奖项 |
| 我的文章 | `/profile-posts.html` | 管理发布的帖子 |
| 我的收藏 | `/profile-favorites.html` | 收藏的帖子列表 |
| 修改密码 | `/profile-password.html` | 修改登录密码 |
| 组队大厅 | `/team-market.html` | 浏览和搜索团队 |
| 发布组队 | `/team-publish.html` | 发布组队需求 |
| 团队详情 | `/team-detail.html?id={id}` | 团队信息和成员 |
| 协作工作台 | `/team-workspace.html?teamId={id}` | 任务看板和聊天 |
| 成果展厅 | `/gallery.html` | 作品展厅浏览 |
| 竞赛目录 | `/competitions.html` | 竞赛信息列表 |
| 交流社区 | `/community.html` | 帖子发布与讨论 |
| 文章详情 | `/community-article.html?id={id}` | 文章阅读页 |
| 导师墙 | `/teacher-wall.html` | 导师招贤 |
| 导师详情 | `/teacher-detail.html?id={id}` | 导师个人信息 |
| 资产管理 | `/asset-detail.html?id={id}` | 成果资产详情 |
| 管理后台 | `/admin.html` | 平台管理 |

## 核心API

### 用户相关

```
GET    /api/user/profile           # 获取当前用户资料
PUT    /api/user/profile           # 更新个人资料
POST   /api/user/avatar           # 上传头像
PUT    /api/user/password         # 修改密码
```

### 用户技能

```
GET    /api/skills                # 获取当前用户技能
POST   /api/skills                # 添加技能
PUT    /api/skills/{id}           # 更新技能
DELETE /api/skills/{id}           # 删除技能
```

### 智能推荐

```
GET /api/recommendations/teams   # 获取推荐团队
GET /api/recommendations/users   # 获取推荐用户
```

### 团队管理

```
GET    /api/team/list             # 获取团队列表
POST   /api/team                  # 创建团队
GET    /api/team/{id}            # 获取团队详情
PUT    /api/team/{id}            # 更新团队
DELETE /api/team/{id}            # 删除团队
POST   /api/team/join            # 申请加入团队
GET    /api/team/{id}/members    # 获取团队成员
GET    /api/team/{id}/messages   # 聊天历史
POST   /api/team/{id}/messages   # 发送聊天消息
PUT    /api/team/application/{id}/approve  # 处理申请
```

### 团队任务

```
GET    /api/tasks?teamId={id}     # 获取团队任务
POST   /api/tasks                 # 创建任务
GET    /api/tasks/{id}           # 获取任务详情
PUT    /api/tasks/{id}           # 更新任务
PUT    /api/tasks/{id}/status    # 更新任务状态
DELETE /api/tasks/{id}           # 删除任务
POST   /api/tasks/{id}/comments  # 添加评论
GET    /api/tasks/{id}/comments  # 获取评论列表
```

### 团队聊天

```
GET  /api/chat/messages?teamId={id}  # 获取聊天记录
WebSocket /ws                         # STOMP 端点（SockJS）
订阅 /topic/team/{teamId}            # 接收团队消息
发送 /app/chat/{teamId}              # 发送团队消息
```

### 通知系统

```
GET  /api/notifications           # 获取通知列表
PUT  /api/notifications/{id}/read  # 标记已读
PUT  /api/notifications/read-all   # 全部已读
GET  /api/notifications/unread-count  # 未读数量
```

### 交流社区

```
GET    /api/community/posts        # 获取帖子列表
POST   /api/community/posts        # 发布帖子
GET    /api/community/posts/{id}  # 获取帖子详情
PUT    /api/community/posts/{id}  # 更新帖子
DELETE /api/community/posts/{id}  # 删除帖子
POST   /api/community/posts/{id}/like    # 点赞
DELETE /api/community/posts/{id}/like     # 取消点赞
POST   /api/community/posts/{id}/favorite # 收藏
DELETE /api/community/posts/{id}/favorite # 取消收藏
POST   /api/community/posts/{id}/comments # 评论
```

## 项目结构

```
src/
├── main/
│   ├── java/cn/ilink/
│   │   ├── config/          # 配置类（安全、数据源、缓存等）
│   │   ├── controller/      # REST控制器 + 页面控制器
│   │   ├── service/        # 服务层接口
│   │   │   └── impl/       # 服务实现
│   │   ├── mapper/        # MyBatis Mapper接口
│   │   ├── entity/         # 实体类
│   │   ├── dto/            # 数据传输对象
│   │   ├── vo/             # 视图对象
│   │   ├── exception/      # 异常处理
│   │   └── util/           # 工具类
│   └── resources/
│       ├── static/         # 静态资源
│       │   ├── css/        # 样式文件
│       │   ├── js/         # 脚本文件
│       │   └── lib/        # 第三方库
│       ├── templates/      # Thymeleaf模板
│       │   └── fragments/   # 公共片段
│       └── db/migration/   # Flyway迁移脚本
└── test/                   # 测试代码
```

## 设计系统

### CSS变量

```css
:root {
  /* 玻璃态 */
  --glass-bg: rgba(255, 255, 255, 0.72);
  --glass-blur: 20px;
  --glass-border: rgba(0, 0, 0, 0.08);

  /* 色彩 */
  --color-black: #111827;
  --color-primary: #2563eb;
  --color-gray: #6b7280;

  /* 圆角 */
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;

  /* z-index层级 */
  --z-base: 0;
  --z-raised: 10;
  --z-dropdown: 100;
  --z-sticky: 200;
  --z-overlay: 300;
  --z-modal: 400;
  --z-popover: 500;
  --z-toast: 600;
}
```

### 组件命名

- `.il-header` - 页面头部导航
- `.il-card` - 玻璃态卡片
- `.il-btn` - 按钮
- `.il-badge` - 徽章
- `.il-avatar` - 头像
- `.il-input` - 输入框
- `.il-modal` - 模态框
- `.il-section-card` - 区块卡片
- `.il-profile-sidebar` - 个人中心侧边栏
- `.il-tabs` - 标签页

### JavaScript模块

| 文件 | 功能 |
|------|------|
| `profile.js` | 个人中心核心逻辑（荣誉、技能、概览） |
| `profile-edit.js` | 资料编辑页逻辑 |
| `profile-password.js` | 密码修改逻辑 |
| `profile-posts.js` | 我的帖子逻辑 |
| `profile-favorites.js` | 我的收藏逻辑 |
| `team-detail.js` | 团队详情页 |
| `team-workspace.js` | 任务看板与协作 |
| `team-chat.js` | 团队聊天功能 |
| `team-market.js` | 组队大厅 |
| `community.js` | 交流社区 |
| `common.js` | 通用工具函数 |

## 数据库表

### 用户相关
- `user` - 用户信息
- `user_skill` - 用户技能标签
- `user_honors` - 用户荣誉奖项

### 组队相关
- `team_demand` - 组队需求
- `team_application` - 加入申请
- `team_member` - 团队成员

### 协作相关
- `team_task` - 团队任务
- `task_participant` - 任务参与者
- `task_comment` - 任务评论
- `project_milestone` - 项目里程碑
- `chat_message` - 聊天消息

### 社区相关
- `community_post` - 社区帖子
- `community_comment` - 帖子评论
- `community_post_like` - 帖子点赞
- `community_post_favorite` - 帖子收藏

### 其他
- `notification` - 通知消息
- `asset` - 成果资产
- `recommendation_log` - 推荐日志

## 开发说明

### 启动脚本

Windows 用户可直接双击 `start.bat` 或在终端运行：
```bash
start.bat
```

脚本会自动设置 JDK 路径并启动 Spring Boot。

### 前端资源

静态资源（CSS、JS）在 `src/main/resources/static/` 目录，修改后需重启服务或清除缓存。

### 数据初始化

首次启动时会自动初始化演示数据（教师信息等）。

## License

MIT License
