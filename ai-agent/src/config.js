/**
 * 运行时配置。密钥只从环境变量读取，任何日志都不得输出其值。
 */
import { fileURLToPath } from 'node:url';

export const config = {
  /** Java 后端基址，工具调用与文件下载都指向它 */
  javaBaseUrl: process.env.ILINK_JAVA_BASE_URL || 'http://127.0.0.1:8090',
  /** 内部工具端点共享令牌（Java 侧校验） */
  internalToken: process.env.ILINK_INTERNAL_TOKEN || '',
  /** 模型服务：默认走 DashScope 的 OpenAI 兼容端点 */
  model: {
    id: process.env.ILINK_MODEL_ID || 'qwen3.7-flash',
    baseUrl: process.env.ILINK_MODEL_BASE_URL || 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    apiKeyEnv: 'DASHSCOPE_API_KEY',
    contextWindow: Number(process.env.ILINK_MODEL_CONTEXT || 131072),
    maxTokens: Number(process.env.ILINK_MODEL_MAX_TOKENS || 4096)
  },
  /** 沙箱工作区：Agent 只能在这里读写文件 */
  workspace: process.env.ILINK_AGENT_WORKSPACE || fileURLToPath(new URL('../workspace/', import.meta.url)),
  /** 单文件上限，与 Java 侧 TeamFileTextExtractor.MAX_SOURCE_BYTES 对齐 */
  maxFileBytes: 20 * 1024 * 1024,
  /** 单次运行最多工具轮次 */
  maxTurns: Number(process.env.ILINK_AGENT_MAX_TURNS || 12)
};

export function modelDescriptor() {
  return {
    id: config.model.id,
    name: config.model.id,
    api: 'openai-completions',
    provider: 'dashscope',
    baseUrl: config.model.baseUrl,
    input: ['text'],
    output: ['text'],
    reasoning: false,
    contextWindow: config.model.contextWindow,
    maxTokens: config.model.maxTokens,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
  };
}
