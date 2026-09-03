package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.config.AiProperties;
import cn.ilink.entity.Competition;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.UserService;
import cn.ilink.service.ai.AiAssistantService;
import cn.ilink.service.ai.AiClient;
import cn.ilink.service.ai.AiQuotaService;
import cn.ilink.service.impl.CompetitionServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpSession;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    @Autowired
    private AiAssistantService aiAssistantService;

    @Autowired
    private AiQuotaService aiQuotaService;

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

        ResponseEntity<Result<?>> guard = aiGuard(user);
        if (guard != null) {
            return guard;
        }

        try {
            Competition competition = findTeamCompetition(team);
            List<Map<String, Object>> subtasks = aiAssistantService.breakdownTask(task, competition);
            aiQuotaService.record(user.getId(), teamId, ACTION_TASK_BREAKDOWN, null, null, true);
            return Result.ok("拆解完成", subtasks).toResponseEntity();
        } catch (AiClient.AiUnavailableException e) {
            aiQuotaService.record(user.getId(), teamId, ACTION_TASK_BREAKDOWN, null, null, false);
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
        Long competitionId = payload.get("competitionId") == null ? null
            : Long.valueOf(String.valueOf(payload.get("competitionId")));
        Competition competition = competitionId == null ? null : competitionService.getById(competitionId);
        if (competition == null) {
            return Result.badRequest("请选择有效的竞赛").toResponseEntity();
        }

        ResponseEntity<Result<?>> guard = aiGuard(user);
        if (guard != null) {
            return guard;
        }

        try {
            String answer = aiAssistantService.answerCompetitionQuestion(question, competition);
            aiQuotaService.record(user.getId(), null, ACTION_COMPETITION_QA, null, null, true);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("answer", answer);
            data.put("competitionName", competition.getName());
            return Result.ok("回答完成", data).toResponseEntity();
        } catch (AiClient.AiUnavailableException e) {
            aiQuotaService.record(user.getId(), null, ACTION_COMPETITION_QA, null, null, false);
            log.warn("竞赛答疑失败: {}", e.getMessage());
            return Result.fail(502, "AI 服务暂时不可用，请稍后重试").toResponseEntity();
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
        data.put("remainingQuota", Math.max(0, aiQuotaService.dailyQuota() - aiQuotaService.usedToday(user.getId())));
        return Result.ok("生成成功", data).toResponseEntity();
    }

    // ==================== 私有方法 ====================

    /** AI 功能前置检查：未配置 / 超配额 时直接拒绝，不发起外部调用 */
    private ResponseEntity<Result<?>> aiGuard(User user) {
        if (!aiProperties.isEnabled()) {
            return Result.fail(503, "AI 功能已关闭").toResponseEntity();
        }
        if (!aiAssistantService.isConfigured()) {
            return Result.fail(503, "AI 服务未配置，请联系管理员设置 AGNES_API_KEY").toResponseEntity();
        }
        if (aiQuotaService.isOverQuota(user.getId())) {
            return Result.fail(429, "今日 AI 使用次数已达上限（" + aiQuotaService.dailyQuota() + " 次/天），明天再来吧")
                .toResponseEntity();
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
