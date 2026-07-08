-- 为 notification 表添加 sender_id 字段及联合索引
ALTER TABLE `notification` ADD COLUMN `sender_id` BIGINT DEFAULT NULL COMMENT '发送通知的用户ID' AFTER `user_id`;
ALTER TABLE `notification` ADD INDEX `idx_notification_receiver_read_created` (`user_id`, `is_read`, `created_at` DESC);
