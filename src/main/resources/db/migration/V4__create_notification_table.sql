-- 创建通知表，供全站顶部通知铃铛与通知列表使用
CREATE TABLE IF NOT EXISTS `notification` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    `user_id` BIGINT NOT NULL COMMENT '接收用户ID',
    `type` VARCHAR(50) NOT NULL DEFAULT 'SYSTEM' COMMENT '通知类型',
    `title` VARCHAR(200) NOT NULL COMMENT '通知标题',
    `content` VARCHAR(1000) DEFAULT NULL COMMENT '通知内容',
    `is_read` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已读',
    `related_id` BIGINT DEFAULT NULL COMMENT '关联业务ID',
    `related_type` VARCHAR(50) DEFAULT NULL COMMENT '关联业务类型',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_notification_user_read` (`user_id`, `is_read`),
    INDEX `idx_notification_user_created` (`user_id`, `created_at`),
    INDEX `idx_notification_related` (`related_type`, `related_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户通知表';
