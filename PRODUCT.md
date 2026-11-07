# iLink · 产品事实

> 本文件记录不会随视觉改版变化的产品事实。设计与实现冲突时，以这里为准。

## 是什么

面向高校学生的组队与协作平台。一个学生在这里发组队需求、找队友、找导师、看竞赛、交成果、在社区交流；组队成功之后在团队空间里做任务、聊天、共享资料。

## 谁在用

| 角色 | 身份 | 主要动作 |
| --- | --- | --- |
| 学生 | 绝大多数 | 发组队、申请入队、交成果、发帖、参加任务 |
| 教师 | 少数（需审核） | 发布招贤、指导学生、审核任务 |
| 管理员 | 2 人 | 管用户、管组队、审核导师、管竞赛目录、管社区、查审计 |

## 表面与各自的成功标准

| 表面 | 路由 | 用户成功的样子 |
| --- | --- | --- |
| 门户首页 | `index.html` | 看懂这是什么、决定注册。**本次改版不动** |
| 浏览与检索 | `team-market` `teacher-wall` `gallery` `community` `competitions` | 在几十条里快速找到想看的，并点进去 |
| 详情 | `team-detail` `teacher-detail` `asset-detail` `community-article` `user-profile` | 判断"这个人/这个队靠不靠谱"，然后发出申请或建立联系 |
| 创作 | `team-publish` `asset-publish` `profile-article-edit` | 把内容写出来并发出去，中途不丢 |
| 个人中心 | `profile*` 共 9 页 | 管理自己的资料、内容、收藏、密码 |
| 团队空间 | `team-space` `team-workspace` `team-chat` `team-detail` | 队内协作：任务、聊天、资料、复盘 |
| 管理后台 | `admin.html` | 发现问题、处理问题、留痕 |

## 硬约束

- **技术栈固定**：Spring Boot 2.7 + Thymeleaf 服务端渲染 + 原生 JS，没有打包器。不能引入 React/Tailwind/构建链。
- **样式层**：所有 CSS 包在 `@layer legacy` 里，`layers.css` 最先加载。层外样式（Bootstrap）优先于层内。
- **静态资源按内容指纹**：改完 CSS 不需要版本号，但必须 `mvn resources:resources` 同步到 `target/classes`。
- **页面白名单**：新页面必须同时登记在 `PageController.ALLOWED_PAGES` 和 `SecurityConfig`。
- **字体**：Inter + Noto Sans SC，不引入新字体。
- **首页例外**：`index.html` 与 `home.css` 本次不改。

## 内容事实

- 规模：51 个账号、27 条组队、11 条导师申请、32 份成果、13 篇帖子、125 条审计。其中 44 个账号是种子/测试数据。
- 状态枚举：用户 `ACTIVE/DISABLED`；组队 `OPEN/TEAMING/CLOSED/DISSOLVED`；导师申请 `PENDING/APPROVED/REJECTED`。
- 数据可信度：当前大部分是联调数据，任何"率"都只能当结构参考，不能当结论。
