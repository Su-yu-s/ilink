-- 社区文章置顶：管理后台可置顶优质内容，列表按 is_pinned DESC 优先展示。
-- 实体侧以 Integer 映射（0/1），命名与 team_messages.is_pinned 保持一致。
ALTER TABLE community_post
    ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶 0否 1是';
