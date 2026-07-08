package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.service.ChatService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.vo.ChatMessageVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private TeamApplicationServiceImpl teamApplicationService;

    @Autowired
    private TeamDemandServiceImpl teamDemandService;

    @GetMapping("/{teamId}/messages")
    @ResponseBody
    public ResponseEntity<Result<?>> getHistory(@PathVariable Long teamId,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        if (!isTeamMember(teamId, user.getId())) {
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

        if (!isTeamMember(teamId, user.getId())) {
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

    /**
     * 检查用户是否为团队成员（已通过申请的成员或团队创建者）
     */
    private boolean isTeamMember(Long teamId, Long userId) {
        // 检查是否为团队创建者
        long creatorCount = teamDemandService.count(
                new LambdaQueryWrapper<TeamDemand>()
                        .eq(TeamDemand::getId, teamId)
                        .eq(TeamDemand::getCreatorId, userId)
        );
        if (creatorCount > 0) {
            return true;
        }
        // 检查是否已通过申请的成员
        long memberCount = teamApplicationService.count(
                new LambdaQueryWrapper<TeamApplication>()
                        .eq(TeamApplication::getTeamId, teamId)
                        .eq(TeamApplication::getUserId, userId)
                        .eq(TeamApplication::getStatus, "APPROVED")
        );
        return memberCount > 0;
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

        String content = (String) payload.get("content");
        String type = (String) payload.getOrDefault("type", "TEXT");

        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        return chatService.sendMessage(teamId, user.getId(), content.trim(), type);
    }
}
