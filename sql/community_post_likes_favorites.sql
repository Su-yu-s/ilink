-- 社区文章：点赞、收藏（升级脚本，与启动时自动补全逻辑一致）
-- 若表或列已存在则对应语句会报错，可忽略

SET NAMES utf8mb4;

ALTER TABLE `community_post`
    ADD COLUMN `like_count` INT NOT NULL DEFAULT 0 COMMENT '点赞数' AFTER `view_count`;

ALTER TABLE `community_post`
    ADD COLUMN `favorite_count` INT NOT NULL DEFAULT 0 COMMENT '收藏数' AFTER `like_count`;

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
