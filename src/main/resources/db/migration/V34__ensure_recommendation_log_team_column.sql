-- 兼容从历史手工脚本 baseline 的安装：V6 可能被基线跳过而未真正执行，
-- 导致 recommendation_log 缺少 recommended_team_id 列。管理端删除队伍时会按该列清理推荐日志，
-- 缺列会让删除队伍整体失败（Unknown column 'recommended_team_id'）。
-- 这里只做存在性补列，不修改 V6（避免 Flyway 校验和冲突）。

SET @schema_name = DATABASE();

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'recommended_team_id'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE `recommendation_log` ADD COLUMN `recommended_team_id` BIGINT DEFAULT NULL COMMENT ''被推荐的团队ID''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
