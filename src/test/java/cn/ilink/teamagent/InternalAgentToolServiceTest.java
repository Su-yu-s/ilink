package cn.ilink.teamagent;

import cn.ilink.service.kg.tool.GraphTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内部工具端点的鉴权、白名单与作用域绑定。 */
class InternalAgentToolServiceTest {
    private TeamAgentProperties properties;
    private TeamAgentAuthorization authorization;
    private GraphTool search;
    private InternalAgentToolService service;

    @BeforeEach
    void setUp() {
        properties = new TeamAgentProperties();
        properties.getInternal().setEnabled(true);
        properties.getInternal().setToken("test-token");
        authorization = mock(TeamAgentAuthorization.class);
        search = mock(GraphTool.class);
        when(search.getName()).thenReturn("search_entities");
        service = new InternalAgentToolService(properties, authorization, List.of(search));
    }

    @Test
    void missingTokenIsRejectedBeforeAnyScopeLookup() {
        ResponseEntity<?> response = service.invoke("kg_search_entities", null, request());
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(authorization, never()).requireScope(any(), any());
    }

    @Test
    void wrongTokenIsRejected() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            service.invoke("kg_search_entities", "wrong", request()).getStatusCode());
    }

    @Test
    void disabledEndpointReturnsNotFound() {
        properties.getInternal().setEnabled(false);
        assertEquals(HttpStatus.NOT_FOUND,
            service.invoke("kg_search_entities", "test-token", request()).getStatusCode());
    }

    @Test
    void toolsOutsideWhitelistAreRejected() {
        assertEquals(HttpStatus.BAD_REQUEST,
            service.invoke("kg_delete_everything", "test-token", request()).getStatusCode());
        verify(search, never()).execute(any(), any());
    }

    @Test
    void authorizedCallExecutesWithBoundScope() {
        // TeamAgentScope 是 final，直接构造真实实例
        when(authorization.requireScope(1L, 8L)).thenReturn(new TeamAgentScope(
            8L, 1L, "测试团队", "TEAMING", TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.ALL, null, null, null, null, java.time.Instant.now()));
        when(search.execute(any(), any())).thenReturn(Map.of("entities", List.of()));

        ResponseEntity<?> response = service.invoke("kg_search_entities", "test-token", request());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(search).execute(Map.of("query", "iLink"), 8L);
        assertTrue(TeamAgentScopeContext.current() == null, "作用域必须在调用结束后关闭");
    }

    @Test
    void permissionDeniedIsMappedToForbiddenAndToolNotExecuted() {
        when(authorization.requireScope(1L, 8L))
            .thenThrow(new TeamAgentException(TeamAgentErrorCode.TEAM_ACCESS_DENIED, "不是团队成员"));

        ResponseEntity<?> response = service.invoke("kg_search_entities", "test-token", request());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(search, never()).execute(any(), any());
    }

    private static InternalToolRequest request() {
        return new InternalToolRequest(1L, 8L, 99L, Map.of("query", "iLink"));
    }
}
