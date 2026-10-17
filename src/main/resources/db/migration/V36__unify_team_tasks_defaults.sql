-- 修复 D-P2-6：V1 与 V10 对 team_tasks 的定义分叉。全新库按版本序先跑 V1 建表，
-- V10 的 CREATE TABLE IF NOT EXISTS 被静默跳过，线上 schema 永远停留在 V1 语义：
--   status 默认 'pending'（小写）、task_type NOT NULL 无默认。
-- 实体枚举 TeamTask.TaskStatus/TaskType 全大写英文，目前靠应用层显式 setStatus 兜底；
-- 任何绕过代码的插入（默认值路径、运维 SQL）都会产出小写/中文脏值。
-- 本迁移把默认值与注释统一到 V10 语义（列类型不变，仅收敛默认值，对存量数据无影响）。
ALTER TABLE `team_tasks`
    MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态: PENDING/IN_PROGRESS/REVIEW/COMPLETED/CANCELLED',
    MODIFY COLUMN `task_type` VARCHAR(50) NOT NULL DEFAULT 'OTHER' COMMENT '任务类型: DEVELOPMENT/DESIGN/TESTING/DOCUMENTATION/OTHER',
    MODIFY COLUMN `estimated_hours` DECIMAL(10,2) DEFAULT NULL COMMENT '预估工时(小时)，精度与 V10 对齐(10,2)';
