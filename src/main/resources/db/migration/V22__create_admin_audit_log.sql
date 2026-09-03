CREATE TABLE IF NOT EXISTS `admin_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `admin_user_id` BIGINT NOT NULL,
    `action` VARCHAR(64) NOT NULL,
    `target_type` VARCHAR(64) NOT NULL,
    `target_id` VARCHAR(128) NOT NULL DEFAULT '',
    `details` VARCHAR(1000) NOT NULL DEFAULT '',
    `ip_address` VARCHAR(64) NOT NULL DEFAULT '',
    `user_agent` VARCHAR(255) NOT NULL DEFAULT '',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_admin_audit_admin_time` (`admin_user_id`, `created_at`),
    KEY `idx_admin_audit_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
