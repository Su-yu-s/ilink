# iLink 团队 Agent 运行时（ai-agent）

基于 [`@earendil-works/pi-agent-core`](https://github.com/earendil-works/pi) 的团队 Agent 运行时。

职责边界：

- **pi 负责**：对话循环、工具调度、上下文与流式事件。
- **Java 负责**：知识检索（Agentic GraphRAG）、团队权限、动作提案与确认、资料库入库与索引。
- **本服务不实现检索算法**，所有检索通过 HTTP 调用 Java 内部端点。
- **本服务不直接写团队资料库**，生成的文件先落在沙箱 `workspace/`，经用户确认后由 Java 入库。

## 目录

```
ai-agent/
├── src/
│   ├── config.js           环境配置（密钥只读环境变量）
│   ├── agent.js            Agent 组装 + 事件摘要
│   ├── index.js            CLI / 自检入口
│   ├── tools/kg.js         检索工具（转发 Java）
│   ├── tools/office.js     办公文件工具
│   └── office/writers.js   docx/xlsx/pptx/pdf 生成器
├── skills/                 自建办公规范（不是 Anthropic 官方材料的副本）
│   ├── office-docx.md
│   ├── office-xlsx.md
│   ├── office-pptx.md
│   └── office-pdf.md
└── workspace/              沙箱目录（已 gitignore）
```

## 运行

```bash
npm install
npm start                  # HTTP/SSE 服务，供 Java 调用
npm run cli -- --self-test # 本地工具链自检
npm run cli -- "2026 年 3 月 iLink 交流社区关联了什么竞赛？"
```

Java 侧灰度开关：

```bash
APP_TEAM_AGENT_PI_AGENT_ENABLED=true
APP_TEAM_AGENT_PI_AGENT_BASE_URL=http://127.0.0.1:8787
APP_TEAM_AGENT_PI_AGENT_TOKEN=<与 ILINK_INTERNAL_TOKEN 相同或独立>
# 可选：只对指定团队开启
APP_TEAM_AGENT_PI_AGENT_TEAM_ALLOWLIST=1,2,3
```

pi 不可用、超时或返回错误时，自动回退到进程内 Agentic GraphRAG。

## 环境变量

| 变量 | 说明 | 默认 |
|---|---|---|
| `ILINK_JAVA_BASE_URL` | Java 后端地址 | `http://127.0.0.1:8090` |
| `ILINK_INTERNAL_TOKEN` | 调用 Java 内部端点的共享令牌 | 空（必填） |
| `ILINK_MODEL_ID` | 模型 ID | `qwen3.7-flash` |
| `ILINK_MODEL_BASE_URL` | OpenAI 兼容端点 | DashScope 兼容模式 |
| `DASHSCOPE_API_KEY` | 模型密钥 | 无（必填） |
| `ILINK_AGENT_WORKSPACE` | 沙箱目录 | `ai-agent/workspace/` |
| `ILINK_PDF_FONT` | PDF 中文字体 | `C:/Windows/Fonts/simhei.ttf` |
| `ILINK_AGENT_MAX_TURNS` | 单次运行最大轮次 | 12 |
| `ILINK_AGENT_PORT` | HTTP/SSE 服务端口 | 8787 |

## 工具

检索（只读，转发 Java `/internal/agent/tools/*`）：

- `kg_search_entities`、`kg_filter_entities`、`kg_expand_neighbors`
- `kg_query_temporal`、`kg_get_document`、`kg_search_documents`
- `kg_get_community_summary`、`kg_read_relation_source`

办公文件：`office_generate`（docx/xlsx/pptx/pdf）、`office_list_workspace`。

资料库写入：

- `library_propose_upload`——生成 `CREATE_LIBRARY_FILE` 动作草案
- `library_propose_update`——用新文件替换同名文件，生成 `UPDATE_LIBRARY_FILE` 草案

两者都**不会立即入库**，用户在“待确认动作”里确认后才写入 `team_library_files`
并触发索引；更新动作会把旧文件移入回收站。

模板填充：`office_generate` 传 `templatePath` + `replacements`，按 `${key}` 占位符
填充 docx/pptx，保留模板原有样式。

## Java 侧启用

内部端点默认不注册，需要同时配置：

```bash
APP_TEAM_AGENT_INTERNAL_ENABLED=true
APP_TEAM_AGENT_INTERNAL_TOKEN=<随机长令牌>
```

pi 侧对应 `ILINK_INTERNAL_TOKEN`。令牌只走环境变量，不写配置文件。

## 关于 skills

`skills/` 下的四份规范是**本项目自己写的**，面向竞赛材料场景，基于实际使用的开源库
（`docx`、`exceljs`、`pptxgenjs`、`pdf-lib`）。

它们参考了 Anthropic 公开文档中关于 Office 文件结构的一般性知识，但**没有复制或改编
`anthropics/skills` 仓库中的任何文本或代码**——该仓库的 document skills 采用
Proprietary 许可，禁止复制、创作衍生作品和再分发。

## 当前状态

- [x] 阶段 0：pi 运行时骨架、工具注册、四种办公文件生成、自检
- [x] 阶段 1：Java `/internal/agent/tools/*` 端点、白名单、令牌鉴权、作用域重建
- [x] 办公规范：5 份自建 skill，启动时注入系统提示
- [x] 阶段 2：`library_propose_upload`、文件暂存、`CREATE_LIBRARY_FILE` 动作与确认后入库
- [x] 阶段 3：docx/pptx 模板填充、`UPDATE_LIBRARY_FILE` 版本更新（旧版进回收站）
- [x] 阶段 4：HTTP/SSE 服务、事件转译、按团队灰度、失败自动回退
