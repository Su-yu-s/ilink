ALTER TABLE `user`
    ADD COLUMN `last_active_at` DATETIME DEFAULT NULL COMMENT '最近活跃时间' AFTER `honors`,
    ADD INDEX `idx_user_last_active_at` (`last_active_at`);
