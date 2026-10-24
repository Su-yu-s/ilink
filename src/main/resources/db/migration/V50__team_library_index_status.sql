-- Add asynchronous text-index state to team library files. Existing rows start
-- PENDING so the Worker can index them after upgrade without blocking uploads.

ALTER TABLE `team_library_files`
    ADD COLUMN `content_revision` BIGINT NOT NULL DEFAULT 0 AFTER `updated_at`,
    ADD COLUMN `index_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER `content_revision`,
    ADD COLUMN `index_error` VARCHAR(500) NULL AFTER `index_status`,
    ADD COLUMN `indexed_at` DATETIME(3) NULL AFTER `index_error`,
    ADD INDEX `idx_library_index_status` (`team_id`, `index_status`, `updated_at`);
