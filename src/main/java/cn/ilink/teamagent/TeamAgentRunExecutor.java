package cn.ilink.teamagent;

import cn.ilink.entity.TeamAgentMessage;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.mapper.TeamAgentMessageMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.service.kg.AgenticGraphRagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Service
public class TeamAgentRunExecutor {
    private static final Logger log = LoggerFactory.getLogger(TeamAgentRunExecutor.class);
    private final ThreadPoolExecutor workerPool;
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentMessageMapper messageMapper;
    private final TeamAgentAuthorization authorization;
    private final TeamAgentIntentRouter intentRouter;
    private final TeamAgentBusinessAnswerService answerService;
    private final TeamAgentActionProposalService proposalService;
    private final TeamAgentEventService eventService;
    private final TeamAgentRunCompletionService completionService;
    private final TeamAgentAdmissionController admission;
    private final TeamAgentProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    public TeamAgentRunExecutor(@Qualifier("teamAgentExecutor") ThreadPoolExecutor workerPool,
                                TeamAgentRunMapper runMapper,
                                TeamAgentMessageMapper messageMapper,
                                TeamAgentAuthorization authorization,
                                TeamAgentIntentRouter intentRouter,
                                TeamAgentBusinessAnswerService answerService,
                                TeamAgentActionProposalService proposalService,
                                TeamAgentEventService eventService,
                                TeamAgentRunCompletionService completionService,
                                TeamAgentAdmissionController admission,
                                TeamAgentProperties properties) {
        this.workerPool = workerPool;
        this.runMapper = runMapper;
        this.messageMapper = messageMapper;
        this.authorization = authorization;
        this.intentRouter = intentRouter;
        this.answerService = answerService;
        this.proposalService = proposalService;
        this.eventService = eventService;
        this.completionService = completionService;
        this.admission = admission;
        this.properties = properties;
    }

    public void submit(Long runId) {
        try {
            workerPool.execute(() -> execute(runId));
        } catch (RejectedExecutionException e) {
            log.warn("团队 Agent 队列已满，运行 {} 失败收口", runId);
            completionService.failQueued(runId);
        }
    }

    void execute(Long runId) {
        TeamAgentRun run = runMapper.selectById(runId);
        if (run == null || !TeamAgentRun.STATUS_QUEUED.equals(run.getStatus())) return;
        TeamAgentMessage request = messageMapper.selectById(run.getRequestMessageId());
        if (request == null) {
            completionService.failQueued(runId);
            return;
        }
        TeamAgentIntentRouter.Route route = intentRouter.route(
            request.getContent(), run.getRequestedMode(), null);
        String leaseToken = newToken();
        Date leaseUntil = new Date(System.currentTimeMillis() + properties.getLeaseSeconds() * 1000L);
        int claimed = runMapper.claimQueued(runId, workerId, leaseToken, leaseUntil,
            route.name(), new Date());
        if (claimed != 1) return;
        run = runMapper.selectById(runId);

        try {
            TeamAgentScope scope = authorization.requireScope(run.getTeamId(), run.getUserId());
            if (route == TeamAgentIntentRouter.Route.ACTION_PLAN && !scope.actionsEnabled()) {
                completionService.cancelRunning(runId, leaseToken,
                    "团队已结束，动作型请求已停止");
                return;
            }
            // Collaboration proposals follow the collaboration write rule: every
            // active member may propose while the team is TEAMING, and nothing is
            // written before the user confirms.
            if (intentRouter.usesCollaborationAssets(route)
                && route != TeamAgentIntentRouter.Route.MATERIAL_REVIEW
                && !scope.collaborationActionsEnabled()) {
                completionService.cancelRunning(runId, leaseToken,
                    "团队当前为只读状态，协作资产提案已停止");
                return;
            }
            append(runId, leaseToken, "run.started", TeamAgentEventService.payload(
                "runId", runId,
                "requestedMode", run.getRequestedMode(),
                "startedAt", TeamAgentTime.iso(run.getStartedAt())));
            append(runId, leaseToken, "context.ready", TeamAgentEventService.payload(
                "teamId", scope.teamId(),
                "teamName", scope.teamName(),
                "role", scope.role().name(),
                "taskReadMode", scope.taskReadMode().name(),
                "competitionId", scope.competitionId(),
                "customCompetition", scope.customCompetition(),
                "privateGraphAllowed", false));
            append(runId, leaseToken, "progress", TeamAgentEventService.payload(
                "stage", route.name(),
                "safeText", routeLabel(route)));

            AgenticGraphRagService.AgenticStreamListener listener =
                new AgenticGraphRagService.AgenticStreamListener() {
                    @Override
                    public void onThought(int round, String text) {
                        // Hidden reasoning is intentionally never persisted or shown.
                    }

                    @Override
                    public void onAction(int round, String tool, String paramsJson) {
                        requireStillExecutable(runId, leaseToken);
                        append(runId, leaseToken, "tool.started",
                            TeamAgentEventService.payload(
                                "callId", "round-" + round + "-" + safeTool(tool),
                                "tool", safeTool(tool),
                                "displayName", "知识检索",
                                "scope", "CURRENT_TEAM"));
                    }

                    @Override
                    public void onObservation(int round, String text) {
                        requireStillExecutable(runId, leaseToken);
                        append(runId, leaseToken, "progress",
                            TeamAgentEventService.payload(
                                "stage", "EVIDENCE_CHECK",
                                "safeText", "已完成第 " + round + " 轮证据核验"));
                    }

                    @Override
                    public void onFinal(String answer, Object citations, Object trace) {
                        // The durable terminal transaction persists the complete answer.
                    }
                };

            TeamAgentBusinessAnswerService.Answer answer = answerService.answer(
                route, request.getContent(), scope, runId, run.getConversationId(), listener);
            // Authorization is rebuilt after the longest-running external work.
            requireStillExecutable(runId, leaseToken);
            TeamAgentScope freshScope = authorization.requireScope(run.getTeamId(), run.getUserId());
            List<TeamAgentActionProposalService.EnrichedDraft> drafts =
                proposalService.prepare(freshScope, answer.actionDrafts());
            completionService.complete(runId, leaseToken, answer, drafts);
        } catch (TeamAgentException e) {
            log.info("团队 Agent 运行 {} 业务失败: {}", runId, e.getErrorCode());
            completionService.fail(runId, leaseToken, e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("团队 Agent 运行 {} 异常", runId, e);
            completionService.fail(runId, leaseToken,
                TeamAgentErrorCode.AI_UPSTREAM_FAILED,
                "团队 Agent 暂时无法完成本次请求，请稍后重试");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverActiveOnStartup() {
        if (!properties.isEnabled()) return;
        List<TeamAgentRun> active = runMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TeamAgentRun>()
                .in(TeamAgentRun::getStatus,
                    List.of(TeamAgentRun.STATUS_QUEUED, TeamAgentRun.STATUS_RUNNING))
                .orderByAsc(TeamAgentRun::getId)
                .last("LIMIT 200"));
        for (TeamAgentRun run : active) {
            try {
                // Rebuild the in-process permit table from durable active rows.
                // A restart must not silently reset per-user/team capacity.
                admission.acquire(run.getId(), run.getTeamId(), run.getUserId());
            } catch (TeamAgentException rejected) {
                log.error("启动恢复无法重建运行 {} 的准入名额: {}",
                    run.getId(), rejected.getErrorCode());
                if (TeamAgentRun.STATUS_QUEUED.equals(run.getStatus())) {
                    completionService.failQueued(run.getId());
                }
                continue;
            }
            if (TeamAgentRun.STATUS_QUEUED.equals(run.getStatus())) {
                submit(run.getId());
            }
        }
    }

    private void append(Long runId, String leaseToken, String type,
                        Map<String, Object> payload) {
        eventService.append(runId, leaseToken, type, payload);
    }

    private void requireStillExecutable(Long runId, String leaseToken) {
        TeamAgentRun current = runMapper.selectById(runId);
        if (current == null || !TeamAgentRun.STATUS_RUNNING.equals(current.getStatus())
            || !Objects.equals(leaseToken, current.getWorkerLeaseToken())
            || current.getCancelRequestedAt() != null) {
            throw new TeamAgentException(TeamAgentErrorCode.WORKER_LOST,
                "当前运行已停止");
        }
        authorization.requireScope(current.getTeamId(), current.getUserId());
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String safeTool(String tool) {
        if (tool == null || tool.isBlank()) return "knowledge_search";
        String safe = tool.replaceAll("[^A-Za-z0-9_.-]", "");
        return safe.isBlank() ? "knowledge_search" : safe.substring(0, Math.min(40, safe.length()));
    }

    private String routeLabel(TeamAgentIntentRouter.Route route) {
        return switch (route) {
            case KNOWLEDGE_QUICK -> "正在检索当前团队可访问的知识";
            case KNOWLEDGE_DEEP -> "正在执行多轮 Agentic GraphRAG";
            case TEAM_METRICS -> "正在汇总结构化任务事实";
            case TEAM_RISK -> "正在巡检任务风险";
            case WEEKLY_DRAFT -> "正在生成可确认的周报草稿";
            case ACTION_PLAN -> "正在生成受控任务动作提案";
            case COLLAB_DOCUMENT -> "正在起草可确认的协作文档提案";
            case COLLAB_TABLE -> "正在生成可确认的协作表格提案";
            case MATERIAL_REVIEW -> "正在核对竞赛材料矩阵";
        };
    }
}
