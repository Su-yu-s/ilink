/**
 * 知识检索工具：只读，全部转发给 Java 的 Agentic GraphRAG 内部端点。
 * 这里不实现任何检索算法——检索始终由 Java 侧负责。
 *
 * teamId/userId/runId 由服务端注入，模型既看不到也改不了。
 */
import { config } from '../config.js';

const TOOL_SPECS = [
  {
    name: 'kg_search_entities',
    description: '按名称或语义在知识图谱里检索实体。返回实体 ID、名称、类型。探索新问题时先调用它。',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', description: '实体名称或一句描述' },
        top_k: { type: 'integer', minimum: 1, maximum: 20, default: 8 }
      },
      required: ['query'],
      additionalProperties: false
    }
  },
  {
    name: 'kg_filter_entities',
    description: '按实体类型或名称关键词过滤实体。',
    parameters: {
      type: 'object',
      properties: {
        entity_type: { type: 'string', description: '实体类型，可省略' },
        keyword: { type: 'string', description: '名称关键词，可省略' },
        limit: { type: 'integer', minimum: 1, maximum: 30, default: 10 }
      },
      additionalProperties: false
    }
  },
  {
    name: 'kg_expand_neighbors',
    description: '查询某个实体的直接关系。返回关系 ID、方向和对方实体。',
    parameters: {
      type: 'object',
      properties: {
        entity_id: { type: 'integer' },
        limit: { type: 'integer', minimum: 1, maximum: 50, default: 20 },
        offset: { type: 'integer', minimum: 0, default: 0 }
      },
      required: ['entity_id'],
      additionalProperties: false
    }
  },
  {
    name: 'kg_query_temporal',
    description: '查询某实体在指定时间窗内有效的竞赛关联。时间窗为半开区间 [start, end)。',
    parameters: {
      type: 'object',
      properties: {
        entity_id: { type: 'integer', description: '目标实体 ID' },
        time_range: {
          type: 'object',
          properties: {
            start: { type: 'string', description: '开始日期 yyyy-MM-dd（含）' },
            end: { type: 'string', description: '结束日期 yyyy-MM-dd（不含）' }
          },
          required: ['start', 'end'],
          additionalProperties: false
        },
        limit: { type: 'integer', minimum: 1, maximum: 50, default: 20 }
      },
      required: ['entity_id', 'time_range'],
      additionalProperties: false
    }
  },
  {
    name: 'kg_get_document',
    description: '读取某个实体对应的原文文档片段。',
    parameters: {
      type: 'object',
      properties: { entity_id: { type: 'integer' } },
      required: ['entity_id'],
      additionalProperties: false
    }
  },
  {
    name: 'kg_search_documents',
    description: '按关键词检索文档原文片段。适合回答“原文怎么说”这类问题。',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string' },
        top_k: { type: 'integer', minimum: 1, maximum: 20, default: 8 }
      },
      required: ['query'],
      additionalProperties: false
    }
  },
  {
    name: 'kg_get_community_summary',
    description: '读取某个实体所在社区的摘要。',
    parameters: {
      type: 'object',
      properties: { entity_id: { type: 'integer' } },
      required: ['entity_id'],
      additionalProperties: false
    }
  },
  {
    name: 'kg_read_relation_source',
    description: '读取某条关系指向的原文片段，用于核对事实。',
    parameters: {
      type: 'object',
      properties: {
        relation_id: { type: 'integer' },
        member_entity_id: { type: 'integer' }
      },
      required: ['relation_id', 'member_entity_id'],
      additionalProperties: false
    }
  }
];

async function callJava(path, envelope, signal) {
  if (!config.internalToken) {
    throw new Error('缺少 ILINK_INTERNAL_TOKEN，无法调用 Java 内部端点');
  }
  const response = await fetch(`${config.javaBaseUrl}${path}`, {
    method: 'POST',
    signal,
    headers: {
      'content-type': 'application/json',
      'x-ilink-internal-token': config.internalToken
    },
    body: JSON.stringify(envelope)
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Java 工具端点 ${path} 返回 ${response.status}: ${text.slice(0, 300)}`);
  }
  return text;
}

/** 工具执行结果统一包成 pi 需要的 content 数组。 */
export function asToolResult(text) {
  return { content: [{ type: 'text', text: typeof text === 'string' ? text : JSON.stringify(text) }] };
}

/**
 * @param {{teamId: number, userId: number, runId?: number}} context
 *        由 Java 运行器注入，模型不可见。
 */
export function kgTools(context) {
  if (!context?.teamId || !context?.userId) {
    throw new Error('kgTools 需要 teamId 与 userId');
  }
  return TOOL_SPECS.map((spec) => ({
    name: spec.name,
    label: spec.name,
    description: spec.description,
    parameters: spec.parameters,
    execute: async (_toolCallId, params, signal) => {
      const text = await callJava(`/internal/agent/tools/${spec.name}`, {
        teamId: context.teamId,
        userId: context.userId,
        runId: context.runId ?? null,
        params: params ?? {}
      }, signal);
      return asToolResult(text);
    }
  }));
}

/** 阶段 0 自检用：不依赖 Java，验证工具注册与执行链路。 */
export function kgPingTool() {
  return {
    name: 'kg_ping',
    label: 'kg_ping',
    description: '连通性自检工具，返回 Java 后端配置是否就绪（不发起真实请求）。',
    parameters: { type: 'object', properties: {}, additionalProperties: false },
    execute: async () => asToolResult({
      ok: true,
      javaBaseUrl: config.javaBaseUrl,
      internalTokenConfigured: Boolean(config.internalToken)
    })
  };
}
