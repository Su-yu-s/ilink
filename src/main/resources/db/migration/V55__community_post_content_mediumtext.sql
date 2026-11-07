-- 接口层 CONTENT_MAX = 200000 字符，而 community_post.content 是 TEXT（上限 64KB）。
-- utf8mb4 下中文最多占 3 字节，实际约 2.1 万字就会撞列宽，接口变成 500。
-- 放宽到 MEDIUMTEXT（16MB），与接口允许的长度对齐。
ALTER TABLE `community_post`
    MODIFY COLUMN `content` MEDIUMTEXT COMMENT '正文内容';
