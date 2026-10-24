package cn.ilink.teamagent;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.User;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@RequestMapping("/api/team/{teamId}/agent")
public class TeamAgentController {
    private final TeamAgentContextService contextService;
    private final TeamAgentConversationService conversationService;
    private final TeamAgentRunService runService;
    private final TeamAgentSseService sseService;
    private final TeamAgentActionService actionService;

    public TeamAgentController(TeamAgentContextService contextService,
                               TeamAgentConversationService conversationService,
                               TeamAgentRunService runService,
                               TeamAgentSseService sseService,
                               TeamAgentActionService actionService) {
        this.contextService = contextService;
        this.conversationService = conversationService;
        this.runService = runService;
        this.sseService = sseService;
        this.actionService = actionService;
    }

    @GetMapping("/bootstrap")
    public ResponseEntity<Result<?>> bootstrap(@PathVariable Long teamId, HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(contextService.bootstrap(teamId, userId));
    }

    @PostMapping("/conversations")
    public ResponseEntity<Result<?>> createConversation(
        @PathVariable Long teamId,
        @RequestBody TeamAgentRequests.CreateConversation request,
        HttpSession session) {
        if (request == null) invalid("请求体不能为空");
        Long userId = requireUser(session).getId();
        Map<String, Object> data = conversationService.create(
            teamId, userId, request.clientRequestId(), request.title());
        return privateResponse(HttpStatus.CREATED, "会话已创建", data);
    }

    @GetMapping("/conversations")
    public ResponseEntity<Result<?>> conversations(
        @PathVariable Long teamId,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(required = false) String cursor,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(conversationService.list(teamId, userId, limit, cursor));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<Result<?>> conversation(
        @PathVariable Long teamId,
        @PathVariable Long conversationId,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) Long beforeSeq,
        @RequestParam(required = false) Long afterSeq,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(conversationService.detail(
            teamId, userId, conversationId, limit, beforeSeq, afterSeq));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Result<?>> deleteConversation(
        @PathVariable Long teamId,
        @PathVariable Long conversationId,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        conversationService.delete(teamId, userId, conversationId);
        return privateOk(Map.of("deleted", true, "conversationId", conversationId));
    }

    @PostMapping("/runs")
    public ResponseEntity<Result<?>> createRun(
        @PathVariable Long teamId,
        @RequestBody TeamAgentRequests.CreateRun request,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateResponse(HttpStatus.ACCEPTED, "运行已创建",
            runService.create(teamId, userId, request));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<Result<?>> run(
        @PathVariable Long teamId,
        @PathVariable Long runId,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(runService.get(teamId, userId, runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Result<?>> cancel(
        @PathVariable Long teamId,
        @PathVariable Long runId,
        @RequestBody TeamAgentRequests.CancelRun request,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(runService.cancel(teamId, userId, runId, request));
    }

    @GetMapping(value = "/runs/{runId}/events",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
        @PathVariable Long teamId,
        @PathVariable Long runId,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
        @RequestParam(value = "afterSeq", required = false) String afterSeq,
        HttpSession session,
        HttpServletResponse response) {
        try {
            User user = requireUser(session);
            long seq = sseService.parseLastEventId(runId, lastEventId, afterSeq);
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setHeader(HttpHeaders.CACHE_CONTROL,
                CacheControl.noStore().mustRevalidate().getHeaderValue());
            response.setHeader(HttpHeaders.PRAGMA, "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            return sseService.open(teamId, user.getId(), runId, seq, session);
        } catch (TeamAgentException error) {
            // EventSource advertises Accept: text/event-stream. Returning a
            // JSON body through normal content negotiation in that case
            // becomes a 406/500 instead of the stable Agent status. Keep the
            // stream endpoint bodyless on pre-stream validation failures and
            // expose the machine-readable reason in a header.
            response.setStatus(error.getErrorCode().status().value());
            response.setHeader("X-Team-Agent-Error-Code", error.getErrorCode().name());
            if (error.getRetryAfterSeconds() != null) {
                response.setHeader("Retry-After",
                    String.valueOf(error.getRetryAfterSeconds()));
            }
            return null;
        }
    }

    @GetMapping("/action-groups")
    public ResponseEntity<Result<?>> actionGroups(
        @PathVariable Long teamId,
        @RequestParam(required = false) Long conversationId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(required = false) String cursor,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(actionService.list(
            teamId, userId, conversationId, status, limit, cursor));
    }

    @GetMapping("/action-groups/{groupId}")
    public ResponseEntity<Result<?>> actionGroup(
        @PathVariable Long teamId,
        @PathVariable Long groupId,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(actionService.detail(teamId, userId, groupId));
    }

    @PostMapping("/action-groups/{groupId}/confirm")
    public ResponseEntity<Result<?>> confirmActions(
        @PathVariable Long teamId,
        @PathVariable Long groupId,
        @RequestBody TeamAgentRequests.ConfirmActions request,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(actionService.confirm(teamId, userId, groupId, request));
    }

    @PostMapping("/action-groups/{groupId}/dismiss")
    public ResponseEntity<Result<?>> dismissActions(
        @PathVariable Long teamId,
        @PathVariable Long groupId,
        @RequestBody TeamAgentRequests.DismissActions request,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(actionService.dismiss(teamId, userId, groupId, request));
    }

    @PostMapping("/actions/{actionId}/undo")
    public ResponseEntity<Result<?>> undoAction(
        @PathVariable Long teamId,
        @PathVariable Long actionId,
        @RequestBody TeamAgentRequests.UndoAction request,
        HttpSession session) {
        Long userId = requireUser(session).getId();
        return privateOk(actionService.undo(teamId, userId, actionId, request));
    }

    protected ResponseEntity<Result<?>> privateOk(Object data) {
        return privateResponse(HttpStatus.OK, "获取成功", data);
    }

    protected ResponseEntity<Result<?>> privateResponse(HttpStatus status,
                                                        String message, Object data) {
        return ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore().mustRevalidate())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(Result.ok(message, data));
    }

    protected User requireUser(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            throw new TeamAgentException(TeamAgentErrorCode.AUTH_REQUIRED, "登录状态已失效");
        }
        return user;
    }

    protected void invalid(String message) {
        throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, message);
    }
}
