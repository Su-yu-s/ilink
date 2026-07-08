-- ============================================
-- iLink 核心业务表初始化（补充迁移）
-- 迁移版本: V12
-- 创建日期: 2026-07-08
-- 说明: 为核心表补充 Flyway 迁移，使用 IF NOT EXISTS 确保幂等
-- ============================================

-- ------------------------------------------
-- 1. 用户表 (user)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(100) NOT NULL COMMENT '用户名',
    `student_id` BIGINT DEFAULT NULL COMMENT '学号/工号',
    `phone_number` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `password` VARCHAR(255) NOT NULL COMMENT '密码（BCrypt）',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `role` VARCHAR(20) NOT NULL DEFAULT 'STUDENT' COMMENT '角色: STUDENT/TEACHER/ADMIN',
    `avatar` VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `real_name` VARCHAR(100) DEFAULT NULL COMMENT '真实姓名',
    `gender` VARCHAR(10) DEFAULT NULL COMMENT '性别: MALE/FEMALE/OTHER',
    `grade` VARCHAR(20) DEFAULT NULL COMMENT '年级',
    `major` VARCHAR(100) DEFAULT NULL COMMENT '专业',
    `school` VARCHAR(100) DEFAULT NULL COMMENT '学校',
    `college` VARCHAR(100) DEFAULT NULL COMMENT '学院',
    `bio` TEXT DEFAULT NULL COMMENT '个人简介',
    `honors` TEXT DEFAULT NULL COMMENT '个人荣誉(JSON数组)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    INDEX `idx_phone_number` (`phone_number`),
    INDEX `idx_student_id` (`student_id`),
    INDEX `idx_email` (`email`),
    INDEX `idx_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ------------------------------------------
-- 2. 组队需求表 (team_demand)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `team_demand` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '需求ID',
    `title` VARCHAR(200) NOT NULL COMMENT '标题',
    `description` TEXT COMMENT '内容描述',
    `competition_id` INT DEFAULT NULL COMMENT '竞赛ID',
    `required_skills` VARCHAR(500) DEFAULT NULL COMMENT '所需技能',
    `required_member_count` INT DEFAULT NULL COMMENT '所需队员人数',
    `deadline` DATETIME DEFAULT NULL COMMENT '招募截止时间',
    `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN/TEAMING/CLOSED',
    `creator_id` BIGINT NOT NULL COMMENT '创建者用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_creator_id` (`creator_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_competition_id` (`competition_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组队需求表';

-- ------------------------------------------
-- 3. 组队申请表 (team_application)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `team_application` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '申请ID',
    `team_id` BIGINT NOT NULL COMMENT '团队ID',
    `user_id` BIGINT NOT NULL COMMENT '申请人用户ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED',
    `message` TEXT COMMENT '申请留言',
    `reviewer_note` VARCHAR(500) DEFAULT NULL COMMENT '审批备注/拒绝理由',
    `reviewed_at` DATETIME DEFAULT NULL COMMENT '审批时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_team_user` (`team_id`, `user_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_team_id` (`team_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组队申请表';

-- ------------------------------------------
-- 4. 社区帖子表 (community_post)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `community_post` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '帖子ID',
    `author_id` BIGINT NOT NULL COMMENT '作者用户ID',
    `category` VARCHAR(50) NOT NULL DEFAULT 'general' COMMENT '分区: general/tech/competition/resource',
    `title` VARCHAR(200) NOT NULL COMMENT '标题',
    `content` TEXT COMMENT '正文内容',
    `attachments` TEXT DEFAULT NULL COMMENT '附件(JSON数组)',
    `view_count` INT NOT NULL DEFAULT 0 COMMENT '阅读量',
    `like_count` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
    `favorite_count` INT NOT NULL DEFAULT 0 COMMENT '收藏数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (`id`),
    INDEX `idx_author_id` (`author_id`),
    INDEX `idx_category` (`category`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区帖子表';

-- ------------------------------------------
-- 5. 社区评论表 (community_comment)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `community_comment` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `post_id` BIGINT NOT NULL COMMENT '帖子ID',
    `user_id` BIGINT NOT NULL COMMENT '评论用户ID',
    `content` TEXT NOT NULL COMMENT '评论内容',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    PRIMARY KEY (`id`),
    INDEX `idx_post_id` (`post_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区评论表';

-- ------------------------------------------
-- 6. 项目申请表 (project_application)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `project_application` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '申请ID',
    `teacher_id` BIGINT NOT NULL COMMENT '导师ID',
    `user_id` BIGINT NOT NULL COMMENT '申请人用户ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED',
    `message` TEXT COMMENT '申请留言',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (`id`),
    INDEX `idx_teacher_id` (`teacher_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目申请表';

-- ------------------------------------------
-- 7. 导师申请表 (teacher_application)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `teacher_application` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '申请ID',
    `user_id` BIGINT NOT NULL COMMENT '申请人用户ID',
    `introduction` TEXT COMMENT '个人简介',
    `research_direction` VARCHAR(200) DEFAULT NULL COMMENT '研究方向',
    `projects` TEXT COMMENT '项目经历',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导师申请表';
