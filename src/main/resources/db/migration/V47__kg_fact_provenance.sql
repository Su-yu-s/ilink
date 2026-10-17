-- Keep each extracted claim tied to one source text unit. Existing ingestion
-- timestamps cannot be interpreted as historical validity dates.
ALTER TABLE `kg_relation`
    ADD COLUMN `fact_text` VARCHAR(1000) NULL AFTER `relation_type`,
    ADD COLUMN `source_chunk_id` BIGINT NULL AFTER `source_doc_id`,
    MODIFY COLUMN `valid_from` DATETIME NULL DEFAULT NULL COMMENT '原文明示的生效时间；NULL=未知',
    DROP INDEX `uk_src_type_tgt`,
    ADD UNIQUE KEY `uk_relation_source_chunk` (`source_id`, `target_id`, `relation_type`, `source_chunk_id`),
    ADD INDEX `idx_relation_source_chunk` (`source_chunk_id`);
