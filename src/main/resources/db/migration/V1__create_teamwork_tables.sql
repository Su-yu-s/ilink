-- ============================================
-- iLink 智能组队与协作工作台数据库表结构
-- 迁移版本: V1
-- 创建日期: 2026-05-10
-- ============================================

-- ------------------------------------------
-- 1. 用户技能表 (user_skills)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `user_skills` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '技能记录ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `skill_name` VARCHAR(100) NOT NULL COMMENT '技能名称',
    `skill_level` TINYINT NOT NULL DEFAULT 3 COMMENT '技能等级(1-5)',
    `skill_category` VARCHAR(50) DEFAULT NULL COMMENT '技能分类(编程/设计/产品/运营等)',
    `certification` VARCHAR(200) DEFAULT NULL COMMENT '资质认证',
    `years_experience` INT DEFAULT 0 COMMENT '从业年限',
    `portfolio_url` VARCHAR(500) DEFAULT NULL COMMENT '作品集链接',
    `is_verified` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已认证(0-未认证,1-已认证)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_skill_name` (`skill_name`),
    INDEX `idx_skill_category` (`skill_category`),
    UNIQUE KEY `uk_user_skill` (`user_id`, `skill_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户技能表';

-- ------------------------------------------
-- 2. 团队任务表 (team_tasks)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `team_tasks` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `team_id` BIGINT NOT NULL COMMENT '团队ID',
    `task_title` VARCHAR(200) NOT NULL COMMENT '任务标题',
    `task_description` TEXT COMMENT '任务描述',
    `task_type` VARCHAR(50) NOT NULL COMMENT '任务类型(开发/设计/测试/文档/其他)',
    `priority` TINYINT NOT NULL DEFAULT 2 COMMENT '优先级(1-低,2-中,3-高,4-紧急)',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '任务状态(pending/in_progress/review/completed/cancelled)',
    `estimated_hours` DECIMAL(5,2) DEFAULT NULL COMMENT '预计工时(小时)',
    `actual_hours` DECIMAL(5,2) DEFAULT NULL COMMENT '实际工时(小时)',
    `deadline` DATETIME DEFAULT NULL COMMENT '截止日期',
    `assigned_to` BIGINT DEFAULT NULL COMMENT '负责人用户ID',
    `created_by` BIGINT NOT NULL COMMENT '创建人用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (`id`),
    INDEX `idx_team_id` (`team_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_assigned_to` (`assigned_to`),
    INDEX `idx_created_by` (`created_by`),
    INDEX `idx_deadline` (`deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队任务表';

-- ------------------------------------------
-- 3. 任务参与者关联表 (task_participants)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `task_participants` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '关联记录ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role` VARCHAR(50) NOT NULL DEFAULT 'member' COMMENT '角色(owner/lead/member/reviewer)',
    `contribution_hours` DECIMAL(5,2) DEFAULT 0 COMMENT '贡献工时',
    `contribution_rate` DECIMAL(5,2) DEFAULT 0 COMMENT '贡献占比(%)',
    `joined_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '参与时间',
    `left_at` DATETIME DEFAULT NULL COMMENT '离开时间',
    `status` VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态(active/inactive/left)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_user` (`task_id`, `user_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务参与者关联表';

-- ------------------------------------------
-- 4. 任务评论表 (task_comments)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `task_comments` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `parent_id` BIGINT DEFAULT NULL COMMENT '父评论ID(用于回复)',
    `user_id` BIGINT NOT NULL COMMENT '评论用户ID',
    `content` TEXT NOT NULL COMMENT '评论内容',
    `comment_type` VARCHAR(20) NOT NULL DEFAULT 'comment' COMMENT '类型(comment/reply/update)',
    `mentions` VARCHAR(500) DEFAULT NULL COMMENT '@提及的用户ID列表(JSON格式)',
    `attachments` VARCHAR(1000) DEFAULT NULL COMMENT '附件URL列表(JSON格式)',
    `like_count` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除(0-否,1-是)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_parent_id` (`parent_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务评论表';

-- ------------------------------------------
-- 5. 项目里程碑表 (project_milestones)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `project_milestones` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '里程碑ID',
    `team_id` BIGINT NOT NULL COMMENT '团队ID',
    `milestone_name` VARCHAR(200) NOT NULL COMMENT '里程碑名称',
    `milestone_description` TEXT COMMENT '里程碑描述',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态(pending/in_progress/completed/delayed)',
    `due_date` DATETIME NOT NULL COMMENT '计划完成日期',
    `completed_date` DATETIME DEFAULT NULL COMMENT '实际完成日期',
    `completion_rate` TINYINT NOT NULL DEFAULT 0 COMMENT '完成进度(0-100%)',
    `deliverables` TEXT COMMENT '交付物(JSON格式)',
    `created_by` BIGINT NOT NULL COMMENT '创建人用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_team_id` (`team_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_due_date` (`due_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目里程碑表';

-- ------------------------------------------
-- 6. 智能推荐日志表 (recommendation_log)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `recommendation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '推荐记录ID',
    `team_id` BIGINT NOT NULL COMMENT '团队ID',
    `recommended_user_id` BIGINT NOT NULL COMMENT '被推荐用户ID',
    `recommendation_type` VARCHAR(50) NOT NULL COMMENT '推荐类型(skill_match/availability/compatibility/personality)',
    `match_score` DECIMAL(5,2) NOT NULL COMMENT '匹配分数(0-100)',
    `skill_match_details` TEXT COMMENT '技能匹配详情(JSON格式)',
    `reason` VARCHAR(500) DEFAULT NULL COMMENT '推荐理由',
    `status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '状态(pending/viewed/accepted/declined/expired)',
    `viewed_at` DATETIME DEFAULT NULL COMMENT '查看时间',
    `responded_at` DATETIME DEFAULT NULL COMMENT '响应时间',
    `expires_at` DATETIME DEFAULT NULL COMMENT '过期时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_team_id` (`team_id`),
    INDEX `idx_recommended_user_id` (`recommended_user_id`),
    INDEX `idx_recommendation_type` (`recommendation_type`),
    INDEX `idx_status` (`status`),
    INDEX `idx_match_score` (`match_score`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能推荐日志表';

-- ------------------------------------------
-- 7. 团队聊天消息表 (team_messages)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `team_messages` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `team_id` BIGINT NOT NULL COMMENT '团队ID',
    `task_id` BIGINT DEFAULT NULL COMMENT '关联任务ID(可选)',
    `sender_id` BIGINT NOT NULL COMMENT '发送者用户ID',
    `message_type` VARCHAR(20) NOT NULL DEFAULT 'text' COMMENT '消息类型(text/image/file/link/task_reminder)',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `metadata` TEXT COMMENT '元数据(JSON格式:文件信息等)',
    `mentions` VARCHAR(500) DEFAULT NULL COMMENT '@提及的用户ID列表(JSON格式)',
    `reply_to` BIGINT DEFAULT NULL COMMENT '回复的消息ID',
    `is_pinned` TINYINT NOT NULL DEFAULT 0 COMMENT '是否置顶(0-否,1-是)',
    `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除(0-否,1-是)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_team_id` (`team_id`),
    INDEX `idx_sender_id` (`sender_id`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_message_type` (`message_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队聊天消息表';

-- ------------------------------------------
-- 8. 技能匹配规则表 (skill_match_rules)
-- ------------------------------------------
CREATE TABLE IF NOT EXISTS `skill_match_rules` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则ID',
    `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
    `rule_type` VARCHAR(50) NOT NULL COMMENT '规则类型(skill_importance/skill_conflict/workload_balance/availability)',
    `rule_config` TEXT NOT NULL COMMENT '规则配置(JSON格式)',
    `weight` DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '权重系数(0.00-1.00)',
    `priority` INT NOT NULL DEFAULT 100 COMMENT '优先级(数字越小优先级越高)',
    `is_active` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用(0-禁用,1-启用)',
    `description` TEXT COMMENT '规则描述',
    `created_by` BIGINT NOT NULL COMMENT '创建人用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_rule_type` (`rule_type`),
    INDEX `idx_is_active` (`is_active`),
    INDEX `idx_priority` (`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='技能匹配规则表';

-- ------------------------------------------
-- 初始化默认匹配规则
-- ------------------------------------------
INSERT INTO `skill_match_rules` (`rule_name`, `rule_type`, `rule_config`, `weight`, `priority`, `is_active`, `description`, `created_by`) VALUES
('技能匹配权重', 'skill_importance', '{"primary_skills_weight": 0.4, "secondary_skills_weight": 0.2}', 1.00, 10, 1, '根据技能匹配度计算推荐分数', 1),
('技能互斥规则', 'skill_conflict', '{"conflicts": [{"skill_a": "前端开发", "skill_b": "后端开发", "penalty": 0.5}]}', 0.80, 20, 1, '避免技能过度重叠导致效率降低', 1),
('工作负载均衡', 'workload_balance', '{"max_tasks_per_user": 3, "max_hours_per_week": 40}', 0.60, 30, 1, '确保成员工作量均衡分配', 1),
('可用时间匹配', 'availability', '{"min_overlap_hours": 10, "timezone_tolerance": 2}', 0.70, 40, 1, '根据可用时间进行智能匹配', 1),
('协作历史评分', 'compatibility', '{"success_threshold": 0.8, "history_weight": 0.3}', 0.50, 50, 1, '基于历史协作成功率推荐', 1);
