package cn.ilink.config;

import cn.ilink.entity.User;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * WebSocket/STOMP 鉴权：要求已登录用户才能订阅团队频道或发送消息。
 * 兼容 SockJS 和原生 WebSocket 两种连接方式。
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

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
                throw new IllegalStateException("未登录，无法使用聊天服务");
            }
            accessor.setUser(() -> String.valueOf(user.getId()));
        }
        return message;
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
        // 2. 原生 WebSocket：从当前线程的 HTTP Session 读取
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
