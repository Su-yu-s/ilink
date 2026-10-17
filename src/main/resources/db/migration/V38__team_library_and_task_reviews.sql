-- 团队资料库 + 任务批阅记录 + 任务审阅开关
-- 设计文档《iLink · 团队资料与任务批阅设计文档 v1.0》一期落地。

-- 1) 团队资料库文件树：parent_id 自引用组树；is_deleted 软删除（回收站），
--    彻底删除时置 is_purged 并抹去 file_url，物理文件由清理任务处理。
CREATE TABLE `team_library_files` (
    `id`                 BIGINT(20)   NOT NULL AUTO_INCREMENT,
    `team_id`            BIGINT(20)   NOT NULL COMMENT '所属团队',
    `parent_id`          BIGINT(20)   DEFAULT NULL COMMENT '父文件夹ID，NULL=资料库根目录',
    `name`               VARCHAR(255) NOT NULL COMMENT '文件或文件夹名称',
    `is_folder`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=文件夹',
    `file_url`           VARCHAR(500) DEFAULT NULL COMMENT '文件访问URL（文件夹为NULL，彻底删除后抹去）',
    `original_file_name` VARCHAR(255) DEFAULT NULL COMMENT '上传时的原始文件名',
    `file_size`          BIGINT(20)   DEFAULT NULL COMMENT '文件大小（字节），文件夹为NULL',
    `file_type`          VARCHAR(20)  DEFAULT NULL COMMENT 'pdf/doc/img/zip/md/other，文件夹为NULL',
    `uploader_id`        BIGINT(20)   NOT NULL COMMENT '上传者/创建者（所有权依据）',
    `is_deleted`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0=正常 1=回收站',
    `deleted_at`         DATETIME     DEFAULT NULL,
    `deleted_by`         BIGINT(20)   DEFAULT NULL,
    `is_purged`          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=已彻底删除（回收站列表不再展示）',
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_team_parent` (`team_id`, `parent_id`, `is_deleted`),
    KEY `idx_team_deleted` (`team_id`, `is_deleted`, `deleted_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '团队资料库文件树';

-- 2) 任务批阅记录：现有 review 只改状态不留痕，时间线/逐文件点评/驳回理由在此落库。
--    verdict=COMMENT 为补充点评（队长/导师在已通过后追加意见，不改变任务状态）。
--    file_comments 为 JSON 数组：[{"fileId":1,"fileName":"材料.pdf","comment":"…"}]，
--    fileName 是快照，文件被删除后点评仍可展示。
CREATE TABLE `task_reviews` (
    `id`              BIGINT(20)    NOT NULL AUTO_INCREMENT,
    `task_id`         BIGINT(20)    NOT NULL,
    `submission_id`   BIGINT(20)    DEFAULT NULL COMMENT '本次批阅针对的提交记录，补充点评可为NULL',
    `reviewer_id`     BIGINT(20)    NOT NULL,
    `verdict`         VARCHAR(20)   NOT NULL COMMENT 'PASS=通过 REJECT=驳回 COMMENT=补充点评',
    `overall_comment` VARCHAR(2000) DEFAULT NULL COMMENT '整体点评',
    `file_comments`   TEXT          DEFAULT NULL COMMENT '逐文件点评 JSON 数组',
    `created_at`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_task_time` (`task_id`, `created_at`),
    KEY `idx_submission` (`submission_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '任务批阅记录';

-- 3) 任务是否需要审阅：默认 1（存量任务保持现有审核流程，行为不变）。
--    requires_review=0 的任务允许被分配者直接完成（IN_PROGRESS→COMPLETED）。
ALTER TABLE `team_tasks`
    ADD COLUMN `requires_review` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=需提交审阅(走REVIEW) 0=可直接完成';
