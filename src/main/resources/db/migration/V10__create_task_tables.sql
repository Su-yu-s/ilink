-- 创建团队任务表
CREATE TABLE IF NOT EXISTS `team_tasks` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `team_id` BIGINT NOT NULL COMMENT '所属团队ID',
    `task_title` VARCHAR(200) NOT NULL COMMENT '任务标题',
    `task_description` TEXT DEFAULT NULL COMMENT '任务描述',
    `task_type` VARCHAR(50) DEFAULT 'OTHER' COMMENT '任务类型: DEVELOPMENT/DESIGN/TESTING/DOCUMENTATION/OTHER',
    `priority` INT DEFAULT 2 COMMENT '优先级: 1-低 2-中 3-高 4-紧急',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态: PENDING/IN_PROGRESS/REVIEW/COMPLETED/CANCELLED',
    `estimated_hours` DECIMAL(10,2) DEFAULT NULL COMMENT '预估工时',
    `actual_hours` DECIMAL(10,2) DEFAULT NULL COMMENT '实际工时',
    `deadline` DATETIME DEFAULT NULL COMMENT '截止时间',
    `assigned_to` BIGINT DEFAULT NULL COMMENT '指派的队员ID',
    `created_by` BIGINT NOT NULL COMMENT '创建人ID（通常为队长）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (`id`),
    INDEX `idx_team_id` (`team_id`),
    INDEX `idx_assigned_to` (`assigned_to`),
    INDEX `idx_status` (`status`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='团队任务表';

-- 创建任务参与者表
CREATE TABLE IF NOT EXISTS `task_participants` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '参与者ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role` VARCHAR(20) DEFAULT 'member' COMMENT '角色: owner/member',
    `status` VARCHAR(20) DEFAULT 'active' COMMENT '状态: active/inactive',
    `joined_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    `contribution_hours` DECIMAL(10,2) DEFAULT NULL COMMENT '贡献工时',
    `contribution_rate` DECIMAL(5,2) DEFAULT NULL COMMENT '贡献率(%)',
    PRIMARY KEY (`id`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_user_id` (`user_id`),
    UNIQUE INDEX `uk_task_user` (`task_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务参与者表';

-- 创建任务评论表
CREATE TABLE IF NOT EXISTS `task_comments` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `user_id` BIGINT NOT NULL COMMENT '评论人ID',
    `parent_id` BIGINT DEFAULT NULL COMMENT '父评论ID（用于回复）',
    `content` TEXT NOT NULL COMMENT '评论内容',
    `comment_type` VARCHAR(20) DEFAULT 'comment' COMMENT '类型: comment/reply',
    `like_count` INT DEFAULT 0 COMMENT '点赞数',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '是否删除: 0-正常 1-已删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务评论表';
