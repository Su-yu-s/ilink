package cn.ilink.service;

import cn.ilink.entity.RememberMeToken;
import cn.ilink.entity.User;
import cn.ilink.mapper.RememberMeTokenMapper;
import cn.ilink.security.SecureTokenSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.http.Cookie;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RememberMeServiceTest {
    private RememberMeTokenMapper mapper;
    private UserService userService;
    private RememberMeService service;

    @BeforeEach
    void setUp() {
        mapper = mock(RememberMeTokenMapper.class);
        userService = mock(UserService.class);
        service = new RememberMeService(mapper, userService);
    }

    @Test
    void issueStoresHashedValidatorAndHttpOnlyCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ArgumentCaptor<RememberMeToken> captor = ArgumentCaptor.forClass(RememberMeToken.class);

        service.issue(9L, request, response);

        verify(mapper).insert(captor.capture());
        java.util.List<String> setCookies = response.getHeaders("Set-Cookie");
        String setCookie = setCookies.get(setCookies.size() - 1);
        assertTrue(setCookie.contains("ILINK_REMEMBER="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        String cookieValue = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        String validator = cookieValue.split("\\.", 2)[1];
        org.junit.jupiter.api.Assertions.assertEquals(
            SecureTokenSupport.hash(validator), captor.getValue().getValidatorHash());
    }

    @Test
    void validCookieIsRotatedAndAuthenticatesUser() {
        String selector = "selector";
        String validator = "validator";
        RememberMeToken token = new RememberMeToken();
        token.setId(2L);
        token.setUserId(9L);
        token.setSelector(selector);
        token.setValidatorHash(SecureTokenSupport.hash(validator));
        token.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        when(mapper.selectOne(any())).thenReturn(token);
        User user = new User();
        user.setId(9L);
        user.setPassword("secret");
        when(userService.getById(9L)).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(RememberMeService.COOKIE_NAME, selector + "." + validator));
        MockHttpServletResponse response = new MockHttpServletResponse();

        User authenticated = service.authenticateAndRotate(request, response);

        org.junit.jupiter.api.Assertions.assertEquals(9L, authenticated.getId());
        assertNull(authenticated.getPassword());
        verify(mapper).updateById(token);
        assertTrue(response.getHeader("Set-Cookie").contains(selector + "."));
    }

    @Test
    void invalidValidatorRevokesToken() {
        RememberMeToken token = new RememberMeToken();
        token.setId(2L);
        token.setSelector("selector");
        token.setValidatorHash(SecureTokenSupport.hash("correct"));
        token.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        when(mapper.selectOne(any())).thenReturn(token);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(RememberMeService.COOKIE_NAME, "selector.wrong"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertNull(service.authenticateAndRotate(request, response));

        verify(mapper).deleteById(2L);
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
    }
}
