package cn.ilink.teamagent;

import cn.ilink.config.AiProperties;
import cn.ilink.entity.TeamAgentConversation;
import cn.ilink.entity.TeamAgentMessage;
import cn.ilink.entity.TeamAgentRun;
import cn.ilink.entity.TeamAgentRunEvent;
import cn.ilink.entity.TeamTask;
import cn.ilink.mapper.TeamAgentActionGroupMapper;
import cn.ilink.mapper.TeamAgentConversationMapper;
import cn.ilink.mapper.TeamAgentMessageMapper;
import cn.ilink.mapper.TeamAgentRunMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class TeamAgentRunService {
    public static final String PROMPT_VERSION = "team-agent-workbench-v1";
    private static final Set<String> MODES = Set.of(
        "AUTO", "KNOWLEDGE_QUICK", "KNOWLEDGE_DEEP", "TEAM_INSIGHT", "ACTION_PLAN");
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?is)(password|passwd|密码|api[_ -]?key|secret|token|authorization|bearer\\s+|cookie)"
            + "\\s*[:=：]\\s*\\S+");

    private final TeamAgentAuthorization authorization;
    private final TeamAgentConversationService conversationService;
    private final TeamAgentConversationMapper conversationMapper;
    private final TeamAgentMessageMapper messageMapper;
    private final TeamAgentRunMapper runMapper;
    private final TeamAgentActionGroupMapper actionGroupMapper;
    private final TeamTaskMapper taskMapper;
    private final TeamDemandMapper teamMapper;
    private final TeamTaskPolicy taskPolicy;
    private final TeamAgentEventService eventService;
    private final TeamAgentAdmissionController admission;
    private final TeamAgentRunExecutor executor;
    private final TeamAgentIntentRouter intentRouter;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public TeamAgentRunService(TeamAgentAuthorization authorization,
                               TeamAgentConversationService conversationService,
                               TeamAgentConversationMapper conversationMapper,
                               TeamAgentMessageMapper messageMapper,
                               TeamAgentRunMapper runMapper,
                               TeamAgentActionGroupMapper actionGroupMapper,
                               TeamDemandMapper teamMapper,
                               TeamTaskMapper taskMapper,
                               TeamTaskPolicy taskPolicy,
                               TeamAgentEventService eventService,
                               TeamAgentAdmissionController admission,
                               TeamAgentRunExecutor executor,
                               TeamAgentIntentRouter intentRouter,
                               AiProperties aiProperties,
                               ObjectMapper objectMapper) {
        this.authorization = authorization;
        this.conversationService = conversationService;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
        this.actionGroupMapper = actionGroupMapper;
        this.teamMapper = teamMapper;
        this.taskMapper = taskMapper;
        this.taskPolicy = taskPolicy;
        this.eventService = eventService;
        this.admission = admission;
        this.executor = executor;
        this.intentRouter = intentRouter;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Long teamId, Long userId,
                                      TeamAgentRequests.CreateRun request) {
        TeamAgentScope scope = authorization.requireScope(teamId, userId);
        validateRequest(request);
        String clientRequestId = TeamAgentConversationService.requireUuid(
            request.clientRequestId(), "clientRequestId");
        TeamAgentConversation conversation = conversationService.lockOwnedConversation(
            teamId, userId, request.conversationId());

        String text = normalizeMessage(request.message().text());
        String mode = normalizeMode(request.message().mode());
        TeamAgentIntentRouter.Route route = intentRouter.route(text, mode, scope);
        List<Long> focusIds = normalizeFocus(request.focus());
        validateFocus(scope, focusIds);
        String inputHash = inputHash(conversation.getId(), text, mode, focusIds);

        TeamAgentRun existing = runMapper.selectOne(new LambdaQueryWrapper<TeamAgentRun>()
            .eq(TeamAgentRun::getTeamId, teamId)
            .eq(TeamAgentRun::getUserId, userId)
            .eq(TeamAgentRun::getClientRequestId, clientRequestId)
            .last("LIMIT 1"));
        if (existing != null) {
            if (!Objects.equals(existing.getInputHash(), inputHash)) {
                throw new TeamAgentException(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "同一请求标识不能用于不同运行内容");
            }
            return runView(existing);
        }
        if ((route == TeamAgentIntentRouter.Route.KNOWLEDGE_QUICK
            || route == TeamAgentIntentRouter.Route.KNOWLEDGE_DEEP
            || route == TeamAgentIntentRouter.Route.ACTION_PLAN
            || route == TeamAgentIntentRouter.Route.COLLAB_DOCUMENT
            || route == TeamAgentIntentRouter.Route.COLLAB_TABLE)
            && !aiProperties.isConfigured()) {
            throw new TeamAgentException(TeamAgentErrorCode.AI_NOT_CONFIGURED,
                "AI 服务尚未配置，知识问答与动作规划暂不可用");
        }

        TeamAgentRun active = activeRun(teamId, userId, conversation.getId());
        if (active != null) {
            throw new TeamAgentException(TeamAgentErrorCode.RUN_ALREADY_ACTIVE,
                "该会话已有排队或运行中的请求", null, 3);
        }

        TeamAgentMessage requestMessage = conversationService.appendMessageLocked(
            conversation, TeamAgentMessage.ROLE_USER, userId, text,
            TeamAgentMessage.STATUS_COMPLETE, null, List.of());
        Date now = new Date();
        TeamAgentRun run = new TeamAgentRun();
        run.setTeamId(teamId);
        run.setConversationId(conversation.getId());
        run.setUserId(userId);
        run.setRequestMessageId(requestMessage.getId());
        run.setClientRequestId(clientRequestId);
        run.setRequestedMode(mode);
        run.setStatus(TeamAgentRun.STATUS_QUEUED);
        run.setInputHash(inputHash);
        run.setContextSnapshotJson(contextSnapshot(scope, focusIds));
        run.setPromptVersion(PROMPT_VERSION);
        run.setRounds(0);
        run.setKnowledgeToolCalls(0);
        run.setBusinessToolCalls(0);
        run.setModelCallCount(0);
        run.setEventSeq(0L);
        run.setElapsedMs(0L);
        run.setCreatedAt(now);
        run.setVersion(0);
        try {
            if (runMapper.insert(run) != 1) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                    "运行创建失败");
            }
        } catch (DuplicateKeyException race) {
            TeamAgentRun raced = runMapper.selectOne(new LambdaQueryWrapper<TeamAgentRun>()
                .eq(TeamAgentRun::getTeamId, teamId)
                .eq(TeamAgentRun::getUserId, userId)
                .eq(TeamAgentRun::getClientRequestId, clientRequestId)
                .last("LIMIT 1"));
            if (raced != null && Objects.equals(raced.getInputHash(), inputHash)) {
                return runView(raced);
            }
            throw new TeamAgentException(TeamAgentErrorCode.IDEMPOTENCY_KEY_REUSED,
                "运行创建请求发生冲突，请刷新后重试");
        }
        requestMessage.setRunId(run.getId());
        requestMessage.setVersion(1);
        if (messageMapper.updateById(requestMessage) != 1) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_TRANSACTION_FAILED,
                "用户消息与运行关联失败");
        }
        try {
            admission.acquire(run.getId(), teamId, userId);
            releaseAdmissionOnRollback(run);
        } catch (RuntimeException e) {
            throw e;
        }
        afterCommit(() -> executor.submit(run.getId()));
        return runView(run);
    }

    public Map<String, Object> get(Long teamId, Long userId, Long runId) {
        TeamAgentRun run = requireOwnedRun(teamId, userId, runId);
        return runView(run);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(Long teamId, Long userId, Long runId,
                                      TeamAgentRequests.CancelRun request) {
        authorization.requireScope(teamId, userId);
        if (request == null) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID, "请求体不能为空");
        }
        String cancelRequestId = TeamAgentConversationService.requireUuid(
            request.clientRequestId(), "clientRequestId");
        TeamAgentRun run = requireOwnedRunForUpdate(teamId, userId, runId);
        if (TeamAgentRun.STATUS_CANCELLED.equals(run.getStatus())) {
            return runView(run);
        }
        if (TeamAgentRun.STATUS_QUEUED.equals(run.getStatus())) {
            Date now = new Date();
            run.setStatus(TeamAgentRun.STATUS_CANCELLED);
            run.setCancelRequestId(cancelRequestId);
            run.setCancelRequestedAt(now);
            run.setFinishedAt(now);
            run.setElapsedMs(elapsed(run.getStartedAt(), now));
            long seq = eventService.appendLocked(run, "run.cancelled",
                TeamAgentEventService.payload("message", "运行已取消"));
            run.setEventSeq(seq);
            run.setVersion(next(run.getVersion()));
            runMapper.updateById(run);
            releaseAfterCommit(run);
            return runView(run);
        }
        if (!TeamAgentRun.STATUS_RUNNING.equals(run.getStatus())) {
            throw new TeamAgentException(TeamAgentErrorCode.RUN_NOT_CANCELLABLE,
                "运行已经结束，无法取消");
        }
        if (run.getCancelRequestedAt() == null) {
            run.setCancelRequestId(cancelRequestId);
            run.setCancelRequestedAt(new Date());
            run.setVersion(next(run.getVersion()));
            runMapper.updateById(run);
        }
        return runView(run);
    }

    public TeamAgentRun requireOwnedRun(Long teamId, Long userId, Long runId) {
        TeamAgentRun run = runId == null ? null : runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getTeamId(), teamId)
            || !Objects.equals(run.getUserId(), userId)) {
            throw new TeamAgentException(TeamAgentErrorCode.RUN_NOT_FOUND, "运行不存在");
        }
        return run;
    }

    public TeamAgentRun requireOwnedRunForUpdate(Long teamId, Long userId, Long runId) {
        TeamAgentRun run = runId == null ? null : runMapper.selectByIdForUpdate(runId);
        if (run == null || !Objects.equals(run.getTeamId(), teamId)
            || !Objects.equals(run.getUserId(), userId)) {
            throw new TeamAgentException(TeamAgentErrorCode.RUN_NOT_FOUND, "运行不存在");
        }
        return run;
    }

    public Map<String, Object> runView(TeamAgentRun run) {
        List<Long> groupIds = actionGroupMapper.selectList(
            new LambdaQueryWrapper<cn.ilink.entity.TeamAgentActionGroup>()
                .eq(cn.ilink.entity.TeamAgentActionGroup::getRunId, run.getId())
                .orderByAsc(cn.ilink.entity.TeamAgentActionGroup::getId))
            .stream().map(cn.ilink.entity.TeamAgentActionGroup::getId).toList();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runId", run.getId());
        row.put("conversationId", run.getConversationId());
        row.put("requestMessageId", run.getRequestMessageId());
        row.put("responseMessageId", run.getResponseMessageId());
        row.put("requestedMode", run.getRequestedMode());
        row.put("resolvedRoute", run.getResolvedRoute());
        row.put("status", run.getStatus());
        row.put("actionGroupIds", groupIds);
        row.put("rounds", run.getRounds());
        row.put("knowledgeToolCalls", run.getKnowledgeToolCalls());
        row.put("businessToolCalls", run.getBusinessToolCalls());
        row.put("elapsedMs", run.getElapsedMs());
        row.put("errorCode", run.getErrorCode());
        row.put("cancelRequested", run.getCancelRequestedAt() != null);
        row.put("createdAt", TeamAgentTime.iso(run.getCreatedAt()));
        row.put("finishedAt", TeamAgentTime.iso(run.getFinishedAt()));
        row.put("eventsUrl", "/api/team/" + run.getTeamId() + "/agent/runs/"
            + run.getId() + "/events");
        return row;
    }

    public void releaseAfterCommit(TeamAgentRun run) {
        afterCommit(() -> admission.release(run.getId(), run.getTeamId(), run.getUserId()));
    }

    private void validateRequest(TeamAgentRequests.CreateRun request) {
        if (request == null || request.conversationId() == null
            || request.message() == null) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "会话与消息内容必填");
        }
    }

    private String normalizeMessage(String raw) {
        String text = TeamAgentHashes.normalizeText(raw);
        text = text == null ? "" : text.trim();
        if (text.isEmpty() || text.length() > 500) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "消息长度需为 1-500 字符");
        }
        if (CREDENTIAL.matcher(text).find()) {
            throw new TeamAgentException(TeamAgentErrorCode.SENSITIVE_INPUT_REJECTED,
                "消息中不能包含密码、令牌或其他凭据");
        }
        return text;
    }

    private String normalizeMode(String raw) {
        String mode = raw == null ? "AUTO" : raw.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "消息模式不合法");
        }
        return mode;
    }

    private List<Long> normalizeFocus(TeamAgentRequests.RunFocus focus) {
        if (focus == null || focus.taskIds().isEmpty()) return List.of();
        return focus.taskIds().stream().filter(Objects::nonNull).distinct().sorted()
            .toList();
    }

    private void validateFocus(TeamAgentScope scope, List<Long> ids) {
        if (ids.size() > 10) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "聚焦任务最多 10 个");
        }
        if (ids.isEmpty()) return;
        List<TeamTask> tasks = taskMapper.selectBatchIds(ids);
        if (tasks.size() != ids.size()) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                "存在不属于当前团队或不可见的聚焦任务");
        }
        cn.ilink.entity.TeamDemand team = teamMapper.selectById(scope.teamId());
        if (team == null) {
            throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                "当前团队不存在");
        }
        cn.ilink.entity.User user = new cn.ilink.entity.User();
        user.setId(scope.userId());
        for (TeamTask task : tasks) {
            if (!Objects.equals(task.getTeamId(), scope.teamId())
                || !taskPolicy.canReadTask(team, task, user)) {
                throw new TeamAgentException(TeamAgentErrorCode.ACTION_SELECTION_INVALID,
                    "聚焦任务超出读取范围");
            }
        }
    }

    private String inputHash(Long conversationId, String text, String mode, List<Long> focus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("text", text);
        payload.put("mode", mode);
        payload.put("taskIds", focus);
        return TeamAgentHashes.structureHash("run-input", payload);
    }

    private TeamAgentRun activeRun(Long teamId, Long userId, Long conversationId) {
        return runMapper.selectOne(new LambdaQueryWrapper<TeamAgentRun>()
            .eq(TeamAgentRun::getTeamId, teamId)
            .eq(TeamAgentRun::getUserId, userId)
            .eq(TeamAgentRun::getConversationId, conversationId)
            .in(TeamAgentRun::getStatus,
                List.of(TeamAgentRun.STATUS_QUEUED, TeamAgentRun.STATUS_RUNNING))
            .orderByDesc(TeamAgentRun::getId)
            .last("LIMIT 1"));
    }

    private String contextSnapshot(TeamAgentScope scope, List<Long> focus) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("teamId", scope.teamId());
        context.put("competitionId", scope.competitionId());
        context.put("catalogCompetitionName", scope.catalogCompetitionName());
        context.put("customCompetition", scope.customCompetition());
        context.put("role", scope.role().name());
        context.put("taskReadMode", scope.taskReadMode().name());
        context.put("privateGraphAllowed", false);
        context.put("focusTaskIds", focus);
        context.put("generatedAt", TeamAgentTime.iso(scope.generatedAt()));
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "运行上下文无法保存");
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
        } else {
            action.run();
        }
    }

    private void releaseAdmissionOnRollback(TeamAgentRun run) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        admission.release(run.getId(), run.getTeamId(), run.getUserId());
                    }
                }
            });
    }

    private long elapsed(Date started, Date finished) {
        if (started == null || finished == null) return 0L;
        return Math.max(0L, Duration.between(started.toInstant(), finished.toInstant()).toMillis());
    }

    private int next(Integer value) {
        return value == null ? 1 : value + 1;
    }
}
