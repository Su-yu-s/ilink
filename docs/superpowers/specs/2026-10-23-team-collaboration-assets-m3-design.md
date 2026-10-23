# 高校竞赛协作平台 M3 团队协作资产设计

- 日期：2026-10-23
- 状态：用户已确认方案，待书面规格复核后进入实施
- 上游设计：
  - `docs/superpowers/specs/2026-10-22-team-agent-workbench-design.md`
  - `docs/superpowers/specs/2026-10-22-ai-collab-system-integrated-ui-design.md`
- 实施范围：团队文档、团队表格、资料正文索引、材料对照、资产级协作权限和 Agent 协作能力
- 明确排除：聊天已读状态、已读人数、消息已读回执

## 1. 决策摘要

M3 将已有的“团队空间”从任务看板扩展为可持久化、可追溯、可被 Agent 安全使用的协作工作区。文档和表格是正式业务数据，不再是 Demo。

本轮采用以下已确认方案：

1. 服务端权威存储。
2. 多人可查看，同一资产同一时刻只允许一个编辑者。
3. 编辑租约 + 乐观锁，不引入 OT/CRDT。
4. 自动保存与显式版本快照并存。
5. 团队资料正文进入现有知识图谱索引体系，但严格继承团队和文件权限。
6. 材料核验区分“系统建议”和“真人确认”，无证据不得判为已满足。
7. Agent 只能通过白名单读取工具和受控动作访问协作资产。
8. 所有协作文档、表格和材料数据都受团队状态、资产范围和成员关系约束。
9. 关闭团队只读，已解散或已删除团队不可访问。
10. 不新增聊天已读或消息回执字段。

## 2. 目标与非目标

### 2.1 目标

1. 在团队空间中正式提供 Markdown 文档创建、编辑、自动保存、版本恢复和导出。
2. 提供结构化团队表格、行列编辑、自动保存、版本恢复、CSV/XLSX 导出。
3. 将团队资料库中可解析文件的正文异步索引进当前团队 GraphRAG 作用域。
4. 建立竞赛材料要求与团队证据的对照矩阵。
5. 为文档、表格和材料矩阵提供资产级 `TEAM/RESTRICTED` 权限。
6. 让 Agent 可以在用户确认后起草文档、生成表格、追加章节、更新表格和提出材料核验建议。
7. 为上述能力提供可重复的单元、集成、数据库、安全、性能边界和浏览器验收。

### 2.2 非目标

- 不实现多人同时输入同一段落的实时光标协同。
- 不引入 Yjs、Automerge、ShareJS 或其他 OT/CRDT 运行时。
- 不实现通用 Office 套件、宏运行、公式计算引擎或在线演示文稿编辑。
- 不执行 DOCX/XLSX 内嵌宏，不解析外部链接资源，不渲染文件中的远程图片。
- 不对图片做 OCR；图片只索引文件名、类型、大小、上传者和用户填写的说明。
- 不允许 Agent 在未确认时覆盖文档、表格或材料状态。
- 不把资料正文、私聊、通知正文、手机号、邮箱或凭据送入无关模型上下文。
- 不新增任何聊天已读状态或回执。

## 3. 角色、团队状态与权限

### 3.1 身份来源

每次 API 调用都从 Session 获取用户，从 URL 的 `teamId` 获取团队，然后重新验证：

- 当前用户是团队创建者，或存在 `APPROVED` 成员关系。
- 团队存在且未删除。
- 资产属于该团队。
- 资产可见范围和显式授权允许当前用户访问。
- 写操作发生时团队状态为 `TEAMING`。

前端提交的 `role`、`canEdit`、`teamId` 归属声明和 `permission` 不作为授权依据。

### 3.2 资产范围

文档和表格支持两种范围：

| 范围 | 读取 | 编辑 | 管理 |
| --- | --- | --- | --- |
| `TEAM` | 所有有效团队成员 | 有编辑权限的有效成员 | 创建者、队长 |
| `RESTRICTED` | 创建者、队长、显式授权成员 | 创建者、队长、获得 `EDIT` 的成员 | 创建者、队长、获得 `MANAGE` 的成员 |

材料矩阵默认全团队可见。材料要求可由队长、导师或创建者维护；普通成员可以提交证据和核验建议。

### 3.3 团队状态矩阵

| 团队状态 | 列表/详情 | 编辑/自动保存 | 版本恢复 | 导出 | 资料索引 | 材料核验 |
| --- | --- | --- | --- | --- | --- | --- |
| `OPEN` | 是 | 否 | 否 | 是 | 已有文件可排队 | 只读 |
| `TEAMING` | 是 | 是 | 是 | 是 | 是 | 是 |
| `CLOSED` | 是 | 否 | 否 | 是 | 保留历史，不再写入 | 只读 |
| `DISSOLVED`/已删除 | 否 | 否 | 否 | 否 | 清除 | 否 |

界面必须使用明确文案解释只读原因，不能只把按钮置灰。

## 4. 架构

### 4.1 模块边界

| 模块 | 职责 | 不负责 |
| --- | --- | --- |
| `TeamCollaborationAuthorization` | 团队、成员、资产范围、租约和团队成员关系的统一授权 | 内容业务规则 |
| `TeamDocumentService` | 文档 CRUD、自动保存、版本、恢复、导出和索引失效 | HTTP 细节 |
| `TeamSpreadsheetService` | 表格 schema、行列数据、版本、恢复、CSV/XLSX 导出 | 通用公式计算 |
| `TeamCollaborationLeaseService` | 编辑租约获取、续租、释放、过期回收 | 保存内容 |
| `TeamMaterialMatrixService` | 要求、证据、状态、人工确认和统计 | 模型调用 |
| `TeamFileTextExtractor` | PDF/Office/Markdown/文本安全解析 | 文件存储和权限 |
| `TeamCollaborationIndexAdapter` | 协作资产与现有 `kg_index_task`、`kg_document_chunk` 的桥接 | 通用 GraphRAG 算法 |
| `TeamCollaborationWebSocketPublisher` | 事务提交后的变更通知 | 业务事实落库 |
| `TeamCollaborationController` | 鉴权后的 REST API | 直接拼装业务数据 |

### 4.2 数据流

```text
浏览器
  -> TeamCollaborationController
  -> TeamCollaborationAuthorization
  -> Document/Spreadsheet/Lease Service
  -> 条件更新事务
  -> 版本快照 + 索引队列 + 审计
  -> 提交后 WebSocket 变更通知

Team Agent
  -> TeamAgentScope
  -> 协作资产只读工具或动作提案
  -> 用户确认
  -> TeamAgentActionHandlerRegistry
  -> 同一协作服务事务

团队资料上传
  -> team_library_files
  -> kg_index_task(TEAM_FILE)
  -> KgIndexWorker
  -> TeamFileTextExtractor
  -> kg_document_chunk + 图谱
```

## 5. 数据模型

所有时间使用数据库 `DATETIME(3)`，Java 侧使用 `Date`/`Instant` 转换；业务版本从 `1` 开始递增。MySQL 5.7 兼容，不使用生成列或 JSON 约束。

### 5.1 文档

`team_collab_document`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `team_id` | 团队 |
| `title` | 标题，1–120 字 |
| `content_markdown` | 原始 Markdown，最大 200 KiB |
| `visibility` | `TEAM/RESTRICTED` |
| `status` | `ACTIVE/ARCHIVED` |
| `version` | 乐观锁与幂等版本 |
| `content_hash` | 固定 purpose 前缀的 SHA-256 |
| `creator_user_id` | 创建者 |
| `last_editor_user_id` | 最后编辑者 |
| `created_at/updated_at/deleted_at` | 生命周期 |

`team_collab_document_version`

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `document_id/team_id` | 归属 |
| `version` | 对应文档版本 |
| `title/content_markdown/content_hash` | 完整快照 |
| `save_kind` | `AUTO/MANUAL/RESTORE/AGENT` |
| `actor_user_id` | 保存者，Agent 确认为发起用户 |
| `change_summary` | 可选说明 |
| `created_at` | 版本时间 |

版本唯一键为 `(document_id, version)`。每个文档最多保留 200 个版本；清理只删除较旧的 `AUTO` 版本，始终保留最新 20 个版本以及全部 `MANUAL/RESTORE/AGENT` 版本，直到触发总容量限制后按最旧优先清理。

### 5.2 表格

`team_collab_spreadsheet`

| 字段 | 说明 |
| --- | --- |
| `id/team_id/title/visibility/status` | 与文档相同 |
| `columns_json` | 稳定列 ID、键、显示名、类型、宽度 |
| `rows_json` | 稳定行 ID 与 `cells` 映射 |
| `version` | 乐观锁 |
| `content_hash` | 规范化 schema + rows 哈希 |
| `creator_user_id/last_editor_user_id` | 归属 |
| `created_at/updated_at/deleted_at` | 生命周期 |

`team_collab_spreadsheet_version` 保存对应版本快照，规则与文档版本一致。

表格约束：

- 最多 50 列、5000 行、总 JSON 1 MiB。
- 列类型仅允许 `TEXT/NUMBER/DATE/CHECKBOX/SELECT`。
- 列 ID 和行 ID 为服务端生成的不可变字符串。
- `SELECT` 选项最多 50 个，单元格值必须属于选项集合。
- 本阶段不执行用户公式；以 `=` 开头的文本原样存储但在导出时防公式注入。

### 5.3 资产授权与租约

`team_collab_acl`

- `asset_type`: `DOCUMENT/SPREADSHEET`
- `asset_id`
- `team_id`
- `user_id`
- `permission`: `VIEW/EDIT/MANAGE`
- 唯一键：`(asset_type, asset_id, user_id)`

`team_collab_edit_lease`

- `asset_type/asset_id/team_id`
- `user_id`
- `token_hash`
- `base_version`
- `acquired_at/renewed_at/expires_at`
- 唯一键：`(asset_type, asset_id)`

租约默认 90 秒，客户端每 30 秒续租。过期后可由其他成员原子接管。原始租约 token 只返回客户端，数据库只存 SHA-256。保存请求必须同时提供租约 token 与 `baseVersion`。

### 5.4 材料矩阵

`team_material_requirement`

- `id/team_id`
- `competition_id` 可空
- `custom_competition` 可空
- `title/description`
- `category`
- `required` 布尔值
- `source_type`: `CATALOG/CUSTOM/MANUAL/AGENT_SUGGESTED`
- `source_doc_type/source_doc_id/source_chunk_id/source_excerpt`
- `status`: `ACTIVE/ARCHIVED`
- `review_status`: `UNCHECKED/NEEDS_REVIEW/MET/PARTIAL/MISSING`
- `current_review_id` 可空
- `sort_order/review_version/version`
- `created_by/updated_by/created_at/updated_at`

`team_material_evidence`

- `id/team_id/requirement_id`
- `asset_type`: `FILE/DOCUMENT/SPREADSHEET/TASK`
- `asset_id`
- `evidence_chunk_id` 可空
- `evidence_excerpt`
- `match_status`: `UNVERIFIED/CANDIDATE`
- `created_by/created_at`

`team_material_review`

- `id/team_id/requirement_id`
- `status`: `UNCHECKED/MET/PARTIAL/MISSING/NEEDS_REVIEW`
- `note`
- `reviewed_by/reviewed_at`
- `version`

`team_material_requirement.review_status/current_review_id/review_version` 共同保存当前状态摘要，所有更新在同一事务内追加新的 `team_material_review` 并条件更新要求行。Agent 只能创建 `NEEDS_REVIEW` 状态和候选证据，不能把材料标记为 `MET`。`MET/PARTIAL/MISSING` 必须来自真人操作。

### 5.5 资料索引状态

`team_library_files` 增加：

- `content_revision BIGINT NOT NULL DEFAULT 0`
- `index_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'`
- `index_error VARCHAR(500) NULL`
- `indexed_at DATETIME(3) NULL`

状态为 `PENDING/INDEXING/READY/FAILED/STALE/SKIPPED`。不可解析类型为 `SKIPPED`，失败信息不得包含服务器绝对路径或原始异常堆栈。

### 5.6 审计

人工协作操作写入新表 `team_collab_event`，只追加：

- 团队、资产类型、资产 ID
- 操作者、事件类型、结果
- 版本、请求 ID
- 脱敏后的元数据 JSON
- 创建时间

Agent 触发的同一业务操作继续写入 `team_agent_audit_log`，并通过动作结果关联 `team_collab_event`，不复制对话正文。

## 6. 编辑与版本协议

### 6.1 获取租约

`POST /api/team/{teamId}/collaboration/{assetType}/{assetId}/lease`

- 返回 `leaseToken`、`expiresAt`、`baseVersion`、`editorPublicProfile`。
- 若其他用户仍持有租约，返回 409 `EDIT_LEASE_HELD`，附带只读展示所需的安全资料。
- 同一用户已有租约时幂等返回新 token 并撤销旧 token。

### 6.2 自动保存

客户端在内容变化后 1.5 秒防抖，最多每 3 秒发起一次保存。请求包含：

- `leaseToken`
- `baseVersion`
- `clientRequestId`
- 完整规范化内容

服务端事务步骤：

1. 再次鉴权团队、资产范围、编辑权限、团队状态。
2. 校验租约 token、用户与过期时间。
3. 校验 `baseVersion == asset.version`。
4. 规范化内容并计算哈希。
5. 条件更新 `version = version + 1`。
6. 插入版本快照和协作事件。
7. 合并 `kg_index_task` 修订。
8. 提交。
9. 提交后推送 WebSocket 变更。

相同幂等键和相同载荷重放时返回原结果；不同载荷复用相同幂等键返回 409 `REQUEST_ID_REUSED`。

### 6.3 冲突

版本不匹配返回 409 `EDIT_VERSION_CONFLICT`，包含：

- 客户端 `baseVersion`
- 当前 `version`
- 当前内容哈希
- 当前最后编辑者公开信息
- 更新时间

服务端不回传受资产范围限制的完整内容。客户端在有权读取时重新 GET；无权读取时只显示冲突和退出编辑。

### 6.4 版本恢复

恢复历史版本不是删除历史，而是以该快照创建新版本：

1. 校验恢复权限和团队状态。
2. 校验租约与当前版本。
3. 复制历史内容为新的 `version + 1`。
4. `save_kind=RESTORE`。
5. 重新排队索引。

## 7. 文档功能

### 7.1 编辑体验

- 独立沉浸式团队文档页面，不为文档复制第二套全站页头。
- 左侧文档目录、中间编辑/阅读区、右侧版本与协作信息；手机端单列。
- 编辑/阅读双模式。
- Markdown 工具栏覆盖标题、粗体、斜体、列表、引用、代码、链接和表格。
- 流式编辑不改变全站滚动容器行为。
- 自动保存状态明确显示“已保存/保存中/未保存/冲突/只读”。
- 关闭页面或切换资产前，如有未完成保存，使用自定义确认弹窗。

### 7.2 渲染与安全

- 存储规范化 Markdown。
- 浏览器使用本地 `Marked + DOMPurify` 渲染。
- 禁止脚本、事件属性、表单、iframe、object、embed、外链媒体和自动加载远程图片。
- 链接只允许 `http/https/mailto`，并补 `rel="noopener noreferrer"`。
- 服务端同时以 Jsoup 白名单清洗 HTML 片段，形成纵深防御。

### 7.3 导出

- Markdown 导出使用用户可见标题和 UTF-8 正文。
- 文件名经过路径分隔符、控制字符和 Windows 保留名清理。
- 后续不声称支持 DOCX/PDF 导出，除非另立迁移与验收。

## 8. 表格功能

- 行列增删、列重命名、类型切换、列宽、首行冻结、单元格编辑。
- 工具栏提供新增行、新增列、删除选中、撤销前端未保存操作、保存、版本和导出。
- 选中行列使用系统中性强调，不使用状态绿铺满。
- 键盘支持 Tab/Shift+Tab 横移、Enter/Shift+Enter 纵移和 Escape 退出单元格。
- CSV 使用 UTF-8 BOM，字段按 RFC 4180 转义。
- 导出前对 `= + - @ \t \r` 开头单元格增加文本前缀，避免电子表格公式注入。
- XLSX 由 Apache POI 生成，不读取或执行工作簿宏。

## 9. 资料正文索引

### 9.1 支持格式

| 类型 | 解析方式 | 限制 |
| --- | --- | --- |
| PDF | Apache PDFBox 文本层 | 不 OCR，最多 300 页 |
| DOCX | Apache POI XWPF | 不执行宏，不访问外链 |
| XLSX | Apache POI XSSF | 只提取已填单元格，最多 20,000 个非空单元格 |
| PPTX | Apache POI XSLF | 提取文本框，最多 300 页 |
| Markdown/TXT/CSV | UTF-8 文本读取 | 最多 5 MiB 原始文本 |
| 图片/压缩包/音视频 | 不解析正文 | 标记 `SKIPPED` |

文件超过 20 MiB、加密、损坏或超出页数/单元格限制时标记 `FAILED` 或 `SKIPPED`，保留可读原因，不影响文件本身的下载权限。

### 9.2 索引队列

新增文档类型：

- `TEAM_DOCUMENT`
- `TEAM_SPREADSHEET`
- `TEAM_FILE`

正文变更、文件上传成功、文件重命名后正文相关元数据变化、回收站恢复时合并登记新修订。软删除和彻底删除登记 `DELETE` tombstone。

Worker 必须：

1. 校验源仍存在、未删除、团队未解散且修订最新。
2. 验证实际文件路径位于配置的上传根目录。
3. 使用租约 fencing 发布 chunks。
4. 成功后更新 `team_library_files.index_status=READY`。
5. 失败后记录稳定错误码，并按现有队列退避策略重试。

### 9.3 GraphRAG 授权

- `TEAM_FILE` 只能映射当前团队资料库文件。
- `TEAM_DOCUMENT/TEAM_SPREADSHEET` 必须先校验资产团队、可见范围与当前用户 ACL。
- `RESTRICTED` 资产默认不能进入其他成员 Agent 上下文，除非该成员有显式 `VIEW`。
- 普通团队成员的资料库权限沿用现有“有效团队成员”规则。
- 回收站、软删除、彻底删除和 `DISSOLVED` 团队的数据不得通过实体、关系、摘要、计数或引用泄漏。

## 10. 材料对照

### 10.1 要求来源

1. 目录竞赛：从当前绑定竞赛的 GLOBAL 来源通过 Agentic GraphRAG 提取候选要求，每条要求必须带受权引用。
2. 自定义竞赛：只能由成员手工创建要求，不自动匹配同名目录竞赛。
3. 手工要求：任何有材料维护权限的成员可创建。
4. Agent 建议：以 `NEEDS_REVIEW` 状态出现，用户确认后才转成正式要求。

### 10.2 证据关联

可关联：

- 团队资料库文件及其索引片段
- 团队文档及版本
- 团队表格及版本
- 有权限访问的任务提交材料

证据必须显示来源名称、版本、片段摘要和可访问链接。删除来源后，矩阵保留“来源已删除”快照但移除可点击链接。

### 10.3 状态

- `UNCHECKED`：尚未核验。
- `NEEDS_REVIEW`：存在候选证据或 Agent 建议，等待真人判断。
- `MET`：真人确认已满足。
- `PARTIAL`：真人确认部分满足，并填写缺口。
- `MISSING`：真人确认缺失。

顶部统计只显示当前用户有权查看的材料。矩阵支持按状态、必需项和类别筛选，并支持 CSV 导出。

## 11. Agent 能力

### 11.1 只读工具

- `list_team_collaboration_assets`：列出有权限的文档、表格和材料矩阵摘要。
- `get_team_document`：读取文档正文与允许的版本元数据。
- `get_team_spreadsheet`：读取表格 schema 和受行数限制的数据。
- `search_team_collaboration_assets`：只检索有权来源。
- `get_material_matrix`：读取材料要求、状态和证据摘要。

工具参数不接受 `teamId/userId/scope`。未知字段、越权 ID 和超量请求在调用前拒绝。

### 11.2 白名单动作

- `CREATE_TEAM_DOCUMENT`
- `APPEND_TEAM_DOCUMENT`
- `CREATE_TEAM_TABLE`
- `UPDATE_TEAM_TABLE_ROWS`
- `CREATE_MATERIAL_REQUIREMENT`
- `LINK_MATERIAL_EVIDENCE`
- `SET_MATERIAL_REVIEW`

动作规则：

1. 目标版本由服务端读取，不接受模型提供。
2. 创建类动作必须满足团队状态、角色和资产范围。
3. 追加文档在目标版本变化后拒绝，不盲目覆盖。
4. 表格更新使用稳定行 ID，单次最多修改 200 个单元格。
5. Agent 只能把材料复核设为 `NEEDS_REVIEW`，不能设为 `MET`。
6. 创建动作可撤销；覆盖式动作通过恢复 `before` 快照撤销，若目标已有更新则撤销冲突。
7. 所有动作组继续整组事务、幂等和只追加审计。
8. Agent 更新或追加既有文档/表格时，发起确认的用户必须持有有效编辑租约，或该资产当前没有其他成员租约；系统在事务内获取短租约并执行版本复验，不能绕过“同一资产同一时刻一个编辑者”。

### 11.3 路由与提示

新增意图路由：

- `COLLAB_DOCUMENT`
- `COLLAB_TABLE`
- `MATERIAL_REVIEW`

事实来源按优先级选择：

1. 明确任务统计走结构化服务。
2. 文档/表格操作走协作资产工具与动作规划。
3. 材料要求解释走团队作用域 GraphRAG。
4. 模型不可用时仍允许手工编辑，不阻塞基本协作。

## 12. API 契约

### 12.1 公共

- `GET /api/team/{teamId}/collaboration/bootstrap`
- `GET /api/team/{teamId}/collaboration/events?afterId=...`

### 12.2 文档

- `GET /api/team/{teamId}/documents`
- `POST /api/team/{teamId}/documents`
- `GET /api/team/{teamId}/documents/{documentId}`
- `PUT /api/team/{teamId}/documents/{documentId}`
- `DELETE /api/team/{teamId}/documents/{documentId}`
- `GET /api/team/{teamId}/documents/{documentId}/versions`
- `POST /api/team/{teamId}/documents/{documentId}/versions/{version}/restore`
- `GET /api/team/{teamId}/documents/{documentId}/export.md`

### 12.3 表格

- `GET /api/team/{teamId}/spreadsheets`
- `POST /api/team/{teamId}/spreadsheets`
- `GET /api/team/{teamId}/spreadsheets/{spreadsheetId}`
- `PUT /api/team/{teamId}/spreadsheets/{spreadsheetId}`
- `DELETE /api/team/{teamId}/spreadsheets/{spreadsheetId}`
- `GET /api/team/{teamId}/spreadsheets/{spreadsheetId}/versions`
- `POST /api/team/{teamId}/spreadsheets/{spreadsheetId}/versions/{version}/restore`
- `GET /api/team/{teamId}/spreadsheets/{spreadsheetId}/export.csv`
- `GET /api/team/{teamId}/spreadsheets/{spreadsheetId}/export.xlsx`

### 12.4 租约和授权

- `POST|PUT|DELETE /api/team/{teamId}/collaboration/{assetType}/{assetId}/lease`
- `GET|POST|DELETE /api/team/{teamId}/collaboration/{assetType}/{assetId}/acl`

### 12.5 材料

- `GET /api/team/{teamId}/materials/matrix`
- `POST /api/team/{teamId}/materials/requirements`
- `PUT /api/team/{teamId}/materials/requirements/{requirementId}`
- `DELETE /api/team/{teamId}/materials/requirements/{requirementId}`
- `POST /api/team/{teamId}/materials/requirements/{requirementId}/evidence`
- `DELETE /api/team/{teamId}/materials/evidence/{evidenceId}`
- `PUT /api/team/{teamId}/materials/requirements/{requirementId}/review`
- `GET /api/team/{teamId}/materials/matrix/export.csv`

### 12.6 错误契约

稳定错误码至少包括：

- `TEAM_NOT_FOUND`
- `TEAM_READ_ONLY`
- `ASSET_NOT_FOUND`
- `ASSET_FORBIDDEN`
- `EDIT_LEASE_HELD`
- `EDIT_LEASE_INVALID`
- `EDIT_LEASE_EXPIRED`
- `EDIT_VERSION_CONFLICT`
- `REQUEST_ID_REUSED`
- `CONTENT_TOO_LARGE`
- `SPREADSHEET_SCHEMA_INVALID`
- `EXPORT_FAILED`
- `FILE_INDEX_FAILED`
- `MATERIAL_EVIDENCE_INVALID`
- `AGENT_ACTION_CONFLICT`

私有 API 响应带 `Cache-Control: no-store`。

## 13. 界面设计

### 13.1 导航与布局

团队空间侧边栏新增“协作资产”，内部使用三个页签：

- 文档
- 表格
- 材料对照

资料正文索引状态显示在现有“团队资料”文件树的文件行内，不复制一个新的索引管理页面。

### 13.2 视觉规则

- 沿用共享页头、团队侧栏、字体、圆角、边框、按钮、输入框、Toast、弹窗和响应式断点。
- 编辑状态标识使用中性文字与系统状态色：保存中琥珀、成功绿、冲突/失败红。
- 选中导航继续使用系统深色中性面；状态绿只用于图标、标签和小面积反馈。
- 不使用紫色渐变、发光边框、装饰性大字或独立 AI 产品外壳。

### 13.3 响应式

- 1440/1280：目录 + 主编辑区 + 信息栏。
- 1024/768：目录折叠为顶部选择器，信息栏转为抽屉。
- 390：单列，固定底部保存状态条，触控目标不小于 44px。
- 所有宽度不得横向溢出。

### 13.4 可访问性

- 编辑/阅读模式有语义化切换。
- 保存状态和冲突使用 `aria-live="polite"`。
- 表格单元格具备表头关联与方向键导航说明。
- 租约被他人占用时提供可读原因和只读入口。
- 颜色不是唯一状态表达。

## 14. 生命周期与清理

### 14.1 文档与表格

- 删除为软删除，30 天内创建者或队长可恢复。
- 超过 30 天或执行彻底删除时，删除版本、ACL、租约、材料证据链接并登记 KG DELETE tombstone。
- 团队删除时立即清理文档、表格、版本、租约、ACL、材料、索引与协作事件。
- 账号注销时把仍保留的团队资产 `creator/last_editor/actor` 置空并显示“已注销用户”；资产本身归团队所有。

### 14.2 资料文件

- 进入回收站立即登记 DELETE tombstone，停止 Agent 检索。
- 恢复后按当前内容修订重新排队。
- 彻底删除时删除 chunks、来源 mapping 和物理文件。

## 15. 安全要求

1. 跨团队伪造 `teamId/assetId/requirementId` 全部拒绝。
2. 关闭团队所有写接口返回 `TEAM_READ_ONLY`。
3. 已退出成员不能借旧租约继续保存。
4. 租约 token 使用常量时间比较，数据库不存明文。
5. 文档 Markdown 防 XSS；链接协议白名单。
6. CSV 防公式注入；XLSX 不执行宏。
7. 文件解析防路径穿越、zip bomb、超大页数、加密文件和恶意 XML。
8. 解析错误不输出绝对路径、堆栈、数据库信息或密钥。
9. 所有私有响应 `no-store`。
10. Agent 工具不能通过摘要、计数或候选数泄漏无权资产。
11. 材料证据只允许引用当前用户可读来源；引用失效后不缓存原文。
12. 自动保存和动作确认都使用请求哈希，防止网络重试重复写入。
13. 不引入聊天已读、消息回执或在线状态字段。

## 16. 测试与验收

### 16.1 单元测试

- 角色/团队状态/资产范围矩阵。
- 文档标题、正文、Markdown 链接和大小校验。
- 表格 schema、稳定 ID、行数/列数/单元格类型和 CSV 注入防护。
- 租约获取、续租、过期、接管、常量时间 token 校验。
- 版本哈希、幂等键复用、冲突、恢复和版本清理。
- 材料状态机、证据权限和 Agent 不可设置 `MET`。
- 文件类型识别、敏感路径、超大文件、损坏 PDF/Office。
- 文件名和导出头安全。

### 16.2 集成测试

- 文档创建 → 自动保存 → 新版本 → 恢复 → 索引新修订。
- 表格编辑 → CSV/XLSX 导出 → 恢复。
- 两个用户争用租约，只有一人保存成功。
- 保存与成员移除并发，删除/撤销后旧租约失败。
- 资料上传 → 索引 READY；软删除 → 引用立即不可读。
- 关闭团队 → 历史可读、写操作被拒绝。
- 团队删除与保存/索引 Worker 并发，无孤儿数据。
- Agent 创建文档/表格、追加内容、更新行、材料建议、确认、撤销。
- 相同动作请求重放只执行一次；版本冲突时不部分执行。
- `RESTRICTED` 资产不出现在无权用户的列表、搜索、引用和工具计数中。

### 16.3 浏览器测试

- `1440/1280/1024/768/390px` 无横向溢出。
- 文档阅读/编辑、自动保存状态、冲突提示、版本恢复。
- 表格键盘导航、增删行列、导出。
- 资料索引状态显示。
- 材料矩阵筛选、关联证据、人工确认。
- Agent 动作卡确认、失败、撤销。
- 无控制台错误、页面异常或失败请求。
- 屏幕阅读器可播报保存、冲突、索引失败和材料状态。

### 16.4 最终门禁

1. `mvn -DskipTests compile`
2. 定向 M3 测试
3. 干净全量 `mvn test`
4. MySQL 5.7 Flyway 新迁移执行和重复启动验证
5. 真实 API smoke test
6. 五档浏览器验收
7. 敏感信息、绝对路径、已读字段和 Demo 伪实现扫描
8. `git diff --check`

## 17. 实施顺序

### M3.0 基础

1. 新增 V49 协作资产表、V50 文件索引字段与索引类型兼容。
2. 实现统一授权、租约、版本与审计。
3. 实现文档和表格服务。

### M3.1 界面

1. 团队空间“协作资产”导航。
2. 文档阅读/编辑页。
3. 表格画布。
4. 材料矩阵。
5. 资料索引状态。

### M3.2 索引与材料

1. 文件正文解析器和索引 Worker 桥接。
2. GraphRAG 文档类型授权。
3. 材料要求、证据、复核与导出。

### M3.3 Agent

1. 只读工具。
2. 白名单动作与 schema。
3. 意图路由、提示词、动作卡和撤销。

### M3.4 收口

1. 数据清理与账号/团队删除接入。
2. 安全测试与浏览器回归。
3. 更新主设计、视觉设计、实施计划、隐私说明和论文能力边界。
4. 不新增任何聊天已读实现。

## 18. 完成标准

M3 只有同时满足以下条件才可标记完成：

1. 四项正式能力均可在团队空间进入，无 Demo 标记冒充正式实现。
2. 文档和表格持久化、版本、租约、冲突和导出通过自动化与浏览器测试。
3. 资料正文按权限索引，删除、关闭和解散状态不会泄漏。
4. 材料矩阵的每条满足结论都可追溯至真人确认和证据。
5. Agent 的全部协作写能力经过确认、版本复验、事务、幂等与审计。
6. 关闭团队只读，已解散团队不可访问。
7. 跨团队和 `RESTRICTED` 越权测试全部通过。
8. XSS、CSV 注入、路径穿越、zip bomb 和敏感信息测试全部通过。
9. 五档响应式与可访问性验收通过。
10. 全量测试 0 失败、0 错误，迁移可在 MySQL 5.7 重复执行。
11. 文档和论文只声明已经过验证的能力。
12. 系统不存在聊天已读状态或消息已读回执。
