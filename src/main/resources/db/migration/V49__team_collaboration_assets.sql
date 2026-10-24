-- M3 team collaboration assets: Markdown documents, structured spreadsheets,
-- version snapshots, asset ACLs/leases/events and competition material matrix.
-- MySQL 5.7 compatible: explicit DATETIME(3), no JSON constraints/generated columns.

CREATE TABLE `team_collab_document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `title` VARCHAR(120) NOT NULL,
    `content_markdown` MEDIUMTEXT NOT NULL,
    `visibility` VARCHAR(16) NOT NULL DEFAULT 'TEAM',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `version` INT NOT NULL DEFAULT 1,
    `content_hash` CHAR(64) NOT NULL,
    `creator_user_id` BIGINT NULL,
    `last_editor_user_id` BIGINT NULL,
    `created_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    `deleted_at` DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_doc_team_status` (`team_id`, `status`, `deleted_at`, `updated_at`),
    INDEX `idx_doc_creator` (`creator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队协作文档';

CREATE TABLE `team_collab_document_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `document_id` BIGINT NOT NULL,
    `team_id` BIGINT NOT NULL,
    `version` INT NOT NULL,
    `title` VARCHAR(120) NOT NULL,
    `content_markdown` MEDIUMTEXT NOT NULL,
    `content_hash` CHAR(64) NOT NULL,
    `save_kind` VARCHAR(16) NOT NULL,
    `actor_user_id` BIGINT NULL,
    `change_summary` VARCHAR(500) NULL,
    `created_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_version` (`document_id`, `version`),
    INDEX `idx_doc_version_team` (`team_id`, `document_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队文档版本快照';

CREATE TABLE `team_collab_spreadsheet` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `title` VARCHAR(120) NOT NULL,
    `columns_json` MEDIUMTEXT NOT NULL,
    `rows_json` MEDIUMTEXT NOT NULL,
    `visibility` VARCHAR(16) NOT NULL DEFAULT 'TEAM',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `version` INT NOT NULL DEFAULT 1,
    `content_hash` CHAR(64) NOT NULL,
    `creator_user_id` BIGINT NULL,
    `last_editor_user_id` BIGINT NULL,
    `created_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    `deleted_at` DATETIME(3) NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_sheet_team_status` (`team_id`, `status`, `deleted_at`, `updated_at`),
    INDEX `idx_sheet_creator` (`creator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队协作表格';

CREATE TABLE `team_collab_spreadsheet_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `spreadsheet_id` BIGINT NOT NULL,
    `team_id` BIGINT NOT NULL,
    `version` INT NOT NULL,
    `title` VARCHAR(120) NOT NULL,
    `columns_json` MEDIUMTEXT NOT NULL,
    `rows_json` MEDIUMTEXT NOT NULL,
    `content_hash` CHAR(64) NOT NULL,
    `save_kind` VARCHAR(16) NOT NULL,
    `actor_user_id` BIGINT NULL,
    `change_summary` VARCHAR(500) NULL,
    `created_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sheet_version` (`spreadsheet_id`, `version`),
    INDEX `idx_sheet_version_team` (`team_id`, `spreadsheet_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队表格版本快照';

CREATE TABLE `team_collab_acl` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `asset_type` VARCHAR(16) NOT NULL,
    `asset_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `permission` VARCHAR(16) NOT NULL,
    `granted_by` BIGINT NULL,
    `created_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_collab_acl` (`asset_type`, `asset_id`, `user_id`),
    INDEX `idx_acl_team_user` (`team_id`, `user_id`, `asset_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队协作资产显式授权';

CREATE TABLE `team_collab_edit_lease` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `asset_type` VARCHAR(16) NOT NULL,
    `asset_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `token_hash` CHAR(64) NOT NULL,
    `base_version` INT NOT NULL,
    `acquired_at` DATETIME(3) NOT NULL,
    `renewed_at` DATETIME(3) NOT NULL,
    `expires_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_collab_lease_asset` (`asset_type`, `asset_id`),
    INDEX `idx_collab_lease_expiry` (`expires_at`),
    INDEX `idx_collab_lease_team_user` (`team_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队协作资产编辑租约';

CREATE TABLE `team_collab_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `asset_type` VARCHAR(16) NOT NULL,
    `asset_id` BIGINT NOT NULL,
    `actor_user_id` BIGINT NULL,
    `event_type` VARCHAR(48) NOT NULL,
    `outcome` VARCHAR(16) NOT NULL,
    `version` INT NULL,
    `request_id` VARCHAR(64) NULL,
    `metadata_json` MEDIUMTEXT NULL,
    `created_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_collab_event_team` (`team_id`, `created_at`),
    INDEX `idx_collab_event_asset` (`asset_type`, `asset_id`, `created_at`),
    INDEX `idx_collab_event_request` (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='团队协作资产只追加事件';

CREATE TABLE `team_material_requirement` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `competition_id` INT NULL,
    `custom_competition` VARCHAR(200) NULL,
    `title` VARCHAR(200) NOT NULL,
    `description` VARCHAR(2000) NULL,
    `category` VARCHAR(64) NULL,
    `required` TINYINT(1) NOT NULL DEFAULT 1,
    `source_type` VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    `source_doc_type` VARCHAR(32) NULL,
    `source_doc_id` BIGINT NULL,
    `source_chunk_id` BIGINT NULL,
    `source_excerpt` VARCHAR(1000) NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `review_status` VARCHAR(20) NOT NULL DEFAULT 'UNCHECKED',
    `current_review_id` BIGINT NULL,
    `sort_order` INT NOT NULL DEFAULT 0,
    `review_version` INT NOT NULL DEFAULT 0,
    `version` INT NOT NULL DEFAULT 0,
    `created_by` BIGINT NULL,
    `updated_by` BIGINT NULL,
    `created_at` DATETIME(3) NOT NULL,
    `updated_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_material_req_team` (`team_id`, `status`, `sort_order`, `id`),
    INDEX `idx_material_req_review` (`team_id`, `review_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='竞赛材料要求';

CREATE TABLE `team_material_evidence` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `requirement_id` BIGINT NOT NULL,
    `asset_type` VARCHAR(16) NOT NULL,
    `asset_id` BIGINT NOT NULL,
    `evidence_chunk_id` BIGINT NULL,
    `evidence_excerpt` VARCHAR(1000) NULL,
    `match_status` VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED',
    `created_by` BIGINT NULL,
    `created_at` DATETIME(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_material_evidence` (`requirement_id`, `asset_type`, `asset_id`),
    INDEX `idx_material_evidence_team` (`team_id`, `requirement_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='材料要求证据关联';

CREATE TABLE `team_material_review` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `team_id` BIGINT NOT NULL,
    `requirement_id` BIGINT NOT NULL,
    `status` VARCHAR(20) NOT NULL,
    `note` VARCHAR(1000) NULL,
    `reviewed_by` BIGINT NULL,
    `reviewed_at` DATETIME(3) NOT NULL,
    `version` INT NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`),
    INDEX `idx_material_review_team` (`team_id`, `requirement_id`, `reviewed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='材料人工复核记录';
