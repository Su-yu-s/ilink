-- ============================================================
-- iLink 完整数据库 Schema (v2)
-- 与 Java 实体保持一致，适用于全新部署
-- ============================================================

-- ----------------------------
-- 用户表
-- ----------------------------
CREATE TABLE `user` (
    `id`          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `username`    VARCHAR(50)  UNIQUE,
    `student_id`  BIGINT       DEFAULT NULL COMMENT '学号',
    `phone_number` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `password`    VARCHAR(200) NOT NULL,
    `email`       VARCHAR(100) DEFAULT NULL,
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'STUDENT' COMMENT 'STUDENT / TEACHER / ADMIN',
    `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像 URL',
    `real_name`   VARCHAR(50)  DEFAULT NULL COMMENT '真实姓名',
    `gender`      VARCHAR(10)  DEFAULT NULL COMMENT '性别',
    `grade`       VARCHAR(20)  DEFAULT NULL COMMENT '年级',
    `major`       VARCHAR(100) DEFAULT NULL COMMENT '专业',
    `school`      VARCHAR(100) DEFAULT NULL COMMENT '学校',
    `college`     VARCHAR(100) DEFAULT NULL COMMENT '学院',
    `bio`         TEXT         DEFAULT NULL COMMENT '个人简介（技能、擅长方向、可提供帮助等）',
    `honors`      TEXT         DEFAULT NULL COMMENT 'JSON 数组：个人荣誉（奖学金、荣誉称号、竞赛奖项等）',
    `created_at`  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------
-- 组队需求表
-- ----------------------------
CREATE TABLE `team_demand` (
    `id`               INT          PRIMARY KEY AUTO_INCREMENT,
    `title`            VARCHAR(100) NOT NULL,
    `description`      TEXT         DEFAULT NULL,
    `competition_id`   INT          DEFAULT NULL,
    `required_skills`  VARCHAR(500) DEFAULT NULL,
    `required_member_count` INT     DEFAULT NULL COMMENT '所需队员人数',
    `deadline`         DATETIME     DEFAULT NULL COMMENT '招募截止时间',
    `status`           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    `creator_id`       INT          NOT NULL,
    `created_at`       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`creator_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------
-- 团队申请表
-- ----------------------------
CREATE TABLE `team_application` (
    `id`         INT                        PRIMARY KEY AUTO_INCREMENT,
    `team_id`    INT                        NOT NULL,
    `user_id`    INT                        NOT NULL,
    `status`     ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
    `message`    TEXT                       DEFAULT NULL,
    `created_at` TIMESTAMP                  DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`team_id`) REFERENCES `team_demand`(`id`),
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------
-- 导师申请表
-- ----------------------------
CREATE TABLE `teacher_application` (
    `id`                 INT                        PRIMARY KEY AUTO_INCREMENT,
    `user_id`            INT                        NOT NULL,
    `introduction`       TEXT                       DEFAULT NULL,
    `research_direction` VARCHAR(200)               DEFAULT NULL,
    `projects`           TEXT                       DEFAULT NULL,
    `status`             ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
    `created_at`         TIMESTAMP                  DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------
-- 项目申请表
-- ----------------------------
CREATE TABLE `project_application` (
    `id`         INT                        PRIMARY KEY AUTO_INCREMENT,
    `teacher_id` INT                        NOT NULL,
    `user_id`    INT                        NOT NULL,
    `status`     ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
    `message`    TEXT                       DEFAULT NULL,
    `created_at` TIMESTAMP                  DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`teacher_id`) REFERENCES `teacher_application`(`id`),
    FOREIGN KEY (`user_id`)    REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------
-- 成果表
-- ----------------------------
CREATE TABLE `asset` (
    `id`         INT          PRIMARY KEY AUTO_INCREMENT,
    `title`      VARCHAR(200) NOT NULL,
    `description` TEXT        DEFAULT NULL,
    `file_url`   VARCHAR(500) DEFAULT NULL,
    `user_id`    INT          NOT NULL,
    `view_count` INT          NOT NULL DEFAULT 0,
    `created_at` TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------
-- 消息表
-- ----------------------------
CREATE TABLE `message` (
    `id`         INT          PRIMARY KEY AUTO_INCREMENT,
    `sender_id`  INT          NOT NULL,
    `receiver_id` INT         NOT NULL,
    `content`    TEXT         NOT NULL,
    `room_id`    VARCHAR(50)  DEFAULT NULL,
    `is_read`    BOOLEAN      NOT NULL DEFAULT FALSE,
    `created_at` TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`sender_id`)   REFERENCES `user`(`id`),
    FOREIGN KEY (`receiver_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------
-- 交流社区帖子表
-- ----------------------------
CREATE TABLE `community_post` (
    `id`             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `author_id`      BIGINT       NOT NULL,
    `title`          VARCHAR(200) NOT NULL,
    `content`        TEXT         NOT NULL,
    `category`       VARCHAR(32)  NOT NULL COMMENT 'general / tech / competition / resource',
    `view_count`     INT          NOT NULL DEFAULT 0,
    `like_count`     INT          NOT NULL DEFAULT 0,
    `favorite_count` INT          NOT NULL DEFAULT 0,
    `attachments`    TEXT         DEFAULT NULL COMMENT 'JSON：[{ "name": "文件名", "url": "/uploads/..." }]',
    `created_at`     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY `idx_category_created` (`category`, `created_at`),
    KEY `idx_author` (`author_id`),
    FOREIGN KEY (`author_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ----------------------------
-- 交流社区评论表
-- ----------------------------
CREATE TABLE `community_comment` (
    `id`         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `post_id`    BIGINT       NOT NULL,
    `user_id`    BIGINT       NOT NULL,
    `content`    VARCHAR(2000) NOT NULL,
    `created_at` TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_post_created` (`post_id`, `created_at`),
    KEY `idx_user` (`user_id`),
    FOREIGN KEY (`post_id`) REFERENCES `community_post`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
