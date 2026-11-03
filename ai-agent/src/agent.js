/**
 * Agent 组装：pi 负责循环与工具调度，检索/文件能力通过工具注入。
 * 这里不实现检索算法，也不直接读写团队资料库。
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Agent } from '@earendil-works/pi-agent-core';
import { streamSimple } from '@earendil-works/pi-ai/api/openai-completions';
import { config, modelDescriptor } from './config.js';
import { kgTools, kgPingTool } from './tools/kg.js';
import { officeTools } from './tools/office.js';
import { libraryTools } from './tools/library.js';

const SKILLS_DIR = fileURLToPath(new URL('../skills/', import.meta.url));

/** 启动时加载自建办公规范，拼进系统提示。 */
function loadSkills() {
  try {
    return readdirSync(SKILLS_DIR)
      .filter((name) => name.endsWith('.md'))
      .sort()
      .map((name) => readFileSync(join(SKILLS_DIR, name), 'utf8'))
      .join('\n\n---\n\n');
  } catch {
    return '';
  }
}

const BASE_PROMPT = `你是 iLink 团队协作平台里的团队 Agent。你只服务当前团队，只使用提供的工具。

规则：
1. 知识问题先检索，再回答。回答必须基于工具返回的事实；没有证据就说明没有。
2. 引用关系时使用工具返回的关系 ID（r#）与原文片段 ID（c#），不要编造。
3. 时间问题必须使用时间窗工具，不要把不同月份的事实混在一起。
4. 文件生成使用 office_generate，只传结构化内容；生成后报告文件名与用途。
5. 你不能创建任务、修改成员、解散团队或审核任务；这些只能作为建议由用户确认。
6. 不输出隐藏推理过程，不输出工具名以外的内部实现细节。
7. 中文回答，简洁、可核对。`;

const SYSTEM_PROMPT = `${BASE_PROMPT}

以下是本项目的办公文件规范，生成文件前必须先阅读并遵守：

${loadSkills()}`;

/**
 * @param {{context?: {teamId:number, userId:number, runId?:number}, includePing?: boolean}} options
 *        context 由 Java 运行器注入，模型不可见；缺省时不注册检索工具。
 */
export function createAgent({ context, includePing = false, sessionId } = {}) {
  const tools = [
    ...(context ? kgTools(context) : []),
    ...(context ? libraryTools(context) : []),
    ...officeTools(),
    ...(includePing ? [kgPingTool()] : [])
  ];
  return new Agent({
    initialState: {
      systemPrompt: SYSTEM_PROMPT,
      model: modelDescriptor(),
      tools
    },
    streamFn: streamSimple,
    getApiKey: () => process.env[config.model.apiKeyEnv],
    toolExecution: 'sequential',
    maxRetryDelayMs: 5000,
    sessionId
  });
}

/** 把 pi 事件压成调用方关心的一行摘要，便于接入 SSE。 */
export function describeEvent(event) {
  switch (event.type) {
    case 'agent_start':
      return { type: 'run.started' };
    case 'turn_start':
      return { type: 'turn.started', round: event.turnIndex ?? null };
    case 'tool_execution_start':
      return { type: 'tool.started', tool: event.toolName, callId: event.toolCallId };
    case 'tool_execution_end':
      return { type: 'tool.finished', tool: event.toolName, callId: event.toolCallId, isError: event.isError };
    case 'message_end':
      return { type: 'message.finished', role: event.message?.role };
    case 'agent_end':
      return { type: 'run.finished' };
    default:
      return null;
  }
}
