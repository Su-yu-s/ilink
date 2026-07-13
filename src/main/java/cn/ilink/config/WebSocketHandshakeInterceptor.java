package cn.ilink.config;

import cn.ilink.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.servlet.http.HttpSession;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

/**
 * 将 HTTP Session 中的登录用户复制到 WebSocket 会话属性，并进行 Origin 校验防 CSRF。
 */
@Component
public class WebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHandshakeInterceptor.class);

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpSession session = servletRequest.getServletRequest().getSession(false);
            if (session != null) {
                User user = (User) session.getAttribute("user");
                if (user != null && user.getId() != null) {
                    // Origin 校验：阻止跨域 WebSocket 连接（防 CSRF）
                    String origin = servletRequest.getServletRequest().getHeader("Origin");
                    if (origin != null && !origin.isBlank() && !isOriginAllowed(origin, request)) {
                        log.warn("WebSocket 拒绝非法 Origin: {}", origin);
                        return false;
                    }
                    attributes.put("user", user);
                    attributes.put("userId", user.getId());
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 检查 Origin 是否合法：允许同源（Origin 与 Host 一致）及本地开发地址。
     */
    private boolean isOriginAllowed(String origin, ServerHttpRequest request) {
        try {
            URI originUri = URI.create(origin);
            String originHost = originUri.getHost();
            InetSocketAddress requestHost = request.getHeaders().getHost();
            if (originHost == null || requestHost == null
                || !originHost.equalsIgnoreCase(requestHost.getHostString())) {
                return false;
            }

            String requestScheme = request.getURI().getScheme();
            if (requestScheme != null && originUri.getScheme() != null
                && !requestScheme.equalsIgnoreCase(originUri.getScheme())) {
                return false;
            }

            return effectivePort(originUri.getScheme(), originUri.getPort())
                == effectivePort(requestScheme, requestHost.getPort());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
