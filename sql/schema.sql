-- ============================================================
-- iLink 完整数据库 Schema（基于 Flyway V0_5 ~ V24）
-- 适用于全新部署，与 Java 实体保持一致
-- 生成日期: 2026-09-03
-- ============================================================

-- ----------------------------
-- 用户表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `username`       VARCHAR(100) NOT NULL UNIQUE COMMENT '用户名',
    `student_id`     BIGINT       DEFAULT NULL COMMENT '学号/工号',
    `phone_number`   VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `password`       VARCHAR(255) NOT NULL COMMENT '密码（BCrypt）',
    `email`          VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `role`           VARCHAR(20)  NOT NULL DEFAULT 'STUDENT' COMMENT '角色: STUDENT/TEACHER/ADMIN',
    `avatar`         VARCHAR(500) DEFAULT NULL COMMENT '头像 URL',
    `real_name`      VARCHAR(100) DEFAULT NULL COMMENT '真实姓名',
    `gender`         VARCHAR(10)  DEFAULT NULL COMMENT '性别: MALE/FEMALE/OTHER',
    `grade`          VARCHAR(20)  DEFAULT NULL COMMENT '年级',
    `major`          VARCHAR(100) DEFAULT NULL COMMENT '专业',
    `school`         VARCHAR(100) DEFAULT NULL COMMENT '学校',
    `college`        VARCHAR(100) DEFAULT NULL COMMENT '学院',
    `bio`            TEXT         DEFAULT NULL COMMENT '个人简介',
    `honors`         TEXT         DEFAULT NULL COMMENT '个人荣誉(JSON数组)',
    `last_active_at` DATETIME     DEFAULT NULL COMMENT '最近活跃时间',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_phone_number` (`phone_number`),
    INDEX `idx_student_id` (`student_id`),
    INDEX `idx_email` (`email`),
    INDEX `idx_role` (`role`),
    INDEX `idx_user_last_active_at` (`last_active_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ----------------------------
-- 组队需求表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `team_demand` (
    `id`                      BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `title`                   VARCHAR(200) NOT NULL COMMENT '标题',
    `description`             TEXT         DEFAULT NULL COMMENT '内容描述',
    `competition_id`          INT          DEFAULT NULL COMMENT '竞赛ID',
    `required_skills`         VARCHAR(500) DEFAULT NULL COMMENT '所需技能',
    `required_member_count`   INT          DEFAULT NULL COMMENT '所需队员人数',
    `deadline`                DATETIME     DEFAULT NULL COMMENT '招募截止时间',
    `status`                  VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN/TEAMING/CLOSED',
    `creator_id`              BIGINT       NOT NULL COMMENT '创建者用户ID',
    `created_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_creator_id` (`creator_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_competition_id` (`competition_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组队需求表';

-- ----------------------------
-- 组队申请表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `team_application` (
    `id`            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `team_id`       BIGINT       NOT NULL COMMENT '团队需求ID',
    `user_id`       BIGINT       NOT NULL COMMENT '申请人用户ID',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED/REVOKED',
    `message`       TEXT         DEFAULT NULL COMMENT '申请留言',
    `reviewer_note` VARCHAR(500) DEFAULT NULL COMMENT '审批备注/拒绝理由',
    `reviewed_at`   DATETIME     DEFAULT NULL COMMENT '审批时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_team_user` (`team_id`, `user_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_team_id` (`team_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组队申请表';

-- ----------------------------
-- 导师申请表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `teacher_application` (
    `id`                 BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `user_id`            BIGINT       NOT NULL COMMENT '申请人用户ID',
    `introduction`       TEXT         DEFAULT NULL COMMENT '个人简介',
    `research_direction` VARCHAR(200) DEFAULT NULL COMMENT '研究方向',
    `professional_title` VARCHAR(100) DEFAULT NULL COMMENT '职称',
    `projects`           TEXT         DEFAULT NULL COMMENT '项目经历',
    `status`             VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/INCOMPLETE/REVOKED',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='导师申请表';

-- ----------------------------
-- 项目申请表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `project_application` (
    `id`         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `teacher_id` BIGINT       NOT NULL COMMENT '导师ID',
    `user_id`    BIGINT       NOT NULL COMMENT '申请人用户ID',
    `status`     VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/APPROVED/REJECTED',
    `message`    TEXT         DEFAULT NULL COMMENT '申请留言',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_teacher_id` (`teacher_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目申请表';

-- ----------------------------
-- 成果表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `asset` (
    `id`             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `title`          VARCHAR(200) NOT NULL COMMENT '成果标题',
    `description`    TEXT         DEFAULT NULL COMMENT '成果描述',
    `category`       VARCHAR(50)  NOT NULL DEFAULT '其他' COMMENT '成果分类',
    `file_url`       VARCHAR(500) DEFAULT NULL COMMENT '文件 URL',
    `user_id`        BIGINT       NOT NULL COMMENT '发布者用户ID',
    `view_count`     INT          NOT NULL DEFAULT 0 COMMENT '浏览次数',
    `download_count` INT          NOT NULL DEFAULT 0 COMMENT '下载次数',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_asset_category` (`category`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成果表';

-- ----------------------------
-- 团队聊天消息表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `chat_message` (
    `id`           BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `team_id`      BIGINT       NOT NULL COMMENT '团队ID',
    `sender_id`    BIGINT       NOT NULL COMMENT '发送者用户ID',
    `message_type` VARCHAR(20)  NOT NULL DEFAULT 'TEXT' COMMENT '消息类型',
    `content`      TEXT         NOT NULL COMMENT '消息内容',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_chat_message_team_id` (`team_id`),
    INDEX `idx_chat_message_sender_id` (`sender_id`),
    INDEX `idx_chat_message_created_at` (`created_at`),
    INDEX `idx_chat_message_type` (`message_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队聊天消息表';

-- ----------------------------
-- 交流社区帖子表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `community_post` (
    `id`             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `author_id`      BIGINT       NOT NULL COMMENT '作者用户ID',
    `category`       VARCHAR(50)  NOT NULL DEFAULT 'general' COMMENT '分区: general/tech/competition/resource',
    `title`          VARCHAR(200) NOT NULL COMMENT '标题',
    `content`        TEXT         COMMENT '正文内容',
    `attachments`    TEXT         DEFAULT NULL COMMENT '附件(JSON数组)',
    `view_count`     INT          NOT NULL DEFAULT 0 COMMENT '阅读量',
    `like_count`     INT          NOT NULL DEFAULT 0 COMMENT '点赞数',
    `favorite_count` INT          NOT NULL DEFAULT 0 COMMENT '收藏数',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_author_id` (`author_id`),
    INDEX `idx_category` (`category`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区帖子表';

-- ----------------------------
-- 交流社区评论表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `community_comment` (
    `id`         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `post_id`    BIGINT       NOT NULL COMMENT '帖子ID',
    `user_id`    BIGINT       NOT NULL COMMENT '评论用户ID',
    `content`    TEXT         NOT NULL COMMENT '评论内容',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_post_id` (`post_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`),
    FOREIGN KEY (`post_id`) REFERENCES `community_post`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区评论表';

-- ----------------------------
-- 社区点赞表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `community_post_like` (
    `id`         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `post_id`    BIGINT       NOT NULL,
    `user_id`    BIGINT       NOT NULL,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_post_user_like` (`post_id`, `user_id`),
    INDEX `idx_like_post_id` (`post_id`),
    INDEX `idx_like_user_id` (`user_id`),
    FOREIGN KEY (`post_id`) REFERENCES `community_post`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区点赞表';

-- ----------------------------
-- 社区收藏表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `community_post_favorite` (
    `id`         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `post_id`    BIGINT       NOT NULL,
    `user_id`    BIGINT       NOT NULL,
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_post_user_favorite` (`post_id`, `user_id`),
    INDEX `idx_favorite_post_id` (`post_id`),
    INDEX `idx_favorite_user_id` (`user_id`),
    FOREIGN KEY (`post_id`) REFERENCES `community_post`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区收藏表';

-- ----------------------------
-- 用户技能表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `user_skills` (
    `id`               BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `user_id`          BIGINT       NOT NULL COMMENT '用户ID',
    `skill_name`       VARCHAR(100) NOT NULL COMMENT '技能名称',
    `skill_level`      TINYINT      NOT NULL DEFAULT 3 COMMENT '技能等级(1-5)',
    `skill_category`   VARCHAR(50)  DEFAULT NULL COMMENT '技能分类',
    `certification`    VARCHAR(200) DEFAULT NULL COMMENT '资质认证',
    `years_experience` INT          DEFAULT 0 COMMENT '从业年限',
    `portfolio_url`    VARCHAR(500) DEFAULT NULL COMMENT '作品集链接',
    `is_verified`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已认证',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_skill` (`user_id`, `skill_name`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_skill_name` (`skill_name`),
    INDEX `idx_skill_category` (`skill_category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户技能表';

-- ----------------------------
-- 推荐日志表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `recommendation_log` (
    `id`          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `user_id`     BIGINT       NOT NULL COMMENT '请求推荐的用户ID',
    `target_type` VARCHAR(50)  NOT NULL COMMENT '目标类型: TEAM / USER',
    `target_id`   BIGINT       NOT NULL COMMENT '目标ID',
    `match_score` DECIMAL(5,2) DEFAULT NULL COMMENT '匹配得分',
    `feedback`    TINYINT      DEFAULT NULL COMMENT '反馈: 0-无感 1-有用 2-无用',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_recommendation_user` (`user_id`),
    INDEX `idx_recommendation_target` (`target_type`, `target_id`),
    INDEX `idx_recommendation_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推荐日志表';

-- ----------------------------
-- 团队任务表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `team_tasks` (
    `id`               BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '任务ID',
    `team_id`          BIGINT       NOT NULL COMMENT '所属团队ID',
    `task_title`       VARCHAR(200) NOT NULL COMMENT '任务标题',
    `task_description` TEXT         DEFAULT NULL COMMENT '任务描述',
    `task_type`        VARCHAR(50)  DEFAULT 'OTHER' COMMENT '任务类型: DEVELOPMENT/DESIGN/TESTING/DOCUMENTATION/OTHER',
    `priority`         INT          DEFAULT 2 COMMENT '优先级: 1-低 2-中 3-高 4-紧急',
    `status`           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '任务状态: PENDING/IN_PROGRESS/REVIEW/COMPLETED/CANCELLED',
    `estimated_hours`  DECIMAL(10,2) DEFAULT NULL COMMENT '预估工时',
    `actual_hours`     DECIMAL(10,2) DEFAULT NULL COMMENT '实际工时',
    `deadline`         DATETIME     DEFAULT NULL COMMENT '截止时间',
    `assigned_to`      BIGINT       DEFAULT NULL COMMENT '指派的队员ID',
    `created_by`       BIGINT       NOT NULL COMMENT '创建人ID（通常为队长）',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `completed_at`     DATETIME     DEFAULT NULL COMMENT '完成时间',
    INDEX `idx_team_id` (`team_id`),
    INDEX `idx_assigned_to` (`assigned_to`),
    INDEX `idx_status` (`status`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队任务表';

-- ----------------------------
-- 任务参与者表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `task_participants` (
    `id`                 BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '参与者ID',
    `task_id`            BIGINT       NOT NULL COMMENT '任务ID',
    `user_id`            BIGINT       NOT NULL COMMENT '用户ID',
    `role`               VARCHAR(20)  DEFAULT 'member' COMMENT '角色: owner/member',
    `status`             VARCHAR(20)  DEFAULT 'active' COMMENT '状态: active/inactive',
    `joined_at`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    `contribution_hours` DECIMAL(10,2) DEFAULT NULL COMMENT '贡献工时',
    `contribution_rate`  DECIMAL(5,2)  DEFAULT NULL COMMENT '贡献率(%)',
    UNIQUE KEY `uk_task_user` (`task_id`, `user_id`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务参与者表';

-- ----------------------------
-- 任务评论表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `task_comments` (
    `id`            BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '评论ID',
    `task_id`       BIGINT       NOT NULL COMMENT '任务ID',
    `user_id`       BIGINT       NOT NULL COMMENT '评论人ID',
    `parent_id`     BIGINT       DEFAULT NULL COMMENT '父评论ID（用于回复）',
    `content`       TEXT         NOT NULL COMMENT '评论内容',
    `comment_type`  VARCHAR(20)  DEFAULT 'comment' COMMENT '类型: comment/reply',
    `mentions`      VARCHAR(500) DEFAULT NULL COMMENT '@提及的用户ID列表(JSON格式)',
    `attachments`   VARCHAR(1000) DEFAULT NULL COMMENT '附件URL列表(JSON格式)',
    `like_count`    INT          DEFAULT 0 COMMENT '点赞数',
    `is_deleted`    TINYINT      DEFAULT 0 COMMENT '是否删除: 0-正常 1-已删除',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务评论表';

-- ----------------------------
-- 通知表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `notification` (
    `id`           BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `user_id`      BIGINT       NOT NULL COMMENT '接收者用户ID',
    `sender_id`    BIGINT       DEFAULT NULL COMMENT '发送者用户ID',
    `type`         VARCHAR(50)  NOT NULL COMMENT '通知类型: TEAM_INVITE/TASK_ASSIGNED/TASK_COMPLETED/MILESTONE_UPDATE/RECOMMENDATION/SYSTEM',
    `title`        VARCHAR(200) NOT NULL COMMENT '通知标题',
    `content`      TEXT         DEFAULT NULL COMMENT '通知内容',
    `is_read`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读: 0-未读 1-已读',
    `related_id`   BIGINT       DEFAULT NULL COMMENT '关联业务ID',
    `related_type` VARCHAR(50)  DEFAULT NULL COMMENT '关联业务类型',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_notification_user` (`user_id`),
    INDEX `idx_notification_is_read` (`user_id`, `is_read`),
    INDEX `idx_notification_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';

-- ----------------------------
-- 项目里程碑表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `project_milestones` (
    `id`                    BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '里程碑ID',
    `team_id`               BIGINT       NOT NULL COMMENT '团队ID',
    `milestone_name`        VARCHAR(200) NOT NULL COMMENT '里程碑名称',
    `milestone_description` TEXT         DEFAULT NULL COMMENT '里程碑描述',
    `status`                VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/IN_PROGRESS/COMPLETED/DELAYED',
    `due_date`              DATETIME     NOT NULL COMMENT '计划完成日期',
    `completed_date`        DATETIME     DEFAULT NULL COMMENT '实际完成日期',
    `completion_rate`       TINYINT      NOT NULL DEFAULT 0 COMMENT '完成进度(0-100%)',
    `deliverables`          TEXT         DEFAULT NULL COMMENT '交付物(JSON格式)',
    `created_by`            BIGINT       NOT NULL COMMENT '创建人用户ID',
    `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX `idx_project_milestone_team_id` (`team_id`),
    INDEX `idx_project_milestone_status` (`status`),
    INDEX `idx_project_milestone_due_date` (`due_date`),
    INDEX `idx_project_milestone_created_by` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目里程碑表';

-- ----------------------------
-- 密码重置令牌表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `password_reset_token` (
    `id`           BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `user_id`      BIGINT       NOT NULL,
    `token_hash`   VARCHAR(64)  NOT NULL COMMENT 'Token哈希（SHA-256）',
    `expires_at`   DATETIME     NOT NULL COMMENT '过期时间',
    `used_at`      DATETIME     DEFAULT NULL COMMENT '使用时间',
    `request_ip`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '请求IP',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_password_reset_hash` (`token_hash`),
    INDEX `idx_password_reset_user` (`user_id`),
    INDEX `idx_password_reset_expiry` (`expires_at`),
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='密码重置令牌表';

-- ----------------------------
-- 记住我令牌表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `remember_me_token` (
    `id`             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `user_id`        BIGINT       NOT NULL,
    `selector`       VARCHAR(64)  NOT NULL COMMENT '选择器（Token前半部分）',
    `validator_hash` VARCHAR(64)  NOT NULL COMMENT '验证器哈希',
    `expires_at`     DATETIME     NOT NULL COMMENT '过期时间',
    `last_used_at`   DATETIME     NOT NULL COMMENT '最后使用时间',
    `user_agent`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '用户代理',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_remember_selector` (`selector`),
    INDEX `idx_remember_user` (`user_id`),
    INDEX `idx_remember_expiry` (`expires_at`),
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='记住我令牌表';

-- ----------------------------
-- 管理员操作审计日志表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `admin_audit_log` (
    `id`            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `admin_user_id` BIGINT       NOT NULL COMMENT '操作用户ID',
    `action`        VARCHAR(64)  NOT NULL COMMENT '操作类型: CREATE/UPDATE/DELETE',
    `target_type`   VARCHAR(64)  NOT NULL COMMENT '目标类型: USER/TEAM/TEACHER/ASSET/COMMUNITY_POST',
    `target_id`     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '目标ID',
    `details`       VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '操作详情',
    `ip_address`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '操作IP',
    `user_agent`    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '操作UA',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX `idx_admin_audit_admin_time` (`admin_user_id`, `created_at`),
    INDEX `idx_admin_audit_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员操作审计日志表';

-- ----------------------------
-- 竞赛目录表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `competition` (
    `id`                    BIGINT       PRIMARY KEY AUTO_INCREMENT,
    `name`                  VARCHAR(160) NOT NULL COMMENT '竞赛名称',
    `track`                 VARCHAR(32)  NOT NULL COMMENT '赛道: innovation/stem/cs/ee/robot/general',
    `organizer`             VARCHAR(160) NOT NULL COMMENT '主办机构',
    `season`                VARCHAR(160) NOT NULL DEFAULT '' COMMENT '赛季时间',
    `level_class`           VARCHAR(20)  NOT NULL COMMENT '级别分类: 一类A/一类B/二类A/二类B/三类',
    `scope`                 VARCHAR(20)  NOT NULL COMMENT '范围: 国赛/省赛/国际赛',
    `tags`                  TEXT         NOT NULL COMMENT '标签(JSON数组)',
    `description`           TEXT         NOT NULL COMMENT '竞赛介绍',
    `official_url`          VARCHAR(500) NOT NULL DEFAULT '' COMMENT '官方网站',
    `status`                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/INACTIVE',
    `registration_deadline` DATE         DEFAULT NULL COMMENT '报名截止日期',
    `created_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_competition_name` (`name`),
    INDEX `idx_competition_status_track` (`status`, `track`),
    INDEX `idx_competition_deadline` (`registration_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='竞赛目录表';

-- 竞赛种子数据
INSERT IGNORE INTO `competition`
    (name, track, organizer, season, level_class, scope, tags, description, official_url, status, created_at, updated_at)
VALUES
('中国国际大学生创新大赛（原"互联网+"）',      'innovation', '教育部等',                          '校赛约4–6月，国赛约9–10月', '一类A', '国赛', '["双创","路演","红旅"]',      '覆盖高教主赛道、职教赛道、产业命题、青年红色筑梦之旅等，适合有项目沉淀的团队。',              'https://cy.ncss.cn/', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('"挑战杯"全国大学生课外学术科技作品竞赛',      'innovation', '共青团中央等',                     '奇数年举办，校赛多在上半学年启动', '一类A', '国赛', '["大挑","学术","科技作品"]', '偏学术与科技创新作品展示，与"小挑"创业计划竞赛交替举办。', 'http://www.tiaozhanbei.net/', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('"挑战杯"中国大学生创业计划大赛',               'innovation', '共青团中央等',                     '偶数年举办',                '一类A', '国赛', '["小挑","创业计划"]',         '侧重创业计划与商业模式，适合早期创业项目打磨与路演。',       'http://www.tiaozhanbei.net/', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('全国大学生数学建模竞赛',                        'stem',      '中国工业与应用数学学会等',          '每年9月（通常3天赛）',        '一类B', '国赛', '["建模","论文","国赛"]',      '连续三天封闭式建模与论文写作，考察问题抽象、模型与求解、论文表达。', 'http://www.mcm.edu.cn/', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('美国大学生数学建模竞赛（MCM/ICM）',             'stem',      'COMAP',                            '每年1–2月（寒假）',           '三类', '国际赛', '["美赛","英文论文"]',         '英文论文、选题开放，常与国赛备赛体系衔接。',               'https://www.contest.comap.com/', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('ACM-ICPC / CCPC 系列',                         'cs',        '各赛区/组委会',                     '区域赛多在秋季，校队选拔因校而异', '一类B', '国赛', '["算法","程序设计","组队"]',  '5小时现场编程，强调算法与团队协作，适合长期训练队伍。',       'https://icpc.global/', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('蓝桥杯全国软件和信息技术专业人才大赛',          'cs',        '工信部人才交流中心等',               '省赛约4月，国赛约6月',        '一类B', '国赛/省赛', '["软件类","单片机","嵌入式"]', '个人赛为主，覆盖软件开发、嵌入式、物联网等方向，门槛相对友好。', 'https://dasai.lanqiao.cn/', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('华为ICT大赛',                                  'ee',        '华为技术有限公司',                 '年度赛',                     '一类B', '国赛', '["华为","ICT","云与网络"]',  '面向网络、云、计算、AI等方向的技术竞赛。',              'https://e.huawei.com/cn/talent/ict-academy/ict-competition', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ----------------------------
-- AI 助手调用记录表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ai_usage_log` (
    `id`                BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    `user_id`           BIGINT       NOT NULL COMMENT '调用用户',
    `team_id`           BIGINT       DEFAULT NULL COMMENT '所属团队（团队级功能时记录）',
    `action`            VARCHAR(50)  NOT NULL COMMENT '动作: TASK_BREAKDOWN/COMPETITION_QA',
    `prompt_tokens`     INT          DEFAULT NULL COMMENT '输入token数',
    `completion_tokens` INT          DEFAULT NULL COMMENT '输出token数',
    `success`           TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否成功',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '调用时间',
    INDEX `idx_ai_usage_user_time` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI助手调用记录表';
