-- 修复压测 LT-2：帖子列表 /api/community/posts 在 2 万行规模下即达 ~440 RPS 天花板。
-- EXPLAIN 显示 ORDER BY is_pinned DESC, created_at DESC, id DESC 无可用索引（key=NULL，
-- Using filesort，rows≈全表 1.5 万），排序前导列 is_pinned 使现有 idx_created_at 失效，
-- 每次请求全表扫描 + 排序，并发下 CPU 打满。
-- 为四种排序键各建 (is_pinned, 排序列) 复合索引：5.7 可整体反向扫描满足全 DESC 排序。
ALTER TABLE `community_post`
    ADD INDEX `idx_posts_sort_latest` (`is_pinned`, `created_at`, `id`),
    ADD INDEX `idx_posts_sort_views` (`is_pinned`, `view_count`),
    ADD INDEX `idx_posts_sort_likes` (`is_pinned`, `like_count`),
    ADD INDEX `idx_posts_sort_favorites` (`is_pinned`, `favorite_count`);
