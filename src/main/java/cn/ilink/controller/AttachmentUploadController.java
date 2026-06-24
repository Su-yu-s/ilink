package cn.ilink.controller;

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
import java.util.HashMap;
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
    public ResponseEntity<Map<String, Object>> uploadAttachment(@RequestParam("file") MultipartFile file,
                                                                @RequestParam(value = "kind", defaultValue = "proof") String kind,
                                                                HttpSession session) {
        Map<String, Object> response = new HashMap<>();
        User user = resolveCurrentUser(session);
        if (user == null) {
            response.put("code", 401);
            response.put("message", "未登录");
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        }
        if (file == null || file.isEmpty()) {
            response.put("code", 400);
            response.put("message", "请选择文件");
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        }

        // C-20: 安全校验 — 不仅检查扩展名，还检查 MIME 类型
        String contentType = file.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")
                && !contentType.equals("application/pdf")
                && !contentType.startsWith("application/msword")
                && !contentType.startsWith("application/vnd.openxmlformats")
                && !contentType.startsWith("application/zip")
                && !contentType.startsWith("text/")) {
            response.put("code", 400);
            response.put("message", "不支持的文件类型");
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        }

        String originalFilename = file.getOriginalFilename();
        // C-20: 清理文件名，防止路径遍历
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
            response.put("code", 400);
            response.put("message", avatar ? "仅支持 jpg、png、gif、webp 图片"
                : community ? "不支持的文件类型（可用图片、pdf、office、压缩包、txt 等）"
                : "仅支持 jpg、png、gif、webp 图片或 pdf");
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
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
            response.put("code", 200);
            response.put("message", "上传成功");
            response.put("data", Map.of("url", url));
        } catch (IOException e) {
            response.put("code", 500);
            response.put("message", "文件上传失败，请稍后重试");
        }
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
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
