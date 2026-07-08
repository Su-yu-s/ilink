ALTER TABLE `team_application`
    ADD COLUMN `reviewer_note` VARCHAR(500) DEFAULT NULL COMMENT '审批备注/拒绝理由' AFTER `message`,
    ADD COLUMN `reviewed_at` DATETIME DEFAULT NULL COMMENT '审批时间' AFTER `reviewer_note`;
