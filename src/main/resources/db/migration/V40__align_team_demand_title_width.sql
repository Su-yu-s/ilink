-- 修复 team_demand.title 的列宽漂移（核实报告 P2-5）。
--
-- 事实来源：V0_5__create_core_prerequisites.sql / V12__create_core_tables.sql / sql/schema.sql 均声明 VARCHAR(200)，
-- 应用层 ControllerUtils.TEAM_TITLE_MAX、TeamDemandRequest.@Size(max=200)、前端表单 maxlength 也都是 200。
-- 但线上库实际为 VARCHAR(100)（历史手工建库所致），导致 101–200 字的标题被 MySQL 拒绝，
-- 且全局处理器把它兜成"提交的数据不符合要求"这类与标题无关的通用错误，用户无法判断真实上限。
--
-- 统一口径：以迁移声明为准，把列放宽到 VARCHAR(200)。加宽不丢数据、不改内容，对既有行是空操作。
-- 说明：ALTER 语句本身幂等（目标定义与声明一致时再次执行不会报错），因此无需额外条件判断。

ALTER TABLE `team_demand`
    MODIFY COLUMN `title` VARCHAR(200) NOT NULL COMMENT '标题';
