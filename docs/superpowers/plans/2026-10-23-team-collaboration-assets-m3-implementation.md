# 团队协作资产 M3 实施计划

- 日期：2026-10-23
- 设计基线：`docs/superpowers/specs/2026-10-23-team-collaboration-assets-m3-design.md`
- 状态：已确认设计，待书面规格复核后实施
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

- [ ] V49 创建 `team_collab_document`
- [ ] V49 创建 `team_collab_document_version`
- [ ] V49 创建 `team_collab_spreadsheet`
- [ ] V49 创建 `team_collab_spreadsheet_version`
- [ ] V49 创建 `team_collab_acl`
- [ ] V49 创建 `team_collab_edit_lease`
- [ ] V49 创建 `team_collab_event`
- [ ] V49 创建材料要求、证据、复核表
- [ ] V50 扩展 `team_library_files` 索引状态
- [ ] 迁移兼容 MySQL 5.7，唯一键和索引覆盖团队查询
- [ ] 重复启动 Flyway 不重复执行或破坏数据

### 2.2 授权与租约

- [ ] `TeamCollaborationAuthorization`
- [ ] 团队有效成员、创建者、导师和学生统一判定
- [ ] `TEAM/RESTRICTED` 与 ACL 判定
- [ ] `OPEN/TEAMING/CLOSED/DISSOLVED` 状态矩阵
- [ ] `TeamCollaborationLeaseService`
- [ ] 90 秒租约、30 秒续租、幂等 token 轮换
- [ ] 过期租约原子接管
- [ ] 租约 token 哈希和常量时间比较
- [ ] 成员离队、团队关闭和资产删除即时使租约失效

### 2.3 文档

- [ ] 实体、Mapper、DTO 和严格请求校验
- [ ] 创建、列表、详情、重命名、归档、软删除、恢复
- [ ] 自动保存与手动保存共用命令
- [ ] 版本快照、分页、恢复和清理策略
- [ ] Markdown 规范化、哈希和服务端清洗
- [ ] Markdown 导出与安全文件名
- [ ] WebSocket 提交后变更通知

### 2.4 表格

- [ ] 实体、Mapper、DTO
- [ ] schema/rows 规范化
- [ ] 50 列、5000 行、1 MiB 限制
- [ ] 稳定列 ID/行 ID 和类型校验
- [ ] 创建、列表、详情、保存、版本、恢复、删除
- [ ] RFC 4180 CSV 导出与公式注入防护
- [ ] Apache POI XLSX 导出
- [ ] WebSocket 提交后变更通知

### 2.5 协作事件

- [ ] 人工操作只追加事件
- [ ] 请求 ID 与版本记录
- [ ] 元数据脱敏
- [ ] 团队和账号删除清理
- [ ] WebSocket 失败不影响数据库提交

## 3. M3.1：资料正文索引

### 3.1 依赖与解析器

- [ ] 引入 Apache PDFBox
- [ ] 引入 Apache POI OOXML 和必要 scratchpad
- [ ] `TeamFileTextExtractor`
- [ ] PDF 文本层
- [ ] DOCX/XLSX/PPTX
- [ ] Markdown/TXT/CSV
- [ ] 非文本类型 `SKIPPED`
- [ ] 加密、损坏、超大、超页数、超单元格限制
- [ ] 路径约束到上传根目录
- [ ] zip bomb 和 XML 安全限制

### 3.2 索引队列

- [ ] `TEAM_FILE`、`TEAM_DOCUMENT`、`TEAM_SPREADSHEET` 类型
- [ ] 上传、文档保存、表格保存、重命名、恢复合并入队
- [ ] 软删除、彻底删除和团队删除登记 DELETE
- [ ] Worker 修订号与租约 fencing
- [ ] `PENDING/INDEXING/READY/FAILED/STALE/SKIPPED` 状态同步
- [ ] 失败退避和稳定错误码
- [ ] 旧 Worker 不覆盖新修订

### 3.3 GraphRAG 授权

- [ ] `KgScopeService` 支持三种协作文档类型
- [ ] 文件团队和删除状态复验
- [ ] 文档/表格可见范围复验
- [ ] `RESTRICTED` 不进入无权用户工具上下文
- [ ] 实体、关系、摘要、计数和引用全路径测试

## 4. M3.2：材料矩阵

- [ ] 材料要求 CRUD 与团队状态权限
- [ ] 目录竞赛和自定义竞赛绑定
- [ ] 手工要求
- [ ] Agent 候选要求
- [ ] 证据关联文件、文档、表格和任务材料
- [ ] 删除来源后的安全快照
- [ ] 状态与复核版本
- [ ] Agent 只能建议 `NEEDS_REVIEW`
- [ ] 真人确认 `MET/PARTIAL/MISSING`
- [ ] 统计、筛选和 CSV 导出
- [ ] 跨团队、关闭团队和已删除来源测试

## 5. M3.3：Agent 集成

### 5.1 路由

- [ ] `COLLAB_DOCUMENT`
- [ ] `COLLAB_TABLE`
- [ ] `MATERIAL_REVIEW`
- [ ] 与现有任务动作路由的优先级测试

### 5.2 只读工具

- [ ] `list_team_collaboration_assets`
- [ ] `get_team_document`
- [ ] `get_team_spreadsheet`
- [ ] `search_team_collaboration_assets`
- [ ] `get_material_matrix`
- [ ] 服务端注入团队和用户范围
- [ ] 超量、未知字段、越权 ID 拒绝

### 5.3 动作 schema

- [ ] `CREATE_TEAM_DOCUMENT`
- [ ] `APPEND_TEAM_DOCUMENT`
- [ ] `CREATE_TEAM_TABLE`
- [ ] `UPDATE_TEAM_TABLE_ROWS`
- [ ] `CREATE_MATERIAL_REQUIREMENT`
- [ ] `LINK_MATERIAL_EVIDENCE`
- [ ] `SET_MATERIAL_REVIEW`
- [ ] schemaVersion 白名单
- [ ] 目标版本服务端读取
- [ ] 每动作内容上限
- [ ] 组合约束

### 5.4 执行与撤销

- [ ] 文档/表格 CommandService
- [ ] 动作处理器复用业务命令
- [ ] 整组事务
- [ ] 幂等
- [ ] 目标版本复验
- [ ] 创建撤销
- [ ] 追加/表格更新恢复快照
- [ ] 材料复核撤销
- [ ] 冲突稳定错误码
- [ ] Agent 审计与协作事件关联

## 6. M3.4：前端

### 6.1 导航和协作首页

- [ ] 团队侧栏“协作资产”
- [ ] 文档/表格/材料页签
- [ ] bootstrap 权限和能力
- [ ] 空状态、错误和只读原因

### 6.2 文档

- [ ] 目录与搜索
- [ ] 阅读/编辑模式
- [ ] Markdown 工具栏
- [ ] 自动保存状态
- [ ] 租约占用与冲突提示
- [ ] 版本列表与恢复
- [ ] 导出
- [ ] WebSocket 更新

### 6.3 表格

- [ ] 行列编辑
- [ ] 类型与列宽
- [ ] 键盘导航
- [ ] 自动保存和冲突
- [ ] 版本恢复
- [ ] CSV/XLSX 导出

### 6.4 材料矩阵

- [ ] 状态统计
- [ ] 筛选
- [ ] 新增和编辑要求
- [ ] 关联/移除证据
- [ ] 人工复核
- [ ] CSV 导出

### 6.5 资料索引

- [ ] 文件行状态图标和中文说明
- [ ] 失败原因与重试入口
- [ ] 回收站不展示可检索状态

### 6.6 Agent

- [ ] 新能力入口
- [ ] 文档/表格/材料工具结果卡
- [ ] 新动作卡
- [ ] 确认、冲突、撤销
- [ ] 不展示伪造的实时协作或已读能力

### 6.7 响应式与可访问性

- [ ] 1440/1280/1024/768/390px
- [ ] 低高度窗口
- [ ] 键盘完成编辑和表格导航
- [ ] `aria-live` 保存/冲突
- [ ] 所有图标按钮可访问名称
- [ ] 无横向溢出

## 7. 清理与生命周期

- [ ] 文档/表格软删除、回收站和彻底删除
- [ ] 团队删除接入 `AdminDataService`
- [ ] 账号注销匿名化
- [ ] 资料删除与索引 tombstone
- [ ] 租约过期定时清理
- [ ] 历史版本保留策略
- [ ] `team_agent_*` 清理与协作资产一致

## 8. 安全与质量门禁

- [ ] 跨团队伪造 ID
- [ ] 非成员/待审批/已移除成员
- [ ] `RESTRICTED` 越权
- [ ] 关闭团队写入
- [ ] 解散团队读取
- [ ] 租约抢写
- [ ] 版本冲突
- [ ] 请求 ID 复用
- [ ] XSS Markdown
- [ ] 危险链接协议
- [ ] CSV 公式注入
- [ ] 上传路径穿越
- [ ] zip bomb
- [ ] 加密/损坏 Office/PDF
- [ ] Agent Prompt injection
- [ ] 敏感信息与绝对路径扫描
- [ ] 无已读字段/接口/文案扫描

## 9. 测试矩阵

### 9.1 单元

- [ ] 授权矩阵
- [ ] 租约
- [ ] 文档校验
- [ ] 表格 schema
- [ ] 版本与哈希
- [ ] 材料状态机
- [ ] 文件解析
- [ ] CSV/XLSX
- [ ] Agent schema
- [ ] 撤销

### 9.2 集成

- [ ] Controller → Service → DB
- [ ] 文档/表格并发保存
- [ ] 索引 Worker fencing
- [ ] 删除与保存竞态
- [ ] 团队关闭/成员离队
- [ ] Agent 工具到动作确认
- [ ] 团队和账号清理
- [ ] 材料来源权限收回

### 9.3 数据库

- [ ] H2 测试迁移
- [ ] MySQL 5.7 Flyway
- [ ] Flyway 重复启动
- [ ] 索引和唯一键检查
- [ ] 软删除/恢复/彻底删除数据检查

### 9.4 浏览器

- [ ] 文档全流程
- [ ] 表格全流程
- [ ] 资料索引状态
- [ ] 材料矩阵
- [ ] Agent 动作
- [ ] 五档响应式
- [ ] 控制台/网络/页面异常为 0

## 10. 最终命令

- [ ] `mvn -DskipTests compile`
- [ ] 定向 `mvn test`
- [ ] 干净全量 `mvn test`
- [ ] 最终 JAR 启动
- [ ] 真实 API smoke
- [ ] 浏览器回归
- [ ] `git diff --check`
- [ ] 敏感信息扫描

## 11. 文档收口

- [ ] 更新主工作台设计
- [ ] 更新一体化视觉设计
- [ ] 更新 M3 设计状态
- [ ] 更新本实施计划全部勾选
- [ ] 更新隐私说明和平台规则
- [ ] 更新论文能力边界
- [ ] 记录已知限制和未执行性能压测
- [ ] 明确聊天已读仍未实现且不是缺陷
