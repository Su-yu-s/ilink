package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentRun;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentRunCompletionServiceTest {
    private TeamAgentRunMapper runMapper;
    private TeamDemandMapper teamMapper;
    private TeamAgentAdmissionController admission;
    private TeamAgentRunCompletionService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(TeamAgentRunMapper.class);
        teamMapper = mock(TeamDemandMapper.class);
        admission = mock(TeamAgentAdmissionController.class);
        service = new TeamAgentRunCompletionService(
            runMapper,
            teamMapper,
            mock(TeamAgentConversationMapper.class),
            mock(TeamAgentConversationService.class),
            mock(TeamAgentEventService.class),
            mock(TeamAgentActionProposalService.class),
            admission,
            mock(TeamAgentAuthorization.class));
    }

    @Test
    void completionReleasesPermitWhenTeamWasDeletedConcurrently() {
        TeamAgentRun snapshot = run(9L, 20L, 7L);
        when(runMapper.selectById(9L)).thenReturn(snapshot);
        when(teamMapper.selectByIdForUpdate(20L)).thenReturn(null);

        service.complete(9L, "lease", null, List.of());

        verify(admission).release(9L, 20L, 7L);
        verify(runMapper, never()).updateById(snapshot);
    }

    @Test
    void cancellationReleasesPermitWhenTeamWasDeletedConcurrently() {
        TeamAgentRun snapshot = run(10L, 21L, 8L);
        when(runMapper.selectById(10L)).thenReturn(snapshot);
        when(teamMapper.selectByIdForUpdate(21L)).thenReturn(null);

        service.cancelRunning(10L, "lease", "stop");

        verify(admission).release(10L, 21L, 8L);
        verify(runMapper, never()).updateById(snapshot);
    }

    private TeamAgentRun run(Long id, Long teamId, Long userId) {
        TeamAgentRun run = new TeamAgentRun();
        run.setId(id);
        run.setTeamId(teamId);
        run.setUserId(userId);
        run.setStatus(TeamAgentRun.STATUS_RUNNING);
        return run;
    }
}
