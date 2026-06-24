-- 社区文章：点赞 / 收藏（幂等补丁，无存储过程）
-- 用途：避免 Unknown table 'community_post_like' / 'community_post_favorite'。
-- 你可反复执行。
--
-- 执行：
--   mysql -u root -p1234 ilink --default-character-set=utf8mb4 < sql/community_post_likes_favorites_idempotent_no_proc.sql

SET NAMES utf8mb4;

-- 1) like_count 列补齐（若不存在）
SET @need_like := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'community_post'
    AND column_name = 'like_count'
);
SET @sql_like := IF(
  @need_like = 0,
  'ALTER TABLE `community_post` ADD COLUMN `like_count` INT NOT NULL DEFAULT 0 COMMENT ''点赞数'' AFTER `view_count`',
  'SELECT 1'
);
PREPARE stmt_like FROM @sql_like;
EXECUTE stmt_like;
DEALLOCATE PREPARE stmt_like;

-- 2) favorite_count 列补齐（若不存在）
SET @need_fav := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'community_post'
    AND column_name = 'favorite_count'
);
SET @sql_fav := IF(
  @need_fav = 0,
  'ALTER TABLE `community_post` ADD COLUMN `favorite_count` INT NOT NULL DEFAULT 0 COMMENT ''收藏数'' AFTER `like_count`',
  'SELECT 1'
);
PREPARE stmt_fav FROM @sql_fav;
EXECUTE stmt_fav;
DEALLOCATE PREPARE stmt_fav;

-- 3) 点赞表
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

-- 4) 收藏表
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

