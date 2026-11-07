package cn.ilink.config;

import cn.ilink.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * WebSocket 握手的 Origin 校验。
 *
 * <p>回归来源：nginx 终止 HTTPS 后转发到本机，Tomcat 看到的 scheme 恒为 http，
 * 而浏览器发的 Origin 是 https——直接比较会让生产环境所有握手失败，
 * 聊天降级轮询、协同编辑事件收不到。
 */
class WebSocketHandshakeInterceptorTest {

    private final HandshakeInterceptor interceptor = new WebSocketHandshakeInterceptor();

    private boolean handshake(String origin, String forwardedProto) throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setServerName("ilink.work");
        servletRequest.setServerPort(80);
        servletRequest.addHeader("Host", "ilink.work");
        servletRequest.addHeader("Origin", origin);
        if (forwardedProto != null) {
            servletRequest.addHeader("X-Forwarded-Proto", forwardedProto);
        }

        MockHttpSession session = new MockHttpSession();
        User user = new User();
        user.setId(1L);
        session.setAttribute("user", user);
        servletRequest.setSession(session);

        return interceptor.beforeHandshake(
            new ServletServerHttpRequest(servletRequest),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            mock(WebSocketHandler.class),
            new HashMap<>());
    }

    @Test
    void 反代透传https时接受同源Origin() throws Exception {
        assertTrue(handshake("https://ilink.work", "https"));
    }

    @Test
    void 跨源Origin仍被拒绝() throws Exception {
        assertFalse(handshake("https://evil.example", "https"));
    }

    @Test
    void 缺少转发协议头时https来源被拒() throws Exception {
        // 没有 X-Forwarded-Proto 说明不是反代场景，Origin 与 request scheme 不符就该拒绝
        assertFalse(handshake("https://ilink.work", null));
    }
}
