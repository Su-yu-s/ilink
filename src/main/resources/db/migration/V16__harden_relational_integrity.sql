-- Bring community interaction tables under Flyway and protect their pure child relationships.
-- Other aggregate deletions remain explicit in AdminDataService so file cleanup and audit logic can run.

CREATE TABLE IF NOT EXISTS `community_post_like` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `post_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_user_like` (`post_id`, `user_id`),
    INDEX `idx_like_post_id` (`post_id`),
    INDEX `idx_like_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `community_post_favorite` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `post_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_user_favorite` (`post_id`, `user_id`),
    INDEX `idx_favorite_post_id` (`post_id`),
    INDEX `idx_favorite_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELETE interaction
FROM `community_post_like` interaction
LEFT JOIN `community_post` post ON post.`id` = interaction.`post_id`
LEFT JOIN `user` account ON account.`id` = interaction.`user_id`
WHERE post.`id` IS NULL OR account.`id` IS NULL;

DELETE interaction
FROM `community_post_favorite` interaction
LEFT JOIN `community_post` post ON post.`id` = interaction.`post_id`
LEFT JOIN `user` account ON account.`id` = interaction.`user_id`
WHERE post.`id` IS NULL OR account.`id` IS NULL;

SET @schema_name := DATABASE();

-- Historical databases used INT for user.id, while clean Flyway databases use BIGINT.
-- Foreign-key columns must match the exact integer type, so normalize both child
-- columns to the type found in the current database before adding constraints.
SET @user_id_type := (
    SELECT COLUMN_TYPE FROM information_schema.columns
    WHERE table_schema = @schema_name AND table_name = 'user' AND column_name = 'id'
    LIMIT 1
);
SET @sql := CONCAT('ALTER TABLE `community_post_like` MODIFY `user_id` ', @user_id_type, ' NOT NULL');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := CONCAT('ALTER TABLE `community_post_favorite` MODIFY `user_id` ', @user_id_type, ' NOT NULL');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @constraint_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = @schema_name AND table_name = 'community_post_like'
      AND constraint_name = 'fk_like_post'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE `community_post_like` ADD CONSTRAINT `fk_like_post` FOREIGN KEY (`post_id`) REFERENCES `community_post` (`id`) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @constraint_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = @schema_name AND table_name = 'community_post_like'
      AND constraint_name = 'fk_like_user'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE `community_post_like` ADD CONSTRAINT `fk_like_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @constraint_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = @schema_name AND table_name = 'community_post_favorite'
      AND constraint_name = 'fk_favorite_post'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE `community_post_favorite` ADD CONSTRAINT `fk_favorite_post` FOREIGN KEY (`post_id`) REFERENCES `community_post` (`id`) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @constraint_exists := (
    SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = @schema_name AND table_name = 'community_post_favorite'
      AND constraint_name = 'fk_favorite_user'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE `community_post_favorite` ADD CONSTRAINT `fk_favorite_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
