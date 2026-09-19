-- 团队邀请与团队内角色。
-- 邀请与申请复用同一张 team_application：initiator_id = user_id 表示本人申请，
-- initiator_id <> user_id 表示团队邀请。uk_team_user 天然保证一人一队只有一行。
ALTER TABLE `team_application`
    ADD COLUMN `initiator_id` BIGINT NULL COMMENT '发起人：申请=申请人本人，邀请=邀请人' AFTER `user_id`,
    ADD COLUMN `member_role` VARCHAR(20) NOT NULL DEFAULT 'STUDENT' COMMENT '团队内角色 MENTOR/STUDENT' AFTER `status`,
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT '状态: PENDING/APPROVED/REJECTED 为在队与审批态；LEFT=主动退出，REMOVED=被移出，VOID=随团队解散作废';

-- 历史行全部来自「本人申请」，回填后方向判断保持一致
UPDATE `team_application` SET `initiator_id` = `user_id` WHERE `initiator_id` IS NULL;

-- 按注册角色回填团队内角色
UPDATE `team_application` a
JOIN `user` u ON u.`id` = a.`user_id`
SET a.`member_role` = IF(u.`role` = 'TEACHER', 'MENTOR', 'STUDENT')
WHERE u.`role` IS NOT NULL;

CREATE INDEX `idx_team_status` ON `team_application` (`team_id`, `status`);
