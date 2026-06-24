-- 兼容已存在 Flyway 历史但缺少 user_skills 表的本地数据库
CREATE TABLE IF NOT EXISTS `user_skills` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '技能记录ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `skill_name` VARCHAR(100) NOT NULL COMMENT '技能名称',
    `skill_level` TINYINT NOT NULL DEFAULT 3 COMMENT '技能等级',
    `skill_category` VARCHAR(50) DEFAULT NULL COMMENT '技能分类',
    `certification` VARCHAR(200) DEFAULT NULL COMMENT '资质认证',
    `years_experience` INT DEFAULT 0 COMMENT '经验年限',
    `portfolio_url` VARCHAR(500) DEFAULT NULL COMMENT '作品集链接',
    `is_verified` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已认证',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_skill` (`user_id`, `skill_name`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_skill_name` (`skill_name`),
    INDEX `idx_skill_category` (`skill_category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户技能表';
