package cn.ilink.service.kg.tool;

import java.util.Map;

/**
 * Agentic GraphRAG 工具接口。每个工具是一个独立 Spring Bean，
 * 由 AgenticGraphRagService 的 ReAct 循环按 Action 名分发调用；
 * GraphPermissionAspect 拦截 execute() 做权限校验。
 */
public interface GraphTool {

    /** 工具名（ReAct 的 Action 名），如 search_entities */
    String getName();

    /** 给 LLM 的使用说明：什么时候该用这个工具 */
    String getDescription();

    /** 参数的 JSON Schema（type/properties/required） */
    String getParametersSchema();

    /**
     * 执行工具。
     *
     * @param params ReAct 解析出的 Action Input（JSON 对象）
     * @param userId 当前用户（用于可见性过滤）
     * @return 观察结果（结构化文本/JSON 字符串）。权限不足等失败场景返回
     *         结构化错误字符串而不是抛异常，Agent 读到后可自行换策略。
     */
    Object execute(Map<String, Object> params, Long userId);
}
