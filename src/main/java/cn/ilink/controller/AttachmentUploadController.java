package cn.ilink.controller;

import cn.ilink.common.Result;
import cn.ilink.entity.User;
import cn.ilink.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 与 {@link AssetController#getAsset} 的 <code>GET /api/asset/{id}</code> 分离，
 * 避免部分环境下路径与单段变量冲突导致 POST 405。
 */
@Controller
@RequestMapping("/api/upload")
public class AttachmentUploadController {

    @Autowired
    private UserMapper userMapper;

    private static final Set<String> AVATAR_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");
    private static final Set<String> PROOF_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf");
    /** 社区文章随文附件：常见文档与压缩包 */
    private static final Set<String> COMMUNITY_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf",
        ".zip", ".rar", ".7z",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".txt", ".md", ".csv"
    );
    private static final Set<String> TASK_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".pdf",
        ".zip", ".rar", ".7z",
        ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".txt", ".md", ".csv"
    );

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /**
     * @param kind avatar：仅图片；proof：图片或 pdf
     */
    @PostMapping("/attachment")
    @ResponseBody
    public ResponseEntity<Result<?>> uploadAttachment(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(value = "kind", defaultValue = "proof") String kind,
                                                       HttpSession session) {
        User user = resolveCurrentUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (file == null || file.isEmpty()) {
            return Result.badRequest("请选择文件").toResponseEntity();
        }

        // 安全校验 — 不仅检查扩展名，还检查 MIME 类型
        String contentType = file.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                && !contentType.equals("application/pdf")
                && !contentType.startsWith("application/msword")
                && !contentType.startsWith("application/vnd.openxmlformats")
                && !contentType.startsWith("application/zip")
                && !contentType.startsWith("text/")) {
            return Result.badRequest("不支持的文件类型").toResponseEntity();
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null) {
            originalFilename = originalFilename.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5\\-_.]", "_");
        }
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase(Locale.ROOT);
        }
        boolean avatar = "avatar".equalsIgnoreCase(kind);
        boolean community = "community".equalsIgnoreCase(kind);
        boolean task = "task".equalsIgnoreCase(kind);
        Set<String> allowed = avatar ? AVATAR_EXTENSIONS : community ? COMMUNITY_EXTENSIONS : task ? TASK_EXTENSIONS : PROOF_EXTENSIONS;
        if (extension.isEmpty() || !allowed.contains(extension)) {
            return Result.badRequest(avatar ? "仅支持 jpg、png、gif、webp 图片"
                : community ? "不支持的文件类型（可用图片、pdf、office、压缩包、txt 等）"
                : "仅支持 jpg、png、gif、webp 图片或 pdf").toResponseEntity();
        }

        try {
            File uploadDir = new File(uploadPath);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            String filename = UUID.randomUUID().toString() + extension;
            Path filePath = Paths.get(uploadPath, filename);
            Files.write(filePath, file.getBytes());
            String url = "/uploads/" + filename;
            return Result.ok("上传成功", Map.of("url", url)).toResponseEntity();
        } catch (IOException e) {
            return Result.fail(500, "文件上传失败，请稍后重试").toResponseEntity();
        }
    }

    /**
     * 以 session 中的 user 为准；若丢失但 Spring Security 仍已登录，则按 principal（用户名）补查，避免上传接口误报未登录。
     */
    private User resolveCurrentUser(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user != null) {
            return user;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof String)) {
            return null;
        }
        User loaded = userMapper.findByUsername((String) principal);
        if (loaded != null) {
            loaded.setPassword(null);
        }
        return loaded;
    }
}
