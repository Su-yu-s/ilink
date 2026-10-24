package cn.ilink.teamagent;

import cn.ilink.entity.KgIndexTask;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.KgIndexTaskMapper;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Read-only, record-authorized team facts used by the Agent and bootstrap. */
@Service
public class TeamAgentMetricsService {
    private final TeamDemandMapper teamMapper;
    private final TeamTaskMapper taskMapper;
    private final TeamApplicationMapper applicationMapper;
    private final KgIndexTaskMapper indexTaskMapper;
    private final UserService userService;
    private final TeamTaskPolicy taskPolicy;

    public TeamAgentMetricsService(TeamDemandMapper teamMapper,
                                   TeamTaskMapper taskMapper,
                                   TeamApplicationMapper applicationMapper,
                                   KgIndexTaskMapper indexTaskMapper,
                                   UserService userService,
                                   TeamTaskPolicy taskPolicy) {
        this.teamMapper = teamMapper;
        this.taskMapper = taskMapper;
        this.applicationMapper = applicationMapper;
        this.indexTaskMapper = indexTaskMapper;
        this.userService = userService;
        this.taskPolicy = taskPolicy;
    }

    public Snapshot snapshot(TeamAgentScope scope) {
        TeamDemand team = teamMapper.selectById(scope.teamId());
        if (team == null) {
            throw new TeamAgentException(TeamAgentErrorCode.TEAM_ACCESS_DENIED, "团队不存在");
        }
        User user = new User();
        user.setId(scope.userId());
        List<TeamTask> allTasks = new ArrayList<>(taskMapper.selectList(
            new LambdaQueryWrapper<TeamTask>()
                .eq(TeamTask::getTeamId, scope.teamId())
                .orderByAsc(TeamTask::getCreatedAt)));
        allTasks.sort(Comparator.comparing(TeamTask::getId,
            Comparator.nullsLast(Comparator.naturalOrder())));
        List<TeamTask> visibleTasks = allTasks.stream()
            .filter(task -> taskPolicy.canReadTask(team, task, user))
            .toList();
        return snapshot(team, allTasks, visibleTasks, scope);
    }

    public Snapshot snapshot(TeamDemand team, List<TeamTask> allTasks,
                             List<TeamTask> visibleTasks, TeamAgentScope scope) {
        Date now = new Date();
        Instant weekStart = LocalDate.now(TeamAgentTime.ZONE)
            .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .atStartOfDay(TeamAgentTime.ZONE).toInstant();
        Instant weekEnd = LocalDate.now(TeamAgentTime.ZONE)
            .with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
            .plusDays(1).atStartOfDay(TeamAgentTime.ZONE).toInstant();

        long pending = 0;
        long inProgress = 0;
        long review = 0;
        long completed = 0;
        long overdue = 0;
        long dueIn7Days = 0;
        long completedThisWeek = 0;
        long total = 0;
        // Counts are a record-level fact. A student may only infer the state of
        // tasks they can actually read; leaders and mentors will naturally see
        // all tasks because visibleTasks equals allTasks for those roles.
        for (TeamTask task : visibleTasks) {
            String status = task.getStatus() == null ? "" : task.getStatus().toUpperCase();
            if (!"CANCELLED".equals(status)) total++;
            switch (status) {
                case "PENDING" -> pending++;
                case "IN_PROGRESS" -> inProgress++;
                case "REVIEW" -> review++;
                case "COMPLETED" -> completed++;
                default -> { }
            }
            if (!"COMPLETED".equals(status) && !"CANCELLED".equals(status)
                && task.getDeadline() != null && task.getDeadline().before(now)) {
                overdue++;
            }
            if (!"COMPLETED".equals(status) && !"CANCELLED".equals(status)
                && task.getDeadline() != null && !task.getDeadline().before(now)
                && task.getDeadline().toInstant().isBefore(now.toInstant().plusSeconds(7L * 86400))) {
                dueIn7Days++;
            }
            if ("COMPLETED".equals(status) && task.getCompletedAt() != null
                && !task.getCompletedAt().toInstant().isBefore(weekStart)
                && task.getCompletedAt().toInstant().isBefore(weekEnd)) {
                completedThisWeek++;
            }
        }

        List<TeamApplication> approved = applicationMapper.selectList(
            new LambdaQueryWrapper<TeamApplication>()
                .eq(TeamApplication::getTeamId, team.getId())
                .eq(TeamApplication::getStatus, "APPROVED"));
        long mentorCount = approved.stream()
            .filter(row -> "MENTOR".equalsIgnoreCase(row.getMemberRole())).count();
        long memberCount = approved.size() + 1L;
        Map<Long, User> members = loadMembers(team, approved);

        List<Map<String, Object>> risks = new ArrayList<>();
        for (TeamTask task : visibleTasks) {
            if ("COMPLETED".equalsIgnoreCase(task.getStatus())
                || "CANCELLED".equalsIgnoreCase(task.getStatus())) continue;
            if (task.getDeadline() != null && task.getDeadline().before(now)) {
                risks.add(risk(task, "OVERDUE", "已超过截止日期"));
            } else if (task.getAssignedTo() == null) {
                risks.add(risk(task, "UNASSIGNED", "尚未指派负责人"));
            } else if ("REVIEW".equalsIgnoreCase(task.getStatus())
                && task.getUpdatedAt() != null
                && task.getUpdatedAt().toInstant().isBefore(now.toInstant().minusSeconds(48L * 3600))) {
                risks.add(risk(task, "REVIEW_STALE", "等待审核超过 48 小时"));
            }
        }

        KnowledgeFreshness freshness = freshness(team.getId());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("totalTasks", total);
        counts.put("openTasks", pending + inProgress + review);
        counts.put("completedTasks", completed);
        counts.put("pending", pending);
        counts.put("inProgress", inProgress);
        counts.put("review", review);
        counts.put("overdue", overdue);
        counts.put("dueIn7Days", dueIn7Days);
        counts.put("completionRate", total == 0 ? null
            : Math.round((completed * 10000.0) / total) / 10000.0);
        counts.put("generatedAt", TeamAgentTime.iso(Instant.now()));

        return new Snapshot(team, scope, List.copyOf(allTasks), List.copyOf(visibleTasks),
            List.copyOf(approved),
            memberCount, mentorCount, counts, freshness, members, risks,
            completedThisWeek, weekStart, weekEnd);
    }

    private KnowledgeFreshness freshness(Long teamId) {
        List<KgIndexTask> rows = indexTaskMapper.selectList(
            new LambdaQueryWrapper<KgIndexTask>()
                .eq(KgIndexTask::getTeamId, teamId)
                .orderByDesc(KgIndexTask::getUpdatedAt));
        long pending = rows.stream().filter(row ->
            KgIndexTask.PENDING.equals(row.getStatus()) || KgIndexTask.RUNNING.equals(row.getStatus())).count();
        long failed = rows.stream().filter(row -> KgIndexTask.FAILED.equals(row.getStatus())).count();
        Date lastIndexed = rows.stream()
            .filter(row -> KgIndexTask.DONE.equals(row.getStatus()))
            .map(KgIndexTask::getFinishedAt)
            .filter(Objects::nonNull)
            .max(Date::compareTo).orElse(null);
        String status;
        if (failed > 0) status = "DEGRADED";
        else if (pending > 0) status = "UPDATING";
        else if (lastIndexed != null
            && lastIndexed.toInstant().isBefore(Instant.now().minusSeconds(24L * 3600))) status = "STALE";
        else status = "FRESH";
        return new KnowledgeFreshness(status, pending, failed, lastIndexed);
    }

    private Map<Long, User> loadMembers(TeamDemand team, List<TeamApplication> approved) {
        List<Long> ids = approved.stream().map(TeamApplication::getUserId)
            .filter(Objects::nonNull).distinct().collect(Collectors.toCollection(ArrayList::new));
        if (team.getCreatorId() != null) ids.add(team.getCreatorId());
        return ids.isEmpty() ? Map.of() : userService.listByIds(ids).stream()
            .collect(Collectors.toMap(User::getId, row -> row, (a, b) -> a));
    }

    private Map<String, Object> risk(TeamTask task, String code, String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.getId());
        row.put("title", task.getTaskTitle());
        row.put("status", task.getStatus());
        row.put("priority", task.getPriority());
        row.put("deadline", TeamAgentTime.iso(task.getDeadline()));
        row.put("assignedTo", task.getAssignedTo());
        row.put("code", code);
        row.put("message", message);
        return row;
    }

    public record KnowledgeFreshness(String status, long pendingDocuments,
                                     long failedDocuments, Date lastIndexedAt) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("pendingDocuments", pendingDocuments);
            map.put("failedDocuments", failedDocuments);
            map.put("lastIndexedAt", TeamAgentTime.iso(lastIndexedAt));
            return map;
        }
    }

    public record Snapshot(TeamDemand team, TeamAgentScope scope, List<TeamTask> allTasks,
                           List<TeamTask> visibleTasks,
                           List<TeamApplication> approvedApplications, long memberCount,
                           long mentorCount, Map<String, Object> counts,
                           KnowledgeFreshness freshness, Map<Long, User> members,
                           List<Map<String, Object>> risks, long completedThisWeek,
                           Instant weekStart, Instant weekEnd) {
        public String weeklyMarkdown() {
            long pending = number(counts.get("pending"));
            long active = number(counts.get("inProgress"));
            long review = number(counts.get("review"));
            long overdue = number(counts.get("overdue"));
            long completed = number(counts.get("completedTasks"));
            return "## " + team.getTitle() + " · 本周协作简报\n\n"
                + "- 统计周期：" + weekStart.atZone(TeamAgentTime.ZONE).toLocalDate()
                + " 至 " + weekEnd.minusSeconds(1).atZone(TeamAgentTime.ZONE).toLocalDate() + "\n"
                + "- 已完成任务：" + completed + " 个；本周完成：" + completedThisWeek + " 个\n"
                + "- 待处理：" + pending + " 个；进行中：" + active + " 个；待审核：" + review + " 个\n"
                + "- 已逾期：" + overdue + " 个\n\n"
                + "### 风险提示\n\n"
                + (risks.isEmpty() ? "当前没有可证明的逾期、未指派或长期待审核风险。"
                    : risks.stream().limit(8).map(row -> "- " + row.get("title") + "："
                        + row.get("message")).collect(Collectors.joining("\n")))
                + "\n\n> 本简报由团队事实聚合生成，数字以服务器当前任务状态为准。";
        }

        private static long number(Object value) {
            return value instanceof Number number ? number.longValue() : 0L;
        }
    }
}
