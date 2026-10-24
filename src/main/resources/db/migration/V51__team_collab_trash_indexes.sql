-- Recoverable trash queries and nightly expiry scans filter on deleted_at.
-- MySQL 5.7 does not support partial indexes, so a dedicated index is needed.

ALTER TABLE `team_collab_document`
    ADD INDEX `idx_doc_team_deleted` (`team_id`, `deleted_at`),
    ADD INDEX `idx_doc_deleted` (`deleted_at`);

ALTER TABLE `team_collab_spreadsheet`
    ADD INDEX `idx_sheet_team_deleted` (`team_id`, `deleted_at`),
    ADD INDEX `idx_sheet_deleted` (`deleted_at`);

ALTER TABLE `team_material_evidence`
    ADD INDEX `idx_material_evidence_asset` (`team_id`, `asset_type`, `asset_id`);
