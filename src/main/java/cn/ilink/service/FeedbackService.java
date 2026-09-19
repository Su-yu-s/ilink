package cn.ilink.service;

import cn.ilink.dto.UploadedFileInfo;
import cn.ilink.entity.Feedback;
import cn.ilink.mapper.FeedbackMapper;
import cn.ilink.security.SecureTokenSupport;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 意见反馈：接收提交、生成免登录的详情页令牌、按令牌读取反馈。
 *
 * <p>详情页在微信内置浏览器打开，没有会话，因此访问控制完全依赖 URL 里的不可猜令牌。
 * 令牌明文只在提交响应中返回一次，库里只存 SHA-256。
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static final int MAX_IMAGES = 5;
    public static final int MAX_DESCRIPTION = 500;
    public static final int MAX_CONTACT = 120;

    /** 反馈类型白名单与展示名，推送正文与详情页共用同一份映射 */
    private static final Map<String, String> TYPE_LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("BUG", "Bug 反馈");
        labels.put("FEATURE", "功能建议");
        labels.put("UX", "体验问题");
        labels.put("OTHER", "其他");
        TYPE_LABELS = Collections.unmodifiableMap(labels);
    }

    private final FeedbackMapper feedbackMapper;
    private final FileService fileService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.public-base-url:http://localhost:8090}")
    private String publicBaseUrl;

    public FeedbackService(FeedbackMapper feedbackMapper, FileService fileService) {
        this.feedbackMapper = feedbackMapper;
        this.fileService = fileService;
    }

    /** 提交结果：已落库的反馈 + 只在本次响应中出现的明文令牌 */
    public static class Submission {
        private final Feedback feedback;
        private final String rawToken;

        public Submission(Feedback feedback, String rawToken) {
            this.feedback = feedback;
            this.rawToken = rawToken;
        }

        public Feedback getFeedback() {
            return feedback;
        }

        public String getRawToken() {
            return rawToken;
        }
    }

    /**
     * 落库一条反馈。只做校验、传图、插图，不做任何外部网络调用
     * （推送由 {@link FeedbackPushService} 在事务之外执行）。
     */
    @Transactional
    public Submission submit(String rawType, String description, String contact,
                             List<MultipartFile> images, Long submitterId,
                             String clientIp, String userAgent) {
        String type = normalizeType(rawType);
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("请填写详细描述");
        }
        if (text.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("描述不能超过 " + MAX_DESCRIPTION + " 字");
        }
        String contactValue = contact == null ? "" : contact.trim();
        if (contactValue.length() > MAX_CONTACT) {
            throw new IllegalArgumentException("联系方式不能超过 " + MAX_CONTACT + " 个字符");
        }

        List<MultipartFile> selected = new ArrayList<>();
        if (images != null) {
            for (MultipartFile image : images) {
                if (image != null && !image.isEmpty()) {
                    selected.add(image);
                }
            }
        }
        if (selected.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("最多上传 " + MAX_IMAGES + " 张截图");
        }

        List<String> imageUrls = new ArrayList<>();
        // 先注册回滚清理再开始上传：中途失败时已经落盘的那几张也要被删掉，
        // 否则会留下孤儿文件（清理闭包读取的是这个列表的最终内容）。
        registerUploadCleanupOnRollback(imageUrls);
        try {
            for (MultipartFile image : selected) {
                UploadedFileInfo info = fileService.uploadWithMetadata(image, "feedback");
                imageUrls.add(info.getUrl());
            }
        } catch (IOException e) {
            throw new IllegalStateException("截图上传失败，请稍后重试", e);
        }

        String rawToken = SecureTokenSupport.randomToken(32);
        Feedback feedback = new Feedback();
        feedback.setAccessTokenHash(SecureTokenSupport.hash(rawToken));
        feedback.setType(type);
        feedback.setDescription(text);
        feedback.setContact(contactValue.isEmpty() ? null : contactValue);
        feedback.setImageUrls(writeImageUrls(imageUrls));
        feedback.setSubmitterId(submitterId);
        feedback.setClientIp(trimToLength(clientIp, 64));
        feedback.setUserAgent(trimToLength(userAgent, 255));
        feedback.setHandled(false);
        feedback.setCreatedAt(new Date());

        feedbackMapper.insert(feedback);
        return new Submission(feedback, rawToken);
    }

    /** 按明文令牌读取反馈；令牌无效一律返回 null，不区分「不存在」与「已失效」。 */
    public Feedback findByToken(String rawToken) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        String hash = SecureTokenSupport.hash(rawToken.trim());
        return feedbackMapper.selectOne(new LambdaQueryWrapper<Feedback>()
            .eq(Feedback::getAccessTokenHash, hash)
            .last("LIMIT 1"));
    }

    /** 详情页绝对地址，供推送使用。 */
    public String buildDetailUrl(String rawToken) {
        String base = StringUtils.hasText(publicBaseUrl) ? publicBaseUrl.trim() : "";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/feedback/view/" + rawToken;
    }

    /** 展示编号：FB + 提交日期 + 补零主键，例如 #FB20260912001。 */
    public static String displayNumber(Feedback feedback) {
        if (feedback == null) {
            return "#FB-";
        }
        Date createdAt = feedback.getCreatedAt() == null ? new Date() : feedback.getCreatedAt();
        String datePart = LocalDate.ofInstant(createdAt.toInstant(), DEFAULT_ZONE).format(DATE_PART);
        long id = feedback.getId() == null ? 0L : feedback.getId();
        return String.format("#FB%s%03d", datePart, id);
    }

    /** 未知类型归一到 OTHER，而不是拒收整条反馈。 */
    static String normalizeType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return "OTHER";
        }
        String key = rawType.trim().toUpperCase(Locale.ROOT);
        return TYPE_LABELS.containsKey(key) ? key : "OTHER";
    }

    public static String labelOf(String type) {
        String label = TYPE_LABELS.get(normalizeType(type));
        return label == null ? "其他" : label;
    }

    /** 解析截图地址 JSON；数据损坏时按「无截图」处理，不让详情页整体挂掉。 */
    public static List<String> parseImageUrls(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            List<String> urls = new ObjectMapper().readValue(json, new TypeReference<List<String>>() { });
            return urls == null ? Collections.emptyList() : urls;
        } catch (IOException e) {
            log.warn("反馈截图地址解析失败：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String writeImageUrls(List<String> imageUrls) {
        if (imageUrls.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(imageUrls);
        } catch (IOException e) {
            throw new IllegalStateException("截图地址序列化失败", e);
        }
    }

    /**
     * 事务回滚时删掉本次已落盘的截图，避免留下孤儿文件。
     * 与 {@link AssetLifecycleService} 处理文件副作用的方式一致。
     *
     * <p>必须在开始上传之前调用：{@code uploadedUrls} 是一个持续增长的列表，
     * 闭包在回滚时读取它的最终内容，因此中途失败时已经传上去的也会被清理。
     */
    private void registerUploadCleanupOnRollback(List<String> uploadedUrls) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                for (String url : List.copyOf(uploadedUrls)) {
                    try {
                        fileService.delete(url);
                    } catch (IOException e) {
                        log.warn("回滚清理截图失败 {}：{}", url, e.getMessage());
                    }
                }
            }
        });
    }

    private static String trimToLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
