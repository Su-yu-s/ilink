package cn.ilink.service;

import cn.ilink.common.ControllerUtils;
import cn.ilink.entity.EmailVerificationCode;
import cn.ilink.entity.User;
import cn.ilink.mapper.EmailVerificationCodeMapper;
import cn.ilink.mapper.UserMapper;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.security.SecureTokenSupport;
import cn.ilink.util.I18nMessageResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;
import java.util.regex.Pattern;

/**
 * 注册邮箱验证码：发码 + 校验消费。
 * <p>
 * 验证码只存 SHA-256（与 password_reset_token 同一套 SecureTokenSupport），日志里不打明文；
 * 发码与校验两侧都有独立限流，校验失败按次数累计、达上限作废，防暴力猜码。
 * <p>
 * 对外以「返回错误提示字符串，成功返回 null」表达结果，调用方直接把它交给前端。
 */
@Service
public class EmailVerificationService {
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** 验证码有效期 */
    private static final int CODE_TTL_MINUTES = 10;
    /** 同一验证码允许猜错的次数，超过作废 */
    private static final int MAX_ATTEMPTS = 5;
    private static final String PURPOSE_REGISTER = "REGISTER";
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationCodeMapper codeMapper;
    private final UserMapper userMapper;
    private final MailService mailService;
    private final LoginAttemptService attemptService;
    private final boolean devProfile;

    @Autowired(required = false)
    private I18nMessageResolver i18nMessages;

    public EmailVerificationService(EmailVerificationCodeMapper codeMapper,
                                    UserMapper userMapper,
                                    MailService mailService,
                                    LoginAttemptService attemptService,
                                    Environment environment) {
        this.codeMapper = codeMapper;
        this.userMapper = userMapper;
        this.mailService = mailService;
        this.attemptService = attemptService;
        this.devProfile = Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }

    /** 发送注册验证码；返回错误提示，成功返回 null */
    public String sendRegisterCode(String rawEmail, String requestIp) {
        String email = normalize(rawEmail);
        if (!ControllerUtils.isValidEmail(email)) {
            return msg("auth.email.invalid", "邮箱格式不正确");
        }
        // 已注册的邮箱直接挡掉：注册场景下让用户立刻知道该换邮箱，比走到最后一步再失败好
        if (emailTaken(email)) {
            return msg("auth.email.taken", "该邮箱已被注册，请直接登录或更换邮箱");
        }
        if (!attemptService.tryEmailCodeCooldown(email)) {
            return msg("auth.email.cooldown", "验证码已发送，请 60 秒后再试", 60);
        }
        if (!attemptService.tryEmailCodeQuota(email)) {
            return msg("auth.email.dailyLimit", "该邮箱今日验证码次数已达上限，请稍后再试");
        }
        if (!attemptService.tryEmailCodeByIp(requestIp)) {
            return msg("auth.email.rateLimit", "发送过于频繁，请稍后再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        EmailVerificationCode record = new EmailVerificationCode();
        record.setEmail(email);
        record.setCodeHash(SecureTokenSupport.hash(code));
        record.setPurpose(PURPOSE_REGISTER);
        record.setExpiresAt(new Date(System.currentTimeMillis() + CODE_TTL_MINUTES * 60_000L));
        record.setAttemptCount(0);
        record.setRequestIp(requestIp == null ? "" : requestIp);
        record.setCreatedAt(new Date());
        codeMapper.insert(record);

        String subject = msg("auth.email.code.subject", SUBJECT);
        String text = msg("auth.email.code.text", textBody(code), code);
        String html = msg("auth.email.code.html", htmlBody(code), code);
        if (!mailService.send(email, subject, text, html)) {
            if (devProfile) {
                // 与密码重置邮件一致：本地没配邮件通道时把内容打进日志，保证开发环境能走通注册
                log.info("开发环境注册验证码（仅本地日志可见）: {} -> {}", email, code);
                return null;
            }
            log.warn("邮件服务未配置，注册验证码未能发出（{}）", email);
            return msg("auth.email.unavailable", "邮件服务暂不可用，请稍后重试");
        }
        return null;
    }

    /**
     * 校验验证码（不消费）；返回错误提示，成功返回 null。
     * <p>
     * 与消费分开：注册还要过手机号/学号等校验，全靠通过后才消费，
     * 否则用户只是填错了个手机号就得重新等验证码。
     */
    public String verify(String rawEmail, String rawCode) {
        String email = normalize(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            return msg("auth.email.code.required", "请输入 6 位数字验证码");
        }
        EmailVerificationCode record = latestUsable(email);
        if (record == null) {
            return msg("auth.email.code.expired", "验证码已过期或不存在，请重新获取");
        }
        int attempts = record.getAttemptCount() == null ? 0 : record.getAttemptCount();
        if (attempts >= MAX_ATTEMPTS) {
            return msg("auth.email.code.attempts", "验证码错误次数过多，请重新获取");
        }
        if (!SecureTokenSupport.hash(code).equals(record.getCodeHash())) {
            record.setAttemptCount(attempts + 1);
            codeMapper.updateById(record);
            int left = MAX_ATTEMPTS - record.getAttemptCount();
            return left > 0
                ? msg("auth.email.code.incorrect", "验证码不正确，还可尝试 {0} 次", left)
                : msg("auth.email.code.attempts", "验证码错误次数过多，请重新获取");
        }
        return null;
    }

    /** 消费验证码（注册成功后调用），使其不可再用 */
    public void consume(String rawEmail) {
        EmailVerificationCode record = latestUsable(normalize(rawEmail));
        if (record == null) {
            return;
        }
        record.setUsedAt(new Date());
        codeMapper.updateById(record);
    }

    /** 该邮箱最近一条未用、未过期的验证码 */
    private EmailVerificationCode latestUsable(String email) {
        return codeMapper.selectOne(new LambdaQueryWrapper<EmailVerificationCode>()
            .eq(EmailVerificationCode::getEmail, email)
            .eq(EmailVerificationCode::getPurpose, PURPOSE_REGISTER)
            .isNull(EmailVerificationCode::getUsedAt)
            .gt(EmailVerificationCode::getExpiresAt, new Date())
            .orderByDesc(EmailVerificationCode::getCreatedAt)
            .last("LIMIT 1"));
    }

    /** 邮箱是否已被占用（列 collation 大小写不敏感，故统一按小写比对） */
    private boolean emailTaken(String email) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
            .eq(User::getEmail, email)) > 0;
    }

    /**
     * 清理过期验证码（每天 03:47）。
     *
     * <p>此前只靠 {@code expires_at} 惰性过滤，没有任何删除动作：放弃注册、反复重发的记录会永久堆在表里
     * （核实回归报告 R-04）。这里按天清理，并额外保留 1 天，方便排障时回看最近的失败记录。
     * 与 {@code PasswordResetService}/{@code RememberMeService} 的清理任务保持同一写法。
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 47 3 * * *")
    public void cleanupExpiredCodes() {
        Date cutoff = new Date(System.currentTimeMillis() - 24L * 3600 * 1000);
        codeMapper.delete(new LambdaQueryWrapper<EmailVerificationCode>()
            .lt(EmailVerificationCode::getExpiresAt, cutoff));
    }

    private String normalize(String rawEmail) {
        String normalized = ControllerUtils.normalizeEmail(rawEmail);
        return normalized == null ? "" : normalized;
    }

    private static final String SUBJECT = "iLink 注册验证码";

    private String textBody(String code) {
        return "你的 iLink 注册验证码是：" + code + "\n\n"
            + "该验证码 " + CODE_TTL_MINUTES + " 分钟内有效，请勿转发给他人。\n"
            + "如非本人操作，请忽略本邮件。";
    }

    private String htmlBody(String code) {
        return "<p>你好，</p>"
            + "<p>你的 iLink 注册验证码是：</p>"
            + "<p style=\"font-size:22px;font-weight:700;letter-spacing:4px;\">" + code + "</p>"
            + "<p>该验证码 " + CODE_TTL_MINUTES + " 分钟内有效，请勿转发给他人。"
            + "如非本人操作，请忽略本邮件。</p>";
    }

    private String msg(String key, String fallback, Object... arguments) {
        return i18nMessages == null ? fallback : i18nMessages.get(key, fallback, arguments);
    }
}
