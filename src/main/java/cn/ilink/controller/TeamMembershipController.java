package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.User;
import cn.ilink.service.TeamInviteService;
import cn.ilink.service.TeamMembershipException;
import cn.ilink.service.TeamOwnershipService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队邀请、成员进出与所有权变更。
 *
 * <p>与 {@link TeamController} 共用 /api/team 前缀，但路径不重叠：邀请/响应/转让/解散都是
 * 既有路由之外的新段，删成员的 {@code /{id}/members/{userId}} 也不与 {@code /{id}} 冲突。
 */
@Controller
@RequestMapping("/api/team")
public class TeamMembershipController {

    private final TeamInviteService teamInviteService;
    private final TeamOwnershipService teamOwnershipService;

    public TeamMembershipController(TeamInviteService teamInviteService,
                                    TeamOwnershipService teamOwnershipService) {
        this.teamInviteService = teamInviteService;
        this.teamOwnershipService = teamOwnershipService;
    }

    /** 批量邀请。逐个返回跳过原因，前端可以精确提示「已邀请 / 已加入 / 队伍已满」。 */
    @PostMapping("/{teamId}/invite")
    @ResponseBody
    public ResponseEntity<Result<?>> invite(@PathVariable Long teamId,
                                            @RequestBody(required = false) Map<String, Object> payload,
                                            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        try {
            TeamInviteService.InviteOutcome outcome =
                teamInviteService.invite(teamId, user.getId(), parseUserIds(payload));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("invited", outcome.invited);
            data.put("skipped", outcome.skipped);
            String message = outcome.invited.isEmpty() ? "没有新增邀请" : "已发送 " + outcome.invited.size() + " 个邀请";
            return Result.ok(message, data).toResponseEntity();
        } catch (TeamMembershipException e) {
            return failure(e);
        }
    }

    /** 被邀请人同意加入 */
    @PutMapping("/invitation/{id}/accept")
    @ResponseBody
    public ResponseEntity<Result<?>> accept(@PathVariable Long id, HttpSession session) {
        return respond(id, session, true);
    }

    /** 被邀请人拒绝邀请 */
    @PutMapping("/invitation/{id}/reject")
    @ResponseBody
    public ResponseEntity<Result<?>> reject(@PathVariable Long id, HttpSession session) {
        return respond(id, session, false);
    }

    /** 移除成员；对自己调用即为退出团队。 */
    @DeleteMapping("/{teamId}/members/{userId}")
    @ResponseBody
    public ResponseEntity<Result<?>> removeMember(@PathVariable Long teamId,
                                                  @PathVariable Long userId,
                                                  HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        try {
            boolean self = user.getId().equals(userId);
            teamInviteService.removeMember(teamId, user.getId(), userId);
            return Result.ok(self ? "已退出团队" : "已移除该成员", null).toResponseEntity();
        } catch (TeamMembershipException e) {
            return failure(e);
        }
    }

    /** 转让创建者 */
    @PostMapping("/{teamId}/transfer")
    @ResponseBody
    public ResponseEntity<Result<?>> transfer(@PathVariable Long teamId,
                                              @RequestBody(required = false) Map<String, Object> payload,
                                              HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        Long newOwnerId = payload == null ? null : ControllerUtils.parseLongParam(payload.get("newOwnerId"));
        if (newOwnerId == null) {
            return Result.badRequest("请选择新的创建者").toResponseEntity();
        }
        try {
            teamOwnershipService.transfer(teamId, user.getId(), newOwnerId);
            return Result.ok("已转让创建者", null).toResponseEntity();
        } catch (TeamMembershipException e) {
            return failure(e);
        }
    }

    /** 解散团队（软删，协作历史保留） */
    @PostMapping("/{teamId}/dissolve")
    @ResponseBody
    public ResponseEntity<Result<?>> dissolve(@PathVariable Long teamId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        try {
            int notified = teamOwnershipService.dissolve(teamId, user.getId());
            return Result.ok("团队已解散", Map.of("notified", notified)).toResponseEntity();
        } catch (TeamMembershipException e) {
            return failure(e);
        }
    }

    private ResponseEntity<Result<?>> respond(Long id, HttpSession session, boolean accept) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        try {
            TeamApplication application = teamInviteService.respond(id, user.getId(), accept);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("applicationId", application.getId());
            data.put("teamId", application.getTeamId());
            data.put("status", application.getStatus());
            return Result.ok(accept ? "已加入团队" : "已拒绝邀请", data).toResponseEntity();
        } catch (TeamMembershipException e) {
            return failure(e);
        }
    }

    private static List<Long> parseUserIds(Map<String, Object> payload) {
        List<Long> ids = new ArrayList<>();
        if (payload == null) {
            return ids;
        }
        Object raw = payload.get("userIds");
        if (!(raw instanceof List)) {
            return ids;
        }
        for (Object item : (List<?>) raw) {
            Long id = ControllerUtils.parseLongParam(item);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Result 没有带文案的 forbidden 快捷方法，统一用 fail(code, message) 并交给 toHttpStatus 映射状态码 */
    private static ResponseEntity<Result<?>> failure(TeamMembershipException e) {
        return Result.fail(e.getStatus(), e.getMessage()).toResponseEntity();
    }
}
