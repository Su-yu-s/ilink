-- 交流社区：帖子表 + 评论表 + 阅读量
-- 适用：① 从未建过 community_post（整库执行即可）
--       ② 若 community_post 已存在但缺少 view_count，见文件末尾「仅补列」

-- 与 user.id 类型一致（int），否则外键 ERROR 1215
CREATE TABLE IF NOT EXISTS `community_post` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `author_id` INT NOT NULL,
    `category` VARCHAR(32) NOT NULL COMMENT 'general/tech/competition/resource',
    `title` VARCHAR(200) NOT NULL,
    `content` TEXT NOT NULL,
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

-- ---------------------------------------------------------------------------
-- 仅当：community_post 早就存在、且没有 view_count 列时再执行下面一句
-- （若报 Duplicate column 说明已加过，可忽略）
-- ALTER TABLE `community_post`
--     ADD COLUMN `view_count` INT NOT NULL DEFAULT 0 AFTER `content`;

-- 正文附件（若未执行过 sql/community_post_attachments.sql）
-- ALTER TABLE `community_post`
--     ADD COLUMN `attachments` TEXT NULL COMMENT 'JSON list: name + url' AFTER `content`;
