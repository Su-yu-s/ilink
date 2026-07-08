package cn.ilink.service.impl;

import cn.ilink.dto.LoginRequest;
import cn.ilink.dto.RegisterRequest;
import cn.ilink.entity.User;
import cn.ilink.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * UserService 核心业务单元测试。
 * 覆盖注册、登录、密码修改等核心流程。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    private RegisterRequest validRegister;
    private LoginRequest validLogin;

    @BeforeEach
    void setUp() {
        validRegister = new RegisterRequest();
        validRegister.setUsername("testuser");
        validRegister.setPhoneNumber("13800138000");
        validRegister.setPassword("Test@1234");
        validRegister.setRole("STUDENT");

        validLogin = new LoginRequest();
        validLogin.setPhoneNumber("13800138000");
        validLogin.setPassword("Test@1234");
    }

    // ===== 注册测试 =====

    @Test
    void registerCreatesUserWhenPhoneUnique() {
        when(userMapper.findByPhoneNumber(anyString())).thenReturn(null);
        when(userMapper.findByUsername(anyString())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded_pwd");

        // baseMapper.insert() 通过 ServiceImpl.save() -> baseMapper.insert() 链调用
        // 在单元测试中,userService.save() 最终调用 userMapper.insert()
        when(userMapper.insert(any(User.class))).thenReturn(1);

        boolean result = userService.register(validRegister);

        assertTrue(result);
        verify(passwordEncoder).encode("Test@1234");
    }

    @Test
    void registerRejectsDuplicatePhone() {
        User existing = new User();
        existing.setId(1L);
        when(userMapper.findByPhoneNumber("13800138000")).thenReturn(existing);

        boolean result = userService.register(validRegister);

        assertFalse(result);
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userMapper.findByPhoneNumber(anyString())).thenReturn(null);
        User existing = new User();
        existing.setId(2L);
        when(userMapper.findByUsername("testuser")).thenReturn(existing);

        boolean result = userService.register(validRegister);

        assertFalse(result);
    }

    @Test
    void registerDefaultsRoleToStudentWhenNull() {
        validRegister.setRole(null);
        when(userMapper.findByPhoneNumber(anyString())).thenReturn(null);
        when(userMapper.findByUsername(anyString())).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded_pwd");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        userService.register(validRegister);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("STUDENT", captor.getValue().getRole());
    }

    // ===== 登录测试 =====

    @Test
    void loginSucceedsWithCorrectPassword() {
        User dbUser = new User();
        dbUser.setId(1L);
        dbUser.setUsername("testuser");
        dbUser.setPassword("stored_hash");

        when(userMapper.findByPhoneNumber("13800138000")).thenReturn(dbUser);
        when(passwordEncoder.matches("Test@1234", "stored_hash")).thenReturn(true);

        User result = userService.login(validLogin);

        assertNotNull(result);
        assertEquals(1L, result.getId());
    }

    @Test
    void loginFailsWithWrongPassword() {
        User dbUser = new User();
        dbUser.setId(1L);
        dbUser.setPassword("stored_hash");

        when(userMapper.findByPhoneNumber("13800138000")).thenReturn(dbUser);
        when(passwordEncoder.matches("Test@1234", "stored_hash")).thenReturn(false);

        User result = userService.login(validLogin);

        assertNull(result);
    }

    @Test
    void loginReturnsNullWhenUserNotFound() {
        // 按 phone → studentId → email → username 顺序，所有都返回 null
        when(userMapper.findByPhoneNumber("13800138000")).thenReturn(null);
        when(userMapper.findByStudentId(anyString())).thenReturn(null);
        when(userMapper.findByEmail(anyString())).thenReturn(null);
        when(userMapper.findByUsername(anyString())).thenReturn(null);

        User result = userService.login(validLogin);

        assertNull(result);
    }

    @Test
    void loginFindsUserByEmail() {
        validLogin.setPhoneNumber(null);
        validLogin.setIdentifier("user@test.com");

        User dbUser = new User();
        dbUser.setId(3L);
        dbUser.setPassword("hash");

        // 按 phone(null) → studentId(null) → email("user@test.com") → 找到
        when(userMapper.findByPhoneNumber(null)).thenReturn(null);
        when(userMapper.findByStudentId(null)).thenReturn(null);
        when(userMapper.findByEmail("user@test.com")).thenReturn(dbUser);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        User result = userService.login(validLogin);
        assertNotNull(result);
        assertEquals(3L, result.getId());
    }

    // ===== 密码修改测试 =====

    @Test
    void changePasswordSucceedsWithCorrectOldPassword() {
        User user = new User();
        user.setId(1L);
        user.setPassword("old_hash");

        // 需要 stub userMapper.selectById()（getById 内部调用 baseMapper.selectById）
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("oldpass", "old_hash")).thenReturn(true);
        when(passwordEncoder.encode("newpass")).thenReturn("new_hash");
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        boolean result = userService.changePassword(1L, "oldpass", "newpass");

        assertTrue(result);
        verify(passwordEncoder).encode("newpass");
    }

    @Test
    void changePasswordFailsWithWrongOldPassword() {
        User user = new User();
        user.setId(1L);
        user.setPassword("old_hash");

        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("wrong", "old_hash")).thenReturn(false);

        boolean result = userService.changePassword(1L, "wrong", "newpass");

        assertFalse(result);
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void changePasswordFailsWhenUserNotFound() {
        when(userMapper.selectById(999L)).thenReturn(null);

        boolean result = userService.changePassword(999L, "old", "new");

        assertFalse(result);
    }

    @Test
    void changePasswordEncodesAndPersistsNewPassword() {
        User user = new User();
        user.setId(1L);
        user.setPassword("old_hash");

        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("oldpass", "old_hash")).thenReturn(true);
        when(passwordEncoder.encode("newpass")).thenReturn("new_hash_encoded");
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        userService.changePassword(1L, "oldpass", "newpass");

        assertEquals("new_hash_encoded", user.getPassword());
        verify(userMapper).updateById(user);
    }
}
