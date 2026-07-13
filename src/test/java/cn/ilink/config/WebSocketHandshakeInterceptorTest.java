package cn.ilink.config;

import cn.ilink.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebSocketHandshakeInterceptorTest {

    private final WebSocketHandshakeInterceptor interceptor = new WebSocketHandshakeInterceptor();

    @Test
    void rejectsAnOriginThatOnlyContainsTheExpectedHostName() {
        MockHttpServletRequest request = requestFor("http://maliciouslocalhost:8090");

        assertFalse(interceptor.beforeHandshake(
            new ServletServerHttpRequest(request), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), new HashMap<>()));
    }

    @Test
    void acceptsOnlyTheExactSameOriginForAnAuthenticatedSession() {
        User user = new User();
        user.setId(9L);
        MockHttpServletRequest request = requestFor("http://localhost:8090");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", user);
        request.setSession(session);
        Map<String, Object> attributes = new HashMap<>();

        assertTrue(interceptor.beforeHandshake(
            new ServletServerHttpRequest(request), mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes));
        assertEquals(user, attributes.get("user"));
        assertEquals(9L, attributes.get("userId"));
    }

    private MockHttpServletRequest requestFor(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8090);
        request.addHeader("Host", "localhost:8090");
        request.addHeader("Origin", origin);
        return request;
    }
}
