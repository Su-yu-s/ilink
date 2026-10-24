package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.TaskAssignDTO;
import cn.ilink.dto.TaskCommentDTO;
import cn.ilink.dto.TaskParticipantDTO;
import cn.ilink.dto.TaskStatusUpdateDTO;
import cn.ilink.dto.TeamTaskDTO;
import cn.ilink.entity.TaskReview;
import cn.ilink.entity.TaskSubmission;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TaskSubmissionMapper;
import cn.ilink.service.NotificationService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import cn.ilink.teamagent.TeamTaskCommandService;
import cn.ilink.teamagent.TeamTaskCommands;
import cn.ilink.vo.TaskCommentVO;
import cn.ilink.vo.TaskParticipantVO;
import cn.ilink.vo.TeamTaskVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpSession;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import cn.ilink.util.DisplayNameResolver;

@Controller
@RequestMapping("/api")
@Slf4j
public class TeamTaskController {

    private static final String TEAMING = "TEAMING";
    private static final String CLOSED = "CLOSED";
    private static final String MSG_TASK_DISABLED = "\u4efb\u52a1\u7cfb\u7edf\u4ec5\u5728\u7ec4\u961f\u4e2d\u542f\u7528";
    private static final String MSG_TASK_NOT_FOUND = "\u4efb\u52a1\u4e0d\u5b58\u5728";
    private static final String MSG_TEAM_NOT_FOUND = "\u7ec4\u961f\u9700\u6c42\u4e0d\u5b58\u5728";
    private static final String MSG_VALID_ASSIGNEE = "\u8bf7\u9009\u62e9\u6709\u6548\u7684\u961f\u5458";
    private static final String MSG_SUCCESS = "\u64cd\u4f5c\u6210\u529f";

    @Autowired
    private TeamTaskService teamTaskService;

    @Autowired
    private TeamDemandServiceImpl teamDemandService;

    @Autowired
    private TeamApplicationServiceImpl teamApplicationService;

    @Autowired
    private TaskSubmissionMapper taskSubmissionMapper;

    @Autowired
    private cn.ilink.mapper.TaskReviewMapper taskReviewMapper;

    @Autowired
    private cn.ilink.mapper.TaskParticipantMapper taskParticipantMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private cn.ilink.service.kg.KgIndexService kgIndexService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private TeamTaskCommandService teamTaskCommandService;

    @GetMapping("/tasks")
    @ResponseBody
    public ResponseEntity<Result<?>> getTasksByTeamId(@RequestParam Long teamId, HttpSession session) {
        return getVisibleTasks(teamId, session);
    }

    @GetMapping("/team/{teamId}/tasks")
    @ResponseBody
    public ResponseEntity<Result<?>> getTasksByTeam(@PathVariable Long teamId, HttpSession session) {
        return getVisibleTasks(teamId, session);
    }

    @GetMapping("/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Result<?>> getTaskById(@PathVariable Long taskId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return Result.forbidden().toResponseEntity();
        }
        return Result.ok(MSG_SUCCESS, teamTaskService.getTaskById(taskId)).toResponseEntity();
    }

    @PostMapping("/team/{teamId}/tasks")
    @ResponseBody
    public ResponseEntity<Result<?>> createTask(@PathVariable Long teamId,
                                                          @RequestBody TeamTaskDTO dto,
                                                          HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.createTask(
            new TeamTaskCommands.CreateTask(
                teamId, user.getId(), null, null,
                dto == null ? null : dto.getTaskTitle(),
                dto == null ? null : dto.getTaskDescription(),
                dto == null ? null : dto.getTaskType(),
                dto == null ? null : dto.getPriority(),
                dto == null ? null : dto.getEstimatedHours(),
                dto == null ? null : dto.getDeadline(),
                dto == null ? null : dto.getAssignedTo(),
                dto == null ? null : dto.getRequiresReview(),
                dto == null ? null : dto.getParentTaskId()));
        return Result.ok(mutation.actionMessage(),
            teamTaskService.getTaskById(mutation.taskId())).toResponseEntity();
    }

    @PutMapping("/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Result<?>> updateTask(@PathVariable Long taskId,
                                                          @RequestBody com.fasterxml.jackson.databind.JsonNode body,
                                                          HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        if (body == null || body.isNull() || !body.isObject()) {
            return Result.badRequest("请求体格式错误").toResponseEntity();
        }
        TeamTaskDTO dto;
        try {
            dto = objectMapper.treeToValue(body, TeamTaskDTO.class);
        } catch (JsonProcessingException e) {
            return Result.badRequest("请求体格式错误").toResponseEntity();
        }
        Integer expectedVersion = body.has("version") && body.get("version").canConvertToInt()
            ? body.get("version").asInt() : null;
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.updateTask(
            new TeamTaskCommands.UpdateTask(
                task.getTeamId(), user.getId(), taskId, expectedVersion,
                dto.getTaskTitle(), dto.getTaskDescription(), dto.getTaskType(),
                dto.getEstimatedHours(), body.has("deadline"), dto.getDeadline(),
                dto.getRequiresReview(), dto.getAssignedTo(), null));
        return Result.ok(mutation.actionMessage(),
            teamTaskService.getTaskById(taskId)).toResponseEntity();
    }

    @PutMapping("/tasks/{taskId}/status")
    @ResponseBody
    public ResponseEntity<Result<?>> updateTaskStatus(@PathVariable Long taskId,
                                                          @RequestBody TaskStatusUpdateDTO dto,
                                                          HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (dto == null || dto.getStatus() == null || dto.getStatus().trim().isEmpty()) {
            return Result.badRequest("\u72b6\u6001\u4e0d\u80fd\u4e3a\u7a7a").toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        if (!"IN_PROGRESS".equalsIgnoreCase(dto.getStatus())) {
            return Result.badRequest("请通过提交与审核操作推进任务状态").toResponseEntity();
        }
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.changeStatus(
            new TeamTaskCommands.ChangeStatus(
                task.getTeamId(), user.getId(), taskId, null,
                dto.getStatus(), null, null));
        return Result.ok(mutation.actionMessage(), null).toResponseEntity();
    }

    @DeleteMapping("/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteTask(@PathVariable Long taskId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.deleteTask(
            new TeamTaskCommands.DeleteTask(
                task.getTeamId(), user.getId(), taskId, null, null, null));
        return Result.ok(mutation.actionMessage(), null).toResponseEntity();
    }

    @PutMapping("/tasks/{taskId}/assign")
    @ResponseBody
    public ResponseEntity<Result<?>> assignTask(@PathVariable Long taskId,
                                                   @RequestBody TaskAssignDTO dto,
                                                   HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (dto == null || dto.getUserId() == null) {
            return Result.badRequest(MSG_VALID_ASSIGNEE).toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.assignTask(
            new TeamTaskCommands.AssignTask(
                task.getTeamId(), user.getId(), taskId, null, dto.getUserId(), null, null));
        return Result.ok(mutation.actionMessage(), null).toResponseEntity();
    }

    @GetMapping("/tasks/{taskId}/participants")
    @ResponseBody
    public ResponseEntity<Result<?>> getParticipants(@PathVariable Long taskId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return Result.forbidden().toResponseEntity();
        }
        return Result.ok(MSG_SUCCESS, teamTaskService.getParticipants(taskId)).toResponseEntity();
    }

    @PostMapping("/tasks/{taskId}/participants")
    @ResponseBody
    public ResponseEntity<Result<?>> addParticipant(@PathVariable Long taskId,
                                                        @RequestBody TaskParticipantDTO dto,
                                                        HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (dto == null || dto.getUserId() == null) {
            return Result.badRequest(MSG_VALID_ASSIGNEE).toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.addParticipant(
            new TeamTaskCommands.AddParticipant(
                task.getTeamId(), user.getId(), taskId, null, dto.getUserId(),
                dto.getRole(), null));
        return Result.ok(mutation.actionMessage(), null).toResponseEntity();
    }

    @DeleteMapping("/tasks/{taskId}/participants/{userId}")
    @ResponseBody
    public ResponseEntity<Result<?>> removeParticipant(@PathVariable Long taskId,
                                                           @PathVariable Long userId,
                                                           HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.removeParticipant(
            new TeamTaskCommands.RemoveParticipant(
                task.getTeamId(), user.getId(), taskId, null, userId, null));
        return Result.ok(mutation.actionMessage(), null).toResponseEntity();
    }

    @GetMapping("/tasks/{taskId}/comments")
    @ResponseBody
    public ResponseEntity<Result<?>> getComments(@PathVariable Long taskId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return Result.forbidden().toResponseEntity();
        }
        return Result.ok(MSG_SUCCESS, teamTaskService.getComments(taskId)).toResponseEntity();
    }

    @PostMapping("/tasks/{taskId}/comments")
    @ResponseBody
    public ResponseEntity<Result<?>> addComment(@PathVariable Long taskId,
                                                             @RequestBody TaskCommentDTO dto,
                                                             HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (dto == null || dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return Result.badRequest("\u8bc4\u8bba\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a").toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.addComment(
            new TeamTaskCommands.AddComment(
                task.getTeamId(), user.getId(), taskId,
                dto.getContent(), dto.getParentId(), null));
        return Result.ok(mutation.actionMessage(),
            teamTaskService.getComments(taskId)).toResponseEntity();
    }

    @PutMapping("/tasks/{taskId}/review")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<Result<?>> reviewTask(@PathVariable Long taskId,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        String action = body == null ? "" : strOf(body.get("action"));
        String overall = strOf(body.get("overall"));
        Object fileComments = body == null ? null : body.get("fileComments");
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.reviewTask(
            new TeamTaskCommands.ReviewTask(
                task.getTeamId(), user.getId(), taskId,
                longOf(body == null ? null : body.get("submissionId")),
                action, overall, fileComments instanceof List ? (List<?>) fileComments : null,
                null));
        return Result.ok(mutation.actionMessage(), null).toResponseEntity();
    }

    /** 直接完成通道：requires_review=0 的任务，被分配者在进行中可直接结束（不经审核） */
    @PostMapping("/tasks/{taskId}/complete")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<Result<?>> completeTaskDirectly(@PathVariable Long taskId,
                                                          HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        if (Boolean.TRUE.equals(task.getRequiresReview())) {
            return Result.badRequest("该任务需要提交审阅，请先交付材料").toResponseEntity();
        }
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.changeStatus(
            new TeamTaskCommands.ChangeStatus(
                task.getTeamId(), user.getId(), taskId, null, "COMPLETED", null, null));
        return Result.ok(mutation.actionMessage(), null).toResponseEntity();
    }

    /** 批阅记录（任务详情时间线）：提交事件来自 submissions，批阅事件来自 task_reviews */
    @GetMapping("/tasks/{taskId}/reviews")
    @ResponseBody
    public ResponseEntity<Result<?>> getTaskReviews(@PathVariable Long taskId,
                                                    HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return Result.forbidden().toResponseEntity();
        }
        List<TaskReview> reviews = taskReviewMapper.selectList(new LambdaQueryWrapper<TaskReview>()
            .eq(TaskReview::getTaskId, taskId)
            .orderByAsc(TaskReview::getCreatedAt));
        List<Map<String, Object>> rows = reviews.stream().map(review -> {
            Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("id", review.getId());
            row.put("taskId", review.getTaskId());
            row.put("submissionId", review.getSubmissionId());
            row.put("verdict", review.getVerdict());
            row.put("overall", review.getOverallComment());
            row.put("fileComments", review.getFileComments());
            row.put("createdAt", review.getCreatedAt());
            User reviewer = review.getReviewerId() == null ? null : userService.getById(review.getReviewerId());
            row.put("reviewerName", reviewer != null
                ? DisplayNameResolver.resolve(reviewer)
                : "批阅者");
            return row;
        }).collect(java.util.stream.Collectors.toList());
        return Result.ok(MSG_SUCCESS, rows).toResponseEntity();
    }

    private boolean isApprovedMentor(Long teamId, Long userId) {
        return approvedMentorIds(teamId).contains(userId);
    }

    /** 团队内已批准的导师 ID 列表（批阅权限与提交通知的接收方） */
    private List<Long> approvedMentorIds(Long teamId) {
        if (teamId == null) return List.of();
        return teamApplicationService.list(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getStatus, "APPROVED")
            .eq(TeamApplication::getMemberRole, "MENTOR"))
            .stream().map(TeamApplication::getUserId).collect(java.util.stream.Collectors.toList());
    }

    private static String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** fileComments 结构：[{fileId, fileName, comment}]；至少一条非空点评 */
    private boolean hasAnyFileComment(Object fileComments) {
        return countFileComments(fileComments) > 0;
    }

    private int countFileComments(Object fileComments) {
        if (!(fileComments instanceof List)) return 0;
        int count = 0;
        for (Object item : (List<?>) fileComments) {
            if (item instanceof Map) {
                Object comment = ((Map<?, ?>) item).get("comment");
                if (comment != null && !String.valueOf(comment).isBlank()) count++;
            }
        }
        return count;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    @GetMapping("/tasks/{taskId}/submissions")
    @ResponseBody
    public ResponseEntity<Result<?>> getTaskSubmissions(@PathVariable Long taskId,
                                                                                 HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return Result.forbidden().toResponseEntity();
        }
        LambdaQueryWrapper<TaskSubmission> wrapper = new LambdaQueryWrapper<TaskSubmission>()
            .eq(TaskSubmission::getTaskId, taskId)
            .orderByDesc(TaskSubmission::getCreatedAt);
        // 队长与导师要看全部提交（批阅的前提）；其余人只看自己交的。
        // 少了导师这一支，导师进批阅工作台只会看到「这个提交没有附件材料」
        if (!isTeamLeader(team, user) && !isApprovedMentor(team.getId(), user.getId())) {
            wrapper.eq(TaskSubmission::getSubmitterId, user.getId());
        }
        List<Map<String, Object>> rows = taskSubmissionMapper.selectList(wrapper).stream()
            .map(this::submissionToView)
            .collect(Collectors.toList());
        return Result.ok(MSG_SUCCESS, rows).toResponseEntity();
    }

    @PostMapping("/tasks/{taskId}/submit")
    @ResponseBody
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<Result<?>> submitTask(@PathVariable Long taskId,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        String content = body == null ? "" : String.valueOf(body.getOrDefault("remark", body.getOrDefault("content", "")));
        TeamTaskCommands.MutationResult mutation = teamTaskCommandService.submitTask(
            new TeamTaskCommands.SubmitTask(
                task.getTeamId(), user.getId(), taskId, content,
                body == null ? null : body.get("attachments"), null));
        return Result.ok(mutation.actionMessage(), null).toResponseEntity();
    }

    private ResponseEntity<Result<?>> getVisibleTasks(Long teamId, HttpSession session) {
        try {
            log.info("[任务] 开始加载任务, teamId={}, sessionId={}", teamId, session != null ? session.getId() : "null");
            User user = ControllerUtils.requireUser(session);
            if (user == null) {
                log.warn("[任务] 用户未登录, sessionId={}", session != null ? session.getId() : "null");
                return Result.unauthorized().toResponseEntity();
            }
            log.info("[任务] 用户已登录, userId={}", user.getId());
            TeamDemand team = teamDemandService.getById(teamId);
            if (team == null) {
                log.warn("[任务] 团队不存在, teamId={}", teamId);
                return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
            }
            log.info("[任务] 团队信息, teamId={}, status={}", team.getId(), team.getStatus());
            if (!isTaskReadEnabled(team)) {
                log.warn("[任务] 任务功能未启用, teamStatus={}", team.getStatus());
                return Result.badRequest(MSG_TASK_DISABLED).toResponseEntity();
            }
            if (!isTeamParticipant(team, user)) {
                log.warn("[任务] 非团队成员, teamId={}, userId={}", team.getId(), user.getId());
                return Result.forbidden().toResponseEntity();
            }
            log.info("[任务] 用户是团队成员, 开始查询任务列表");
            List<TeamTaskVO> tasks = teamTaskService.getTasksByTeam(teamId);
            log.info("[任务] 查询到 {} 个任务", tasks != null ? tasks.size() : 0);
            // 导师与队长平级、同样可以批阅任务，因此也要看到全部任务，
            // 否则导师的批阅工作台会因为没有可见任务而空着
            boolean canSeeAllTasks = isTeamLeader(team, user) || isApprovedMentor(team.getId(), user.getId());
            if (!canSeeAllTasks) {
                log.info("[任务] 非队长/导师, 过滤只显示自己的任务");
                tasks = tasks.stream()
                    .filter(task -> task.getAssignedTo() != null && task.getAssignedTo().equals(user.getId()))
                    .collect(Collectors.toList());
            }
            log.info("[任务] 返回 {} 个任务给用户 {}", tasks.size(), user.getId());
            return Result.ok(MSG_SUCCESS, tasks).toResponseEntity();
        } catch (Exception e) {
            log.error("[任务] 加载任务异常, teamId={}", teamId, e);
            return Result.fail("加载任务失败").toResponseEntity();
        }
    }

    private ResponseEntity<Result<?>> requireLeaderAndTaskEnabled(TeamDemand team, User user) {
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTaskFeatureEnabled(team)) {
            return Result.badRequest(MSG_TASK_DISABLED).toResponseEntity();
        }
        if (!isTeamLeader(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        return null;
    }

    private ResponseEntity<Result<?>> requireLeaderAndTaskEnabledVoid(TeamDemand team, User user) {
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTaskFeatureEnabled(team)) {
            return Result.badRequest(MSG_TASK_DISABLED).toResponseEntity();
        }
        if (!isTeamLeader(team, user)) {
            return Result.forbidden().toResponseEntity();
        }
        return null;
    }

    private boolean isTaskFeatureEnabled(TeamDemand team) {
        return team != null && TEAMING.equals(team.getStatus());
    }

    /** 关闭后的团队仍可按角色读取历史任务，但所有任务写操作继续禁用。 */
    private boolean isTaskReadEnabled(TeamDemand team) {
        return team != null
            && (TEAMING.equals(team.getStatus()) || CLOSED.equals(team.getStatus()));
    }

    private static final java.util.Set<String> ALLOWED_TASK_TYPES =
        java.util.Set.of("DEVELOPMENT", "DESIGN", "TESTING", "DOCUMENTATION", "OTHER");

    /**
     * 任务字段的长度/取值校验。返回 null 表示通过。
     * 不做校验时超长标题、超长类型会直接撞列宽，被数据库拒绝后变成 500。
     */
    private String validateTaskFields(TeamTaskDTO dto) {
        if (dto == null) {
            return null;
        }
        String error = ControllerUtils.checkMaxLength(
            dto.getTaskTitle(), ControllerUtils.TASK_TITLE_MAX, "任务名称");
        if (error == null) {
            error = ControllerUtils.checkMaxLength(
                dto.getTaskType(), ControllerUtils.TASK_TYPE_MAX, "任务类型");
        }
        if (error != null) {
            return error;
        }
        String type = dto.getTaskType() == null ? "" : dto.getTaskType().trim();
        if (!type.isEmpty() && !ALLOWED_TASK_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
            return "任务类型非法，仅支持 DEVELOPMENT / DESIGN / TESTING / DOCUMENTATION / OTHER";
        }
        Integer priority = dto.getPriority();
        if (priority != null && (priority < 1 || priority > 4)) {
            return "优先级需在 1-4 之间";
        }
        if (dto.getEstimatedHours() != null
                && dto.getEstimatedHours().compareTo(java.math.BigDecimal.ZERO) < 0) {
            return "预估工时不能为负数";
        }
        return null;
    }

    private boolean isTeamLeader(TeamDemand team, User user) {
        return team != null && user != null && team.getCreatorId() != null && team.getCreatorId().equals(user.getId());
    }

    private boolean isTeamParticipant(TeamDemand team, User user) {
        return isTeamLeader(team, user) || (team != null && isApprovedMember(team.getId(), user.getId()));
    }

    private boolean isTeamParticipantByUserId(Long teamId, Long userId) {
        TeamDemand team = teamDemandService.getById(teamId);
        return team != null && (Objects.equals(team.getCreatorId(), userId) || isApprovedMember(teamId, userId));
    }

    private boolean isApprovedMember(Long teamId, Long userId) {
        if (teamId == null || userId == null) {
            return false;
        }
        return teamApplicationService.count(new LambdaQueryWrapper<TeamApplication>()
            .eq(TeamApplication::getTeamId, teamId)
            .eq(TeamApplication::getUserId, userId)
            .eq(TeamApplication::getStatus, "APPROVED")) > 0;
    }

    private boolean canAccessTask(TeamDemand team, TeamTask task, User user) {
        if (!isTaskReadEnabled(team) || task == null || user == null) {
            return false;
        }
        // 导师与队长平级、有批阅权，必须能看任务的提交材料与批阅时间线，
        // 否则新加入的导师点开批阅工作台只会看到「这个提交没有附件材料」
        if (isTeamLeader(team, user)) {
            return true;
        }
        if (isApprovedMentor(team.getId(), user.getId())) {
            return true;
        }
        // 任务的直接关系人：被指派人和参与者。
        if (task.getAssignedTo() != null && task.getAssignedTo().equals(user.getId())) {
            return true;
        }
        // 参与者名单里的人（addParticipant 写入的行）同样应该能看/评论自己参与的任务，
        // 否则他们会变成「查不到自己参与的任务」的隐形人。
        return taskParticipantMapper.selectCount(new LambdaQueryWrapper<cn.ilink.entity.TaskParticipant>()
            .eq(cn.ilink.entity.TaskParticipant::getTaskId, task.getId())
            .eq(cn.ilink.entity.TaskParticipant::getUserId, user.getId())) > 0;
    }

    private String serializeAttachments(Object attachments) {
        if (attachments == null) {
            return null;
        }
        if (attachments instanceof String) {
            String value = ((String) attachments).trim();
            return value.isEmpty() ? null : value;
        }
        try {
            return objectMapper.writeValueAsString(attachments);
        } catch (JsonProcessingException e) {
            return String.valueOf(attachments);
        }
    }

    private Map<String, Object> submissionToView(TaskSubmission submission) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", submission.getId());
        view.put("taskId", submission.getTaskId());
        view.put("submitterId", submission.getSubmitterId());
        view.put("content", submission.getContent());
        view.put("attachments", submission.getAttachments());
        view.put("createdAt", submission.getCreatedAt());
        User submitter = userService.getById(submission.getSubmitterId());
        if (submitter != null) {
            view.put("submitterName", DisplayNameResolver.resolve(submitter));
            view.put("submitterAvatar", submitter.getAvatar());
        }
        return view;
    }
}
