# iLink 稳定性修复与功能补全实施计划

依据：[2026-08-15-ilink-stability-and-completion-design.md](../specs/2026-08-15-ilink-stability-and-completion-design.md)

## 执行约束

- 保留首页现有内容与排版。
- 不实现聊天已读状态或已读回执，并删除相关空接口。
- 不覆盖工作区中已经存在的 UI 改动。
- 每个阶段先加回归测试，再实现，再执行阶段测试和全量测试。
- 新数据库变更只追加 Flyway 迁移，不修改已经执行的迁移。
- 不自动删除无法明确识别的用户数据。

## 阶段一：数据安全与核心业务正确性

### 1. 安全文件服务与成果生命周期

涉及文件：

- `service/FileService.java`
- `controller/AssetController.java`
- `controller/AttachmentUploadController.java`
- `controller/FileController.java`
- `controller/AdminController.java`
- `service/AssetLifecycleService.java`（新增）
- `controller/AssetControllerWebTest.java`（新增）
- `service/AssetLifecycleServiceTest.java`（新增）

步骤：

1. 扩展 `FileService`，按使用场景定义允许类型并返回受管文件引用。
2. 将成果和附件上传迁移到统一服务，移除 Controller 中的直接 `Files.write`。
3. 实现“先存新文件、提交数据库、提交后删旧文件”的替换流程。
4. 增加成果所有者删除接口和管理员共用删除服务。
5. 增加扩展名伪造、文件签名、路径边界、越权删除、失败回滚和孤儿文件测试。

### 2. 任务状态机

涉及文件：

- `service/TeamTaskService.java`
- `service/impl/TeamTaskServiceImpl.java`
- `controller/TeamTaskController.java`
- `controller/TeamTaskControllerWebTest.java`（新增）
- `service/impl/TeamTaskServiceImplTest.java`（新增）

步骤：

1. 定义允许状态集合和角色相关转换。
2. 执行人只允许开始任务；提交审核只能经过提交接口。
3. 队长只能在审核接口完成或退回任务。
4. 创建任务直接返回持久化实体/ID，删除标题反查逻辑。
5. 测试未知状态、越级转换、非指派成员和合法完整流程。

### 3. 集中式删除与角色一致性

涉及文件：

- `service/AdminDataService.java`（新增）
- `service/UserRoleService.java`（新增）
- `controller/AdminController.java`
- 各业务 Mapper
- `db/migration/V16__harden_relational_integrity.sql`（新增）
- `controller/AdminControllerWebTest.java`
- `service/AdminDataServiceTest.java`（新增）
- `service/UserRoleServiceTest.java`（新增）

步骤：

1. 构建帖子、成果、队伍和用户的事务删除顺序。
2. 清理可确认的历史孤儿记录，再为纯从属表补外键/索引。
3. 管理员删除接口统一调用删除服务，并记录成功与失败日志。
4. 角色变更统一调用角色服务，同步教师档案和项目申请状态。
5. 覆盖删除中途失败回滚、完整清理、教师升降级和缓存失效测试。

### 4. 组队审批并发

涉及文件：

- `mapper/TeamDemandMapper.java`
- `mapper/TeamApplicationMapper.java`
- `service/TeamApplicationWorkflowService.java`（新增）
- `controller/TeamController.java`
- `service/TeamApplicationWorkflowServiceTest.java`（新增）

步骤：

1. 增加 `SELECT ... FOR UPDATE` 查询。
2. 将审批校验、人数计算、状态更新和通知放入同一事务。
3. 检查每次写入结果；冲突返回明确业务错误。
4. 增加并发审批不超员和重复审批测试。

## 阶段二：数据模型与已确认缺陷

### 5. 导师查询与完整度

- 新增 Mapper 联表分页查询，覆盖姓名、用户名、专业、院系、研究方向、职称和简介。
- 新增导师资料完整性判断，教师注册档案使用 `INCOMPLETE`。
- 新增 `V17__mentor_profile_completeness.sql`，回填旧空档案。
- 更新个人中心保存逻辑，资料完整后才公开。
- 增加姓名搜索、专业筛选和公开性测试。

### 6. 通知正文

- 抽取统一显示名与通知正文构建方法。
- 修复任务、评论、点赞、收藏等三元表达式优先级错误。
- 增加每种通知的正文断言。

### 7. 成果分类、浏览与下载

- 新增 `V18__normalize_asset_metadata.sql`：`category`、`download_count` 和索引。
- 回填描述中的旧分类标记并保持过渡读取兼容。
- 详情访问原子增加浏览量，下载原子增加下载量。
- 分类统计改为 Mapper 聚合查询。
- 更新实体、接口、详情页和后台展示测试。

## 阶段三：功能补全

### 8. 在线状态与聊天冗余清理

- 删除聊天 `markAsRead` 空接口及引用。
- 新增连接计数、心跳、断开监听和最近活跃时间。
- 新增 `V19__add_user_last_active.sql`。
- 团队空间返回真实在线/最近活跃状态并测试连接生命周期。

### 9. 导师统计

- 后端聚合已通过合作项目数和公开成果数。
- 导师列表与详情 DTO 返回统计数据。
- 前端移除固定破折号并使用准确标签。

### 10. 推荐系统接入

- 反馈动作白名单并校验日志归属。
- 组队大厅增加登录用户推荐区；团队空间增加队长候选人推荐区。
- 推荐失败时回退普通列表。
- 增加权限、空结果和回退测试。

### 11. 竞赛目录后台化

- 新增 `Competition` 实体、Mapper、Service、公开 API 和管理员 API。
- 新增 `V20__create_competition_catalog.sql` 并幂等导入现有静态目录。
- 竞赛页改为 API 数据，后台增加维护界面。
- 验证 URL、日期、分类和状态，并增加 CRUD 测试。

### 12. 密码找回与持久登录

- 引入 Spring Mail，新增重置令牌、持久登录令牌实体和服务。
- 新增 `V21__add_account_recovery_tokens.sql`。
- 实现申请重置、验证令牌、更新密码和撤销令牌。
- 将登录页“记住我”接入轮换式持久令牌。
- 增加枚举防护、过期、复用、退出撤销和 Cookie 属性测试。

## 阶段四：体验与发布准备

### 13. 公开详情与登录回跳

- 匿名开放团队、导师和成果详情 HTML。
- 保持写操作、申请和受限下载登录校验。
- 所有受限动作使用安全站内回跳并增加安全配置测试。

### 14. 移动端触控与状态

- 在共享组件 CSS 中统一 44×44px 最小触控尺寸。
- 修复 Logo、卡片操作、竞赛官网和表单控件。
- 以 393px、768px 和桌面宽度进行截图与无溢出验证。

### 15. 数据清理工具

- 新增只读数据扫描脚本，输出明确种子、疑似测试和不可自动判断三类。
- 新增只删除明确 demo/视觉回归种子的显式脚本，不在应用启动时执行。
- 在开发数据库先生成报告，再执行受控清理并复查。

### 16. Flyway 与生产安全

- 移除重复 Flyway 初始化和自动 repair，关闭 out-of-order。
- 默认安全策略改为明确公开白名单，其余请求认证。
- 增加 CSP、Permissions-Policy、会话超时、安全 Cookie 和生产配置校验。
- 增加管理员操作审计记录和相应测试。

## 最终回归矩阵

1. `mvn test` 全量通过，并记录测试数量。
2. 在空 MySQL 数据库执行全部迁移。
3. 在当前开发数据库从现有版本向前迁移。
4. 学生：注册、登录、记住我、找回密码、搜索、申请组队、执行任务、发布和删除成果。
5. 教师：注册、完善导师资料、公开搜索、处理合作申请、查看统计。
6. 队长：创建队伍、并发审批、团队空间、任务创建/提交/审核、推荐成员。
7. 管理员：角色变更、竞赛 CRUD、用户/队伍/帖子/成果删除及审计。
8. 匿名用户：首页、组队、导师、社区、竞赛、成果及三类详情浏览。
9. WebSocket：连接、心跳、断开、重连和最近活跃状态。
10. 393px、768px、桌面三档视觉回归、控制台错误和网络失败检查。
