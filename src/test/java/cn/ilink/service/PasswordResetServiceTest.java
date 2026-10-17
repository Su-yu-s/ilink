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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        ArgumentCaptor<String> recipientCaptor = ArgumentCaptor.forClass(String.class);

        service.requestReset("student@example.com", "127.0.0.1");

        verify(tokenMapper).insert(tokenCaptor.capture());
        verify(mailService).sendResetLink(recipientCaptor.capture(), rawCaptor.capture());
        assertEquals("student@example.com", recipientCaptor.getValue());
        assertNotEquals(rawCaptor.getValue(), tokenCaptor.getValue().getTokenHash());
        assertEquals(
            SecureTokenSupport.hash(rawCaptor.getValue()), tokenCaptor.getValue().getTokenHash());
    }

    /**
     * 回归：账号未绑定邮箱时不得发信、更不得签发 token。
     * 历史缺陷是把重置链接发到「请求者自填」的邮箱，导致任何人可接管他人账号。
     */
    @Test
    void unboundAccountNeverSendsAMail() {
        User user = user(7L);
        user.setEmail(null);
        when(userMapper.findByUsername("susu")).thenReturn(user);

        service.requestReset("susu", "10.0.0.1");

        verify(mailService, never()).sendResetLink(any(), any());
        verify(tokenMapper, never()).insert(any());
    }

    @Test
    void unboundAccountWithBlankEmailNeverSendsAMail() {
        User user = user(7L);
        user.setEmail("   ");
        when(userMapper.findByUsername("susu")).thenReturn(user);

        service.requestReset("susu", "10.0.0.1");

        verify(mailService, never()).sendResetLink(any(), any());
        verify(tokenMapper, never()).insert(any());
    }

    @Test
    void boundEmailIsTheOnlyRecipient() {
        User user = user(7L);
        when(userMapper.findByUsername("susu")).thenReturn(user);
        ArgumentCaptor<String> recipientCaptor = ArgumentCaptor.forClass(String.class);

        service.requestReset("susu", "10.0.0.1");

        verify(mailService).sendResetLink(recipientCaptor.capture(), any());
        assertEquals("student@example.com", recipientCaptor.getValue());
    }

    @Test
    void unknownAccountSendsNothing() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);

        service.requestReset("ghost", "10.0.0.1");

        verify(mailService, never()).sendResetLink(any(), any());
        verify(tokenMapper, never()).insert(any());
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
