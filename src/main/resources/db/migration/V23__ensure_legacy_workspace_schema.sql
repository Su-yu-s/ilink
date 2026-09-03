-- 兼容从既有业务库 baseline 的安装：V1 可能被标记为基线而未实际执行。
-- V10 会补建任务评论表，但旧定义缺少当前实体使用的 mentions / attachments 字段。

SET @schema_name = DATABASE();

SET @has_mentions = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'task_comments' AND COLUMN_NAME = 'mentions'
);
SET @sql = IF(@has_mentions = 0,
    'ALTER TABLE `task_comments` ADD COLUMN `mentions` VARCHAR(500) DEFAULT NULL COMMENT ''@提及的用户ID列表(JSON格式)'' AFTER `comment_type`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_attachments = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'task_comments' AND COLUMN_NAME = 'attachments'
);
SET @sql = IF(@has_attachments = 0,
    'ALTER TABLE `task_comments` ADD COLUMN `attachments` VARCHAR(1000) DEFAULT NULL COMMENT ''附件URL列表(JSON格式)'' AFTER `mentions`',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `project_milestones` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '里程碑ID',
    `team_id` BIGINT NOT NULL COMMENT '团队ID',
    `milestone_name` VARCHAR(200) NOT NULL COMMENT '里程碑名称',
    `milestone_description` TEXT COMMENT '里程碑描述',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    `due_date` DATETIME NOT NULL COMMENT '计划完成日期',
    `completed_date` DATETIME DEFAULT NULL COMMENT '实际完成日期',
    `completion_rate` TINYINT NOT NULL DEFAULT 0 COMMENT '完成进度(0-100%)',
    `deliverables` TEXT COMMENT '交付物(JSON格式)',
    `created_by` BIGINT NOT NULL COMMENT '创建人用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_project_milestone_team_id` (`team_id`),
    INDEX `idx_project_milestone_status` (`status`),
    INDEX `idx_project_milestone_due_date` (`due_date`),
    INDEX `idx_project_milestone_created_by` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目里程碑表';
