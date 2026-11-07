package cn.ilink.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpResolverTest {

    @Test
    void 公网直连时忽略伪造的XForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        assertEquals("203.0.113.9", ClientIpResolver.resolve(request));
    }

    @Test
    void 受信代理时取XForwardedFor最右一跳() {
        // nginx 的 $proxy_add_x_forwarded_for 把 $remote_addr 追加在末尾，
        // 最右一跳才是不可伪造的真实对端
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8");
        assertEquals("5.6.7.8", ClientIpResolver.resolve(request));
    }

    @Test
    void 客户端伪造的左侧地址不影响解析结果() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");
        assertEquals("203.0.113.7", ClientIpResolver.resolve(request));
    }

    @Test
    void 无XForwardedFor时使用直连地址() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        assertEquals("203.0.113.9", ClientIpResolver.resolve(request));
    }

    @Test
    void 仅私网或回环对端视为受信代理() {
        assertTrue(ClientIpResolver.isTrustedProxy("127.0.0.1"));
        assertTrue(ClientIpResolver.isTrustedProxy("192.168.1.10"));
        assertTrue(ClientIpResolver.isTrustedProxy("10.0.0.1"));
        assertTrue(ClientIpResolver.isTrustedProxy("::1"));
        assertFalse(ClientIpResolver.isTrustedProxy("203.0.113.9"));
        assertFalse(ClientIpResolver.isTrustedProxy(""));
        assertFalse(ClientIpResolver.isTrustedProxy(null));
    }
}