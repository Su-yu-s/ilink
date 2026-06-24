-- 交流社区帖子 + 评论（新建库一次性执行）
CREATE TABLE IF NOT EXISTS `community_post` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `author_id` INT NOT NULL,
    `category` VARCHAR(32) NOT NULL COMMENT 'general/tech/competition/resource',
    `title` VARCHAR(200) NOT NULL,
    `content` TEXT NOT NULL,
    `attachments` TEXT NULL COMMENT 'JSON: attachments list',
    `view_count` INT NOT NULL DEFAULT 0,
    `like_count` INT NOT NULL DEFAULT 0,
    `favorite_count` INT NOT NULL DEFAULT 0,
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_category_created` (`category`, `created_at`),
    KEY `idx_author` (`author_id`),
    CONSTRAINT `fk_community_post_author` FOREIGN KEY (`author_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `community_comment` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `post_id` INT NOT NULL,
    `user_id` INT NOT NULL,
    `content` VARCHAR(2000) NOT NULL,
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_post_created` (`post_id`, `created_at`),
    KEY `idx_user` (`user_id`),
    CONSTRAINT `fk_cc_post` FOREIGN KEY (`post_id`) REFERENCES `community_post` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_cc_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
