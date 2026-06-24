-- 社区文章：正文附件（JSON 列表），与 community_blog_upgrade.sql 独立可重复执行
-- 若列已存在会报错，可忽略

ALTER TABLE `community_post`
    ADD COLUMN `attachments` TEXT NULL COMMENT 'JSON list: name + url under /uploads/' AFTER `content`;
