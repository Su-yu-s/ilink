-- ============================================================
-- 演示种子数据 · 修正脚本（只修 demo_showcase_teams.sql 早期版本的三个问题）
--   1. 智能温室监控系统：需 3 人但已有 3 名 APPROVED 成员 → 扩到 5 人，避免卡片显示「已满」
--   2. 老年友好型 App 可用性研究：竞赛误绑 MCM/ICM(5) → 改为中国高校计算机大赛(8)
--   3. 清掉早期版本遗留的 typo 队伍「工业质检缺陷同学」
-- 只影响 ilink_demo_ 前缀种子用户创建的数据，不动任何既有业务数据。
-- ============================================================

SET NAMES utf8mb4;

START TRANSACTION;

-- 1. 智能温室扩员到 5 人（队长 + 3 成员 = 4 人，仍显示招募中）
UPDATE `team_demand`
SET `required_member_count` = 5, `updated_at` = NOW()
WHERE `title` = '智能温室监控系统 —— 硬件组招人'
  AND `required_member_count` = 3
  AND `creator_id` IN (SELECT `id` FROM `user` WHERE `username` = 'ilink_demo_s_tangwei');

-- 2. 老年友好型 App 换绑竞赛
UPDATE `team_demand`
SET `competition_id` = 8, `updated_at` = NOW()
WHERE `title` = '老年友好型 App 可用性研究'
  AND `creator_id` IN (SELECT `id` FROM `user` WHERE `username` = 'ilink_demo_s_ainuo');

-- 3. 清理 typo 队伍及其申请（若无成员会自动级联删除，先删申请）
DELETE a FROM `team_application` a
JOIN `team_demand` t ON t.id = a.team_id
WHERE t.`title` = '工业质检缺陷同学';

DELETE t FROM `team_demand` t
WHERE t.`title` = '工业质检缺陷同学';

COMMIT;

-- 复核：不应存在超员的种子队伍
SELECT t.id, t.title, t.required_member_count,
       (SELECT COUNT(*) FROM `team_application` a WHERE a.team_id = t.id AND a.status = 'APPROVED') + 1 AS current_members
FROM `team_demand` t
JOIN `user` u ON u.id = t.creator_id
WHERE u.username LIKE 'ilink_demo_%'
HAVING current_members > t.required_member_count;
