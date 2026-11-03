package cn.ilink.teamagent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供本机/内网 pi Agent 运行时调用的工具端点。
 *
 * 默认不注册；只有 app.team-agent.internal.enabled=true 时才暴露。
 * 网关层应同时限制来源网段，令牌只是第二道防线。
 */
@RestController
@RequestMapping("/internal/agent/tools")
@ConditionalOnProperty(prefix = "app.team-agent.internal", name = "enabled", havingValue = "true")
public class InternalAgentToolController {
    private final InternalAgentToolService service;

    public InternalAgentToolController(InternalAgentToolService service) {
        this.service = service;
    }

    @PostMapping("/{tool}")
    public ResponseEntity<?> invoke(@PathVariable String tool,
                                    @RequestHeader(value = "x-ilink-internal-token", required = false) String token,
                                    @RequestBody(required = false) InternalToolRequest request) {
        if (request == null) {
            return service.invoke(tool, token, new InternalToolRequest(null, null, null, null));
        }
        return service.invoke(tool, token, request);
    }
}
