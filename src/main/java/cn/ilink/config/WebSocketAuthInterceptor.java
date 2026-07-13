package cn.ilink.config;

import cn.ilink.entity.User;
import cn.ilink.service.TeamAccessService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpSession;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * WebSocket/STOMP 鉴权：要求已登录用户才能订阅团队频道或发送消息。
 * 兼容 SockJS 和原生 WebSocket 两种连接方式。
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final Pattern TEAM_TOPIC_DESTINATION = Pattern.compile("^/topic/team/(\\d+)$");
    private static final Pattern NOTIFICATION_DESTINATION = Pattern.compile(
        "^/topic/notification/(\\d+)(?:/(?:unread|mark-all-read))?$");
    private static final Pattern CHAT_DESTINATION = Pattern.compile("^/app/chat/(\\d+)$");

    private final TeamAccessService teamAccessService;

    public WebSocketAuthInterceptor(TeamAccessService teamAccessService) {
        this.teamAccessService = teamAccessService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.SEND.equals(command)
            || StompCommand.SUBSCRIBE.equals(command)) {
            User user = resolveUser(accessor);
            if (user == null) {
                throw new AccessDeniedException("Authentication is required for WebSocket access");
            }
            // Inbound STOMP frames are commonly immutable by the time they
            // reach this interceptor. Session attributes remain the source of
            // truth for authorization; only set a Principal when the frame is
            // still mutable so a valid frame is never rejected by header writes.
            if (accessor.isMutable()) {
                accessor.setUser(() -> String.valueOf(user.getId()));
            }

            if (StompCommand.SUBSCRIBE.equals(command)) {
                assertCanSubscribe(accessor.getDestination(), user);
            } else if (StompCommand.SEND.equals(command)) {
                assertCanSend(accessor.getDestination(), user);
            }
        }
        return message;
    }

    private void assertCanSubscribe(String destination, User user) {
        Matcher teamTopic = TEAM_TOPIC_DESTINATION.matcher(safeDestination(destination));
        if (teamTopic.matches()) {
            assertTeamParticipant(teamTopic.group(1), user);
            return;
        }

        Matcher notificationTopic = NOTIFICATION_DESTINATION.matcher(safeDestination(destination));
        if (notificationTopic.matches() && user.getId() != null
            && user.getId().equals(parsePositiveId(notificationTopic.group(1)))) {
            return;
        }

        throw new AccessDeniedException("Not allowed to subscribe to this destination");
    }

    private void assertCanSend(String destination, User user) {
        Matcher chatDestination = CHAT_DESTINATION.matcher(safeDestination(destination));
        if (chatDestination.matches()) {
            assertTeamParticipant(chatDestination.group(1), user);
            return;
        }

        throw new AccessDeniedException("Not allowed to send to this destination");
    }

    private void assertTeamParticipant(String rawTeamId, User user) {
        Long teamId = parsePositiveId(rawTeamId);
        if (teamId == null || user.getId() == null
            || !teamAccessService.isTeamParticipant(teamId, user.getId())) {
            throw new AccessDeniedException("Not a member of this team");
        }
    }

    private Long parsePositiveId(String rawValue) {
        try {
            long value = Long.parseLong(rawValue);
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String safeDestination(String destination) {
        return destination == null ? "" : destination;
    }

    /**
     * 从 STOMP 会话属性或 HTTP Session 中解析当前登录用户。
     * SockJS 通过 sessionAttributes 传递 user，原生 WebSocket 需要从 HTTP Session 读取。
     */
    private User resolveUser(StompHeaderAccessor accessor) {
        // 1. 优先从 SockJS sessionAttributes 读取
        if (accessor.getSessionAttributes() != null) {
            Object user = accessor.getSessionAttributes().get("user");
            if (user instanceof User) {
                return (User) user;
            }
        }
        // 2. 从 Spring Security 上下文读取
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof User) {
            return (User) auth.getPrincipal();
        }
        // 3. Fallback：HTTP Session（向后兼容）
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpSession session = attrs.getRequest().getSession(false);
                if (session != null) {
                    Object user = session.getAttribute("user");
                    if (user instanceof User) {
                        return (User) user;
                    }
                }
            }
        } catch (Exception e) {
            // 非 HTTP 请求上下文，忽略
        }
        return null;
    }
}
