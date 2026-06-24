package cn.ilink.config;

import cn.ilink.entity.User;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * WebSocket/STOMP 鉴权：要求已登录用户才能订阅团队频道或发送消息。
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

    private User resolveUser(StompHeaderAccessor accessor) {
        if (accessor.getSessionAttributes() == null) {
            return null;
        }
        Object user = accessor.getSessionAttributes().get("user");
        return user instanceof User ? (User) user : null;
    }
}
