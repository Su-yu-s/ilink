package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Date;
import java.util.List;

/** Closes runs whose worker lease expired without replaying paid model work. */
@Service
public class TeamAgentRecoveryService {
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentConversationMapper conversationMapper;
    private final TeamAgentEventService eventService;
    private final TeamAgentAdmissionController admission;

    public TeamAgentRecoveryService(TeamAgentRunMapper runMapper,
                                    TeamAgentConversationMapper conversationMapper,
                                    TeamAgentEventService eventService,
                                    TeamAgentAdmissionController admission) {
        this.runMapper = runMapper;
        this.conversationMapper = conversationMapper;
        this.eventService = eventService;
        this.admission = admission;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 20000)
    @Transactional(rollbackFor = Exception.class)
    public void recoverExpiredLeases() {
        Date now = new Date();
        List<TeamAgentRun> expired = runMapper.selectList(
            new LambdaQueryWrapper<TeamAgentRun>()
                .eq(TeamAgentRun::getStatus, TeamAgentRun.STATUS_RUNNING)
                .isNotNull(TeamAgentRun::getLeaseUntil)
                .lt(TeamAgentRun::getLeaseUntil, now)
                .orderByAsc(TeamAgentRun::getId)
                .last("LIMIT 50"));
        for (TeamAgentRun candidate : expired) {
            TeamAgentRun run = runMapper.selectByIdForUpdate(candidate.getId());
            if (run == null || !TeamAgentRun.STATUS_RUNNING.equals(run.getStatus())
                || run.getLeaseUntil() == null || !run.getLeaseUntil().before(now)) {
                continue;
            }
            TeamAgentConversation conversation = conversationMapper.selectById(
                run.getConversationId());
            boolean cancelled = run.getCancelRequestedAt() != null
                || conversation == null
                || !TeamAgentConversation.STATUS_ACTIVE.equals(conversation.getStatus());
            run.setStatus(cancelled ? TeamAgentRun.STATUS_CANCELLED : TeamAgentRun.STATUS_FAILED);
            if (!cancelled) {
                run.setErrorCode(TeamAgentErrorCode.WORKER_LOST.name());
                run.setErrorMessage("执行器已中断，本次运行不会自动重放");
            }
            run.setFinishedAt(now);
            run.setElapsedMs(elapsed(run.getStartedAt(), now));
            long seq = eventService.appendLocked(run,
                cancelled ? "run.cancelled" : "run.failed",
                TeamAgentEventService.payload(
                    "errorCode", cancelled ? null : run.getErrorCode(),
                    "message", cancelled ? "运行已停止" : run.getErrorMessage(),
                    "retryable", !cancelled));
            run.setEventSeq(seq);
            run.setVersion(next(run.getVersion()));
            runMapper.updateById(run);
            releaseAfterCommit(run);
        }
    }

    private void releaseAfterCommit(TeamAgentRun run) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        admission.release(run.getId(), run.getTeamId(), run.getUserId());
                    }
                });
        } else {
            admission.release(run.getId(), run.getTeamId(), run.getUserId());
        }
    }

    private long elapsed(Date started, Date finished) {
        if (started == null || finished == null) return 0L;
        return Math.max(0L, Duration.between(started.toInstant(), finished.toInstant()).toMillis());
    }

    private int next(Integer value) {
        return value == null ? 1 : value + 1;
    }
}
