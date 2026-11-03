package cn.ilink.teamagent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pi Agent 运行时调用内部工具时的请求体。
 * teamId/userId 由 Java 侧重新校验，模型无法伪造权限。
 */
public record InternalToolRequest(Long teamId, Long userId, Long runId, Map<String, Object> params) {
    public InternalToolRequest {
        // 工具参数里允许出现 null（例如可选的 offset），因此不能用 Map.copyOf。
        params = params == null ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
