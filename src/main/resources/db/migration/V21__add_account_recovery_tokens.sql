CREATE TABLE IF NOT EXISTS `password_reset_token` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `token_hash` VARCHAR(64) NOT NULL,
    `expires_at` DATETIME NOT NULL,
    `used_at` DATETIME NULL,
    `request_ip` VARCHAR(64) NOT NULL DEFAULT '',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_password_reset_hash` (`token_hash`),
    KEY `idx_password_reset_user` (`user_id`),
    KEY `idx_password_reset_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `remember_me_token` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `selector` VARCHAR(64) NOT NULL,
    `validator_hash` VARCHAR(64) NOT NULL,
    `expires_at` DATETIME NOT NULL,
    `last_used_at` DATETIME NOT NULL,
    `user_agent` VARCHAR(255) NOT NULL DEFAULT '',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_remember_selector` (`selector`),
    KEY `idx_remember_user` (`user_id`),
    KEY `idx_remember_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @schema_name := DATABASE();
SET @user_id_type := (
    SELECT COLUMN_TYPE FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'user' AND column_name = 'id'
    LIMIT 1
);

SET @sql := CONCAT('ALTER TABLE `password_reset_token` MODIFY `user_id` ', @user_id_type, ' NOT NULL');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := CONCAT('ALTER TABLE `remember_me_token` MODIFY `user_id` ', @user_id_type, ' NOT NULL');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @constraint_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = @schema_name AND table_name = 'password_reset_token'
      AND constraint_name = 'fk_password_reset_user'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE `password_reset_token` ADD CONSTRAINT `fk_password_reset_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @constraint_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = @schema_name AND table_name = 'remember_me_token'
      AND constraint_name = 'fk_remember_user'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE `remember_me_token` ADD CONSTRAINT `fk_remember_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
