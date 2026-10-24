package cn.ilink.teamagent;

import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class TeamAgentSseServiceTest {
    private TeamAgentSseService service;

    @BeforeEach
    void setUp() {
        service = new TeamAgentSseService(
            mock(TeamAgentRunMapper.class),
            mock(TeamAgentConversationMapper.class),
            mock(TeamAgentEventService.class),
            mock(TeamAgentAuthorization.class));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void parsesRunScopedLastEventId() {
        assertEquals(9L, service.parseLastEventId(42L, "42:9", null));
        assertEquals(9L, service.parseLastEventId(42L, " 42:9 ", "3"));
    }

    @Test
    void fallsBackToAfterSeqWhenLastEventIdIsAbsent() {
        assertEquals(0L, service.parseLastEventId(42L, null, null));
        assertEquals(0L, service.parseLastEventId(42L, " ", ""));
        assertEquals(7L, service.parseLastEventId(42L, null, "7"));
    }

    @Test
    void rejectsMalformedOrForeignRunCursor() {
        assertError(() -> service.parseLastEventId(42L, "missing-separator", null));
        assertError(() -> service.parseLastEventId(42L, "43:9", null));
        assertError(() -> service.parseLastEventId(42L, "42:-1", null));
        assertError(() -> service.parseLastEventId(42L, null, "-1"));
        assertError(() -> service.parseLastEventId(42L, null, "abc"));
    }

    private void assertError(Runnable invocation) {
        TeamAgentException error = assertThrows(TeamAgentException.class,
            invocation::run);
        assertEquals(TeamAgentErrorCode.REQUEST_INVALID, error.getErrorCode());
    }
}
