-- iLink 唯一约束幂等补丁
-- 用途：为 team_application / teacher_application / project_application 添加唯一索引，
--      修复并发申请竞态条件（TOCTOU）。
-- 可重复执行，不会因"已存在索引"而终止。
--
-- 执行方式：mysql -u root -p ilink < sql/idempotent_unique_constraints.sql

SET NAMES utf8mb4;
SELECT CONCAT('执行时间: ', NOW()) AS '开始';

-- 1) 团队申请表：防止同一用户重复申请同一团队
-- 先检查是否存在，避免重复创建报错
SET @idx_exists_1 = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'team_application'
      AND index_name = 'uk_team_user');
SET @sql1 = IF(@idx_exists_1 = 0,
    'ALTER TABLE `team_application` ADD UNIQUE KEY `uk_team_user` (`team_id`, `user_id`)',
    'SELECT "uk_team_user 已存在，跳过" AS msg');
PREPARE stmt1 FROM @sql1;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

-- 2) 导师申请表：防止同一用户重复申请导师
SET @idx_exists_2 = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'teacher_application'
      AND index_name = 'uk_teacher_user');
SET @sql2 = IF(@idx_exists_2 = 0,
    'ALTER TABLE `teacher_application` ADD UNIQUE KEY `uk_teacher_user` (`user_id`)',
    'SELECT "uk_teacher_user 已存在，跳过" AS msg');
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- 3) 项目申请表：防止同一用户重复申请同一导师的项目
SET @idx_exists_3 = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'project_application'
      AND index_name = 'uk_proj_teacher_user');
SET @sql3 = IF(@idx_exists_3 = 0,
    'ALTER TABLE `project_application` ADD UNIQUE KEY `uk_proj_teacher_user` (`teacher_id`, `user_id`)',
    'SELECT "uk_proj_teacher_user 已存在，跳过" AS msg');
PREPARE stmt3 FROM @sql3;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;

SELECT CONCAT('完成时间: ', NOW()) AS '结束';
