package cn.ilink.teamagent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * agent 生成文件的暂存端点。文件先落盘，但只有用户确认动作后才会登记资料库。
 * 默认不注册，与 /internal/agent/tools 使用同一套开关和令牌。
 */
@RestController
@RequestMapping("/internal/agent")
@ConditionalOnProperty(prefix = "app.team-agent.internal", name = "enabled", havingValue = "true")
public class InternalAgentFileController {
    private final InternalAgentFileService service;

    public InternalAgentFileController(InternalAgentFileService service) {
        this.service = service;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
        @RequestParam("file") MultipartFile file,
        @RequestHeader(value = "x-ilink-internal-token", required = false) String token,
        @RequestParam("teamId") Long teamId,
        @RequestParam("userId") Long userId,
        @RequestParam(value = "runId", required = false) Long runId,
        @RequestParam(value = "folderId", required = false) Long folderId,
        @RequestParam(value = "purpose", required = false) String purpose,
        @RequestParam(value = "reason", required = false) String reason,
        @RequestParam(value = "mode", required = false, defaultValue = "create") String mode,
        @RequestParam(value = "targetFileId", required = false) Long targetFileId) {
        return service.upload(file, token, teamId, userId, runId, folderId, purpose, reason,
            mode, targetFileId);
    }
}
