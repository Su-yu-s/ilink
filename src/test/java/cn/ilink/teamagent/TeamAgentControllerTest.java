package cn.ilink.teamagent;

import cn.ilink.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamAgentControllerTest {

    @Test
    void foreignRunCursorReturnsStable400WithoutMediaNegotiationFailure() {
        TeamAgentSseService sseService = mock(TeamAgentSseService.class);
        TeamAgentController controller = new TeamAgentController(
            mock(TeamAgentContextService.class),
            mock(TeamAgentConversationService.class),
            mock(TeamAgentRunService.class),
            sseService,
            mock(TeamAgentActionService.class));
        MockHttpSession session = new MockHttpSession();
        User user = new User();
        user.setId(75L);
        session.setAttribute("user", user);
        when(sseService.parseLastEventId(11L, "999:1", null))
            .thenThrow(new TeamAgentException(
                TeamAgentErrorCode.REQUEST_INVALID,
                "Last-Event-ID 不属于当前运行"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        SseEmitter result = controller.events(
            93L, 11L, "999:1", null, session, response);

        assertNull(result);
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatus());
        assertEquals("REQUEST_INVALID",
            response.getHeader("X-Team-Agent-Error-Code"));
        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void successfulSubscriptionReturnsEmitterWithStreamHeaders() {
        TeamAgentSseService sseService = mock(TeamAgentSseService.class);
        TeamAgentController controller = new TeamAgentController(
            mock(TeamAgentContextService.class),
            mock(TeamAgentConversationService.class),
            mock(TeamAgentRunService.class),
            sseService,
            mock(TeamAgentActionService.class));
        MockHttpSession session = new MockHttpSession();
        User user = new User();
        user.setId(75L);
        session.setAttribute("user", user);
        SseEmitter emitter = new SseEmitter();
        when(sseService.parseLastEventId(11L, null, "1")).thenReturn(1L);
        when(sseService.open(93L, 75L, 11L, 1L, session)).thenReturn(emitter);
        MockHttpServletResponse response = new MockHttpServletResponse();

        SseEmitter result = controller.events(
            93L, 11L, null, "1", session, response);

        assertSame(emitter, result);
        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals(MediaType.TEXT_EVENT_STREAM_VALUE, response.getContentType());
        assertEquals("no-store, must-revalidate",
            response.getHeader(HttpHeaders.CACHE_CONTROL));
    }
}
