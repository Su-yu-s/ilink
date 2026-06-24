-- ============================================
-- iLink 成果展示表结构
-- 迁移版本: V2
-- 创建日期: 2026-05-11
-- ============================================

-- ------------------------------------------
-- 成果展示表 (asset)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `asset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '成果ID',
    `title` VARCHAR(200) NOT NULL COMMENT '成果标题',
    `description` TEXT COMMENT '成果描述',
    `file_url` VARCHAR(500) DEFAULT NULL COMMENT '文件URL',
    `user_id` BIGINT NOT NULL COMMENT '上传用户ID',
    `view_count` INT NOT NULL DEFAULT 0 COMMENT '浏览次数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成果展示表';
