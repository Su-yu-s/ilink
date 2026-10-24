package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentRun;
import cn.ilink.mapper.TeamAgentMessageMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.service.kg.AgenticGraphRagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentRunExecutorStartupTest {
    private ThreadPoolExecutor workerPool;
    private TeamAgentRunMapper runMapper;
    private TeamAgentAdmissionController admission;
    private TeamAgentRunCompletionService completionService;
    private TeamAgentRunExecutor executor;

    @BeforeEach
    void setUp() {
        workerPool = mock(ThreadPoolExecutor.class);
        runMapper = mock(TeamAgentRunMapper.class);
        admission = mock(TeamAgentAdmissionController.class);
        completionService = mock(TeamAgentRunCompletionService.class);
        executor = new TeamAgentRunExecutor(
            workerPool,
            runMapper,
            mock(TeamAgentMessageMapper.class),
            mock(TeamAgentAuthorization.class),
            mock(TeamAgentIntentRouter.class),
            mock(TeamAgentBusinessAnswerService.class),
            mock(TeamAgentActionProposalService.class),
            mock(TeamAgentEventService.class),
            completionService,
            admission,
            new TeamAgentProperties());
    }

    @Test
    void startupRestoresPermitsForBothQueuedAndRunningRowsThenSubmitsQueued() {
        TeamAgentRun queued = run(1L, 10L, 100L, TeamAgentRun.STATUS_QUEUED);
        TeamAgentRun running = run(2L, 11L, 101L, TeamAgentRun.STATUS_RUNNING);
        when(runMapper.selectList(any())).thenReturn(List.of(queued, running));

        executor.recoverActiveOnStartup();

        verify(admission).acquire(1L, 10L, 100L);
        verify(admission).acquire(2L, 11L, 101L);
        verify(workerPool).execute(any());
    }

    @Test
    void startupFailsRejectedQueuedRunAndNeverSubmitsIt() {
        TeamAgentRun queued = run(3L, 10L, 100L, TeamAgentRun.STATUS_QUEUED);
        when(runMapper.selectList(any())).thenReturn(List.of(queued));
        doThrow(new TeamAgentException(TeamAgentErrorCode.AGENT_CAPACITY_EXCEEDED, "full"))
            .when(admission).acquire(3L, 10L, 100L);

        executor.recoverActiveOnStartup();

        verify(completionService).failQueued(3L);
        verify(workerPool, never()).execute(any());
    }

    private TeamAgentRun run(Long id, Long teamId, Long userId, String status) {
        TeamAgentRun run = new TeamAgentRun();
        run.setId(id);
        run.setTeamId(teamId);
        run.setUserId(userId);
        run.setStatus(status);
        return run;
    }
}
