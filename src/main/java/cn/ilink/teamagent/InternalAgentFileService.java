package cn.ilink.teamagent;

import cn.ilink.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接收 agent 生成的文件并转成待确认动作。
 *
 * 文件先落到正式上传目录（library 业务类型），但不登记资料库；
 * 只有用户在动作组里确认后，LibraryFileHandler 才会写入 team_library_files 并建索引。
 */
@Service
public class InternalAgentFileService {
    private static final Logger log = LoggerFactory.getLogger(InternalAgentFileService.class);
    /** 只接受这些扩展名；宏格式一律拒绝。 */
    private static final List<String> ALLOWED_EXTENSIONS = List.of(
        ".docx", ".xlsx", ".pptx", ".pdf", ".md", ".txt", ".csv");
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final FileService fileService;
    private final TeamAgentAuthorization authorization;
    private final TeamAgentProperties properties;
    private final PendingAgentDrafts pendingDrafts;
    private final cn.ilink.mapper.TeamLibraryFileMapper libraryFileMapper;

    public InternalAgentFileService(FileService fileService,
                                    TeamAgentAuthorization authorization,
                                    TeamAgentProperties properties,
                                    PendingAgentDrafts pendingDrafts,
                                    cn.ilink.mapper.TeamLibraryFileMapper libraryFileMapper) {
        this.fileService = fileService;
        this.authorization = authorization;
        this.properties = properties;
        this.pendingDrafts = pendingDrafts;
        this.libraryFileMapper = libraryFileMapper;
    }

    public ResponseEntity<?> upload(MultipartFile file, String token, Long teamId, Long userId,
                                    Long runId, Long folderId, String purpose, String reason) {
        return upload(file, token, teamId, userId, runId, folderId, purpose, reason, "create", null);
    }

    public ResponseEntity<?> upload(MultipartFile file, String token, Long teamId, Long userId,
                                    Long runId, Long folderId, String purpose, String reason,
                                    String mode, Long targetFileId) {
        if (!properties.getInternal().isEnabled()) {
            return error(HttpStatus.NOT_FOUND, "INTERNAL_TOOLS_DISABLED", "内部工具端点未启用");
        }
        if (!tokenMatches(token)) {
            return error(HttpStatus.UNAUTHORIZED, "INTERNAL_TOKEN_INVALID", "内部令牌无效");
        }
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "FILE_EMPTY", "文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "文件超过 20 MiB");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String extension = extensionOf(fileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return error(HttpStatus.BAD_REQUEST, "FILE_TYPE_NOT_ALLOWED", "不支持的文件类型：" + extension);
        }
        try {
            TeamAgentScope scope = authorization.requireScope(teamId, userId);
            boolean update = "update".equalsIgnoreCase(mode);
            cn.ilink.entity.TeamLibraryFile target = null;
            if (update) {
                if (targetFileId == null) {
                    return error(HttpStatus.BAD_REQUEST, "TARGET_REQUIRED", "更新文件必须提供 targetFileId");
                }
                target = libraryFileMapper.selectById(targetFileId);
                if (target == null || Boolean.TRUE.equals(target.getIsPurged())
                    || Boolean.TRUE.equals(target.getIsDeleted())) {
                    return error(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", "目标文件不存在或已删除");
                }
                if (!java.util.Objects.equals(target.getTeamId(), scope.teamId())) {
                    return error(HttpStatus.FORBIDDEN, "TARGET_FORBIDDEN", "目标文件不属于当前团队");
                }
                if (Boolean.TRUE.equals(target.getIsFolder())) {
                    return error(HttpStatus.BAD_REQUEST, "TARGET_IS_FOLDER", "不能覆盖文件夹");
                }
                // 沿用原文件名，执行时才能命中 REPLACE 分支。
                fileName = target.getName();
            }
            String url = fileService.upload(file, "library");
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("fileName", fileName);
            after.put("fileUrl", url);
            after.put("fileSize", file.getSize());
            after.put("folderId", update ? target.getParentId() : folderId);
            after.put("purpose", purpose == null || purpose.isBlank() ? "agent-generated" : purpose);
            TeamAgentActionDraft draft = TeamAgentActionDraft.of(
                TeamAgentActionSchema.VERSION,
                update ? "UPDATE_LIBRARY_FILE" : "CREATE_LIBRARY_FILE", "TEAM_FILE",
                update ? target.getId() : null, null, Map.of(), after,
                reason == null || reason.isBlank()
                    ? (update ? "Agent 更新了资料库文件" : "Agent 生成的办公文件") : reason,
                List.of());
            pendingDrafts.add(runId, draft);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fileName", fileName);
            result.put("fileUrl", url);
            result.put("fileSize", file.getSize());
            result.put("teamId", scope.teamId());
            result.put("pendingConfirmation", true);
            return ResponseEntity.ok(result);
        } catch (TeamAgentException denied) {
            return error(denied.getErrorCode().status(), denied.getErrorCode().name(), denied.getMessage());
        } catch (IOException failed) {
            log.warn("agent 文件保存失败：team={} user={}", teamId, userId, failed);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_SAVE_FAILED", "文件保存失败");
        } catch (IllegalArgumentException rejected) {
            return error(HttpStatus.BAD_REQUEST, "FILE_REJECTED", rejected.getMessage());
        }
    }

    private boolean tokenMatches(String provided) {
        String expected = properties.getInternal().getToken();
        if (expected == null || expected.isBlank() || provided == null) return false;
        return java.security.MessageDigest.isEqual(
            provided.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String safeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) name = "agent-file";
        if (name.length() > 120) {
            String extension = extensionOf(name);
            name = name.substring(0, Math.max(1, 120 - extension.length())) + extension;
        }
        return name;
    }

    private static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}
