CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `username` VARCHAR(50) UNIQUE,
    `student_id` BIGINT DEFAULT NULL,
    `phone_number` VARCHAR(20) DEFAULT NULL,
    `password` VARCHAR(200) NOT NULL,
    `email` VARCHAR(100) DEFAULT NULL,
    `role` VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
    `avatar` VARCHAR(500) DEFAULT NULL,
    `real_name` VARCHAR(50) DEFAULT NULL,
    `gender` VARCHAR(10) DEFAULT NULL,
    `grade` VARCHAR(20) DEFAULT NULL,
    `major` VARCHAR(100) DEFAULT NULL,
    `school` VARCHAR(100) DEFAULT NULL,
    `college` VARCHAR(100) DEFAULT NULL,
    `bio` TEXT DEFAULT NULL,
    `honors` TEXT DEFAULT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `user_skills` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `skill_name` VARCHAR(64) NOT NULL,
    `skill_level` INT DEFAULT NULL,
    `skill_category` VARCHAR(64) DEFAULT NULL,
    `certification` VARCHAR(255) DEFAULT NULL,
    `years_experience` INT DEFAULT NULL,
    `portfolio_url` VARCHAR(500) DEFAULT NULL,
    `is_verified` BOOLEAN DEFAULT FALSE,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_skills_user` (`user_id`)
);

CREATE TABLE IF NOT EXISTS `notification` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `type` VARCHAR(50) NOT NULL DEFAULT 'SYSTEM',
    `title` VARCHAR(200) NOT NULL,
    `content` VARCHAR(1000) DEFAULT NULL,
    `is_read` TINYINT NOT NULL DEFAULT 0,
    `related_id` BIGINT DEFAULT NULL,
    `related_type` VARCHAR(50) DEFAULT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_notification_user_read` (`user_id`, `is_read`),
    INDEX `idx_notification_user_created` (`user_id`, `created_at`)
);
