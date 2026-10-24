package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentMessage;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Terminal transition protocol. Completion, assistant message, action group,
 * run counters and terminal events commit or roll back as one unit.
 */
@Service
public class TeamAgentRunCompletionService {
    private final TeamAgentRunMapper runMapper;
    private final TeamDemandMapper teamMapper;
    private final TeamAgentConversationMapper conversationMapper;
    private final TeamAgentConversationService conversationService;
    private final TeamAgentEventService eventService;
    private final TeamAgentActionProposalService proposalService;
    private final TeamAgentAdmissionController admission;
    private final TeamAgentAuthorization authorization;

    public TeamAgentRunCompletionService(TeamAgentRunMapper runMapper,
                                         TeamDemandMapper teamMapper,
                                         TeamAgentConversationMapper conversationMapper,
                                         TeamAgentConversationService conversationService,
                                         TeamAgentEventService eventService,
                                         TeamAgentActionProposalService proposalService,
                                         TeamAgentAdmissionController admission,
                                         TeamAgentAuthorization authorization) {
        this.runMapper = runMapper;
        this.teamMapper = teamMapper;
        this.conversationMapper = conversationMapper;
        this.conversationService = conversationService;
        this.eventService = eventService;
        this.proposalService = proposalService;
        this.admission = admission;
        this.authorization = authorization;
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(Long runId, String leaseToken,
                         TeamAgentBusinessAnswerService.Answer answer,
                         List<TeamAgentActionProposalService.EnrichedDraft> drafts) {
        TeamAgentRun snapshot = runMapper.selectById(runId);
        if (snapshot == null) return;
        if (teamMapper.selectByIdForUpdate(snapshot.getTeamId()) == null) {
            releaseAfterCommit(snapshot);
            return;
        }
        TeamAgentRun run = runMapper.selectByIdForUpdate(runId);
        requireRunningWorker(run, leaseToken);
        try {
            authorization.requireScope(run.getTeamId(), run.getUserId());
        } catch (TeamAgentException revoked) {
            terminalCancelled(run, "团队权限已变化，运行已停止");
            return;
        }
        TeamAgentConversation conversation = conversationMapper.selectByIdForUpdate(
            run.getConversationId());
        if (conversation == null
            || !TeamAgentConversation.STATUS_ACTIVE.equals(conversation.getStatus())) {
            terminalCancelled(run, "来源会话已删除");
            return;
        }

        if (run.getCancelRequestedAt() != null) {
            terminalCancelled(run, "运行已按要求停止");
            return;
        }

        String content = TeamAgentMarkdownSanitizer.sanitize(
            answer == null ? null : answer.content(), 12000);
        List<Map<String, Object>> citations = conversationService.citationsForRun(
            run, answer == null ? List.of() : answer.citations());
        Date now = new Date();

        TeamAgentMessage response = conversationService.appendMessageLocked(
            conversation, TeamAgentMessage.ROLE_ASSISTANT, null, content,
            TeamAgentMessage.STATUS_COMPLETE, run.getId(), citations);
        run.setResponseMessageId(response.getId());
        run.setStatus(TeamAgentRun.STATUS_COMPLETED);
        run.setFinishedAt(now);
        run.setElapsedMs(elapsed(run.getStartedAt(), now));
        run.setRounds(answer == null ? 0 : answer.rounds());
        run.setKnowledgeToolCalls(answer == null ? 0 : answer.toolCalls());
        run.setBusinessToolCalls(answer == null ? 0 : answer.toolCalls());
        run.setModelCallCount(answer == null ? 0 : answer.modelCalls());
        run.setErrorCode(null);
        run.setErrorMessage(null);

        List<Long> groupIds = proposalService.publishLocked(run, response.getId(),
            drafts == null ? List.of() : drafts);
        if (!groupIds.isEmpty()) {
            long executableSeq = eventService.appendLocked(run, "action.proposed",
                TeamAgentEventService.payload(
                    "actionGroupId", groupIds.get(0),
                    "actionCount", drafts.size(),
                    "expiresAt", TeamAgentTime.iso(now.toInstant().plusSeconds(600))));
            run.setEventSeq(executableSeq);
        }
        long seq = eventService.appendLocked(run, "message.delta",
            TeamAgentEventService.payload(
                "messageId", response.getId(),
                "delta", content,
                "citations", citations));
        run.setEventSeq(seq);
        seq = eventService.appendLocked(run, "run.completed",
            TeamAgentEventService.payload(
                "status", TeamAgentRun.STATUS_COMPLETED,
                "rounds", run.getRounds(),
                "knowledgeToolCalls", run.getKnowledgeToolCalls(),
                "businessToolCalls", run.getBusinessToolCalls(),
                "elapsedMs", run.getElapsedMs()));
        run.setEventSeq(seq);
        run.setVersion(next(run.getVersion()));
        if (runMapper.updateById(run) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.WORKER_LOST,
                "运行终态已被其他执行器更新");
        }
        releaseAfterCommit(run);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancelRunning(Long runId, String leaseToken, String message) {
        TeamAgentRun snapshot = runMapper.selectById(runId);
        if (snapshot == null) return;
        if (teamMapper.selectByIdForUpdate(snapshot.getTeamId()) == null) {
            releaseAfterCommit(snapshot);
            return;
        }
        TeamAgentRun run = runMapper.selectByIdForUpdate(runId);
        if (run == null || !TeamAgentRun.STATUS_RUNNING.equals(run.getStatus())) return;
        if (leaseToken != null && !Objects.equals(leaseToken, run.getWorkerLeaseToken())) return;
        terminalCancelled(run, message);
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(Long runId, String leaseToken, TeamAgentErrorCode errorCode,
                     String safeMessage) {
        TeamAgentRun run = runMapper.selectByIdForUpdate(runId);
        if (run == null || !TeamAgentRun.STATUS_RUNNING.equals(run.getStatus())) {
            return;
        }
        if (leaseToken != null && !Objects.equals(leaseToken, run.getWorkerLeaseToken())) {
            return;
        }
        if (run.getCancelRequestedAt() != null) {
            terminalCancelled(run, "运行已按要求停止");
            return;
        }
        Date now = new Date();
        run.setStatus(TeamAgentRun.STATUS_FAILED);
        run.setErrorCode(errorCode == null
            ? TeamAgentErrorCode.ACTION_TRANSACTION_FAILED.name() : errorCode.name());
        run.setErrorMessage(TeamAgentMarkdownSanitizer.sanitize(
            safeMessage == null ? "团队 Agent 运行失败" : safeMessage, 1000));
        run.setFinishedAt(now);
        run.setElapsedMs(elapsed(run.getStartedAt(), now));
        long seq = eventService.appendLocked(run, "run.failed",
            TeamAgentEventService.payload(
                "errorCode", run.getErrorCode(),
                "message", run.getErrorMessage(),
                "retryable", errorCode == null || errorCode.retryable()));
        run.setEventSeq(seq);
        run.setVersion(next(run.getVersion()));
        runMapper.updateById(run);
        releaseAfterCommit(run);
    }

    @Transactional(rollbackFor = Exception.class)
    public void failQueued(Long runId) {
        TeamAgentRun run = runMapper.selectByIdForUpdate(runId);
        if (run == null || !TeamAgentRun.STATUS_QUEUED.equals(run.getStatus())) return;
        Date now = new Date();
        run.setStatus(TeamAgentRun.STATUS_FAILED);
        run.setErrorCode(TeamAgentErrorCode.AGENT_CAPACITY_EXCEEDED.name());
        run.setErrorMessage("运行队列繁忙，请稍后使用新的请求重新发起");
        run.setFinishedAt(now);
        long seq = eventService.appendLocked(run, "run.failed",
            TeamAgentEventService.payload(
                "errorCode", run.getErrorCode(),
                "message", run.getErrorMessage(),
                "retryable", true));
        run.setEventSeq(seq);
        run.setVersion(next(run.getVersion()));
        runMapper.updateById(run);
        releaseAfterCommit(run);
    }

    private void terminalCancelled(TeamAgentRun run, String message) {
        Date now = new Date();
        run.setStatus(TeamAgentRun.STATUS_CANCELLED);
        run.setFinishedAt(now);
        run.setElapsedMs(elapsed(run.getStartedAt(), now));
        long seq = eventService.appendLocked(run, "run.cancelled",
            TeamAgentEventService.payload("message", message));
        run.setEventSeq(seq);
        run.setVersion(next(run.getVersion()));
        runMapper.updateById(run);
        releaseAfterCommit(run);
    }

    private void requireRunningWorker(TeamAgentRun run, String leaseToken) {
        if (run == null || !TeamAgentRun.STATUS_RUNNING.equals(run.getStatus())
            || leaseToken == null || !Objects.equals(leaseToken, run.getWorkerLeaseToken())
            || run.getLeaseUntil() == null || !run.getLeaseUntil().after(new Date())) {
            throw new TeamAgentException(TeamAgentErrorCode.WORKER_LOST,
                "当前运行执行权已失效");
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
