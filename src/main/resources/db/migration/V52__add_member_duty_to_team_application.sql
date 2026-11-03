-- 成员职责：队长在「成员」面板里给队员填写的岗位名，如 策划 / 摄影 / 剪辑。
-- 与 member_role 分工不同：member_role 是 MENTOR/STUDENT 的权限枚举，
-- duty 是纯展示用的自由文本，不参与任何权限判断，因此允许为空。
ALTER TABLE `team_application`
    ADD COLUMN `duty` VARCHAR(16) NULL COMMENT '团队内职责，队长可编辑' AFTER `member_role`;
