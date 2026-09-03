-- AI 助手调用记录（用于每日配额控制与用量审计）
CREATE TABLE IF NOT EXISTS `ai_usage_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL COMMENT '调用用户',
    `team_id` BIGINT DEFAULT NULL COMMENT '所属团队（团队级功能时记录）',
    `action` VARCHAR(50) NOT NULL COMMENT '动作：TASK_BREAKDOWN / COMPETITION_QA',
    `prompt_tokens` INT DEFAULT NULL COMMENT '输入 token 数',
    `completion_tokens` INT DEFAULT NULL COMMENT '输出 token 数',
    `success` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否成功',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY `idx_ai_usage_user_time` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手调用记录';
