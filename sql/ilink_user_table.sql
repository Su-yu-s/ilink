-- iLink 用户表（与实体 cn.ilink.entity.User / MyBatis-Plus 下划线映射一致）
-- 新建库：CREATE DATABASE ilink DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(50) NOT NULL,
    `student_id` BIGINT NULL DEFAULT NULL,
    `phone_number` VARCHAR(20) NULL DEFAULT NULL,
    `password` VARCHAR(120) NOT NULL COMMENT 'BCrypt 哈希',
    `email` VARCHAR(100) NULL DEFAULT NULL,
    `role` VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    `avatar` VARCHAR(255) NULL DEFAULT NULL,
    `real_name` VARCHAR(50) NULL DEFAULT NULL,
    `gender` VARCHAR(10) NULL DEFAULT NULL,
    `grade` VARCHAR(32) NULL DEFAULT NULL COMMENT '年级',
    `major` VARCHAR(64) NULL DEFAULT NULL COMMENT '专业',
    `school` VARCHAR(128) NULL DEFAULT NULL COMMENT '学校',
    `college` VARCHAR(128) NULL DEFAULT NULL COMMENT '学院',
    `bio` VARCHAR(300) NULL DEFAULT NULL COMMENT '个人简介',
    `honors` TEXT NULL DEFAULT NULL COMMENT 'JSON: 个人荣誉',
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_phone` (`phone_number`),
    KEY `idx_student_id` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 若你已有旧表，可手工执行以下补充字段（列已存在会报错，按需注释）：
-- ALTER TABLE `user` ADD COLUMN `student_id` BIGINT NULL AFTER `username`;
-- ALTER TABLE `user` ADD COLUMN `phone_number` VARCHAR(20) NULL AFTER `student_id`;
-- ALTER TABLE `user` ADD COLUMN `gender` VARCHAR(10) NULL AFTER `real_name`;
-- ALTER TABLE `user` ADD COLUMN `grade` VARCHAR(32) NULL AFTER `gender`;
-- ALTER TABLE `user` ADD COLUMN `major` VARCHAR(64) NULL AFTER `grade`;
-- ALTER TABLE `user` ADD COLUMN `school` VARCHAR(128) NULL AFTER `major`;
-- ALTER TABLE `user` ADD COLUMN `college` VARCHAR(128) NULL AFTER `school`;
-- ALTER TABLE `user` ADD COLUMN `bio` VARCHAR(300) NULL AFTER `college`;
-- ALTER TABLE `user` ADD COLUMN `honors` TEXT NULL AFTER `gender`;
-- ALTER TABLE `user` MODIFY COLUMN `password` VARCHAR(120) NOT NULL;
