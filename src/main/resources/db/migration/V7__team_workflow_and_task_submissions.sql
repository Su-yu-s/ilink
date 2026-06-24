ALTER TABLE `team_demand`
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN `required_member_count` INT DEFAULT NULL COMMENT '所需队员人数' AFTER `required_skills`,
    ADD COLUMN `deadline` DATETIME DEFAULT NULL COMMENT '招募截止时间' AFTER `required_member_count`,
    ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER `created_at`;

CREATE TABLE IF NOT EXISTS `team_task_submissions` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务提交记录ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `submitter_id` BIGINT NOT NULL COMMENT '提交人用户ID',
    `content` TEXT DEFAULT NULL COMMENT '提交说明',
    `attachments` TEXT DEFAULT NULL COMMENT '附件列表JSON',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    PRIMARY KEY (`id`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_submitter_id` (`submitter_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务提交记录表';
