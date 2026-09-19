-- 成果表 created_at 带 ON UPDATE CURRENT_TIMESTAMP（老库手工建表遗留）：
-- 每次浏览量 +1 的 UPDATE 都会连带刷新 created_at，导致「最新发布」排序下
-- 刚看过的成果直接跳到列表第一位。去掉 ON UPDATE；TIMESTAMP 一并转 DATETIME
-- （避开 2038 年上限）。community_post 已验证无此问题，保持不动。
ALTER TABLE asset
    MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;
