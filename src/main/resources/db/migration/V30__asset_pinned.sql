-- 成果置顶：管理后台可置顶优质成果，列表 is_pinned DESC 优先展示（与社区帖子约定一致）。
ALTER TABLE asset
    ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶 0否 1是';
