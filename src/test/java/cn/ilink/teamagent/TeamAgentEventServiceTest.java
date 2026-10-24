package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamAgentRunEvent;
import cn.ilink.mapper.TeamAgentRunEventMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamAgentEventServiceTest {

    @Test
    void appendPersistsEventSequenceInTheSameTransaction() {
        TeamAgentRunMapper runMapper = mock(TeamAgentRunMapper.class);
        TeamAgentRunEventMapper eventMapper = mock(TeamAgentRunEventMapper.class);
        TeamAgentRun run = runningRun();
        when(runMapper.selectByIdForUpdate(11L)).thenReturn(run);
        when(runMapper.updateById(run)).thenReturn(1);
        when(eventMapper.insert(any(TeamAgentRunEvent.class))).thenReturn(1);
        TeamAgentEventService service = new TeamAgentEventService(
            runMapper, eventMapper, new ObjectMapper());

        long seq = service.append(11L, "lease", "run.started", Map.of("runId", 11L));

        assertEquals(1L, seq);
        assertEquals(1L, run.getEventSeq());
        assertEquals(2, run.getVersion());
        InOrder order = inOrder(eventMapper, runMapper);
        order.verify(eventMapper).insert(any(TeamAgentRunEvent.class));
        order.verify(runMapper).updateById(run);
    }

    @Test
    void appendContinuesAfterPersistedEventSequence() {
        TeamAgentRunMapper runMapper = mock(TeamAgentRunMapper.class);
        TeamAgentRunEventMapper eventMapper = mock(TeamAgentRunEventMapper.class);
        TeamAgentRun run = runningRun();
        run.setEventSeq(4L);
        when(runMapper.selectByIdForUpdate(11L)).thenReturn(run);
        when(runMapper.updateById(run)).thenReturn(1);
        when(eventMapper.insert(any(TeamAgentRunEvent.class))).thenReturn(1);
        TeamAgentEventService service = new TeamAgentEventService(
            runMapper, eventMapper, new ObjectMapper());

        long seq = service.append(11L, "lease", "context.ready", Map.of());

        assertEquals(5L, seq);
        assertEquals(5L, run.getEventSeq());
        assertEquals(2, run.getVersion());
    }

    private TeamAgentRun runningRun() {
        TeamAgentRun run = new TeamAgentRun();
        run.setId(11L);
        run.setTeamId(20L);
        run.setUserId(30L);
        run.setStatus(TeamAgentRun.STATUS_RUNNING);
        run.setWorkerLeaseToken("lease");
        run.setLeaseUntil(new Date(System.currentTimeMillis() + 60_000L));
        run.setEventSeq(0L);
        run.setVersion(1);
        return run;
    }
}
