INSERT INTO `teacher_application`
  (`user_id`, `introduction`, `research_direction`, `professional_title`, `projects`, `status`, `created_at`)
SELECT
  u.`id`, NULL, NULL, NULL, NULL, 'APPROVED', COALESCE(u.`created_at`, CURRENT_TIMESTAMP)
FROM `user` u
WHERE u.`role` = 'TEACHER'
  AND NOT EXISTS (
    SELECT 1
    FROM `teacher_application` ta
    WHERE ta.`user_id` = u.`id`
  );

UPDATE `teacher_application` ta
JOIN `user` u ON u.`id` = ta.`user_id`
SET ta.`status` = 'APPROVED'
WHERE u.`role` = 'TEACHER'
  AND ta.`status` <> 'APPROVED';
