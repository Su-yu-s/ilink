-- 社区文章：点赞 / 收藏（幂等补丁）
-- 用途：解决 Unknown table 'ilink.community_post_like' / 'community_post_favorite'。
-- 你可反复执行，不会因为“已存在列/表”而终止。
--
-- 执行方式（示例）：
--   mysql -u root -p ilink < sql/community_post_likes_favorites_idempotent.sql

SET NAMES utf8mb4;

-- 1) 点赞/收藏计数字段：若不存在则补列
DELIMITER //
DROP PROCEDURE IF EXISTS add_column_if_missing//
CREATE PROCEDURE add_column_if_missing(
    IN in_table VARCHAR(64),
    IN in_column VARCHAR(64),
    IN in_column_def TEXT,
    IN in_after_column VARCHAR(64)
)
BEGIN
    DECLARE cnt INT DEFAULT 0;
    SELECT COUNT(*) INTO cnt
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = in_table
      AND column_name = in_column;

    IF cnt = 0 THEN
        SET @sql := CONCAT(
            'ALTER TABLE `', in_table, '` ADD COLUMN `', in_column, '` ',
            in_column_def,
            ' AFTER `', in_after_column, '`'
        );
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//
DELIMITER ;

CALL add_column_if_missing('community_post', 'like_count',
    'INT NOT NULL DEFAULT 0 COMMENT ''点赞数''', 'view_count');
CALL add_column_if_missing('community_post', 'favorite_count',
    'INT NOT NULL DEFAULT 0 COMMENT ''收藏数''', 'like_count');

DROP PROCEDURE IF EXISTS add_column_if_missing;

-- 2) 点赞表
CREATE TABLE IF NOT EXISTS `community_post_like` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `post_id` INT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_user_like` (`post_id`, `user_id`),
    KEY `idx_cpl_user` (`user_id`),
    CONSTRAINT `fk_cpl_post` FOREIGN KEY (`post_id`) REFERENCES `community_post` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cpl_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 收藏表
CREATE TABLE IF NOT EXISTS `community_post_favorite` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `post_id` INT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_post_user_fav` (`post_id`, `user_id`),
    KEY `idx_cpf_user` (`user_id`),
    CONSTRAINT `fk_cpf_post` FOREIGN KEY (`post_id`) REFERENCES `community_post` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cpf_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

