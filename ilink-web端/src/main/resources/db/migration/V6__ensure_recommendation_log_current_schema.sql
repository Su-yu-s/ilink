-- 确保推荐日志表与当前 RecommendationLog 实体保持一致。
CREATE TABLE IF NOT EXISTS `recommendation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '推荐记录ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '接收推荐的用户ID',
    `recommended_user_id` BIGINT DEFAULT NULL COMMENT '被推荐的用户ID',
    `recommended_team_id` BIGINT DEFAULT NULL COMMENT '被推荐的团队ID',
    `match_score` DOUBLE DEFAULT NULL COMMENT '匹配分数',
    `match_reasons` VARCHAR(500) DEFAULT NULL COMMENT '匹配原因',
    `action` VARCHAR(50) DEFAULT NULL COMMENT '用户操作：VIEWED/ACCEPTED/DISMISSED',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能推荐日志表';

SET @schema_name := DATABASE();

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'user_id'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE `recommendation_log` ADD COLUMN `user_id` BIGINT DEFAULT NULL COMMENT ''接收推荐的用户ID'' AFTER `id`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'recommended_user_id'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE `recommendation_log` ADD COLUMN `recommended_user_id` BIGINT DEFAULT NULL COMMENT ''被推荐的用户ID'' AFTER `user_id`',
    'ALTER TABLE `recommendation_log` MODIFY COLUMN `recommended_user_id` BIGINT DEFAULT NULL COMMENT ''被推荐的用户ID'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'recommended_team_id'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE `recommendation_log` ADD COLUMN `recommended_team_id` BIGINT DEFAULT NULL COMMENT ''被推荐的团队ID'' AFTER `recommended_user_id`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'match_score'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE `recommendation_log` ADD COLUMN `match_score` DOUBLE DEFAULT NULL COMMENT ''匹配分数'' AFTER `recommended_team_id`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'match_reasons'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE `recommendation_log` ADD COLUMN `match_reasons` VARCHAR(500) DEFAULT NULL COMMENT ''匹配原因'' AFTER `match_score`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'action'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE `recommendation_log` ADD COLUMN `action` VARCHAR(50) DEFAULT NULL COMMENT ''用户操作：VIEWED/ACCEPTED/DISMISSED'' AFTER `match_reasons`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'created_at'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE `recommendation_log` ADD COLUMN `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''创建时间'' AFTER `action`',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 兼容早期 V1 迁移创建的旧列，避免 NOT NULL 旧列阻塞当前实体写入。
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'team_id'
);
SET @sql := IF(@column_exists = 1,
    'ALTER TABLE `recommendation_log` MODIFY COLUMN `team_id` BIGINT DEFAULT NULL COMMENT ''旧版团队ID''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'recommendation_type'
);
SET @sql := IF(@column_exists = 1,
    'ALTER TABLE `recommendation_log` MODIFY COLUMN `recommendation_type` VARCHAR(50) DEFAULT NULL COMMENT ''旧版推荐类型''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'team_id'
);
SET @sql := IF(@column_exists = 1,
    'UPDATE `recommendation_log` SET `recommended_team_id` = COALESCE(`recommended_team_id`, `team_id`) WHERE `recommended_team_id` IS NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND column_name = 'reason'
);
SET @sql := IF(@column_exists = 1,
    'UPDATE `recommendation_log` SET `match_reasons` = COALESCE(`match_reasons`, `reason`) WHERE `match_reasons` IS NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND index_name = 'idx_rec_log_user_id'
);
SET @sql := IF(@index_exists = 0,
    'CREATE INDEX `idx_rec_log_user_id` ON `recommendation_log` (`user_id`)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND index_name = 'idx_rec_log_team_id'
);
SET @sql := IF(@index_exists = 0,
    'CREATE INDEX `idx_rec_log_team_id` ON `recommendation_log` (`recommended_team_id`)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND index_name = 'idx_rec_log_action'
);
SET @sql := IF(@index_exists = 0,
    'CREATE INDEX `idx_rec_log_action` ON `recommendation_log` (`action`)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = @schema_name AND table_name = 'recommendation_log' AND index_name = 'idx_rec_log_created_at'
);
SET @sql := IF(@index_exists = 0,
    'CREATE INDEX `idx_rec_log_created_at` ON `recommendation_log` (`created_at`)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
