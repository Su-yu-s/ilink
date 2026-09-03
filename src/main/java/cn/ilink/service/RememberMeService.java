package cn.ilink.service;

import cn.ilink.entity.RememberMeToken;
import cn.ilink.entity.User;
import cn.ilink.mapper.RememberMeTokenMapper;
import cn.ilink.security.SecureTokenSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Date;

@Service
public class RememberMeService {
    public static final String COOKIE_NAME = "ILINK_REMEMBER";
    private static final long TOKEN_LIFETIME_MS = 30L * 24L * 60L * 60L * 1000L;

    private final RememberMeTokenMapper tokenMapper;
    private final UserService userService;

    public RememberMeService(RememberMeTokenMapper tokenMapper, UserService userService) {
        this.tokenMapper = tokenMapper;
        this.userService = userService;
    }

    @Transactional
    public void issue(Long userId, HttpServletRequest request, HttpServletResponse response) {
        revokeCurrentCookie(request, response);
        Date now = new Date();
        String selector = SecureTokenSupport.randomToken(18);
        String validator = SecureTokenSupport.randomToken(32);
        RememberMeToken token = new RememberMeToken();
        token.setUserId(userId);
        token.setSelector(selector);
        token.setValidatorHash(SecureTokenSupport.hash(validator));
        token.setExpiresAt(new Date(now.getTime() + TOKEN_LIFETIME_MS));
        token.setLastUsedAt(now);
        token.setUserAgent(trimToLength(request.getHeader("User-Agent"), 255));
        token.setCreatedAt(now);
        tokenMapper.insert(token);
        writeCookie(response, request.isSecure(), selector + "." + validator, Duration.ofMillis(TOKEN_LIFETIME_MS));
    }

    @Transactional
    public User authenticateAndRotate(HttpServletRequest request, HttpServletResponse response) {
        String cookieValue = readCookie(request);
        String[] parts = cookieValue == null ? new String[0] : cookieValue.split("\\.", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            if (cookieValue != null) clearCookie(response, request.isSecure());
            return null;
        }
        RememberMeToken token = tokenMapper.selectOne(new LambdaQueryWrapper<RememberMeToken>()
            .eq(RememberMeToken::getSelector, parts[0]).last("LIMIT 1"));
        Date now = new Date();
        if (token == null || token.getExpiresAt() == null || !token.getExpiresAt().after(now)
                || !SecureTokenSupport.matchesHash(parts[1], token.getValidatorHash())) {
            if (token != null) tokenMapper.deleteById(token.getId());
            clearCookie(response, request.isSecure());
            return null;
        }
        User user = userService.getById(token.getUserId());
        if (user == null) {
            tokenMapper.deleteById(token.getId());
            clearCookie(response, request.isSecure());
            return null;
        }
        String nextValidator = SecureTokenSupport.randomToken(32);
        token.setValidatorHash(SecureTokenSupport.hash(nextValidator));
        token.setLastUsedAt(now);
        token.setExpiresAt(new Date(now.getTime() + TOKEN_LIFETIME_MS));
        tokenMapper.updateById(token);
        writeCookie(response, request.isSecure(), token.getSelector() + "." + nextValidator,
            Duration.ofMillis(TOKEN_LIFETIME_MS));
        user.setPassword(null);
        return user;
    }

    @Transactional
    public void revokeCurrentCookie(HttpServletRequest request, HttpServletResponse response) {
        String value = readCookie(request);
        if (value != null) {
            String[] parts = value.split("\\.", 2);
            if (parts.length > 0 && !parts[0].isBlank()) {
                tokenMapper.delete(new LambdaQueryWrapper<RememberMeToken>()
                    .eq(RememberMeToken::getSelector, parts[0]));
            }
        }
        clearCookie(response, request.isSecure());
    }

    public void clearCookieOnly(HttpServletRequest request, HttpServletResponse response) {
        clearCookie(response, request.isSecure());
    }

    public void revokeAllForUser(Long userId) {
        if (userId != null) {
            tokenMapper.delete(new LambdaQueryWrapper<RememberMeToken>()
                .eq(RememberMeToken::getUserId, userId));
        }
    }

    @Scheduled(cron = "0 41 3 * * *")
    public void cleanupExpiredTokens() {
        tokenMapper.delete(new LambdaQueryWrapper<RememberMeToken>()
            .lt(RememberMeToken::getExpiresAt, new Date()));
    }

    private String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private void writeCookie(HttpServletResponse response, boolean secure, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAge)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearCookie(HttpServletResponse response, boolean secure) {
        writeCookie(response, secure, "", Duration.ZERO);
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }
}
