SET @professional_title_exists := (
  SELECT COUNT(*)
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'teacher_application'
    AND column_name = 'professional_title'
);
SET @professional_title_sql := IF(
  @professional_title_exists = 0,
  'ALTER TABLE `teacher_application` ADD COLUMN `professional_title` VARCHAR(100) DEFAULT NULL AFTER `research_direction`',
  'SELECT 1'
);
PREPARE professional_title_stmt FROM @professional_title_sql;
EXECUTE professional_title_stmt;
DEALLOCATE PREPARE professional_title_stmt;
