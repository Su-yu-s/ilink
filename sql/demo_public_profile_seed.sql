-- 公开主页 / 点头像联调用示例数据（可重复执行，按用户名幂等）
-- 前提：已执行 ilink_user_table.sql、schema 中业务表；MySQL 8+ 推荐
-- 登录密码均为：demo1234（BCrypt，与 Spring Security 兼容）
-- 用法示例：mysql -u root -p ilink < sql/demo_public_profile_seed.sql

SET NAMES utf8mb4;

SET @demo_pwd := '$2b$12$oZP/.wsgJ2ZwXWTi/uMSA.sa0o9OEZO270tNDopjakWPtKwkYSfga';

INSERT INTO `user` (`username`, `password`, `email`, `role`, `real_name`, `avatar`, `honors`, `created_at`)
VALUES
    ('ilink_seed_team_leader', @demo_pwd, 'seed-team@demo.local', 'STUDENT', '张队长', NULL,
     '[{"id":"sl1","type":"competition","title":"校赛机器人一等奖","level":"school","issuer":"校团委"}]',
     NOW()),
    ('ilink_seed_teacher_li', @demo_pwd, 'seed-teacher@demo.local', 'TEACHER', '李导师', NULL,
     '[{"id":"sl2","type":"research","title":"省部级教研项目","level":"provincial"}]',
     NOW()),
    ('ilink_seed_teacher_chen', @demo_pwd, 'seed-teacher2@demo.local', 'TEACHER', '陈教授', NULL,
     '[{"id":"sl2b","type":"competition","title":"指导团队获国赛二等奖","level":"national"}]',
     NOW()),
    ('ilink_seed_author_wang', @demo_pwd, 'seed-author@demo.local', 'STUDENT', '王同学', NULL, NULL, NOW())
ON DUPLICATE KEY UPDATE
    `real_name` = VALUES(`real_name`),
    `role` = VALUES(`role`),
    `honors` = VALUES(`honors`);

SET @uid_team := (SELECT `id` FROM `user` WHERE `username` = 'ilink_seed_team_leader' LIMIT 1);
SET @uid_teacher := (SELECT `id` FROM `user` WHERE `username` = 'ilink_seed_teacher_li' LIMIT 1);
SET @uid_teacher2 := (SELECT `id` FROM `user` WHERE `username` = 'ilink_seed_teacher_chen' LIMIT 1);
SET @uid_author := (SELECT `id` FROM `user` WHERE `username` = 'ilink_seed_author_wang' LIMIT 1);

-- 组队需求（发布者：张队长）
INSERT INTO `team_demand` (`title`, `description`, `competition_id`, `required_skills`, `status`, `creator_id`, `created_at`)
SELECT
    '【示例】智能车竞赛找嵌入式队友',
    '需要熟悉 STM32 与传感器融合，每周可投入 10h+。（所需人数：3）（截止日期：2026-12-20）',
    1,
    'C/嵌入式、基础硬件',
    'OPEN',
    @uid_team,
    NOW()
FROM DUAL
WHERE @uid_team IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM `team_demand` t
      WHERE t.`creator_id` = @uid_team
        AND t.`title` = '【示例】智能车竞赛找嵌入式队友'
  );

-- 导师申请（已通过）
INSERT INTO `teacher_application` (`user_id`, `introduction`, `research_direction`, `projects`, `status`, `created_at`)
SELECT
    @uid_teacher,
    '长期从事嵌入式与物联网教学，欢迎竞赛与毕设指导。',
    '嵌入式系统、边缘计算',
    '电子信息工程（副教授）',
    'APPROVED',
    NOW()
FROM DUAL
WHERE @uid_teacher IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM `teacher_application` x WHERE x.`user_id` = @uid_teacher
  );

INSERT INTO `teacher_application` (`user_id`, `introduction`, `research_direction`, `projects`, `status`, `created_at`)
SELECT
    @uid_teacher2,
    '人工智能与机器学习方向，曾带队获多项省级以上奖项。',
    '机器学习、计算机视觉',
    '计算机科学与技术（教授）',
    'APPROVED',
    NOW()
FROM DUAL
WHERE @uid_teacher2 IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM `teacher_application` x WHERE x.`user_id` = @uid_teacher2
  );

-- 社区文章
INSERT INTO `community_post` (`author_id`, `category`, `title`, `content`, `view_count`, `created_at`)
SELECT
    @uid_author,
    'competition',
    '【示例】从校赛到省赛的备赛节奏',
    '建议分三阶段：选题与分工 → 原型与迭代 → 答辩与文档。本帖为种子数据，可删除后自行发布。',
    12,
    NOW()
FROM DUAL
WHERE @uid_author IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM `community_post` p
      WHERE p.`author_id` = @uid_author
        AND p.`title` = '【示例】从校赛到省赛的备赛节奏'
  );

-- 成果作品
INSERT INTO `asset` (`title`, `description`, `file_url`, `user_id`, `view_count`, `created_at`)
SELECT
    '【示例】开源小作品说明',
    '（分类：作品展示）种子数据，用于成果详情页作者头像跳转联调。',
    NULL,
    @uid_author,
    0,
    NOW()
FROM DUAL
WHERE @uid_author IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM `asset` a
      WHERE a.`user_id` = @uid_author
        AND a.`title` = '【示例】开源小作品说明'
  );
