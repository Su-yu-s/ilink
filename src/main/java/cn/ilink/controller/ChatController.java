package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.User;
import cn.ilink.service.ChatService;
import cn.ilink.service.TeamAccessService;
import cn.ilink.vo.ChatMessageVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/api/team")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private TeamAccessService teamAccessService;

    @GetMapping("/{teamId}/messages")
    @ResponseBody
    public ResponseEntity<Result<?>> getHistory(@PathVariable Long teamId,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        if (!teamAccessService.isTeamParticipant(teamId, user.getId())) {
            return Result.fail(403, "你不是该团队成员，无权查看消息").toResponseEntity();
        }

        List<ChatMessageVO> messages = chatService.getHistory(teamId, limit);
        return Result.ok("获取成功", messages).toResponseEntity();
    }

    @PostMapping("/{teamId}/messages")
    @ResponseBody
    public ResponseEntity<Result<?>> sendMessage(@PathVariable Long teamId,
                                                  @RequestBody Map<String, Object> request,
                                                  HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        if (!teamAccessService.isTeamParticipant(teamId, user.getId())) {
            return Result.fail(403, "你不是该团队成员，无权发送消息").toResponseEntity();
        }

        String content = (String) request.get("content");
        String type = (String) request.getOrDefault("type", "TEXT");

        if (content == null || content.trim().isEmpty()) {
            return Result.badRequest("消息内容不能为空").toResponseEntity();
        }

        ChatMessageVO message = chatService.sendMessage(teamId, user.getId(), content.trim(), type);
        return Result.ok("发送成功", message).toResponseEntity();
    }

    @MessageMapping("/chat/{teamId}")
    @SendTo("/topic/team/{teamId}")
    public ChatMessageVO handleWebSocketMessage(@DestinationVariable Long teamId,
                                                Map<String, Object> payload,
                                                SimpMessageHeaderAccessor headerAccessor) {
        User user = null;
        if (headerAccessor.getSessionAttributes() != null) {
            Object sessionUser = headerAccessor.getSessionAttributes().get("user");
            if (sessionUser instanceof User) {
                user = (User) sessionUser;
            }
        }
        if (user == null) {
            throw new IllegalStateException("未登录，无法发送消息");
        }

        // The inbound channel interceptor enforces this too; keep the controller
        // check so an alternate STOMP transport cannot bypass team membership.
        if (!teamAccessService.isTeamParticipant(teamId, user.getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Not a member of this team");
        }

        String content = (String) payload.get("content");
        String type = (String) payload.getOrDefault("type", "TEXT");

        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        return chatService.sendMessage(teamId, user.getId(), content.trim(), type);
    }
}
