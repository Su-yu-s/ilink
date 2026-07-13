package cn.ilink.config;

import cn.ilink.entity.User;
import cn.ilink.service.TeamAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock
    private TeamAccessService teamAccessService;

    @InjectMocks
    private WebSocketAuthInterceptor interceptor;

    @Test
    void notificationSubscriptionIsLimitedToTheCurrentUser() {
        User user = user(7L);

        assertDoesNotThrow(() -> interceptor.preSend(
            message(StompCommand.SUBSCRIBE, "/topic/notification/7/unread", user), null));

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
            message(StompCommand.SUBSCRIBE, "/topic/notification/8", user), null));
    }

    @Test
    void teamTopicSubscriptionRequiresTeamParticipation() {
        User user = user(7L);
        given(teamAccessService.isTeamParticipant(12L, 7L)).willReturn(true);

        assertDoesNotThrow(() -> interceptor.preSend(
            message(StompCommand.SUBSCRIBE, "/topic/team/12", user), null));
        verify(teamAccessService).isTeamParticipant(12L, 7L);

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
            message(StompCommand.SUBSCRIBE, "/topic/team/13", user), null));
    }

    @Test
    void chatSendRequiresTeamParticipation() {
        User user = user(7L);
        given(teamAccessService.isTeamParticipant(12L, 7L)).willReturn(true);

        assertDoesNotThrow(() -> interceptor.preSend(
            message(StompCommand.SEND, "/app/chat/12", user), null));

        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
            message(StompCommand.SEND, "/app/chat/13", user), null));
    }

    @Test
    void unknownBrokerDestinationsAreDenied() {
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(
            message(StompCommand.SUBSCRIBE, "/topic/anything", user(7L)), null));
    }

    private Message<byte[]> message(StompCommand command, String destination, User user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("test-session");
        accessor.setSessionAttributes(new HashMap<>());
        if (user != null) {
            accessor.getSessionAttributes().put("user", user);
        }
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        return user;
    }
}
