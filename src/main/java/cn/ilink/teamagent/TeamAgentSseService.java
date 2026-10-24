package cn.ilink.teamagent;

import cn.ilink.common.ControllerUtils;
import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamAgentRunEvent;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Durable SSE polling with the run-scoped {@code <runId>:<seq>} cursor.
 * Heartbeats are comments, never persisted and never carry an event id.
 */
@Service
public class TeamAgentSseService {
    private static final long POLL_MILLIS = 300;
    private static final long HEARTBEAT_MILLIS = 15000;
    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "team-agent-sse");
            thread.setDaemon(true);
            return thread;
        });
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentConversationMapper conversationMapper;
    private final TeamAgentEventService eventService;
    private final TeamAgentAuthorization authorization;

    public TeamAgentSseService(TeamAgentRunMapper runMapper,
                               TeamAgentConversationMapper conversationMapper,
                               TeamAgentEventService eventService,
                               TeamAgentAuthorization authorization) {
        this.runMapper = runMapper;
        this.conversationMapper = conversationMapper;
        this.eventService = eventService;
        this.authorization = authorization;
    }

    public SseEmitter open(Long teamId, Long userId, Long runId, long afterSeq,
                           HttpSession session) {
        TeamAgentRun run = requireRun(teamId, userId, runId);
        if (afterSeq > (run.getEventSeq() == null ? 0L : run.getEventSeq())) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "SSE 游标超过当前运行序号");
        }
        SseEmitter emitter = new SseEmitter(
            Math.max(60000L, Duration.ofMinutes(3).toMillis()));
        Cursor cursor = new Cursor(afterSeq);
        long[] lastHeartbeat = {System.currentTimeMillis()};
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                User user = ControllerUtils.requireUser(session);
                if (user == null || !Objects.equals(user.getId(), userId)) {
                    emitter.complete();
                    return;
                }
                TeamAgentScope scope = authorization.requireScope(teamId, userId);
                TeamAgentConversation conversation = conversationMapper.selectById(
                    run.getConversationId());
                if (conversation == null
                    || !TeamAgentConversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
                    emitter.complete();
                    return;
                }
                List<TeamAgentRunEvent> events = eventService.after(runId, cursor.value, 50);
                for (TeamAgentRunEvent event : events) {
                    emitter.send(SseEmitter.event()
                        .id(eventService.nextEventId(runId, event.getSeq()))
                        .name(event.getEventType())
                        .data(eventService.decode(event), MediaType.APPLICATION_JSON));
                    cursor.value = event.getSeq();
                    if (terminal(event.getEventType())) {
                        emitter.complete();
                        return;
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat[0] >= HEARTBEAT_MILLIS) {
                    lastHeartbeat[0] = now;
                    emitter.send(SseEmitter.event().comment(
                        "heartbeat " + TeamAgentTime.iso(Instant.now())));
                }
            } catch (TeamAgentException e) {
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                emitter.complete();
            } catch (RuntimeException e) {
                emitter.completeWithError(e);
            }
        }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(error -> future.cancel(true));
        return emitter;
    }

    public long parseLastEventId(Long runId, String lastEventId, String afterSeq) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            String[] parts = lastEventId.trim().split(":", 2);
            if (parts.length != 2) {
                throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                    "Last-Event-ID 格式错误");
            }
            try {
                long id = Long.parseLong(parts[0]);
                long seq = Long.parseLong(parts[1]);
                if (id != runId || seq < 0) throw new NumberFormatException();
                return seq;
            } catch (NumberFormatException e) {
                throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                    "Last-Event-ID 不属于当前运行");
            }
        }
        if (afterSeq == null || afterSeq.isBlank()) return 0L;
        try {
            long seq = Long.parseLong(afterSeq);
            if (seq < 0) throw new NumberFormatException();
            return seq;
        } catch (NumberFormatException e) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "afterSeq 必须是非负整数");
        }
    }

    private TeamAgentRun requireRun(Long teamId, Long userId, Long runId) {
        TeamAgentRun run = runId == null ? null : runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getTeamId(), teamId)
            || !Objects.equals(run.getUserId(), userId)) {
            throw new TeamAgentException(TeamAgentErrorCode.RUN_NOT_FOUND, "运行不存在");
        }
        authorization.requireScope(teamId, userId);
        return run;
    }

    private boolean terminal(String type) {
        return "run.completed".equals(type) || "run.failed".equals(type)
            || "run.cancelled".equals(type);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static final class Cursor {
        private long value;

        private Cursor(long value) {
            this.value = value;
        }
    }
}
