# 团队协作资产 M3 实施计划

- 日期：2026-10-23
- 设计基线：`docs/superpowers/specs/2026-10-23-team-collaboration-assets-m3-design.md`
- 状态：M3 后端、Agent 集成、前端主流程、真实回收站与响应式验收已完成；剩余测试深度限制见文末“12. 实施进度与已知限制”
- 完成原则：代码、迁移、测试和真实验收同时存在才记为完成
- 明确排除：聊天已读状态、已读人数、消息已读回执

## 1. 实施约束

1. 保留工作区全部既有改动，不清理、不重置无关文件。
2. 沿用 Spring Boot 2.7、Java 17、MyBatis-Plus、Thymeleaf、现有 Session 与 `il-*` 视觉系统。
3. 所有团队与资产权限由服务端重建，不接受前端角色和范围声明。
4. 编辑模式为租约 + 乐观锁，不引入 CRDT/OT。
5. Agent 写操作只走动作组，不直接执行模型输出。
6. 不新增聊天已读字段、接口或界面。
7. M3 完成前不得修改论文或界面把任何能力写成“已完成”。

## 2. M3.0：迁移与业务基础

### 2.1 Flyway

- [x] V49 创建 `team_collab_document`
- [x] V49 创建 `team_collab_document_version`
- [x] V49 创建 `team_collab_spreadsheet`
- [x] V49 创建 `team_collab_spreadsheet_version`
- [x] V49 创建 `team_collab_acl`
- [x] V49 创建 `team_collab_edit_lease`
- [x] V49 创建 `team_collab_event`
- [x] V49 创建材料要求、证据、复核表
- [x] V50 扩展 `team_library_files` 索引状态
- [x] V51 为协作资产回收站补团队、删除时间和资产类型组合索引
- [x] 迁移兼容 MySQL 5.7，唯一键和索引覆盖团队查询
- [x] 重复启动 Flyway 不重复执行或破坏数据

### 2.2 授权与租约

- [x] `TeamCollaborationAuthorization`
- [x] 团队有效成员、创建者、导师和学生统一判定
- [x] `TEAM/RESTRICTED` 与 ACL 判定
- [x] `OPEN/TEAMING/CLOSED/DISSOLVED` 状态矩阵
- [x] `TeamCollaborationLeaseService`
- [x] 90 秒租约、30 秒续租、幂等 token 轮换
- [x] 过期租约原子接管
- [x] 租约 token 哈希和常量时间比较
- [x] 成员离队、团队关闭和资产删除即时使租约失效

### 2.3 文档

- [x] 实体、Mapper、DTO 和严格请求校验
- [x] 创建、列表、详情、标题改名、归档、软删除（`deleteAllForTeam` 彻底删除）
- [x] 自动保存与手动保存共用命令
- [x] 版本快照、分页、恢复和清理策略（`MAX_VERSION_HISTORY = 200`，只裁 `AUTO` 快照）
- [x] Markdown 规范化、哈希和服务端清洗（`TeamCollabMarkdownSecurityTest`）
- [x] Markdown 导出与安全文件名（`exportFilename` 过滤穿越与 Windows 保留名）
- [x] WebSocket 提交后变更通知（`afterCommit` 才广播）

### 2.4 表格

- [x] 实体、Mapper、DTO
- [x] schema/rows 规范化
- [x] 50 列、5000 行、1 MiB 限制、单元格 4000 字符
- [x] 稳定列 ID/行 ID 和类型校验
- [x] 创建、列表、详情、保存、版本、恢复、删除
- [x] RFC 4180 CSV 导出与公式注入防护
- [x] Apache POI XLSX 导出
- [x] WebSocket 提交后变更通知

### 2.5 协作事件

- [x] 人工操作只追加事件
- [x] 请求 ID 与版本记录
- [x] 元数据脱敏（广播只发 `COLLABORATION_CHANGED`，不含资产 ID/版本/类型）
- [x] 团队和账号删除清理
- [x] WebSocket 失败不影响数据库提交

## 3. M3.1：资料正文索引

### 3.1 依赖与解析器

- [x] 引入 Apache PDFBox 2.0.32
- [x] 引入 Apache POI OOXML 和必要 scratchpad（5.3.0）
- [x] `TeamFileTextExtractor`
- [x] PDF 文本层
- [x] DOCX/XLSX/PPTX
- [x] Markdown/TXT/CSV
- [x] 非文本类型 `SKIPPED`
- [x] 加密、损坏、超大、超页数、超单元格限制（`TeamFileTextExtractorLimitsTest` 覆盖 PDF 300 页、XLSX 20000 单元格、PPTX 300 页）
- [x] 路径约束到上传根目录（`FileService.resolveStoredPath` 拒绝穿越并校验 root 前缀）
- [x] zip bomb 和 XML 安全限制（`ZipSecureFile.setMaxEntrySize(32 MiB)`，比 POI 默认 4 GiB 更严；条目数与解压比保持库默认，调大反而放松防护；POI `XMLHelper` 已默认禁用外部实体/DTD）

### 3.2 索引队列

- [x] `TEAM_FILE`、`TEAM_DOCUMENT`、`TEAM_SPREADSHEET` 类型
- [x] 上传、文档保存、表格保存、重命名、恢复合并入队
- [x] 软删除、彻底删除和团队删除登记 DELETE
- [x] Worker 修订号与租约 fencing
- [x] `PENDING/INDEXING/READY/FAILED/STALE/SKIPPED` 状态同步
- [x] 失败退避和稳定错误码（`retryDelayMillis` + 稳定 `errorCode`）
- [x] 旧 Worker 不覆盖新修订（`markReady` 带 `contentRevision` 条件）

### 3.3 GraphRAG 授权

- [x] `KgScopeService` 支持三种协作文档类型
- [x] 文件团队和删除状态复验
- [x] 文档/表格可见范围复验
- [x] `RESTRICTED` 不进入无权用户工具上下文
- [ ] 实体、关系、摘要、计数和引用全路径测试（现有覆盖为 `TeamScopedKgScopeServiceTest` 与 `TeamCollaborationAgentToolsAccessTest`，未逐条覆盖摘要/计数/引用）

## 4. M3.2：材料矩阵

- [x] 材料要求 CRUD 与团队状态权限（`TeamMaterialMatrixService` 的 `createRequirement/updateRequirement/archiveRequirement` 全部经 `requireWritableMatrix`；`TeamMaterialMatrixServiceTest` 覆盖普通成员可申请复核但不能下结论、只读团队全部写操作被拒）
- [x] 目录竞赛和自定义竞赛绑定（`matrix()` 用 `team.competitionId` 回查 `Competition` 名称并回传 `customCompetition`；`matrixUsesCatalogCompetitionNameInsteadOfTeamTitle` 断言目录名优先于团队标题，`competitionId` 与 `customCompetition` 互斥校验在 `createRequirement`）
- [x] 手工要求（`sourceType=MANUAL` 只允许 `UNCHECKED` 初始态，AI 不能伪造 `AGENT_SUGGESTED`）
- [x] Agent 候选要求（`createRequirement(..., true)` 强制 `sourceType=AGENT_SUGGESTED` + `NEEDS_REVIEW`，`anAgentCreatedRequirementIsAlwaysAnUnconfirmedSuggestion` 断言）
- [x] 证据关联文件、文档、表格和任务材料（`TeamMaterialEvidenceValidator` 支持 `FILE/DOCUMENT/SPREADSHEET/TASK`；`TeamMaterialEvidenceValidatorTest` 7 个用例覆盖四类来源）
- [x] 删除来源后的安全快照（`EvidenceSnapshot` 回传 `deleted` 标记，`deletedSource` 文案在前端渲染；`nullCoordinatesDescribeAsDeletedWithoutTouchingTheDatabase` 断言空坐标判为已删除且不查库）
- [x] 状态与复核版本（`reviewVersion` 单调递增写入 `team_material_review`，`updateReviewSummary` 同步主表）
- [x] Agent 只能建议 `NEEDS_REVIEW`（`theAgentCannotMarkMaterialAsMet` 断言 `MET` 被 `AGENT_ACTION_CONFLICT` 拒绝）
- [x] 真人确认 `MET/PARTIAL/MISSING`（`requirementCreatorCanRecordTheFinalVerdict`、`ordinaryMemberCannotRecordAFinalVerdictForSomeoneElsesRequirement`）
- [x] 统计、筛选和 CSV 导出（`matrix()` 返回 `UNCHECKED/NEEDS_REVIEW/MET/PARTIAL/MISSING` 计数与 `requiredMet/requiredTotal`；`exportCsv` 输出 BOM + RFC 4180 转义 + `csvValue` 公式前缀防护；前端 `tc-summary` 五格统计 + `data-status` 筛选）
- [x] 跨团队、关闭团队和已删除来源测试（`crossTeamFileIsRejectedBeforeItCanBeLinked`、`softDeletedAndPurgedLibraryFilesCannotBeEvidence`、`deletedDocumentIsRejectedButLiveDocumentNeedsReadAuthorization`、`taskEvidenceNeedsNoAssetGrantButStillNeedsTeamOwnership`、`readOnlyTeamCannotMaintainOrRequestReview`）

## 5. M3.3：Agent 集成

### 5.1 路由

- [x] `COLLAB_DOCUMENT`
- [x] `COLLAB_TABLE`
- [x] `MATERIAL_REVIEW`
- [x] 与现有任务动作路由的优先级测试

### 5.2 只读工具

- [x] `list_team_collaboration_assets`
- [x] `get_team_document`
- [x] `get_team_spreadsheet`
- [x] `search_team_collaboration_assets`
- [x] `get_material_matrix`
- [x] 服务端注入团队和用户范围
- [x] 超量、未知字段、越权 ID 拒绝（工具入参全部夹紧：`MAX_LISTED=50`、`versionLimit` 夹到 1..20、`rowLimit` 夹到 1..200、`topK` 夹到 1..20，非正整数 ID 直接拒绝；`TeamCollaborationAgentToolsLimitsTest` 7 个断言。未知根字段与未知 `after` 字段由 `TeamAgentActionSchemaTest.rejectsUnknownRootFields/rejectsUnknownAfterFields` 拒绝）

### 5.3 动作 schema

- [x] `CREATE_TEAM_DOCUMENT`
- [x] `APPEND_TEAM_DOCUMENT`
- [x] `CREATE_TEAM_TABLE`
- [x] `UPDATE_TEAM_TABLE_ROWS`
- [x] `CREATE_MATERIAL_REQUIREMENT`
- [x] `LINK_MATERIAL_EVIDENCE`
- [x] `SET_MATERIAL_REVIEW`
- [x] schemaVersion 白名单
- [x] 目标版本服务端读取
- [x] 每动作内容上限
- [x] 组合约束

### 5.4 执行与撤销

- [x] 文档/表格 CommandService
- [x] 动作处理器复用业务命令
- [x] 整组事务
- [x] 幂等
- [x] 目标版本复验
- [x] 创建撤销
- [x] 追加/表格更新恢复快照
- [x] 材料复核撤销
- [x] 冲突稳定错误码
- [x] Agent 审计与协作事件关联（Agent 命令复用业务服务，后者统一 `eventService.append`；动作行 ID 经 `agentRequestId(actionRowId, "append"|"rows"|"undo")` 透传为事件 `requestId`，`saveKind="AGENT"`；`TeamCollaborationAgentAuditLinkTest` 用真实事件服务 + 真实文档服务断言 `agent-88-append` / `agent-88-undo` 落到 `DOCUMENT_SAVED` / `DOCUMENT_VERSION_RESTORED`，并断言事件元数据不含正文与租约 token）

## 6. M3.4：前端

### 6.1 导航和协作首页

- [x] 团队侧栏“协作资产”（`team-space.html` 的 `data-panel="collab-panel"` 与 `#collab-panel`；`team-space.js` 的 `switchPanel` 首次进入时调 `teamCollab.reload()`）
- [x] 文档/表格/材料页签（`data-tc-tab="docs|sheets|materials"` + `data-tc-view`，`switchTab` 同步 `aria-selected` 与 `hidden`）
- [x] bootstrap 权限和能力（`loadFirstPaint` 调 `/collaboration/bootstrap` 取 `teamStatus/role/canWrite/canExport/readOnlyReason` 与两份清单，先渲染再补齐页签数据；`renderHeadActions` 在 `canWrite=false` 时不渲染创建按钮，`openDocument/openSpreadsheet` 把 `canWrite` 并入只读判定）
- [x] 空状态、错误和只读原因（`tc-empty` 覆盖无资产/无搜索结果/加载失败；`readOnlyReason()` 优先用服务端文案，`showBanner(..., 'warn')` 展示；`tc-banner.is-warn/is-danger` 已有样式）

### 6.2 文档

- [x] 目录与搜索（`#tcDocSearch` + `filterByTitle`；`renderDocumentList` 与网络加载分离，输入关键词不重复请求）
- [x] 阅读/编辑模式（`tc-mode-btn` 切换 `state.docMode`，预览走 `markdownToSafeHtml`，只读资产渲染 `readonly` 且隐藏工具栏）
- [x] Markdown 工具栏（`docToolbarHtml` 含标题/小标题/粗体/斜体/引用/代码/链接/表格/分隔线，接入 `onDocEditorKeydown` 的 Ctrl+B/I/K）
- [x] 自动保存状态（1.5s 空闲防抖 + 最小 3s 间隔；`tcDocState` 带 `role="status" aria-live="polite"` 显示已保存/只读/冲突）
- [x] 租约占用与冲突提示（`EDIT_LEASE_HELD` 降级只读并 `showLeaseHeld`；`EDIT_LEASE_INVALID/EXPIRED` 与 `EDIT_VERSION_CONFLICT` 有独立文案与刷新动作）
- [x] 版本列表与恢复（`tcDocHistoryBtn` → `/versions?limit=10`，`data-restore` 调 restore 接口后重载）
- [x] 导出（`/export.md` 走 `TeamCollabMarkdown.exportFilename`，标题穿越与 Windows 保留名已过滤）
- [x] WebSocket 更新（`team-space.js` 订阅 `/topic/team/{id}/collaboration`，仅收 `COLLABORATION_CHANGED` 后转 `teamCollab.remoteChanged`；有未保存草稿时改为提示冲突而不覆盖）

### 6.3 表格

- [x] 行列编辑（`contenteditable` 单元格 + `collectSheetRows`/`sheetDraftRows` 回写；`+ 添加一行` 与行尾删除按钮）
- [x] 类型与列宽（列 `type` 支持 `TEXT/NUMBER/DATE/CHECKBOX/SELECT`，`width` 夹到 72..480 并按列渲染；服务端 `TeamSpreadsheetSchema` 复验）
- [x] 键盘导航（`Ctrl/Cmd+S` 保存、`Tab/Shift+Tab` 横向移动、`Enter/Shift+Enter` 纵向移动、`Esc` 退出编辑；新增方向键四向移动，行尾向右/行首向左跨行）
- [x] 自动保存和冲突（同文档的防抖与最小间隔；`EDIT_VERSION_CONFLICT` 提示刷新并保留草稿）
- [x] 版本恢复（`tcSheetHistoryBtn` → `/versions?limit=10`，`data-restore-sheet` 调恢复接口）
- [x] CSV/XLSX 导出（`/export.csv` 走 RFC 4180 + 公式前缀中和；`/export.xlsx` 由 POI 生成安全 sheet 名）

### 6.4 材料矩阵

- [x] 状态统计（`tc-summary` 五格：已满足/满足/待复核/缺失/未核对 + 必需项 `requiredMet/requiredTotal`）
- [x] 筛选（状态下拉 `data-status` 与 `matrixFilter`，服务端 `matrix(status, required, category)` 复验枚举）
- [x] 新增和编辑要求（`tcNewReqBtn` → `openRequirementDialog`，`data-edit-requirement` 复用同一弹窗带 version）
- [x] 关联/移除证据（`openEvidenceDialog` 四类来源；`data-remove-evidence` 走 DELETE 证据接口）
- [x] 人工复核（`data-review` 按钮按权限渲染 `MET/PARTIAL/MISSING/NEEDS_REVIEW`，`/materials/requirements/{id}/review` 落复核轨迹）
- [x] CSV 导出（`/materials/matrix/export.csv`，BOM + RFC 4180 + `csvValue` 公式防护）

### 6.5 资料索引

- [x] 文件行状态图标和中文说明（`LIBRARY_INDEX_LABELS` 覆盖 `PENDING/INDEXING/READY/FAILED/STALE/SKIPPED`，`libraryIndexBadge` 渲染带 title 的徽标）
- [x] 失败原因与重试入口（`FAILED/SKIPPED` 的 `indexError` 进 title；`LIBRARY_INDEX_RETRYABLE` 在右键菜单与预览头提供“重新建立检索索引”，成功重置轮询计数）
- [x] 回收站不展示可检索状态（`libraryIndexBadge` 首行 `if (libraryTrashView || !node.indexStatus) return ''`）

### 6.6 Agent

- [x] 新能力入口（工作台既有能力芯片渲染 `capabilities.readOnly`，协作意图由 `TeamAgentIntentRouter` 路由到 `COLLAB_DOCUMENT/COLLAB_TABLE/MATERIAL_REVIEW`）
- [x] 文档/表格/材料工具结果卡（`team-agent-workbench.js` 从动作结果取 `documentId/spreadsheetId` 生成跳转，配合 `teamCollab.activeLeaseToken` 复用当前编辑租约）
- [x] 新动作卡（`renderActionCard` 渲染动作组、前后 diff、`undoStatus` 与剩余秒数）
- [x] 确认、冲突、撤销（`data-confirm-group` 整组确认；`AGENT_ACTION_CONFLICT` 走稳定错误码；`data-undo-action` 在窗口内可撤销，版本已前进则拒绝）
- [x] 不展示伪造的实时协作或已读能力（`team-agent-workbench.js`/`team-agent-workbench.css`/`team-space.html` 扫描无“已读/已阅/readReceipt/在线编辑”文案与字段；协作广播只发 `COLLABORATION_CHANGED`，不含在线状态）

### 6.7 响应式与可访问性

- [x] 1440/1280/1024/768/390px（登录团队 `93` 后逐档实测文档、表格、材料与 Agent；工具栏均按宽度换行，无页面级横向溢出）
- [x] 低高度窗口（`1280×600` 实测文档工具栏正常换行，页面可纵向滚动，回收站弹窗完整位于视口内）
- [x] 键盘完成编辑和表格导航（文档 `Ctrl/Cmd+S`、`Ctrl+B/I/K`；表格 `Tab/Shift+Tab`、`Enter/Shift+Enter`、四向方向键、`Esc`；弹窗内 `trapFocus` 限制焦点在对话框内）
- [x] `aria-live` 保存/冲突（`#tcDocState`、`#tcSheetState` 为 `role="status" aria-live="polite" aria-atomic="true"`；`#tcBanner` 为 `role="status" aria-live="polite"`）
- [x] 所有图标按钮可访问名称（工具栏按钮由 `docToolbarHtml` 统一写 `aria-label`；删行/移除证据/关闭弹窗均有 `aria-label`；`tc-tab` 有 `role="tab"` + `aria-selected`）
- [x] 无横向溢出（六档宽度 `documentElement.scrollWidth` 均不大于视口；表格只在 `.tc-sheet-wrap` 内横向滚动）

## 7. 清理与生命周期

- [x] 文档/表格软删除、回收站和彻底删除（V51 建索引；`team-collab.js` 提供协作资产回收站、恢复和彻底删除；条目补 `data-trash-item` 后按钮事件可正确定位；文档与表格均完成真实 UI 全链路验收。只读团队只显示说明，不提供恢复/彻底删除）
- [x] 团队删除接入 `AdminDataService`（`AdminDataService` 第 381 行调用 `teamCollaborationCleanupService.deleteTeamData(teamId)`，同处还有 `teamAgentDataCleanupService.deleteTeamData`）
- [x] 账号注销匿名化（`deleteUser` 调 `anonymizeUser`：事件 actor 置空、ACL 与租约按 user 删除，文档/表格保留以维持团队资产完整性）
- [x] 资料删除与索引 tombstone（`KgIndexService.enqueueDelete` 写不可见 tombstone 修订，`cleanupDeletedSource` 清理 chunk/实体；文档、表格、资料删除都调 `enqueueDelete`）
- [x] 租约过期定时清理（`TeamCollaborationCleanupService.purgeExpiredLeases` 删除 `expires_at < now`；调度入口见该类，取用侧另有 `AgentWriteLease` 抢占判定）
- [x] 历史版本保留策略（`MAX_VERSION_HISTORY=200`，只裁 `AUTO` 且 `version < latest-20`，手动/恢复快照不裁）
- [x] `team_agent_*` 清理与协作资产一致（`TeamAgentDataCleanupService` 按团队/账号删除，`AdminDataService` 与协作清理同处调用）

## 8. 安全与质量门禁

- [x] 跨团队伪造 ID（`TeamMaterialEvidenceValidatorTest.crossTeamFileIsRejectedBeforeItCanBeLinked` 断言不泄露来源名；`TeamCollaborationAuthorization.requireAsset` 比对 `asset.teamId`，`TeamDocumentService.requireVisible` 复验 teamId）
- [x] 非成员/待审批/已移除成员（`TeamCollaborationAuthorizationMatrixTest` 12 个用例：陌生人、待审批、已移除三种形态都不因残留 `TeamApplication` 行而获得权限，仅 `APPROVED` 才授予角色；同时覆盖创建者=队长、导师角色、`OPEN/CLOSED` 只读、`DISSOLVED` 按不存在处理）
- [x] `RESTRICTED` 越权（`TeamCollaborationAgentToolsAccessTest` 断言无授权的 `RESTRICTED` 文档/表格不进 Agent 事实集；`TeamScopedKgScopeServiceTest` 覆盖 GraphRAG 侧同一边界）
- [x] 关闭团队写入（`TeamCollaborationAuthorization.requireWritableTeam` 对非 `TEAMING` 抛 `TEAM_READ_ONLY`；`TeamCollaborationAgentCommandsTest.aReadOnlyTeamBlocksEveryCollaborationWrite`）
- [x] 解散团队读取（`requireTeam` 对 `DISSOLVED` 直接按 `TEAM_NOT_FOUND` 处理，避免泄露已解散团队存在性）
- [x] 租约抢写（`TeamCollaborationLeaseServiceTest` 覆盖他人活跃租约阻止获取、过期租约原子接管、token 失效、非持有者释放被拒）
- [x] 版本冲突（`TeamDocumentServiceIdempotencyTest`/`TeamSpreadsheetServiceIdempotencyTest` 的乐观锁断言；`TeamCollaborationControllerTest.leaseConflictUsesAnActualFailureEnvelope` 验证错误信封）
- [x] 请求 ID 复用（两个幂等测试各有一条“同 ID 不同内容被拒”，返回 `REQUEST_ID_REUSED`）
- [x] XSS Markdown（`TeamCollabMarkdownSecurityTest` 断言 script/事件属性/style 被剥离，前端 `renderMarkdownSafe` 走 marked + DOMPurify）
- [x] 危险链接协议（`javascript:`/`data:`/`vbscript:` 被剥离，http/https/mailto 保留，同一测试类断言）
- [x] CSV 公式注入（`TeamSpreadsheetExporterTest` 覆盖 `= + - @` 等前缀中和，材料矩阵 CSV 同一 `csvValue`）
- [x] 上传路径穿越（`FileService.resolveStoredPath` 拒绝穿越并校验 root 前缀，`FileServiceTest` 覆盖）
- [x] zip bomb（`TeamFileZipBombTest` 真实重打包 40 MiB 条目，断言命中 32 MiB 单条目阈值而非 20 MiB 源文件上限）
- [x] 加密/损坏 Office/PDF（`TeamFileTextExtractorTest` 覆盖损坏文件与加密 PDF 安全跳过，`TeamFileTextExtractorLimitsTest` 覆盖页数/单元格上限）
- [x] Agent Prompt injection（`TeamAgentPromptInjectionTest` 4 个对抗用例：文档正文藏伪造动作仍按纯文本保存、模型自带 `before/expectedVersion` 被拒、`DELETE_TEAM` 等高风险动作被拒、协作动作额外指令字段被拒；防护仍以 schema 白名单和服务端版本栅栏为主）
- [x] 敏感信息与绝对路径扫描（源码内无本机绝对路径与密钥字面量；数据库口令走 `SPRING_DATASOURCE_PASSWORD`/`DB_PASSWORD` 环境变量）
- [x] 无已读字段/接口/文案扫描（`team-agent-workbench.*`、`team-space.html`、`collab/`、`teamagent/` 全量扫描无已读回执相关字段或文案）

## 9. 测试矩阵

### 9.1 单元

- [x] 授权矩阵（`TeamCollaborationAuthorizationMatrixTest` 12 个用例）
- [x] 租约（`TeamCollaborationLeaseServiceTest` 9 个用例）
- [x] 文档校验（`TeamCollabMarkdownSecurityTest` 6 个 + `TeamDocumentServiceIdempotencyTest` 2 个）
- [x] 表格 schema（`TeamSpreadsheetSchemaTest` 6 个：键格式、类型、上限、稳定 ID）
- [x] 版本与哈希（`TeamCollabMarkdownSecurityTest.hash` 稳定性 + 两个幂等测试里的 `contentHash`/`requestHash`）
- [x] 材料状态机（`TeamMaterialMatrixServiceTest` 5 个 + `TeamMaterialEvidenceValidatorTest` 7 个）
- [x] 文件解析（`TeamFileTextExtractorTest` 7 个 + `TeamFileTextExtractorLimitsTest` 4 个 + `TeamFileZipBombTest` 2 个）
- [x] CSV/XLSX（`TeamSpreadsheetExporterTest` 3 个：BOM/RFC 4180/公式中和、sheet 名安全）
- [x] Agent schema（`TeamAgentActionSchemaTest` 6 个 + `TeamCollaborationAgentToolsLimitsTest` 7 个）
- [x] Agent prompt injection（`TeamAgentPromptInjectionTest` 4 个对抗用例）
- [x] 撤销（`TeamCollaborationAgentCommandsTest` 9 个 + `TeamAgentActionServiceTest` 10 个）

### 9.2 集成

- [ ] Controller → Service → DB（协作资产目前是 `@WebMvcTest` + Mock Service；已新增可选 `TeamCollaborationMysqlIT` 覆盖 Service→DB 的租约、乐观锁和幂等，本轮未执行；运行方式：`DB_PASSWORD=... mvn -Dtest=TeamCollaborationMysqlIT -Dilink.mysql.it=true test`，会自动创建 `ilink_it_m3`；Controller 链路仍未覆盖）
- [ ] 文档/表格并发保存（乐观锁与幂等是单元级断言，未做真实多线程并发集成）
- [ ] 索引 Worker fencing（`KgIndexServiceTest` 有 `staleWorkerCanNeverPublishGraphData` 与修订号断言，但不在 M3 协作链路的真实集成下）
- [ ] 删除与保存竞态（无真实并发用例）
- [ ] 团队关闭/成员离队（授权矩阵覆盖关闭只读；离队后的实时租约失效只有单元级）
- [ ] Agent 工具到动作确认（`TeamAgentActionServiceTest`、`TeamAgentActionProposalServiceCollabTest` 覆盖流程，但均为 mock 串联）
- [ ] 团队和账号清理（`TeamCollaborationCleanupServiceTest` 3 个、`AdminDataServiceTest` 覆盖调用，非真库）
- [ ] 材料来源权限收回（`TeamMaterialEvidenceValidatorTest.restrictedSpreadsheetKeepsItsViewCheckAndReturnsItsVersion` 覆盖授权复验，但“授权被撤销后已有证据如何降级展示”无集成用例）

### 9.3 数据库

- [ ] H2 测试迁移（`application-test.yml` 用 H2 且 `flyway.enabled: false`，M3 的 V49/V50/V51 从未在 H2 上跑过迁移，故保留未勾）
- [x] MySQL 5.7 Flyway（本机 MySQL 5.7 首次执行 schema `50 → 51`；随后启动记录 `Schema 'ilink' is up to date. No migration necessary`）
- [x] Flyway 重复启动（同一库连续两次启动均为 `up to date. No migration necessary`，未重复执行或破坏数据）
- [x] 索引和唯一键检查（V49 有 5 个 `UNIQUE KEY`：`uk_doc_version`、`uk_sheet_version`、`uk_collab_acl`、`uk_collab_lease_asset`、`uk_material_evidence`；V51 增加回收站查询索引）
- [x] 软删除/恢复/彻底删除数据检查（文档与表格均在真实 MySQL/UI 链路完成删除、回收站查询、恢复版本递增、再次删除和彻底删除；临时资产已清理，种子资产保留）

### 9.4 浏览器

- [x] 文档全流程（团队 `93` 新建 → 删除 → 回收站 → UI 恢复并递增版本 → 再删除 → 彻底删除；种子文档 ID 1 未受影响）
- [x] 表格全流程（团队 `93` 新建 → 删除 → 回收站展示 → 彻底删除；种子表格 ID 1 未受影响）
- [x] 资料索引状态（团队资料树实际显示“可被 AI 检索”，无失败请求）
- [x] 材料矩阵（统计、筛选与空结果状态正常渲染）
- [x] Agent 工作台页面（会话、能力区与输入框正常加载，`aria-busy=false`）
- [ ] Agent 动作确认与撤销（本轮完成页面与数据加载验收，未构造真实动作卡确认/撤销流程）
- [x] 五档响应式（1440/1280/1024/768/390 逐一实测；另补测 1280×600）
- [x] 控制台/网络/页面异常为 0（文档、表格、材料、Agent 页面加载及只读/回收站流程均未出现失败响应、控制台错误或页面异常）

## 10. 最终命令

- [x] `mvn -DskipTests compile`（多轮编译通过）
- [x] 定向 `mvn test`（`TeamCollaborationAgentAuditLinkTest` 2、`TeamCollaborationAgentToolsLimitsTest` 7、`TeamCollaborationAuthorizationMatrixTest` 12、`TeamAgentPromptInjectionTest` 4 全部通过）
- [x] 干净全量 `mvn test`（**1311 tests / 0 failures / 0 errors / 22 skipped / 172 个测试类**）
- [x] 最终 JAR 启动（最新构建在 `18090` 启动成功；V51 首次执行 schema `50 → 51`，再次启动为 `up to date. No migration necessary`）
- [x] 真实 API smoke（登录后团队 `93` 任务、协作 bootstrap、文档、表格、材料矩阵与 Agent 工作台请求均为 2xx；团队 `83` 不请求任务接口，只读能力正确）
- [x] 浏览器回归（覆盖可写/只读团队、文档与表格回收站、资料索引、材料矩阵、Agent 页面加载及六档尺寸）
- [x] `git diff --check`（仅 CRLF 行尾提示，无实际空白错误）
- [x] 前端脚本语法检查（`node --check` 覆盖 `team-collab.js`、`team-space.js`）
- [x] 最终打包成功（`target/iLink-1.0.jar`）
- [x] 敏感信息扫描（无本机绝对路径、无密钥字面量、无已读回执相关实现）

## 11. 文档收口

- [x] 更新主工作台设计（`2026-10-22-team-agent-workbench-design.md`）
- [x] 更新一体化视觉设计（`2026-10-22-ai-collab-system-integrated-ui-design.md`）
- [x] 更新 M3 设计状态（`2026-10-23-team-collaboration-assets-m3-design.md`）
- [x] 更新本实施计划全部勾选（按代码与测试证据逐条核对；无证据的 11 条明确保留未勾）
- [x] 更新隐私说明和平台规则（`privacy.html`/`terms.html` 原本已覆盖协作资产、受限可见范围、编辑租约、资料索引与 AI 建议边界；本轮在 `messages.properties`/`messages_en.properties` 的 `legal.privacy.s5.body` 增补 Agent 动作“需人工确认、审计记录动作编号/执行人/目标资产/版本、不保存正文与租约令牌”的说明，中英文同步）
- [ ] 更新论文能力边界（论文侧文本不在本仓库，未修改）
- [x] 记录已知限制和未执行性能压测（见第 12 章）
- [x] 明确聊天已读仍未实现且不是缺陷（本计划开头“明确排除”已声明，第 12 章再次说明）

## 12. 实施进度与已知限制

### 12.1 本轮真实完成

- V51 回收站索引迁移：MySQL 5.7 首次执行 schema `50 → 51`，再次启动为 `up to date. No migration necessary`。
- 协作资产回收站 UI：文档/表格 `data-trash-item` 修复后，恢复与彻底删除按钮可正确定位条目；真实完成文档恢复、版本递增、再次删除和彻底删除，以及表格删除、展示和彻底删除。
- 只读团队体验：团队 `83` 不请求 `/api/tasks`，不渲染新建文档/表格，协作资产显示只读原因；回收站明确提示不可恢复/彻底删除并隐藏操作按钮。
- OPEN 团队任务请求收口：团队状态为 `OPEN` 时前端不再请求任务接口，消除此前调用任务接口产生的 400 错误。
- 材料矩阵稳定性：仅在 `canWrite && canCreateRequirement` 时绑定新增要求事件，避免无权限状态下空按钮异常。
- 前端预览排版补齐：`team-collab.css` 自带 `.tc-doc__preview` 的标题/段落/列表/引用/代码/表格/复选框样式，不再依赖未加载的 `md-editor.css`。
- 可见范围授权串档缺陷：`hasVisibilityConsent(assetType, assetId)` 按资产比对，`saveDocument` 成功后重置授权，`openDocument/openSpreadsheet` 切换资产即重置。
- 安全门禁：`TeamCollabMarkdownSecurityTest` 6 个用例覆盖脚本/事件属性/style 剥离、危险协议剥离、导出文件名穿越与 Windows 保留名、控制字符与 200 KiB 上限、哈希稳定。
- 索引重试入口：`TeamLibraryService.reindex` + `POST /api/team/{teamId}/library/files/{fileId}/reindex`；前端在右键菜单与预览头提供入口，仅 `FAILED/STALE/SKIPPED` 可见。
- zip bomb 与 XML 安全：`ZipSecureFile.setMaxEntrySize(32 MiB)`（比 POI 默认 4 GiB 更严），条目数与解压比保持库默认；`isZipBomb` 按实测文案精确匹配，归类 `SKIPPED/FILE_ZIP_BOMB`。
- 目录与搜索：文档/表格列表支持按标题即时过滤，渲染与网络加载分离，输入时不重复请求。
- Agent 审计关联：动作行 ID 经 `agentRequestId(actionRowId, "append"|"rows"|"undo")` 透传为事件 `requestId`，`saveKind="AGENT"`；新增 `TeamCollaborationAgentAuditLinkTest` 用真实事件服务断言关联成立且不泄露正文与租约 token。
- 工具入参夹紧：`MAX_LISTED=50`、`versionLimit` 1..20、`rowLimit` 1..200、`topK` 1..20，非正整数 ID 直接拒绝；新增 `TeamCollaborationAgentToolsLimitsTest` 7 个断言。
- 授权矩阵：新增 `TeamCollaborationAuthorizationMatrixTest` 12 个用例，覆盖陌生人/待审批/已移除/创建者/导师/OPEN/CLOSED/DISSOLVED/RESTRICTED 无授权/显式授权/TEAM 可编辑。
- 前端 bootstrap：`loadFirstPaint` 改为调用 `/collaboration/bootstrap`，一次取回团队状态、角色能力与两份资产清单；`canWrite=false` 时不渲染创建按钮，`canWrite` 并入文档/表格只读判定，只读原因走 `tc-banner.is-warn`。
- 表格键盘导航：在既有 `Tab/Enter/Esc` 之外补齐四向方向键，行尾向右、行首向左跨行，输入法组合期间不拦截。
- 授权矩阵与工具上限：新增 `TeamCollaborationAuthorizationMatrixTest`（12）与 `TeamCollaborationAgentToolsLimitsTest`（7），覆盖陌生人/待审批/已移除/解散团队与 `MAX_LISTED`/版本/行数/topK 夹紧。
- 隐私与规则：`privacy.html`/`terms.html` 的中英文 `legal.privacy.s5.body` 增补 Agent 动作审计口径，明确“人工确认后才写入、审计不保存正文与租约令牌”。

### 12.2 测试计数

- 基线（本轮改动前）：1279 tests / 0 failures / 0 errors / 22 skipped / 167 个测试类。
- M3 新增：`TeamCollaborationAgentAuditLinkTest`、`TeamCollaborationAgentToolsLimitsTest`、`TeamCollaborationAuthorizationMatrixTest`、`TeamAgentPromptInjectionTest`、回收站与清理测试等。
- 最终全量：**1311 tests / 0 failures / 0 errors / 22 skipped / 172 个测试类**（已实跑确认；新增 `TeamAgentPromptInjectionTest` 4 个用例）。

### 12.3 已知限制

1. **Agent 动作真实验收未覆盖**：页面、权限和加载链路已验证；真实动作卡确认、冲突和撤销尚未在浏览器中构造并执行。
2. **9.2 集成层覆盖薄**：已新增可选 `TeamCollaborationMysqlIT`（真实 MySQL，覆盖租约、乐观锁和幂等重放），但本轮未执行，默认测试集也不包含它；Controller→Service→DB 的完整链路、并发保存、删除竞态和授权收回后的证据降级仍无真库并发用例。
3. **H2 测试迁移未启用**：`application-test.yml` 的 `flyway.enabled: false`，V49/V50/V51 未在 H2 上验证。
4. **Agent Prompt injection 已补 schema 层对抗用例**：`TeamAgentPromptInjectionTest` 覆盖“文档正文藏伪造动作”等 4 类样本；仍未做完整模型红队或端到端注入评测。
5. **未执行正式性能压测**：只有局部基准（快速检索 P50 3.16ms / P95 4.10ms、1024 维余弦 0.66μs），不是端到端负载测试。
6. **敏感数据与历史演示数据**：`application-dev.yml` 中数据库口令走环境变量，`docs/` 与源码未发现明文密钥；开发库仍有团队 `93`、文档 1、表格 1、材料要求 1/2、文件 49–53、测试 ACL 等验收数据。部分历史资料的显示名仍是存储 UUID，无法反推上传时原名；新上传路径会保存并优先显示 `originalFileName`。

### 12.4 待执行

1. ~~重新打包并重启 JAR~~（已完成：最新 JAR 在 `18090` 启动，V51 迁移与重复启动验证通过；验收结束后临时服务已停止，端口已释放）。
2. ~~完成浏览器全流程与五档响应式验收~~（已完成；文档、表格、材料、Agent 页面与只读/回收站流程均通过）。
3. 后续如需签收“Agent 写动作”端到端能力，补充真实动作卡确认、冲突和撤销浏览器用例，并补强 9.2 真库并发集成测试。

### 12.5 能力边界（论文口径）

- **聊天已读状态、已读人数、消息已读回执不在本期范围，也不作为缺陷**。通知中心的 `is_read` 是既有独立功能，与本条无关。
- 协作广播只发 `COLLABORATION_CHANGED` 这一条脱敏信号，不含资产 ID、版本或类型，避免向无 ACL 授权的成员泄露 `RESTRICTED` 资产的存在。
- Agent 的材料结论最多到 `NEEDS_REVIEW`；`MET/PARTIAL/MISSING` 必须由真人确认。
