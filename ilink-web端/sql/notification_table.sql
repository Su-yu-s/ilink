CREATE TABLE IF NOT EXISTS `notification` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `type` VARCHAR(32) NOT NULL COMMENT 'TEAM_JOIN/TEAM_APPROVE/TEACHER_APPLY/PROJECT_APPLY/LIKE/COMMENT',
    `title` VARCHAR(200) NOT NULL,
    `content` VARCHAR(500),
    `is_read` BOOLEAN NOT NULL DEFAULT FALSE,
    `related_id` BIGINT COMMENT 'related business entity ID',
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_unread` (`user_id`, `is_read`),
    INDEX `idx_user_created` (`user_id`, `created_at`),
    CONSTRAINT `fk_notification_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
