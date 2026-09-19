-- 意见反馈：用户提交的反馈内容与专属详情页令牌
CREATE TABLE IF NOT EXISTS `feedback` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `access_token_hash` VARCHAR(64) NOT NULL COMMENT '详情页访问令牌的 SHA-256（base64url），明文不落库',
    `type` VARCHAR(20) NOT NULL COMMENT '反馈类型：BUG / FEATURE / UX / OTHER',
    `description` VARCHAR(500) NOT NULL COMMENT '详细描述',
    `contact` VARCHAR(120) DEFAULT NULL COMMENT '联系方式，选填',
    `image_urls` TEXT DEFAULT NULL COMMENT '截图地址 JSON 数组，最多 5 项',
    `submitter_id` BIGINT DEFAULT NULL COMMENT '提交者用户 ID，匿名为 NULL',
    `client_ip` VARCHAR(64) DEFAULT NULL COMMENT '提交来源 IP，用于限流与审计',
    `user_agent` VARCHAR(255) DEFAULT NULL COMMENT '提交时浏览器 UA',
    `handled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '开发者是否已处理',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_feedback_token` (`access_token_hash`),
    KEY `idx_feedback_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='意见反馈';
