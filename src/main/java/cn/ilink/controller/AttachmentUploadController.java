package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.User;
import cn.ilink.mapper.UserMapper;
import cn.ilink.service.FileService;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.io.IOException;
import java.util.Map;

/**
 * 与 {@link AssetController#getAsset} 的 <code>GET /api/asset/{id}</code> 分离，
 * 避免部分环境下路径与单段变量冲突导致 POST 405。
 */
@Controller
@RequestMapping("/api/upload")
public class AttachmentUploadController {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private FileService fileService;

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
        try {
            String url = fileService.upload(file, resolveBizType(kind));
            return Result.ok("上传成功", Map.of("url", url)).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (IOException e) {
            return Result.fail(500, "文件上传失败，请稍后重试").toResponseEntity();
        }
    }

    private String resolveBizType(String kind) {
        if ("avatar".equalsIgnoreCase(kind)) return "avatars";
        if ("community".equalsIgnoreCase(kind)) return "community";
        if ("task".equalsIgnoreCase(kind)) return "tasks";
        if (kind == null || kind.trim().isEmpty() || "proof".equalsIgnoreCase(kind)) return "proofs";
        throw new IllegalArgumentException("不支持的附件用途");
    }

    /**
     * 以 session 中的 user 为准；若丢失但 Spring Security 仍已登录，则按 principal（用户名）补查，避免上传接口误报未登录。
     */
    private User resolveCurrentUser(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
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
