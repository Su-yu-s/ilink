package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.TaskAssignDTO;
import cn.ilink.dto.TaskCommentDTO;
import cn.ilink.dto.TaskParticipantDTO;
import cn.ilink.dto.TaskStatusUpdateDTO;
import cn.ilink.dto.TeamTaskDTO;
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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/api")
@Slf4j
public class TeamTaskController {

    private static final String TEAMING = "TEAMING";
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
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationService notificationService;

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
        TeamDemand team = teamDemandService.getById(teamId);
        ResponseEntity<Result<?>> guard = requireLeaderAndTaskEnabled(team, user);
        if (guard != null) {
            return guard;
        }
        if (dto == null || dto.getTaskTitle() == null || dto.getTaskTitle().trim().isEmpty()) {
            return Result.badRequest("\u4efb\u52a1\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a").toResponseEntity();
        }
        if (dto.getAssignedTo() == null || !isTeamParticipantByUserId(teamId, dto.getAssignedTo())) {
            return Result.badRequest(MSG_VALID_ASSIGNEE).toResponseEntity();
        }
        dto.setTeamId(teamId);
        boolean success = teamTaskService.createTask(dto, user.getId());
        if (!success) {
            return Result.fail("\u4efb\u52a1\u521b\u5efa\u5931\u8d25").toResponseEntity();
        }
        // 给被指派人发送任务分配通知
        TeamTaskDTO finalDto = dto;
        Long assigneeId = dto.getAssignedTo();
        User assignee = userService.getById(assigneeId);
        String assigneeName = assignee != null ? (assignee.getRealName() != null ? assignee.getRealName() : assignee.getUsername()) : "队员";
        notificationService.create(
            assigneeId,
            user.getId(),
            "TASK_ASSIGNED",
            "新任务指派",
            user.getRealName() != null ? user.getRealName() : user.getUsername() + " 给你分配了任务「" + finalDto.getTaskTitle() + "」",
            null);
        TeamTaskVO task = teamTaskService.getTasksByTeam(teamId).stream()
            .filter(t -> Objects.equals(t.getCreatedBy(), user.getId()))
            .filter(t -> Objects.equals(t.getTaskTitle(), dto.getTaskTitle()))
            .findFirst()
            .orElse(null);
        return Result.ok("\u4efb\u52a1\u521b\u5efa\u6210\u529f", task).toResponseEntity();
    }

    @PutMapping("/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Result<?>> updateTask(@PathVariable Long taskId,
                                                          @RequestBody TeamTaskDTO dto,
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
        ResponseEntity<Result<?>> guard = requireLeaderAndTaskEnabled(team, user);
        if (guard != null) {
            return guard;
        }
        if (dto != null && dto.getAssignedTo() != null && !isTeamParticipantByUserId(task.getTeamId(), dto.getAssignedTo())) {
            return Result.badRequest(MSG_VALID_ASSIGNEE).toResponseEntity();
        }
        boolean success = teamTaskService.updateTask(taskId, dto);
        if (!success) {
            return Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
        }
        return Result.ok("\u4efb\u52a1\u66f4\u65b0\u6210\u529f", teamTaskService.getTaskById(taskId)).toResponseEntity();
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return Result.forbidden().toResponseEntity();
        }
        boolean success = teamTaskService.updateTaskStatus(taskId, dto.getStatus());
        return success ? Result.ok(MSG_SUCCESS, null).toResponseEntity()
            : Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<?>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        boolean success = teamTaskService.deleteTask(taskId);
        return success ? Result.ok("\u4efb\u52a1\u5220\u9664\u6210\u529f", null).toResponseEntity()
            : Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<?>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        if (!isTeamParticipantByUserId(task.getTeamId(), dto.getUserId())) {
            return Result.badRequest(MSG_VALID_ASSIGNEE).toResponseEntity();
        }
        boolean success = teamTaskService.assignTask(taskId, dto.getUserId());
        if (success) {
            // 给被指派人发送任务分配通知
            TeamTask assignedTask = teamTaskService.getById(taskId);
            if (assignedTask != null) {
                User assignee = userService.getById(dto.getUserId());
                String assigneeName = assignee != null ? (assignee.getRealName() != null ? assignee.getRealName() : assignee.getUsername()) : "队员";
                notificationService.create(
                    dto.getUserId(),
                    user.getId(),
                    "TASK_ASSIGNED",
                    "新任务指派",
                    user.getRealName() != null ? user.getRealName() : user.getUsername() + " 给你分配了任务「" + assignedTask.getTaskTitle() + "」",
                    null);
            }
        }
        return success ? Result.ok("\u4efb\u52a1\u6307\u6d3e\u6210\u529f", null).toResponseEntity()
            : Result.notFound(MSG_TASK_NOT_FOUND).toResponseEntity();
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<?>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        if (!isTeamParticipantByUserId(task.getTeamId(), dto.getUserId())) {
            return Result.badRequest(MSG_VALID_ASSIGNEE).toResponseEntity();
        }
        boolean success = teamTaskService.addParticipant(taskId, dto.getUserId(), dto.getRole());
        return success ? Result.ok("\u6dfb\u52a0\u53c2\u4e0e\u8005\u6210\u529f", null).toResponseEntity()
            : Result.fail("\u6dfb\u52a0\u53c2\u4e0e\u8005\u5931\u8d25").toResponseEntity();
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<?>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        boolean success = teamTaskService.removeParticipant(taskId, userId);
        return success ? Result.ok("\u79fb\u9664\u53c2\u4e0e\u8005\u6210\u529f", null).toResponseEntity()
            : Result.fail("\u79fb\u9664\u53c2\u4e0e\u8005\u5931\u8d25").toResponseEntity();
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return Result.forbidden().toResponseEntity();
        }
        TaskCommentVO comment = teamTaskService.addComment(taskId, user.getId(), dto.getContent(), dto.getParentId());
        return comment != null ? Result.ok("\u8bc4\u8bba\u6210\u529f", comment).toResponseEntity()
            : Result.fail("\u8bc4\u8bba\u5931\u8d25").toResponseEntity();
    }

    @PutMapping("/tasks/{taskId}/review")
    @ResponseBody
    public ResponseEntity<Result<?>> reviewTask(@PathVariable Long taskId,
                                                    @RequestBody Map<String, String> body,
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
        ResponseEntity<Result<?>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        String action = body == null ? "" : body.getOrDefault("action", "");
        if ("COMPLETED".equals(action)) {
            task.setStatus("COMPLETED");
            task.setUpdatedAt(new Date());
            task.setCompletedAt(new Date());
            teamTaskService.updateById(task);
            return Result.ok("\u4efb\u52a1\u5df2\u786e\u8ba4\u5b8c\u6210", null).toResponseEntity();
        }
        if ("RETURNED".equals(action)) {
            task.setStatus("IN_PROGRESS");
            task.setUpdatedAt(new Date());
            teamTaskService.updateById(task);
            return Result.ok("\u5df2\u9000\u56de\u4fee\u6539", null).toResponseEntity();
        }
        return Result.badRequest("\u65e0\u6548\u64cd\u4f5c").toResponseEntity();
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
        if (!isTeamLeader(team, user)) {
            wrapper.eq(TaskSubmission::getSubmitterId, user.getId());
        }
        List<Map<String, Object>> rows = taskSubmissionMapper.selectList(wrapper).stream()
            .map(this::submissionToView)
            .collect(Collectors.toList());
        return Result.ok(MSG_SUCCESS, rows).toResponseEntity();
    }

    @PostMapping("/tasks/{taskId}/submit")
    @ResponseBody
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!isTaskFeatureEnabled(team)) {
            return Result.badRequest(MSG_TASK_DISABLED).toResponseEntity();
        }
        if (task.getAssignedTo() == null || !task.getAssignedTo().equals(user.getId())) {
            return Result.fail(403, "\u4ec5\u88ab\u6307\u6d3e\u7684\u961f\u5458\u53ef\u4ee5\u63d0\u4ea4").toResponseEntity();
        }

        String content = body == null ? "" : String.valueOf(body.getOrDefault("remark", body.getOrDefault("content", "")));
        TaskSubmission submission = new TaskSubmission();
        submission.setTaskId(taskId);
        submission.setSubmitterId(user.getId());
        submission.setContent(content == null ? "" : content.trim());
        submission.setAttachments(serializeAttachments(body == null ? null : body.get("attachments")));
        submission.setCreatedAt(new Date());
        taskSubmissionMapper.insert(submission);

        task.setStatus("REVIEW");
        task.setUpdatedAt(new Date());
        teamTaskService.updateById(task);
        if (content != null && !content.trim().isEmpty()) {
            teamTaskService.addComment(taskId, user.getId(), content, null);
        }
        // 给队长发送任务提交通知
        Long leaderId = team.getCreatorId();
        if (leaderId != null && !leaderId.equals(user.getId())) {
            notificationService.create(
                leaderId,
                user.getId(),
                "TASK_SUBMITTED",
                "任务已提交",
                (user.getRealName() != null ? user.getRealName() : user.getUsername()) + " 提交了任务「" + task.getTaskTitle() + "」，请审核",
                null);
        }
        return Result.ok("\u63d0\u4ea4\u6210\u529f\uff0c\u7b49\u5f85\u961f\u957f\u5ba1\u6838", null).toResponseEntity();
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
            if (!isTaskFeatureEnabled(team)) {
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
            if (!isTeamLeader(team, user)) {
                log.info("[任务] 非队长, 过滤只显示自己的任务");
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
        if (!isTaskFeatureEnabled(team) || task == null || user == null) {
            return false;
        }
        return isTeamLeader(team, user) || (task.getAssignedTo() != null && task.getAssignedTo().equals(user.getId()));
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
            view.put("submitterName", submitter.getRealName() != null ? submitter.getRealName() : submitter.getUsername());
            view.put("submitterAvatar", submitter.getAvatar());
        }
        return view;
    }
}
