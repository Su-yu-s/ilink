-- Prevent concurrent duplicate teacher and project applications.
-- Existing duplicate rows are collapsed deterministically before the constraints are added.

DELETE older
FROM `teacher_application` older
JOIN `teacher_application` newer
  ON newer.`user_id` = older.`user_id`
 AND (
      CASE newer.`status`
        WHEN 'APPROVED' THEN 3
        WHEN 'PENDING' THEN 2
        ELSE 1
      END
      >
      CASE older.`status`
        WHEN 'APPROVED' THEN 3
        WHEN 'PENDING' THEN 2
        ELSE 1
      END
      OR (
        newer.`status` = older.`status`
        AND newer.`id` > older.`id`
      )
 );

DELETE older
FROM `project_application` older
JOIN `project_application` newer
  ON newer.`teacher_id` = older.`teacher_id`
 AND newer.`user_id` = older.`user_id`
 AND (
      CASE newer.`status`
        WHEN 'APPROVED' THEN 3
        WHEN 'PENDING' THEN 2
        ELSE 1
      END
      >
      CASE older.`status`
        WHEN 'APPROVED' THEN 3
        WHEN 'PENDING' THEN 2
        ELSE 1
      END
      OR (
        newer.`status` = older.`status`
        AND newer.`id` > older.`id`
      )
 );

SET @teacher_index_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'teacher_application'
    AND index_name = 'uk_teacher_user'
);
SET @teacher_index_sql := IF(
  @teacher_index_exists = 0,
  'ALTER TABLE `teacher_application` ADD UNIQUE KEY `uk_teacher_user` (`user_id`)',
  'SELECT 1'
);
PREPARE teacher_index_stmt FROM @teacher_index_sql;
EXECUTE teacher_index_stmt;
DEALLOCATE PREPARE teacher_index_stmt;

SET @project_index_exists := (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'project_application'
    AND index_name = 'uk_proj_teacher_user'
);
SET @project_index_sql := IF(
  @project_index_exists = 0,
  'ALTER TABLE `project_application` ADD UNIQUE KEY `uk_proj_teacher_user` (`teacher_id`, `user_id`)',
  'SELECT 1'
);
PREPARE project_index_stmt FROM @project_index_sql;
EXECUTE project_index_stmt;
DEALLOCATE PREPARE project_index_stmt;
