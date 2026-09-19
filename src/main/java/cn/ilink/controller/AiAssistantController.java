package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.config.AiProperties;
import cn.ilink.entity.Competition;import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.UserService;
import cn.ilink.service.ai.AiAssistantService;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.ai.AiUsageService;
import cn.ilink.service.impl.CompetitionServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import javax.servlet.http.HttpSession;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 团队空间 AI 助手。
 * 数据出站原则：仅发送用户明确预览过的任务字段 / 用户问题 / 竞赛目录公开信息，
 * 群聊内容与成员个人信息绝不外发；周报为纯本地聚合，不调用 AI。
 */
@Controller
@RequestMapping("/api")
@Slf4j
public class AiAssistantController {

    private static final String ACTION_TASK_BREAKDOWN = "TASK_BREAKDOWN";
    private static final String ACTION_COMPETITION_QA = "COMPETITION_QA";

    /** SSE 流超时：答疑本身 60 秒量级，留一倍余量 */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * SSE 推送线程池。答疑是长阻塞调用，必须与容器线程解耦；
     * 用有界池而不是 cached，避免并发提问时把外部 AI 连接打爆。
     */
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "ai-qa-sse");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    void shutdownSseExecutor() {
        sseExecutor.shutdownNow();
    }

    @Autowired
    private AiAssistantService aiAssistantService;

    @Autowired
    private AiUsageService aiUsageService;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private TeamDemandServiceImpl teamDemandService;

    @Autowired
    private TeamApplicationServiceImpl teamApplicationService;

    @Autowired
    private TeamTaskService teamTaskService;

    @Autowired
    private CompetitionServiceImpl competitionService;

    @Autowired
    private UserService userService;

    /**
     * 任务拆解：把一个任务拆为子任务建议（人确认后才落库）。
     */
    @PostMapping("/team/{teamId}/ai/task-breakdown")
    @ResponseBody
    public ResponseEntity<Result<?>> breakdownTask(@PathVariable Long teamId,
                                                   @RequestBody Map<String, Object> payload,
                                                   HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        Long taskId = payload.get("taskId") == null ? null : Long.valueOf(String.valueOf(payload.get("taskId")));
        if (taskId == null) {
            return Result.badRequest("缺少任务ID").toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound("团队不存在").toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null || !Objects.equals(task.getTeamId(), teamId)) {
            return Result.notFound("任务不存在").toResponseEntity();
        }

        ResponseEntity<Result<?>> guard = aiGuard();
        if (guard != null) {
            return guard;
        }

        try {
            Competition competition = findTeamCompetition(team);
            List<Map<String, Object>> subtasks = aiAssistantService.breakdownTask(task, competition);
            aiUsageService.record(user.getId(), teamId, ACTION_TASK_BREAKDOWN, null, null, true);
            return Result.ok("拆解完成", subtasks).toResponseEntity();
        } catch (AiClient.AiUnavailableException e) {
            aiUsageService.record(user.getId(), teamId, ACTION_TASK_BREAKDOWN, null, null, false);
            log.warn("任务拆解失败: {}", e.getMessage());
            return Result.fail(502, "AI 服务暂时不可用，请稍后重试").toResponseEntity();
        }
    }

    /**
     * 竞赛答疑：基于竞赛目录公开信息回答问题。
     */
    @PostMapping("/ai/competition-qa")
    @ResponseBody
    public ResponseEntity<Result<?>> competitionQa(@RequestBody Map<String, Object> payload,
                                                   HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        String question = payload.get("question") == null ? "" : String.valueOf(payload.get("question")).trim();
        if (question.isEmpty()) {
            return Result.badRequest("问题不能为空").toResponseEntity();
        }
        if (question.length() > 500) {
            return Result.badRequest("问题过长，请精简到 500 字以内").toResponseEntity();
        }
        // 竞赛选填：留空表示通用提问，靠联网搜索 + 模型知识作答
        Long competitionId = parseOptionalCompetitionId(payload.get("competitionId"));
        Competition competition = competitionId == null ? null : competitionService.getById(competitionId);
        if (competitionId != null && competition == null) {
            return Result.badRequest("请选择有效的竞赛").toResponseEntity();
        }

        ResponseEntity<Result<?>> guard = aiGuard();
        if (guard != null) {
            return guard;
        }

        try {
            AiAssistantService.QaAnswer qa = aiAssistantService.answerCompetitionQuestion(question, competition);
            aiUsageService.record(user.getId(), null, ACTION_COMPETITION_QA, null, null, true);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("answer", qa.answer);
            // 供前端「已思考」折叠区渲染：思考过程、检索到的网页、总耗时
            if (qa.reasoning != null && !qa.reasoning.isEmpty()) {
                data.put("reasoning", qa.reasoning);
            }
            data.put("sources", qa.sources);
            data.put("elapsedMs", qa.elapsedMs);
            if (competition != null) {
                data.put("competitionName", competition.getName());
            }
            return Result.ok("回答完成", data).toResponseEntity();
        } catch (AiClient.AiUnavailableException e) {
            aiUsageService.record(user.getId(), null, ACTION_COMPETITION_QA, null, null, false);
            log.warn("竞赛答疑失败: {}", e.getMessage());
            return Result.fail(502, "AI 服务暂时不可用，请稍后重试").toResponseEntity();
        }
    }

    /**
     * 竞赛 ID 选填：null / 空串 / "null" / "undefined" 都当作「未绑定竞赛」；
     * 非数字则视为非法参数（由 GlobalExceptionHandler 统一转 400）。
     */
    private Long parseOptionalCompetitionId(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "undefined".equalsIgnoreCase(text)) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("竞赛参数无效");
        }
    }

    /**
     * 团队周报：纯本地聚合（任务状态、逾期、本周完成、临近截止），不调用 AI、零数据出站。
     */
    @GetMapping("/team/{teamId}/weekly-report")
    @ResponseBody
    public ResponseEntity<Result<?>> weeklyReport(@PathVariable Long teamId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return Result.notFound("团队不存在").toResponseEntity();
        }
        if (!isTeamParticipant(team, user)) {
            return Result.forbidden().toResponseEntity();
        }

        List<TeamTask> tasks = teamTaskService.list(
            new LambdaQueryWrapper<TeamTask>().eq(TeamTask::getTeamId, teamId));
        Date now = new Date();
        Date weekAgo = addDays(now, -7);
        Date weekLater = addDays(now, 7);

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("pending", tasks.stream().filter(t -> "PENDING".equals(t.getStatus())).count());
        counts.put("inProgress", tasks.stream().filter(t -> "IN_PROGRESS".equals(t.getStatus())).count());
        counts.put("review", tasks.stream().filter(t -> "REVIEW".equals(t.getStatus())).count());
        counts.put("completed", tasks.stream().filter(t -> "COMPLETED".equals(t.getStatus())).count());
        counts.put("cancelled", tasks.stream().filter(t -> "CANCELLED".equals(t.getStatus())).count());
        counts.put("total", (long) tasks.size());

        List<Map<String, Object>> overdue = new ArrayList<>();
        List<Map<String, Object>> completedThisWeek = new ArrayList<>();
        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (TeamTask task : tasks) {
            boolean closed = "COMPLETED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus());
            if (!closed && task.getDeadline() != null && task.getDeadline().before(now)) {
                overdue.add(taskSummary(task));
            }
            if ("COMPLETED".equals(task.getStatus())
                && task.getCompletedAt() != null && !task.getCompletedAt().before(weekAgo)) {
                completedThisWeek.add(taskSummary(task));
            }
            if (!closed && task.getDeadline() != null
                && !task.getDeadline().before(now) && !task.getDeadline().after(weekLater)) {
                upcoming.add(taskSummary(task));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(now));
        data.put("teamTitle", team.getTitle());
        Competition competition = findTeamCompetition(team);
        data.put("competitionName", competition == null ? null : competition.getName());
        data.put("counts", counts);
        data.put("overdue", overdue);
        data.put("completedThisWeek", completedThisWeek);
        data.put("upcomingDeadlines", upcoming);
        return Result.ok("生成成功", data).toResponseEntity();
    }

    /**
     * 竞赛答疑（SSE 流式）：思考过程与正文边生成边推送，前端据此实现「边思考边输出」。
     *
     * <p>校验失败不抛异常给前端猜，而是开一条立刻结束的流，用 {@code error} 事件带回 code/message，
     * 前端对「JSON 错误」与「SSE 错误」走同一套渲染分支。
     */
    @PostMapping(value = "/ai/competition-qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter competitionQaStream(@RequestBody Map<String, Object> payload, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return failedStream(401, "未登录或登录已过期");
        }
        String question = payload.get("question") == null ? "" : String.valueOf(payload.get("question")).trim();
        if (question.isEmpty()) {
            return failedStream(400, "问题不能为空");
        }
        if (question.length() > 500) {
            return failedStream(400, "问题过长，请精简到 500 字以内");
        }
        Long competitionId;
        try {
            competitionId = parseOptionalCompetitionId(payload.get("competitionId"));
        } catch (IllegalArgumentException e) {
            return failedStream(400, "竞赛参数无效");
        }
        Competition competition = competitionId == null ? null : competitionService.getById(competitionId);
        if (competitionId != null && competition == null) {
            return failedStream(400, "请选择有效的竞赛");
        }
        ResponseEntity<Result<?>> guard = aiGuard();
        if (guard != null) {
            Result<?> body = guard.getBody();
            return failedStream(body == null ? 503 : body.getCode(),
                body == null ? "AI 服务暂时不可用" : body.getMessage());
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 答疑是几十秒级的长阻塞调用，必须离开容器线程，否则并发几个问题就把 Tomcat 线程占满
        sseExecutor.execute(() -> runQaStream(emitter, user, question, competition));
        return emitter;
    }

    /** 推送阶段：reasoning/answer 增量 → done。任何失败都补一个 error 事件后收流。 */
    private void runQaStream(SseEmitter emitter, User user, String question, Competition competition) {
        // 已经吐出去的内容不会因为失败而回滚，错误措辞要据此区分「一个字都没有」和「说到一半断了」
        boolean[] streamedAnything = {false};
        try {
            AiAssistantService.QaAnswer qa =
                aiAssistantService.answerCompetitionQuestion(question, competition, new AiClient.StreamListener() {
                    @Override
                    public void onReasoning(String delta) {
                        streamedAnything[0] = true;
                        sendEvent(emitter, "reasoning", delta);
                    }

                    @Override
                    public void onContent(String delta) {
                        streamedAnything[0] = true;
                        sendEvent(emitter, "answer", delta);
                    }
                });
            aiUsageService.record(user.getId(), null, ACTION_COMPETITION_QA, null, null, true);
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("elapsedMs", qa.elapsedMs);
            done.put("sources", qa.sources);
            sendEvent(emitter, "done", done);
            emitter.complete();
        } catch (AiClient.AiUnavailableException e) {
            aiUsageService.record(user.getId(), null, ACTION_COMPETITION_QA, null, null, false);
            log.warn("竞赛答疑（流式）失败: {}", e.getMessage());
            sendEvent(emitter, "error", errorPayload(502, streamedAnything[0]
                ? "回答被中断，以上内容可能不完整。可以再问一次或换个说法。"
                : "AI 服务暂时不可用，请稍后重试"));
            emitter.complete();
        } catch (Exception e) {
            log.warn("竞赛答疑（流式）异常: {}", e.getMessage());
            sendEvent(emitter, "error", errorPayload(500, streamedAnything[0]
                ? "回答被中断，以上内容可能不完整。可以再问一次或换个说法。"
                : "回答失败，请稍后重试"));
            emitter.complete();
        }
    }

    /** 未通过前置校验时开一条立刻结束的流，把 code/message 交给前端统一处理。 */
    private SseEmitter failedStream(int code, String message) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sendEvent(emitter, "error", errorPayload(code, message));
        emitter.complete();
        return emitter;
    }

    private Map<String, Object> errorPayload(int code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        return payload;
    }

    /**
     * 发送一个 SSE 事件。
     *
     * <p>载荷先自己序列化成单行 JSON 再交给 emitter：SSE 的 data 按行书写，
     * 载荷里一旦夹带裸换行（模型的分段输出很常见）就会把一帧拆成两行，
     * 客户端逐行解析时会丢字。序列化后换行变成 {@code \n} 转义，天然单行。
     *
     * <p>中途断连（用户关页面 / 刷新）是常态，写失败只需静默忽略。
     */
    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(toSingleLineJson(data), MediaType.TEXT_PLAIN));
        } catch (Exception ignored) {
            // 客户端已断开，无需处理
        }
    }

    private String toSingleLineJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return "\"\"";
        }
    }

    // ==================== 私有方法 ====================

    /** AI 功能前置检查：未开启 / 未配置时直接拒绝，不发起外部调用。用量只记录、不限额。 */
    private ResponseEntity<Result<?>> aiGuard() {
        if (!aiProperties.isEnabled()) {
            return Result.fail(503, "AI 功能已关闭").toResponseEntity();
        }
        if (!aiAssistantService.isConfigured()) {
            return Result.fail(503, "AI 服务未配置，请联系管理员设置 AGNES_API_KEY").toResponseEntity();
        }
        return null;
    }

    private boolean isTeamParticipant(TeamDemand team, User user) {
        if (team == null || user == null) {
            return false;
        }
        if (team.getCreatorId() != null && team.getCreatorId().equals(user.getId())) {
            return true;
        }
        return teamApplicationService.count(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, team.getId())
            .eq(TeamApplication::getUserId, user.getId())
            .eq(TeamApplication::getStatus, "APPROVED")) > 0;
    }

    private Competition findTeamCompetition(TeamDemand team) {
        if (team == null || team.getCompetitionId() == null) {
            return null;
        }
        try {
            return competitionService.getById(Long.valueOf(team.getCompetitionId()));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> taskSummary(TeamTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.getId());
        map.put("title", task.getTaskTitle());
        map.put("status", task.getStatus());
        map.put("deadline", task.getDeadline() == null ? null
            : new SimpleDateFormat("MM-dd").format(task.getDeadline()));
        if (task.getAssignedTo() != null) {
            User assignee = userService.getById(task.getAssignedTo());
            map.put("assigneeName", assignee == null ? null
                : (assignee.getRealName() != null ? assignee.getRealName() : assignee.getUsername()));
        }
        return map;
    }

    private static Date addDays(Date base, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(base);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return calendar.getTime();
    }
}
