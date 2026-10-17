-- =====================================================================
-- V33: Agentic GraphRAG 知识图谱初始化（7 张新表）
-- 原则：全部为新增表，不修改任何现有表；不使用外键（与现有表风格一致）；
--       MySQL 5.7 语法，utf8mb4_unicode_ci；scope 团队/私有隔离用
--       team_id=0 / user_id=0 表示"不限定"，保证 UNIQUE 约束在 NULL 语义下依然生效。
-- =====================================================================

-- 1. 实体表
CREATE TABLE `kg_entity` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '实体ID',
    `name`          VARCHAR(191) NOT NULL COMMENT '归一化名称（小写、去空格标点），用于对齐去重',
    `display_name`  VARCHAR(255) NOT NULL COMMENT '展示名称（原始大小写）',
    `entity_type`   VARCHAR(32)  NOT NULL COMMENT '实体类型：COMPETITION/SKILL/TEACHER/ORGANIZATION/POST',
    `description`   TEXT         NULL COMMENT '实体描述（来自抽取结果）',
    `scope`         VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT '可见范围：GLOBAL/TEAM/PRIVATE',
    `team_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '所属团队ID，0=不限定',
    `user_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '所属用户ID（PRIVATE 时有效），0=不限定',
    `doc_count`     INT          NOT NULL DEFAULT 1 COMMENT '来源文档计数（对齐合并时保留计数最多的一条）',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/MERGED/DELETED',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name_type` (`name`, `entity_type`, `scope`, `team_id`, `user_id`),
    INDEX `idx_entity_type` (`entity_type`),
    INDEX `idx_scope_team` (`scope`, `team_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识图谱实体表';

-- 2. 关系表（时序：valid_from/valid_to，NULL 表示仍有效）
CREATE TABLE `kg_relation` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '关系ID',
    `source_id`     BIGINT       NOT NULL COMMENT '源实体ID',
    `target_id`     BIGINT       NOT NULL COMMENT '目标实体ID',
    `relation_type` VARCHAR(32)  NOT NULL COMMENT '关系类型：REQUIRES/TEACHES/HOSTED_BY/RELATED_TO/MENTIONS',
    `weight`        DOUBLE       NOT NULL DEFAULT 1.0 COMMENT '关系权重（重复出现累加）',
    `valid_from`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
    `valid_to`      DATETIME     NULL COMMENT '失效时间，NULL=仍然有效',
    `scope`         VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT '可见范围：GLOBAL/TEAM/PRIVATE',
    `team_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '所属团队ID，0=不限定',
    `user_id`       BIGINT       NOT NULL DEFAULT 0 COMMENT '所属用户ID，0=不限定',
    `source_doc_id` VARCHAR(128) NULL COMMENT '抽取来源文档标识（docType:docId）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_src_type_tgt` (`source_id`, `target_id`, `relation_type`, `valid_from`),
    INDEX `idx_source` (`source_id`),
    INDEX `idx_target` (`target_id`),
    INDEX `idx_relation_type` (`relation_type`),
    INDEX `idx_scope_team` (`scope`, `team_id`),
    INDEX `idx_valid_range` (`valid_from`, `valid_to`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识图谱关系表（带时序）';

-- 3. 实体向量表（BLOB 存 float32 小端序列）
CREATE TABLE `kg_entity_embedding` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '向量ID',
    `entity_id`  BIGINT      NOT NULL COMMENT '实体ID',
    `model`      VARCHAR(64) NOT NULL COMMENT 'Embedding 模型名（如 qwen3.7-text-embedding-flash）',
    `dim`        INT         NOT NULL COMMENT '向量维度',
    `vector`     BLOB        NOT NULL COMMENT 'float32 数组的小端字节序序列化',
    `text_hash`  CHAR(64)    NULL COMMENT '被向量化文本的 SHA-256，避免重复计算',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_entity_model` (`entity_id`, `model`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '实体向量表';

-- 4. 文档-实体映射表
CREATE TABLE `kg_doc_entity` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '映射ID',
    `doc_type`   VARCHAR(32) NOT NULL COMMENT '文档类型：POST/COMPETITION/TASK/ASSET 等',
    `doc_id`     BIGINT      NOT NULL COMMENT '文档ID',
    `entity_id`  BIGINT      NOT NULL COMMENT '实体ID',
    `team_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '所属团队ID，0=不限定',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc_entity` (`doc_type`, `doc_id`, `entity_id`),
    INDEX `idx_entity` (`entity_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文档-实体映射表';

-- 5. 索引任务队列
CREATE TABLE `kg_index_task` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `doc_type`     VARCHAR(32)  NOT NULL COMMENT '文档类型：POST/COMPETITION/TASK/ASSET 等',
    `doc_id`       BIGINT       NOT NULL COMMENT '文档ID',
    `title`        VARCHAR(255) NULL COMMENT '文档标题',
    `content`      MEDIUMTEXT   NULL COMMENT '待抽取正文（已净化）',
    `status`       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/RUNNING/DONE/FAILED',
    `retry_count`  INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    `error_msg`    VARCHAR(1024) NULL COMMENT '最近一次失败原因',
    `scope`        VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT '可见范围：GLOBAL/TEAM/PRIVATE',
    `team_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '所属团队ID，0=不限定',
    `user_id`      BIGINT       NOT NULL DEFAULT 0 COMMENT '提交用户ID，0=不限定',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `finished_at`  DATETIME     NULL COMMENT '完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_doc` (`doc_type`, `doc_id`),
    INDEX `idx_status_id` (`status`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'KG 索引任务队列';

-- 6. 社区摘要表（GraphRAG 社区检测的降级实现：由 Agent 侧按需生成）
CREATE TABLE `kg_community` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '社区ID',
    `title`           VARCHAR(255) NULL COMMENT '社区标题（模型生成）',
    `summary`         TEXT     NULL COMMENT '社区摘要文本',
    `entity_ids_json` TEXT     NULL COMMENT '成员实体ID列表的 JSON 数组',
    `entity_count`    INT      NOT NULL DEFAULT 0 COMMENT '成员实体数',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'KG 社区摘要表';

-- 7. Agent 推理链日志表
CREATE TABLE `kg_agent_trace` (
    `id`         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `user_id`    BIGINT        NOT NULL DEFAULT 0 COMMENT '提问用户ID，0=匿名/系统',
    `question`   VARCHAR(1024) NULL COMMENT '用户问题',
    `trace`      LONGTEXT      NULL COMMENT '推理链 JSON：[{round,thought,action,actionInput,observation}]',
    `answer`     TEXT          NULL COMMENT '最终答案',
    `citations`  TEXT          NULL COMMENT '引用实体ID列表的 JSON 数组',
    `rounds`     INT           NOT NULL DEFAULT 0 COMMENT 'ReAct 循环轮数',
    `tool_calls` INT           NOT NULL DEFAULT 0 COMMENT '工具调用次数',
    `status`     VARCHAR(16)   NOT NULL DEFAULT 'OK' COMMENT '状态：OK/FALLBACK/FAILED',
    `elapsed_ms` BIGINT        NOT NULL DEFAULT 0 COMMENT '总耗时（毫秒）',
    `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user` (`user_id`),
    INDEX `idx_created` (`created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'Agent 推理链日志表';
