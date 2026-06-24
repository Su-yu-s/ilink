-- 智能推荐日志表
CREATE TABLE IF NOT EXISTS `recommendation_log` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '推荐记录ID',
    `user_id` BIGINT COMMENT '推荐发起用户ID',
    `recommended_user_id` BIGINT COMMENT '被推荐用户ID',
    `recommended_team_id` BIGINT COMMENT '被推荐团队ID',
    `match_score` DOUBLE COMMENT '匹配分数(0-100)',
    `match_reasons` VARCHAR(500) COMMENT '匹配原因(JSON数组)',
    `action` VARCHAR(50) COMMENT '用户操作:VIEWED/ACCEPTED/DISMISSED',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (`user_id`),
    INDEX idx_recommended_user_id (`recommended_user_id`),
    INDEX idx_recommended_team_id (`recommended_team_id`),
    INDEX idx_action (`action`),
    INDEX idx_created_at (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能推荐日志表';
