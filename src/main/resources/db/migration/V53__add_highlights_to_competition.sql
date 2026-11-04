-- 核心考察点：赛事详情页要展示"这项比赛到底在考什么"。
-- 存结构化 JSON 而不是 HTML —— 前端按数组渲染成列表，
-- 既不引入 XSS 面，也避免后台录入时手写标签。
-- 形如：[{"t":"商业落地","d":"项目是否具备真实的商业闭环与市场验证。"}]
ALTER TABLE `competition`
    ADD COLUMN `highlights` TEXT NULL COMMENT '核心考察点 JSON 数组' AFTER `description`;
