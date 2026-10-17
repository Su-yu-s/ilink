-- 注册邮箱验证码。
-- 发码与注册是两次独立请求，故落库而不是放内存：进程重启（部署、崩溃）后用户手里的码仍然有效，
-- 将来多实例部署也不必换存储。清理策略与 password_reset_token 保持一致——不做定时任务，靠 expires_at 过滤。
CREATE TABLE IF NOT EXISTS `email_verification_code` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `email`         VARCHAR(100) NOT NULL COMMENT '收件邮箱',
    `code_hash`     VARCHAR(64)  NOT NULL COMMENT '验证码的 SHA-256（不存明文）',
    `purpose`       VARCHAR(32)  NOT NULL DEFAULT 'REGISTER' COMMENT '用途，当前仅注册',
    `expires_at`    DATETIME     NOT NULL,
    `used_at`       DATETIME     NULL COMMENT '消费时间，非空即已用过',
    `attempt_count` INT          NOT NULL DEFAULT 0 COMMENT '猜错次数，达上限即作废',
    `request_ip`    VARCHAR(64)  NOT NULL DEFAULT '',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_evc_email` (`email`, `created_at`),
    KEY `idx_evc_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='邮箱验证码';

-- 邮箱成为账号的唯一标识之一。
-- 已核实存量数据：19 个用户中 9 个填了邮箱且无重复，可直接加约束；NULL 可重复，不影响无邮箱的账号。
-- 既有的非唯一索引 idx_email（V0_5/V12 建）保留不动：唯一索引已覆盖其查询能力，改旧迁移得不偿失。
ALTER TABLE `user` ADD UNIQUE KEY `uk_user_email` (`email`);
