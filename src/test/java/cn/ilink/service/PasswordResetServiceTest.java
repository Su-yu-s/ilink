package cn.ilink.service;

import cn.ilink.entity.PasswordResetToken;
import cn.ilink.entity.User;
import cn.ilink.mapper.PasswordResetTokenMapper;
import cn.ilink.mapper.UserMapper;
import cn.ilink.security.SecureTokenSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetServiceTest {
    private PasswordResetTokenMapper tokenMapper;
    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private PasswordResetMailService mailService;
    private RememberMeService rememberMeService;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        tokenMapper = mock(PasswordResetTokenMapper.class);
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        mailService = mock(PasswordResetMailService.class);
        rememberMeService = mock(RememberMeService.class);
        service = new PasswordResetService(tokenMapper, userMapper, passwordEncoder, mailService, rememberMeService);
    }

    @Test
    void requestStoresOnlyHashedTokenAndSendsRawLink() {
        User user = user(7L);
        when(userMapper.findByEmail("student@example.com")).thenReturn(user);
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        ArgumentCaptor<String> rawCaptor = ArgumentCaptor.forClass(String.class);

        service.requestReset("student@example.com", "127.0.0.1");

        verify(tokenMapper).insert(tokenCaptor.capture());
        verify(mailService).sendResetLink(eq(user), rawCaptor.capture());
        assertNotEquals(rawCaptor.getValue(), tokenCaptor.getValue().getTokenHash());
        org.junit.jupiter.api.Assertions.assertEquals(
            SecureTokenSupport.hash(rawCaptor.getValue()), tokenCaptor.getValue().getTokenHash());
    }

    @Test
    void validOneTimeTokenChangesPasswordAndRevokesRememberedDevices() {
        String raw = "valid-reset-token";
        PasswordResetToken token = new PasswordResetToken();
        token.setId(3L);
        token.setUserId(7L);
        token.setExpiresAt(new Date(System.currentTimeMillis() + 60_000));
        User user = user(7L);
        when(tokenMapper.selectByHashForUpdate(SecureTokenSupport.hash(raw))).thenReturn(token);
        when(userMapper.selectById(7L)).thenReturn(user);
        when(passwordEncoder.encode("StrongPwd1")).thenReturn("encoded");
        when(userMapper.updateById(user)).thenReturn(1);

        service.resetPassword(raw, "StrongPwd1");

        org.junit.jupiter.api.Assertions.assertEquals("encoded", user.getPassword());
        org.junit.jupiter.api.Assertions.assertNotNull(token.getUsedAt());
        verify(tokenMapper).updateById(token);
        verify(rememberMeService).revokeAllForUser(7L);
    }

    @Test
    void expiredTokenIsRejected() {
        PasswordResetToken token = new PasswordResetToken();
        token.setExpiresAt(new Date(System.currentTimeMillis() - 1));
        when(tokenMapper.selectByHashForUpdate(any())).thenReturn(token);

        assertThrows(IllegalArgumentException.class,
            () -> service.resetPassword("expired", "StrongPwd1"));
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("student@example.com");
        return user;
    }
}
