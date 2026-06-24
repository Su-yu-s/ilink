-- ============================================================
-- iLink v1 -> v2 迁移脚本
-- 适用于已有 v1 部署的数据库，增量添加缺失的列和索引
-- 请在执行前备份数据库！
-- ============================================================

-- -----------------------------------------------------------
-- 1. user 表：修改 id 类型为 BIGINT，添加缺失字段
-- -----------------------------------------------------------

-- 1.1 将 id 从 INT 改为 BIGINT（如果当前是 INT）
-- 注意：如果已有外键引用 user(id)，需先删除外键再修改
-- 以下操作视具体数据库情况可能需要调整顺序
ALTER TABLE `team_demand`        DROP FOREIGN KEY `team_demand_ibfk_1`;
ALTER TABLE `team_application`   DROP FOREIGN KEY `team_application_ibfk_1`;
ALTER TABLE `team_application`   DROP FOREIGN KEY `team_application_ibfk_2`;
ALTER TABLE `teacher_application` DROP FOREIGN KEY `teacher_application_ibfk_1`;
ALTER TABLE `project_application` DROP FOREIGN KEY `project_application_ibfk_1`;
ALTER TABLE `project_application` DROP FOREIGN KEY `project_application_ibfk_2`;
ALTER TABLE `asset`              DROP FOREIGN KEY `asset_ibfk_1`;
ALTER TABLE `message`            DROP FOREIGN KEY `message_ibfk_1`;
ALTER TABLE `community_post`     DROP FOREIGN KEY `community_post_ibfk_1`;
ALTER TABLE `community_comment`  DROP FOREIGN KEY `community_comment_ibfk_2`;

-- 修改 user.id 为 BIGINT
ALTER TABLE `user` MODIFY COLUMN `id` BIGINT NOT NULL AUTO_INCREMENT;

-- 修改 role 为 VARCHAR（原为 ENUM）
ALTER TABLE `user` MODIFY COLUMN `role` VARCHAR(20) NOT NULL DEFAULT 'STUDENT';

-- 修改 password 为 NOT NULL
ALTER TABLE `user` MODIFY COLUMN `password` VARCHAR(200) NOT NULL;

-- 添加缺失的列
ALTER TABLE `user` ADD COLUMN `student_id`   BIGINT       DEFAULT NULL COMMENT '学号'       AFTER `username`;
ALTER TABLE `user` ADD COLUMN `phone_number` VARCHAR(20)  DEFAULT NULL COMMENT '手机号'      AFTER `student_id`;
ALTER TABLE `user` ADD COLUMN `gender`       VARCHAR(10)  DEFAULT NULL COMMENT '性别'        AFTER `real_name`;
ALTER TABLE `user` ADD COLUMN `grade`        VARCHAR(20)  DEFAULT NULL COMMENT '年级'        AFTER `gender`;
ALTER TABLE `user` ADD COLUMN `major`        VARCHAR(100) DEFAULT NULL COMMENT '专业'        AFTER `grade`;
ALTER TABLE `user` ADD COLUMN `school`       VARCHAR(100) DEFAULT NULL COMMENT '学校'        AFTER `major`;
ALTER TABLE `user` ADD COLUMN `college`      VARCHAR(100) DEFAULT NULL COMMENT '学院'        AFTER `school`;
ALTER TABLE `user` ADD COLUMN `bio`          TEXT         DEFAULT NULL COMMENT '个人简介'    AFTER `college`;
ALTER TABLE `user` ADD COLUMN `honors`       TEXT         DEFAULT NULL COMMENT 'JSON 数组：个人荣誉' AFTER `bio`;

-- -----------------------------------------------------------
-- 2. community_post 表：添加缺失字段
-- -----------------------------------------------------------
ALTER TABLE `community_post` ADD COLUMN `like_count`     INT NOT NULL DEFAULT 0 COMMENT '点赞数'   AFTER `view_count`;
ALTER TABLE `community_post` ADD COLUMN `favorite_count` INT NOT NULL DEFAULT 0 COMMENT '收藏数'   AFTER `like_count`;
ALTER TABLE `community_post` ADD COLUMN `attachments`    TEXT DEFAULT NULL COMMENT 'JSON 附件列表' AFTER `favorite_count`;
ALTER TABLE `community_post` ADD COLUMN `updated_at`     TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER `created_at`;

-- -----------------------------------------------------------
-- 3. 重新创建外键（引用已改为 BIGINT 的 user.id）
-- -----------------------------------------------------------

-- team_demand
ALTER TABLE `team_demand`
    ADD CONSTRAINT `fk_team_demand_creator` FOREIGN KEY (`creator_id`) REFERENCES `user`(`id`);

-- team_application
ALTER TABLE `team_application`
    ADD CONSTRAINT `fk_team_app_team`   FOREIGN KEY (`team_id`) REFERENCES `team_demand`(`id`),
    ADD CONSTRAINT `fk_team_app_user`   FOREIGN KEY (`user_id`) REFERENCES `user`(`id`);

-- teacher_application
ALTER TABLE `teacher_application`
    ADD CONSTRAINT `fk_teacher_app_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`);

-- project_application
ALTER TABLE `project_application`
    ADD CONSTRAINT `fk_proj_app_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `teacher_application`(`id`),
    ADD CONSTRAINT `fk_proj_app_user`    FOREIGN KEY (`user_id`)    REFERENCES `user`(`id`);

-- asset
ALTER TABLE `asset`
    ADD CONSTRAINT `fk_asset_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`);

-- message
ALTER TABLE `message`
    ADD CONSTRAINT `fk_msg_sender`   FOREIGN KEY (`sender_id`)   REFERENCES `user`(`id`),
    ADD CONSTRAINT `fk_msg_receiver` FOREIGN KEY (`receiver_id`) REFERENCES `user`(`id`);

-- community_post
ALTER TABLE `community_post`
    ADD CONSTRAINT `fk_post_author` FOREIGN KEY (`author_id`) REFERENCES `user`(`id`);

-- community_comment
ALTER TABLE `community_comment`
    ADD CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `user`(`id`);
-- community_comment 的 post_id 外键（ON DELETE CASCADE）应已存在，无需重建

-- ============================================================
-- 迁移完成。请验证数据完整性。
-- ============================================================
