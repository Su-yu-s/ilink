-- AI 拆解出的子任务此前与普通任务毫无区别，于是子任务还能再被拆解，任务无限套娃。
-- 记下父任务即可区分两者：有父任务的（子任务）不再提供拆解入口，后端也拒绝再拆。
ALTER TABLE `team_tasks`
    ADD COLUMN `parent_task_id` BIGINT NULL COMMENT '父任务 ID（AI 拆解来源）；非空表示这是子任务',
    ADD KEY `idx_task_parent` (`parent_task_id`);
