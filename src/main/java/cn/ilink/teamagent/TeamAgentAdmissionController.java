package cn.ilink.teamagent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fail-closed in-process admission for the single-node graduation deployment.
 * A permit lives from QUEUED creation through a terminal transition.
 */
@Component
public class TeamAgentAdmissionController {
    private static final int GLOBAL_LIMIT = 8;
    private static final int TEAM_LIMIT = 3;
    private static final int USER_LIMIT = 2;

    private final AtomicInteger global = new AtomicInteger();
    private final Map<Long, AtomicInteger> teams = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> users = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> teamUsers = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> admittedRuns = new ConcurrentHashMap<>();

    public void acquire(Long runId, Long teamId, Long userId) {
        if (runId == null || teamId == null || userId == null) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, "运行准入参数缺失");
        }
        if (!increment(global, GLOBAL_LIMIT)) {
            throw capacity();
        }
        AtomicInteger team = teams.computeIfAbsent(teamId, ignored -> new AtomicInteger());
        if (!increment(team, TEAM_LIMIT)) {
            global.decrementAndGet();
            throw capacity();
        }
        AtomicInteger user = users.computeIfAbsent(userId, ignored -> new AtomicInteger());
        if (!increment(user, USER_LIMIT)) {
            decrement(team);
            global.decrementAndGet();
            throw capacity();
        }
        String teamUserKey = teamUserKey(teamId, userId);
        AtomicInteger teamUser = teamUsers.computeIfAbsent(teamUserKey,
            ignored -> new AtomicInteger());
        if (!increment(teamUser, 1)) {
            decrement(user);
            decrement(team);
            global.decrementAndGet();
            throw new TeamAgentException(TeamAgentErrorCode.RUN_ALREADY_ACTIVE,
                "你在此团队已有排队或运行中的请求");
        }
        admittedRuns.put(runId, new AtomicBoolean(true));
    }

    public void release(Long runId, Long teamId, Long userId) {
        if (runId == null) return;
        AtomicBoolean active = admittedRuns.remove(runId);
        if (active == null || !active.compareAndSet(true, false)) return;
        if (teamId != null) {
            decrement(teams.computeIfAbsent(teamId, ignored -> new AtomicInteger()));
            if (userId != null) {
                decrement(teamUsers.computeIfAbsent(teamUserKey(teamId, userId),
                    ignored -> new AtomicInteger()));
            }
        }
        if (userId != null) decrement(users.computeIfAbsent(userId, ignored -> new AtomicInteger()));
        decrement(global);
    }

    public int activeGlobal() {
        return global.get();
    }

    private boolean increment(AtomicInteger counter, int limit) {
        while (true) {
            int current = counter.get();
            if (current >= limit) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    private void decrement(AtomicInteger counter) {
        counter.updateAndGet(value -> Math.max(0, value - 1));
    }

    private TeamAgentException capacity() {
        return new TeamAgentException(TeamAgentErrorCode.AGENT_CAPACITY_EXCEEDED,
            "团队 Agent 当前繁忙，请稍后重试", null, 5);
    }

    private String teamUserKey(Long teamId, Long userId) {
        return teamId + ":" + userId;
    }
}
