package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamAgentRunEvent;
import cn.ilink.mapper.TeamAgentRunEventMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Appends durable, ordered run events behind the worker lease fence. */
@Service
public class TeamAgentEventService {
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentRunEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public TeamAgentEventService(TeamAgentRunMapper runMapper,
                                 TeamAgentRunEventMapper eventMapper,
                                 ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public long append(Long runId, String leaseToken, String type, Map<String, Object> payload) {
        TeamAgentRun run = runMapper.selectByIdForUpdate(runId);
        requireWorker(run, leaseToken);
        long seq = nextSeq(run);
        insert(run, seq, type, payload);
        run.setVersion(nextVersion(run));
        if (runMapper.updateById(run) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.WORKER_LOST,
                "运行事件序号未能持久化");
        }
        return seq;
    }

    public long appendLocked(TeamAgentRun run, String type, Map<String, Object> payload) {
        long seq = nextSeq(run);
        insert(run, seq, type, payload);
        return seq;
    }

    public List<TeamAgentRunEvent> after(Long runId, long seq, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return eventMapper.selectList(new LambdaQueryWrapper<TeamAgentRunEvent>()
            .eq(TeamAgentRunEvent::getRunId, runId)
            .gt(TeamAgentRunEvent::getSeq, seq)
            .orderByAsc(TeamAgentRunEvent::getSeq)
            .last("LIMIT " + safeLimit));
    }

    public Map<String, Object> decode(TeamAgentRunEvent event) {
        try {
            return objectMapper.readValue(event.getPayloadJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of("message", "事件内容不可用");
        }
    }

    public String nextEventId(Long runId, long seq) {
        return runId + ":" + seq;
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public static Map<String, Object> payload(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) return Map.of();
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) payload.put(String.valueOf(keyValues[i]), value);
        }
        return payload;
    }

    @SafeVarargs
    public static List<Map<String, Object>> events(Map<String, Object>... rows) {
        return rows == null ? List.of() : List.of(rows);
    }

    public static List<TeamAgentRunEvent> immutableEvents(List<TeamAgentRunEvent> rows) {
        return rows == null ? List.of() : List.copyOf(new ArrayList<>(rows));
    }

    private void requireWorker(TeamAgentRun run, String leaseToken) {
        if (run == null || !TeamAgentRun.STATUS_RUNNING.equals(run.getStatus())
            || leaseToken == null || !Objects.equals(leaseToken, run.getWorkerLeaseToken())
            || run.getLeaseUntil() == null || !run.getLeaseUntil().after(new Date())
            || run.getCancelRequestedAt() != null) {
            throw new TeamAgentException(TeamAgentErrorCode.WORKER_LOST,
                "当前运行执行权已失效");
        }
    }

    private long nextSeq(TeamAgentRun run) {
        return (run.getEventSeq() == null ? 0L : run.getEventSeq()) + 1L;
    }

    private int nextVersion(TeamAgentRun run) {
        return (run.getVersion() == null ? 0 : run.getVersion()) + 1;
    }

    private void insert(TeamAgentRun run, long seq, String type, Map<String, Object> payload) {
        TeamAgentRunEvent event = new TeamAgentRunEvent();
        event.setTeamId(run.getTeamId());
        event.setRunId(run.getId());
        event.setSeq(seq);
        event.setEventType(type);
        event.setPayloadJson(writeJson(payload));
        event.setCreatedAt(new Date());
        eventMapper.insert(event);
        run.setEventSeq(seq);
    }
}
