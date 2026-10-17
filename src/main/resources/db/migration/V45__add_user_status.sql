-- 账号生命周期：管理员可暂停/恢复账号使用。
--
-- 设计取舍：限制实现为整体禁用（无法登录），不做「仅禁言」「仅禁止发帖」这类部分限制，
-- 用户协议中的对应表述同步改为「暂停账号使用」。
--
-- status 默认 ACTIVE；应用层把 NULL 也按 ACTIVE 处理，兼容迁移前的会话快照。

ALTER TABLE `user`
    ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE / DISABLED' AFTER `role`,
    ADD COLUMN `disabled_reason` VARCHAR(255) DEFAULT NULL COMMENT '禁用原因' AFTER `status`,
    ADD COLUMN `disabled_at` DATETIME DEFAULT NULL COMMENT '禁用时间' AFTER `disabled_reason`;

CREATE INDEX `idx_user_status` ON `user` (`status`);
