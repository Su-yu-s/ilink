-- Team Agent workbench: versioned task writes, recoverable indexing and
-- private conversation/run/action persistence. MySQL 5.7 compatible.

ALTER TABLE `team_tasks`
    ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本';

-- V1 曾声明该列，但历史 baseline 库可能缺少它。MySQL 5.7 不支持
-- ADD COLUMN IF NOT EXISTS，因此用 information_schema 做一次性兼容补齐。
SET @task_participants_left_at_exists = (
    SELECT COUNT(*)
    FROM `information_schema`.`COLUMNS`
    WHERE `TABLE_SCHEMA` = DATABASE()
      AND `TABLE_NAME` = 'task_participants'
      AND `COLUMN_NAME` = 'left_at'
);
SET @add_task_participants_left_at = IF(
    @task_participants_left_at_exists = 0,
    'ALTER TABLE `task_participants` ADD COLUMN `left_at` DATETIME NULL COMMENT ''离开时间'' AFTER `joined_at`',
    'SELECT 1'
);
PREPARE add_task_participants_left_at_stmt FROM @add_task_participants_left_at;
EXECUTE add_task_participants_left_at_stmt;
DEALLOCATE PREPARE add_task_participants_left_at_stmt;

ALTER TABLE `kg_index_task`
    ADD COLUMN `requested_revision` BIGINT NOT NULL DEFAULT 0 AFTER `user_id`,
    ADD COLUMN `processed_revision` BIGINT NOT NULL DEFAULT 0 AFTER `requested_revision`,
    ADD COLUMN `source_hash` CHAR(64) NULL AFTER `processed_revision`,
    ADD COLUMN `operation` VARCHAR(16) NOT NULL DEFAULT 'UPSERT' AFTER `source_hash`,
    ADD COLUMN `available_at` DATETIME(3) NULL AFTER `operation`,
    ADD COLUMN `lease_owner` VARCHAR(64) NULL AFTER `available_at`,
    ADD COLUMN `lease_token` CHAR(64) NULL AFTER `lease_owner`,
    ADD COLUMN `lease_until` DATETIME(3) NULL AFTER `lease_token`,
    ADD INDEX `idx_index_claim` (`status`, `available_at`, `lease_until`);

ALTER TABLE `kg_agent_trace`
    ADD COLUMN `team_id` BIGINT NULL AFTER `user_id`,
    ADD COLUMN `conversation_id` BIGINT NULL AFTER `team_id`,
    ADD COLUMN `run_id` BIGINT NULL AFTER `conversation_id`,
    ADD INDEX `idx_trace_team_created` (`team_id`, `created_at`);

CREATE TABLE `team_agent_conversation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `owner_user_id` BIGINT NOT NULL,
    `client_request_id` VARCHAR(64) NOT NULL,
    `create_request_hash` CHAR(64) NOT NULL,
    `title` VARCHAR(100) NOT NULL,
    `title_source` VARCHAR(16) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `context_snapshot_json` TEXT NULL,
    `last_message_at` DATETIME(3) NOT NULL,
    `message_seq` BIGINT NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    `version` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv_client_request` (`team_id`, `owner_user_id`, `client_request_id`),
    INDEX `idx_conv_owner` (`team_id`, `owner_user_id`, `status`, `last_message_at`),
    INDEX `idx_conv_updated` (`team_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队 Agent 私有会话';

CREATE TABLE `team_agent_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `conversation_id` BIGINT NOT NULL,
    `seq` BIGINT NOT NULL,
    `role` VARCHAR(16) NOT NULL,
    `author_user_id` BIGINT NULL,
    `content` MEDIUMTEXT NULL,
    `content_format` VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN',
    `status` VARCHAR(16) NOT NULL DEFAULT 'STREAMING',
    `citations_json` MEDIUMTEXT NULL,
    `run_id` BIGINT NULL,
    `created_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    `version` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_seq` (`conversation_id`, `seq`),
    UNIQUE KEY `uk_message_run_role` (`run_id`, `role`),
    INDEX `idx_message_team_conv` (`team_id`, `conversation_id`, `seq`),
    INDEX `idx_message_run` (`run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队 Agent 消息（无已读状态）';

CREATE TABLE `team_agent_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `conversation_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `request_message_id` BIGINT NOT NULL,
    `response_message_id` BIGINT NULL,
    `client_request_id` VARCHAR(64) NOT NULL,
    `requested_mode` VARCHAR(24) NOT NULL,
    `resolved_route` VARCHAR(32) NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    `input_hash` CHAR(64) NOT NULL,
    `context_snapshot_json` MEDIUMTEXT NOT NULL,
    `model_name` VARCHAR(64) NULL,
    `prompt_version` VARCHAR(32) NOT NULL,
    `rounds` INT NOT NULL DEFAULT 0,
    `knowledge_tool_calls` INT NOT NULL DEFAULT 0,
    `business_tool_calls` INT NOT NULL DEFAULT 0,
    `model_call_count` INT NOT NULL DEFAULT 0,
    `event_seq` BIGINT NOT NULL DEFAULT 0,
    `prompt_tokens` INT NULL,
    `completion_tokens` INT NULL,
    `estimated_cost_micros` BIGINT NULL,
    `elapsed_ms` BIGINT NOT NULL DEFAULT 0,
    `cancel_request_id` VARCHAR(64) NULL,
    `cancel_requested_at` DATETIME(3) NULL,
    `worker_id` VARCHAR(64) NULL,
    `worker_lease_token` CHAR(64) NULL,
    `lease_until` DATETIME(3) NULL,
    `error_code` VARCHAR(64) NULL,
    `error_message` VARCHAR(1000) NULL,
    `created_at` DATETIME(3) NOT NULL,
    `started_at` DATETIME(3) NULL,
    `finished_at` DATETIME(3) NULL,
    `version` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_client_request` (`team_id`, `user_id`, `client_request_id`),
    UNIQUE KEY `uk_run_request_message` (`request_message_id`),
    UNIQUE KEY `uk_run_response_message` (`response_message_id`),
    INDEX `idx_run_user_status` (`team_id`, `user_id`, `status`, `created_at`),
    INDEX `idx_run_lease` (`status`, `lease_until`),
    INDEX `idx_run_conversation` (`conversation_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队 Agent 运行';

CREATE TABLE `team_agent_run_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `run_id` BIGINT NOT NULL,
    `seq` BIGINT NOT NULL,
    `event_type` VARCHAR(32) NOT NULL,
    `payload_json` MEDIUMTEXT NOT NULL,
    `created_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_event_seq` (`run_id`, `seq`),
    INDEX `idx_run_event_created` (`run_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队 Agent 可重放运行事件';

CREATE TABLE `team_agent_action_group` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `conversation_id` BIGINT NULL,
    `run_id` BIGINT NULL,
    `initiator_user_id` BIGINT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `client_request_id` VARCHAR(64) NULL,
    `decision_type` VARCHAR(24) NULL,
    `request_hash` CHAR(64) NULL,
    `expires_at` DATETIME(3) NOT NULL,
    `decided_at` DATETIME(3) NULL,
    `executed_at` DATETIME(3) NULL,
    `error_code` VARCHAR(64) NULL,
    `error_message` VARCHAR(1000) NULL,
    `created_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    `version` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_decision_request` (`team_id`, `initiator_user_id`, `client_request_id`),
    UNIQUE KEY `uk_group_run` (`run_id`),
    INDEX `idx_group_owner_status` (`team_id`, `initiator_user_id`, `status`, `created_at`),
    INDEX `idx_group_expiry` (`status`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队 Agent 动作组';

CREATE TABLE `team_agent_action` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `group_id` BIGINT NOT NULL,
    `team_id` BIGINT NOT NULL,
    `seq` INT NOT NULL,
    `schema_version` VARCHAR(16) NOT NULL,
    `action_type` VARCHAR(32) NOT NULL,
    `target_type` VARCHAR(32) NOT NULL,
    `target_id` BIGINT NULL,
    `expected_target_version` INT NULL,
    `before_json` MEDIUMTEXT NULL,
    `after_json` MEDIUMTEXT NOT NULL,
    `reason` VARCHAR(500) NULL,
    `evidence_json` MEDIUMTEXT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `result_json` MEDIUMTEXT NULL,
    `undo_status` VARCHAR(20) NOT NULL DEFAULT 'NOT_SUPPORTED',
    `undo_request_id` VARCHAR(64) NULL,
    `undo_request_hash` CHAR(64) NULL,
    `undo_deadline` DATETIME(3) NULL,
    `undo_result_json` MEDIUMTEXT NULL,
    `undo_error_code` VARCHAR(64) NULL,
    `undo_error_message` VARCHAR(1000) NULL,
    `error_code` VARCHAR(64) NULL,
    `error_message` VARCHAR(1000) NULL,
    `created_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    `executed_at` DATETIME(3) NULL,
    `undone_at` DATETIME(3) NULL,
    `version` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_action_group_seq` (`group_id`, `seq`),
    UNIQUE KEY `uk_action_undo_request` (`id`, `undo_request_id`),
    INDEX `idx_action_team_created` (`team_id`, `created_at`),
    INDEX `idx_action_target` (`target_type`, `target_id`),
    INDEX `idx_action_undo` (`team_id`, `undo_status`, `undo_deadline`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队 Agent 白名单动作';

CREATE TABLE `team_agent_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `conversation_id` BIGINT NULL,
    `run_id` BIGINT NULL,
    `action_group_id` BIGINT NULL,
    `action_id` BIGINT NULL,
    `actor_type` VARCHAR(16) NOT NULL,
    `actor_user_id` BIGINT NULL,
    `event_type` VARCHAR(48) NOT NULL,
    `outcome` VARCHAR(16) NOT NULL,
    `request_id` VARCHAR(64) NOT NULL,
    `metadata_json` MEDIUMTEXT NULL,
    `created_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_audit_team_time` (`team_id`, `created_at`),
    INDEX `idx_audit_action` (`action_id`, `created_at`),
    INDEX `idx_audit_request` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队 Agent 只追加审计';

-- Inventory existing tasks into the same upsert queue. Existing queue rows are
-- advanced to a new revision so old task snapshots are never treated as fresh.
INSERT INTO `kg_index_task`
    (`doc_type`, `doc_id`, `title`, `content`, `status`, `retry_count`,
     `scope`, `team_id`, `user_id`, `requested_revision`, `processed_revision`,
     `source_hash`, `operation`, `available_at`, `created_at`, `updated_at`)
SELECT
    'TASK',
    t.`id`,
    t.`task_title`,
    CONCAT_WS('\n',
        CONCAT('任务ID：', t.`id`),
        CONCAT('标题：', COALESCE(t.`task_title`, '')),
        CONCAT('描述：', COALESCE(t.`task_description`, '')),
        CONCAT('状态：', COALESCE(t.`status`, '')),
        CONCAT('优先级：', COALESCE(t.`priority`, '')),
        CONCAT('截止时间：', COALESCE(DATE_FORMAT(t.`deadline`, '%Y-%m-%d %H:%i:%s'), '')),
        CONCAT('负责人ID：', COALESCE(t.`assigned_to`, '')),
        CONCAT('需审核：', COALESCE(t.`requires_review`, '')),
        CONCAT('父任务ID：', COALESCE(t.`parent_task_id`, '')),
        CONCAT('更新时间：', COALESCE(DATE_FORMAT(t.`updated_at`, '%Y-%m-%d %H:%i:%s'), '')),
        CONCAT('版本：', t.`version`)),
    'PENDING',
    0,
    'TEAM',
    t.`team_id`,
    COALESCE(t.`created_by`, 0),
    1,
    0,
    SHA2(CONCAT(
        'iLink/team-agent/kg-source/v1', CHAR(0),
        t.`id`, '\n',
        COALESCE(t.`task_title`, ''), '\n',
        COALESCE(t.`task_description`, ''), '\n',
        COALESCE(t.`status`, ''), '\n',
        COALESCE(t.`priority`, ''), '\n',
        COALESCE(DATE_FORMAT(t.`deadline`, '%Y-%m-%d %H:%i:%s.%f'), ''), '\n',
        COALESCE(t.`assigned_to`, ''), '\n',
        COALESCE(t.`requires_review`, ''), '\n',
        COALESCE(t.`parent_task_id`, ''), '\n',
        COALESCE(DATE_FORMAT(t.`updated_at`, '%Y-%m-%d %H:%i:%s.%f'), ''), '\n',
        t.`version`
    ), 256),
    'UPSERT',
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3),
    CURRENT_TIMESTAMP(3)
FROM `team_tasks` t
ON DUPLICATE KEY UPDATE
    `title` = VALUES(`title`),
    `content` = VALUES(`content`),
    `status` = 'PENDING',
    `retry_count` = 0,
    `error_msg` = NULL,
    `scope` = VALUES(`scope`),
    `team_id` = VALUES(`team_id`),
    `user_id` = VALUES(`user_id`),
    `requested_revision` = `requested_revision` + 1,
    `source_hash` = VALUES(`source_hash`),
    `operation` = 'UPSERT',
    `available_at` = CURRENT_TIMESTAMP(3),
    `lease_owner` = NULL,
    `lease_token` = NULL,
    `lease_until` = NULL,
    `finished_at` = NULL,
    `updated_at` = CURRENT_TIMESTAMP(3);
