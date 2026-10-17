package cn.ilink.service;

import cn.ilink.entity.EmailVerificationCode;
import cn.ilink.mapper.EmailVerificationCodeMapper;
import cn.ilink.mapper.UserMapper;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.security.SecureTokenSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册邮箱验证码：发码时的准入与限流、校验时的比对与计数、消费后不可复用。
 * 重点是「存的是哈希而非明文」——通过从邮件正文取码再回验来证明。
 */
class EmailVerificationServiceTest {

    private static final String EMAIL = "newbie@example.com";

    private EmailVerificationCodeMapper codeMapper;
    private UserMapper userMapper;
    private MailService mailService;
    private LoginAttemptService attemptService;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        codeMapper = mock(EmailVerificationCodeMapper.class);
        userMapper = mock(UserMapper.class);
        mailService = mock(MailService.class);
        attemptService = mock(LoginAttemptService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        service = new EmailVerificationService(codeMapper, userMapper, mailService, attemptService, environment);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(mailService.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(attemptService.tryEmailCodeCooldown(anyString())).thenReturn(true);
        when(attemptService.tryEmailCodeQuota(anyString())).thenReturn(true);
        when(attemptService.tryEmailCodeByIp(anyString())).thenReturn(true);
    }

    @Test
    void rejectsMalformedEmailWithoutSending() {
        assertEquals("邮箱格式不正确", service.sendRegisterCode("not-an-email", "1.2.3.4"));
        verify(codeMapper, never()).insert(any());
        verify(mailService, never()).send(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsAlreadyRegisteredEmail() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        String error = service.sendRegisterCode(EMAIL, "1.2.3.4");
        assertTrue(error != null && error.contains("已被注册"), "应提示邮箱已被注册，实际: " + error);
        verify(codeMapper, never()).insert(any());
    }

    @Test
    void reportsRateLimitFromCooldown() {
        when(attemptService.tryEmailCodeCooldown(EMAIL)).thenReturn(false);
        assertEquals("验证码已发送，请 60 秒后再试", service.sendRegisterCode(EMAIL, "1.2.3.4"));
        verify(codeMapper, never()).insert(any());
    }

    @Test
    void storesHashNotPlainCodeAndAcceptsCodeFromMail() {
        assertNull(service.sendRegisterCode(EMAIL, "1.2.3.4"));

        ArgumentCaptor<EmailVerificationCode> stored = ArgumentCaptor.forClass(EmailVerificationCode.class);
        verify(codeMapper).insert(stored.capture());
        EmailVerificationCode record = stored.getValue();
        assertEquals(EMAIL, record.getEmail());
        assertEquals("REGISTER", record.getPurpose());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(eq(EMAIL), anyString(), body.capture(), anyString());
        Matcher matcher = Pattern.compile("(\\d{6})").matcher(body.getValue());
        assertTrue(matcher.find(), "邮件正文应包含 6 位验证码: " + body.getValue());
        String plainCode = matcher.group(1);

        // 存的是哈希：既不是明文，也必须与明文的哈希一致
        assertNotEquals(plainCode, record.getCodeHash());
        assertEquals(SecureTokenSupport.hash(plainCode), record.getCodeHash());

        when(codeMapper.selectOne(any())).thenReturn(record);
        assertNull(service.verify(EMAIL, plainCode), "邮件里的验证码应当校验通过");
    }

    @Test
    void wrongCodeIncrementsAttempts() {
        EmailVerificationCode record = pendingCode("123456");
        when(codeMapper.selectOne(any())).thenReturn(record);

        String error = service.verify(EMAIL, "000000");
        assertTrue(error != null && error.contains("还可尝试"), "应提示剩余次数，实际: " + error);
        assertEquals(1, record.getAttemptCount());
        verify(codeMapper).updateById(record);
    }

    @Test
    void rejectsAfterMaxAttempts() {
        EmailVerificationCode record = pendingCode("123456");
        record.setAttemptCount(5);
        when(codeMapper.selectOne(any())).thenReturn(record);

        assertEquals("验证码错误次数过多，请重新获取", service.verify(EMAIL, "123456"));
    }

    @Test
    void rejectsWhenNoUsableCode() {
        when(codeMapper.selectOne(any())).thenReturn(null);
        assertEquals("验证码已过期或不存在，请重新获取", service.verify(EMAIL, "123456"));
    }

    @Test
    void rejectsMalformedCode() {
        assertEquals("请输入 6 位数字验证码", service.verify(EMAIL, "12ab"));
    }

    @Test
    void consumeMarksUsed() {
        EmailVerificationCode record = pendingCode("123456");
        when(codeMapper.selectOne(any())).thenReturn(record);

        service.consume(EMAIL);

        assertTrue(record.getUsedAt() != null, "消费后应记录 used_at");
        verify(codeMapper).updateById(record);
    }

    /**
     * 核实回归报告 R-04：验证码表此前只靠 expires_at 惰性过滤、没有任何删除动作，
     * 放弃注册/反复重发的记录会永久堆积。这里确认：
     *   ① 清理方法带 @Scheduled（确实是定时任务，而不是只能手工调用的死代码）；
     *   ② 调用一次就会向 mapper 发出一次删除（谓词由 LambdaQueryWrapper 生成，
     *      单测不解析 SQL——MyBatis-Plus 生成 SQL 需要实体的 TableInfo 元数据）。
     */
    @Test
    void cleanupExpiredCodesDeletesExpiredRowsOnSchedule() throws Exception {
        java.lang.reflect.Method method = EmailVerificationService.class.getMethod("cleanupExpiredCodes");
        assertTrue(method.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class),
            "清理方法应带 @Scheduled 才会被定时触发");

        service.cleanupExpiredCodes();

        verify(codeMapper).delete(any());
    }

    private EmailVerificationCode pendingCode(String plainCode) {
        EmailVerificationCode record = new EmailVerificationCode();
        record.setEmail(EMAIL);
        record.setCodeHash(SecureTokenSupport.hash(plainCode));
        record.setPurpose("REGISTER");
        record.setExpiresAt(new Date(System.currentTimeMillis() + 600_000L));
        record.setAttemptCount(0);
        return record;
    }
}
