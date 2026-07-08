# SQL 脚本目录说明

此目录包含一次性手工执行的 SQL 脚本，**不纳入 Flyway 自动迁移**。

## 与 db/migration 的关系

- `src/main/resources/db/migration/` — Flyway 自动执行的版本化迁移脚本（V1~V11）
- `sql/` — 手工执行的辅助脚本（种子数据、手动修复、兼容性脚本等）

## 文件清单

| 文件 | 用途 | 执行方式 |
|------|------|---------|
| `schema.sql` | 完整建表语句（历史参考） | 手工 |
| `ilink_user_table.sql` | 用户表单独建表 | 手工 |
| `community_post.sql` | 社区帖子/评论表 | 手工 |
| `community_post_likes_favorites.sql` | 点赞/收藏表（最终版，无外键） | 手工 |
| `community_post_attachments.sql` | 帖子附件字段升级 | 手工 |
| `community_blog_upgrade.sql` | 社区博客升级脚本 | 手工 |
| `notification_table.sql` | 通知表 | 手工 |
| `team_application.sql` | 组队申请表 | 手工 |
| `project_application.sql` | 项目申请表 | 手工 |
| `recommendation_log_table.sql` | 推荐日志表 | 手工 |
| `user_honors.sql` | 用户荣誉字段 | 手工 |
| `schema-v2-migration.sql` | V2 版 schema 迁移参考 | 手工 |
| `idempotent_unique_constraints.sql` | 幂等唯一约束修补 | 手工 |
| `demo_public_profile_seed.sql` | 演示用种子数据（密码: demo1234） | 手工 |

## 注意事项

- 新环境部署应优先使用 Flyway 迁移（`db/migration/`）
- 此目录脚本仅用于特殊情况（手动修复、演示数据注入等）
- 执行前请确认目标数据库状态，避免与 Flyway 迁移冲突
