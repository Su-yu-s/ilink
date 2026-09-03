# SQL 脚本目录说明

本目录保存历史建表、人工修复和演示数据脚本，**不会被应用启动时的 Flyway 自动执行**。

## 数据库事实来源

`src/main/resources/db/migration/` 是当前数据库结构的唯一事实来源。新环境应创建空的 `ilink` 数据库后启动应用，由 Flyway 按 `flyway_schema_history` 自动执行迁移。

当前仓库包含 23 个版本化 SQL 迁移，版本范围为 `V0_5` 至 `V23`；`V3` 是 Java 迁移，因此完整迁移链共 24 个迁移：

| 迁移 | 作用 |
| --- | --- |
| `V0_5__create_core_prerequisites.sql` | 建立后续迁移需要的核心前置结构 |
| `V1__create_teamwork_tables.sql` | 团队协作基础表 |
| `V2__create_asset_table.sql` | 成果表 |
| `V4__create_notification_table.sql` | 通知表 |
| `V5__ensure_user_skills_table.sql` | 用户技能表 |
| `V6__ensure_recommendation_log_current_schema.sql` | 推荐日志结构 |
| `V7__team_workflow_and_task_submissions.sql` | 团队流程和任务提交 |
| `V8__add_reviewer_note_to_team_application.sql` | 组队申请审核备注 |
| `V9__add_sender_id_to_notification.sql` | 通知发送者 |
| `V10__create_task_tables.sql` | 完整任务协作表 |
| `V11__ensure_chat_message_table.sql` | 团队聊天消息表 |
| `V12__create_core_tables.sql` | 补齐社区、导师、合作申请等核心表 |
| `V13__enforce_application_uniqueness.sql` | 申请、点赞、收藏等唯一约束 |
| `V14__add_teacher_professional_title.sql` | 导师职称字段 |
| `V15__backfill_registered_teachers_as_mentors.sql` | 为已有教师账号补齐导师档案（公开完整度由 V17 统一判定） |
| `V16__harden_relational_integrity.sql` | 清理孤儿关系、兼容旧主键类型并强化外键 |
| `V17__enforce_teacher_profile_completeness.sql` | 统一导师状态字段并回填资料完整度 |
| `V18__normalize_asset_category_and_metrics.sql` | 成果分类、浏览量和下载量结构化 |
| `V19__add_user_last_active.sql` | 用户最近活跃时间 |
| `V20__create_competition_catalog.sql` | 竞赛目录表及 55 条幂等初始数据 |
| `V21__add_account_recovery_tokens.sql` | 密码重置与持久登录令牌 |
| `V22__create_admin_audit_log.sql` | 管理员操作审计日志 |
| `V23__ensure_legacy_workspace_schema.sql` | 补齐 baseline 旧库缺失的任务字段和里程碑表 |

仓库另有 Java Flyway 迁移，用于把历史明文密码升级为 BCrypt。不要用本目录脚本绕过该迁移链。

## 本目录文件

| 文件 | 用途 | 默认建议 |
| --- | --- | --- |
| `schema.sql` | 历史完整建表参考 | 只读参考，不用于新环境初始化 |
| `ilink_user_table.sql` | 历史用户表建表 | 只读参考 |
| `community_post.sql` | 历史社区帖子/评论结构 | 只读参考 |
| `community_post_likes_favorites.sql` | 历史点赞/收藏结构 | 只读参考 |
| `community_post_attachments.sql` | 历史帖子附件升级 | 仅人工兼容场景 |
| `community_blog_upgrade.sql` | 历史社区博客升级 | 仅人工兼容场景 |
| `notification_table.sql` | 历史通知表 | 只读参考 |
| `team_application.sql` | 历史组队申请表 | 只读参考 |
| `project_application.sql` | 历史导师项目申请表 | 只读参考 |
| `recommendation_log_table.sql` | 历史推荐日志表 | 只读参考 |
| `user_honors.sql` | 历史用户荣誉字段升级 | 仅人工兼容场景 |
| `schema-v2-migration.sql` | V2 版结构迁移参考 | 只读参考 |
| `idempotent_unique_constraints.sql` | 旧数据库唯一约束修补 | DBA 确认后人工执行 |
| `demo_public_profile_seed.sql` | 演示公开主页种子数据 | 仅本地或专用演示库 |

## 使用规则

1. 新环境不要先执行 `schema.sql`，只创建空数据库并交给 Flyway。
2. 执行任何手工脚本前，先备份数据库并检查 `flyway_schema_history`、目标表结构和数据量。
3. 不要在生产库直接执行演示种子脚本。
4. 手工脚本执行后如果改变了结构，应补写新的 Flyway 迁移，使其他环境可重复获得同一结果。
5. 不要修改已经在共享环境执行过的版本化迁移；使用更高版本新增修复。
6. 所有 profile 都关闭自动 repair 和 out-of-order；校验失败时先查明原因，只允许 DBA 对明确失败记录执行有记录的定向修复。

## 开发数据审计与清理

仓库根目录 `scripts/` 提供两份发布前工具：

| 文件 | 行为 |
| --- | --- |
| `scripts/audit-dev-data.sql` | 只读列出仓库明确种子、疑似测试账号和孤立关系，不修改数据 |
| `scripts/cleanup-known-dev-data.sql` | 默认 `@confirm_cleanup = 0`；仅在本地副本显式确认后删除固定用户名的仓库种子 |

不要根据 `test`、手机号式用户名或示例邮箱等模糊特征自动删除账号。SQL 清理不会删除上传目录中的实体文件；若种子拥有上传文件，应优先通过管理员接口删除，或在数据库提交后单独核对受管文件。

## 常用命令

应用启动会自动迁移：

```bash
mvn spring-boot:run
```

应用启动是推荐迁移方式，因为迁移链包含 Java `V3`。如果使用 Maven 插件，必须确保插件类路径同样包含项目 Java 迁移，并显式确认目标连接：

```bash
mvn flyway:info
mvn flyway:migrate
```

对非本地环境执行前，必须显式确认连接地址，避免误操作其他数据库。禁止把 `repair` 放入自动启动流程。
