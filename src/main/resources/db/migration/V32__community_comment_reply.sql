-- 评论回复：单层父子关系
-- parent_id      被回复评论所属的顶层评论 ID（NULL 即顶层评论）
-- reply_to_user_id 被回复者，仅用于展示「回复 @某某」（回复本身仍是单层，不再嵌套）
ALTER TABLE `community_comment`
    ADD COLUMN `parent_id` BIGINT DEFAULT NULL COMMENT '所属顶层评论ID，NULL 为顶层评论' AFTER `post_id`,
    ADD COLUMN `reply_to_user_id` BIGINT DEFAULT NULL COMMENT '被回复者用户ID（展示「回复 @某某」）' AFTER `parent_id`,
    ADD INDEX `idx_parent_id` (`parent_id`);
