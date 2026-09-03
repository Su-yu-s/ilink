-- 教师账号天然具有导师身份，但资料未完成前不应出现在公开导师墙。
-- Historical schemas used an ENUM that did not contain INCOMPLETE/REVOKED.
ALTER TABLE `teacher_application`
    MODIFY `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING';

INSERT INTO `teacher_application`
    (`user_id`, `introduction`, `research_direction`, `professional_title`, `projects`, `status`, `created_at`)
SELECT
    u.`id`, NULL, NULL, NULL, NULL, 'INCOMPLETE', COALESCE(u.`created_at`, CURRENT_TIMESTAMP)
FROM `user` u
WHERE u.`role` = 'TEACHER'
  AND NOT EXISTS (
      SELECT 1 FROM `teacher_application` ta WHERE ta.`user_id` = u.`id`
  );

UPDATE `teacher_application` ta
JOIN `user` u ON u.`id` = ta.`user_id`
SET ta.`status` = CASE
    WHEN NULLIF(TRIM(u.`real_name`), '') IS NOT NULL
     AND NULLIF(TRIM(u.`school`), '') IS NOT NULL
     AND NULLIF(TRIM(u.`major`), '') IS NOT NULL
     AND NULLIF(TRIM(ta.`professional_title`), '') IS NOT NULL
     AND NULLIF(TRIM(ta.`research_direction`), '') IS NOT NULL
     AND NULLIF(TRIM(ta.`introduction`), '') IS NOT NULL
    THEN 'APPROVED'
    ELSE 'INCOMPLETE'
END
WHERE u.`role` = 'TEACHER'
  AND ta.`status` <> 'REVOKED';
