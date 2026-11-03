package cn.ilink.teamagent;

import cn.ilink.service.kg.tool.GraphTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内部工具端点：把 pi 的工具调用转成现有 GraphTool 执行。
 *
 * 安全边界：
 * 1. 只接受白名单工具名，pi 无法调用任意 Bean；
 * 2. 共享令牌常量时间比较；
 * 3. 每次调用都用 userId/teamId 重新构建 TeamAgentScope，不信任调用方缓存的权限；
 * 4. 作用域在 finally 中关闭，避免线程复用导致越权。
 */
@Service
public class InternalAgentToolService {
    private static final Logger log = LoggerFactory.getLogger(InternalAgentToolService.class);

    /** pi 工具名 → 现有 GraphTool 名 */
    private static final Map<String, String> ALLOWED = Map.of(
        "kg_search_entities", "search_entities",
        "kg_filter_entities", "filter_entities",
        "kg_expand_neighbors", "expand_neighbors",
        "kg_query_temporal", "query_temporal",
        "kg_get_document", "get_document",
        "kg_search_documents", "search_documents",
        "kg_get_community_summary", "get_community_summary",
        "kg_read_relation_source", "read_relation_source");

    private final TeamAgentProperties properties;
    private final TeamAgentAuthorization authorization;
    private final Map<String, GraphTool> toolsByName;

    public InternalAgentToolService(TeamAgentProperties properties,
                                    TeamAgentAuthorization authorization,
                                    List<GraphTool> tools) {
        this.properties = properties;
        this.authorization = authorization;
        this.toolsByName = tools.stream()
            .collect(Collectors.toMap(GraphTool::getName, Function.identity(), (first, second) -> first));
    }

    public ResponseEntity<?> invoke(String toolName, String token, InternalToolRequest request) {
        if (!properties.getInternal().isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "INTERNAL_TOOLS_DISABLED", "内部工具端点未启用");
        }
        if (!tokenMatches(token)) {
            return error(HttpStatus.UNAUTHORIZED, "INTERNAL_TOKEN_INVALID", "内部令牌无效");
        }
        String graphTool = ALLOWED.get(toolName);
        if (graphTool == null) {
            return error(HttpStatus.BAD_REQUEST, "TOOL_NOT_ALLOWED", "不允许的工具：" + toolName);
        }
        GraphTool tool = toolsByName.get(graphTool);
        if (tool == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "TOOL_UNAVAILABLE", "工具未注册：" + graphTool);
        }
        try {
            TeamAgentScope scope = authorization.requireScope(request.teamId(), request.userId());
            try (TeamAgentScopeContext.BoundScope ignored = TeamAgentScopeContext.bind(scope)) {
                Object result = tool.execute(new LinkedHashMap<>(request.params()), request.userId());
                return ResponseEntity.ok(result);
            }
        } catch (TeamAgentException denied) {
            log.info("内部工具 {} 被拒绝：team={} user={} code={}", toolName,
                request.teamId(), request.userId(), denied.getErrorCode());
            return error(denied.getErrorCode().status(), denied.getErrorCode().name(), denied.getMessage());
        } catch (RuntimeException failed) {
            log.warn("内部工具 {} 执行失败：team={} user={}", toolName, request.teamId(), request.userId(), failed);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "TOOL_EXECUTION_FAILED", "工具执行失败");
        }
    }

    private boolean tokenMatches(String provided) {
        String expected = properties.getInternal().getToken();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}
