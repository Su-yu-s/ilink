-- 确保团队空间群聊消息表存在。
-- 部分旧库从 Flyway baseline 启动，早期聊天表迁移不会执行，导致群聊接口查询时报数据库异常。
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `team_id` BIGINT NOT NULL COMMENT '团队ID',
    `sender_id` BIGINT NOT NULL COMMENT '发送者用户ID',
    `message_type` VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT '消息类型',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_chat_message_team_id` (`team_id`),
    INDEX `idx_chat_message_sender_id` (`sender_id`),
    INDEX `idx_chat_message_created_at` (`created_at`),
    INDEX `idx_chat_message_type` (`message_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队聊天消息表';
