package cn.ilink.teamagent;

import cn.ilink.mapper.KgAgentTraceMapper;
import cn.ilink.service.kg.AgenticGraphRagService;
import cn.ilink.service.kg.GraphRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 灰度与回退：pi 成功则用 pi，失败或关闭则回到进程内 B3。 */
class TeamScopedGraphRagAdapterTest {
    private AgenticGraphRagService graphRagService;
    private PiAgentClient piAgentClient;
    private TeamAgentProperties properties;
    private TeamScopedGraphRagAdapter adapter;

    @BeforeEach
    void setUp() {
        graphRagService = mock(AgenticGraphRagService.class);
        piAgentClient = mock(PiAgentClient.class);
        properties = new TeamAgentProperties();
        adapter = new TeamScopedGraphRagAdapter(
            graphRagService,
            mock(GraphRagService.class),
            mock(KgAgentTraceMapper.class),
            piAgentClient,
            properties);
    }

    @Test
    void piSuccessIsUsedAndLegacySkipped() {
        properties.getPiAgent().setEnabled(true);
        when(piAgentClient.query(any(), any(), anyLong(), any()))
            .thenReturn(new TeamScopedGraphRagAdapter.Result("pi 答案", List.of(), 2, 3, false));

        var result = adapter.query("问题", scope(), 99L, 1L, new NoopListener());

        assertEquals("pi 答案", result.answer());
        verify(graphRagService, never()).query(any(), anyLong(), any());
    }

    @Test
    void piFailureFallsBackToLegacy() {
        properties.getPiAgent().setEnabled(true);
        when(piAgentClient.query(any(), any(), anyLong(), any()))
            .thenThrow(new IllegalStateException("pi 不可用"));
        when(graphRagService.query(any(), anyLong(), any()))
            .thenReturn(new AgenticGraphRagService.AgenticAnswer("B3 答案", List.of(), List.of(), 1, 1, false));

        var result = adapter.query("问题", scope(), 99L, 1L, new NoopListener());

        assertEquals("B3 答案", result.answer());
        verify(graphRagService).query(eq("问题"), eq(8L), any());
    }

    @Test
    void teamOutsideAllowlistUsesLegacy() {
        properties.getPiAgent().setEnabled(true);
        properties.getPiAgent().setTeamAllowlist(List.of(2L, 3L));
        when(graphRagService.query(any(), anyLong(), any()))
            .thenReturn(new AgenticGraphRagService.AgenticAnswer("B3 答案", List.of(), List.of(), 1, 1, false));

        adapter.query("问题", scope(), 99L, 1L, new NoopListener());

        verify(piAgentClient, never()).query(any(), any(), anyLong(), any());
        verify(graphRagService).query(any(), eq(8L), any());
    }

    @Test
    void fallbackResultKeepsOriginalAnswerSemantics() {
        properties.getPiAgent().setEnabled(true);
        when(piAgentClient.query(any(), any(), anyLong(), any()))
            .thenThrow(new IllegalStateException("timeout"));
        when(graphRagService.query(any(), anyLong(), any()))
            .thenReturn(new AgenticGraphRagService.AgenticAnswer("B3", List.of(), List.of(), 3, 4, true));

        var result = adapter.query("问题", scope(), 99L, 1L, new NoopListener());

        assertEquals(3, result.rounds());
        assertEquals(4, result.toolCalls());
        assertTrue(result.fallback());
    }

    private static TeamAgentScope scope() {
        return new TeamAgentScope(8L, 1L, "测试团队", "TEAMING", TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.ALL, null, null, null, null, java.time.Instant.now());
    }

    private static final class NoopListener
        implements AgenticGraphRagService.AgenticStreamListener {
        @Override public void onThought(int round, String text) { }
        @Override public void onAction(int round, String tool, String paramsJson) { }
        @Override public void onObservation(int round, String text) { }
        @Override public void onFinal(String answer, Object citations, Object trace) { }
    }
}
