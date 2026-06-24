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
import cn.ilink.service.TeamApplicationService;
import cn.ilink.service.TeamDemandService;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.UserService;
import cn.ilink.vo.TaskCommentVO;
import cn.ilink.vo.TaskParticipantVO;
import cn.ilink.vo.TeamTaskVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private TeamDemandService teamDemandService;

    @Autowired
    private TeamApplicationService teamApplicationService;

    @Autowired
    private TaskSubmissionMapper taskSubmissionMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/tasks")
    @ResponseBody
    public ResponseEntity<Result<List<TeamTaskVO>>> getTasksByTeamId(@RequestParam Long teamId, HttpSession session) {
        return getVisibleTasks(teamId, session);
    }

    @GetMapping("/team/{teamId}/tasks")
    @ResponseBody
    public ResponseEntity<Result<List<TeamTaskVO>>> getTasksByTeam(@PathVariable Long teamId, HttpSession session) {
        return getVisibleTasks(teamId, session);
    }

    @GetMapping("/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Result<TeamTaskVO>> getTaskById(@PathVariable Long taskId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return ResponseEntity.ok(Result.forbidden());
        }
        return ResponseEntity.ok(Result.ok(MSG_SUCCESS, teamTaskService.getTaskById(taskId)));
    }

    @PostMapping("/team/{teamId}/tasks")
    @ResponseBody
    public ResponseEntity<Result<TeamTaskVO>> createTask(@PathVariable Long teamId,
                                                          @RequestBody TeamTaskDTO dto,
                                                          HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamDemand team = teamDemandService.getById(teamId);
        ResponseEntity<Result<TeamTaskVO>> guard = requireLeaderAndTaskEnabled(team, user);
        if (guard != null) {
            return guard;
        }
        if (dto == null || dto.getTaskTitle() == null || dto.getTaskTitle().trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("\u4efb\u52a1\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a"));
        }
        if (dto.getAssignedTo() == null || !isTeamParticipantByUserId(teamId, dto.getAssignedTo())) {
            return ResponseEntity.ok(Result.badRequest(MSG_VALID_ASSIGNEE));
        }
        dto.setTeamId(teamId);
        boolean success = teamTaskService.createTask(dto, user.getId());
        if (!success) {
            return ResponseEntity.ok(Result.fail("\u4efb\u52a1\u521b\u5efa\u5931\u8d25"));
        }
        TeamTaskVO task = teamTaskService.getTasksByTeam(teamId).stream()
            .filter(t -> Objects.equals(t.getCreatedBy(), user.getId()))
            .filter(t -> Objects.equals(t.getTaskTitle(), dto.getTaskTitle()))
            .findFirst()
            .orElse(null);
        return ResponseEntity.ok(Result.ok("\u4efb\u52a1\u521b\u5efa\u6210\u529f", task));
    }

    @PutMapping("/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Result<TeamTaskVO>> updateTask(@PathVariable Long taskId,
                                                          @RequestBody TeamTaskDTO dto,
                                                          HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<TeamTaskVO>> guard = requireLeaderAndTaskEnabled(team, user);
        if (guard != null) {
            return guard;
        }
        if (dto != null && dto.getAssignedTo() != null && !isTeamParticipantByUserId(task.getTeamId(), dto.getAssignedTo())) {
            return ResponseEntity.ok(Result.badRequest(MSG_VALID_ASSIGNEE));
        }
        boolean success = teamTaskService.updateTask(taskId, dto);
        if (!success) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        return ResponseEntity.ok(Result.ok("\u4efb\u52a1\u66f4\u65b0\u6210\u529f", teamTaskService.getTaskById(taskId)));
    }

    @PutMapping("/tasks/{taskId}/status")
    @ResponseBody
    public ResponseEntity<Result<Void>> updateTaskStatus(@PathVariable Long taskId,
                                                          @RequestBody TaskStatusUpdateDTO dto,
                                                          HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        if (dto == null || dto.getStatus() == null || dto.getStatus().trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("\u72b6\u6001\u4e0d\u80fd\u4e3a\u7a7a"));
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return ResponseEntity.ok(Result.forbidden());
        }
        boolean success = teamTaskService.updateTaskStatus(taskId, dto.getStatus());
        return success ? ResponseEntity.ok(Result.ok(MSG_SUCCESS, null))
            : ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
    }

    @DeleteMapping("/tasks/{taskId}")
    @ResponseBody
    public ResponseEntity<Result<Void>> deleteTask(@PathVariable Long taskId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<Void>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        boolean success = teamTaskService.deleteTask(taskId);
        return success ? ResponseEntity.ok(Result.ok("\u4efb\u52a1\u5220\u9664\u6210\u529f", null))
            : ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
    }

    @PutMapping("/tasks/{taskId}/assign")
    @ResponseBody
    public ResponseEntity<Result<Void>> assignTask(@PathVariable Long taskId,
                                                   @RequestBody TaskAssignDTO dto,
                                                   HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        if (dto == null || dto.getUserId() == null) {
            return ResponseEntity.ok(Result.badRequest(MSG_VALID_ASSIGNEE));
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<Void>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        if (!isTeamParticipantByUserId(task.getTeamId(), dto.getUserId())) {
            return ResponseEntity.ok(Result.badRequest(MSG_VALID_ASSIGNEE));
        }
        boolean success = teamTaskService.assignTask(taskId, dto.getUserId());
        return success ? ResponseEntity.ok(Result.ok("\u4efb\u52a1\u6307\u6d3e\u6210\u529f", null))
            : ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
    }

    @GetMapping("/tasks/{taskId}/participants")
    @ResponseBody
    public ResponseEntity<Result<List<TaskParticipantVO>>> getParticipants(@PathVariable Long taskId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return ResponseEntity.ok(Result.forbidden());
        }
        return ResponseEntity.ok(Result.ok(MSG_SUCCESS, teamTaskService.getParticipants(taskId)));
    }

    @PostMapping("/tasks/{taskId}/participants")
    @ResponseBody
    public ResponseEntity<Result<Void>> addParticipant(@PathVariable Long taskId,
                                                        @RequestBody TaskParticipantDTO dto,
                                                        HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        if (dto == null || dto.getUserId() == null) {
            return ResponseEntity.ok(Result.badRequest(MSG_VALID_ASSIGNEE));
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<Void>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        if (!isTeamParticipantByUserId(task.getTeamId(), dto.getUserId())) {
            return ResponseEntity.ok(Result.badRequest(MSG_VALID_ASSIGNEE));
        }
        boolean success = teamTaskService.addParticipant(taskId, dto.getUserId(), dto.getRole());
        return success ? ResponseEntity.ok(Result.ok("\u6dfb\u52a0\u53c2\u4e0e\u8005\u6210\u529f", null))
            : ResponseEntity.ok(Result.fail("\u6dfb\u52a0\u53c2\u4e0e\u8005\u5931\u8d25"));
    }

    @DeleteMapping("/tasks/{taskId}/participants/{userId}")
    @ResponseBody
    public ResponseEntity<Result<Void>> removeParticipant(@PathVariable Long taskId,
                                                           @PathVariable Long userId,
                                                           HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<Void>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        boolean success = teamTaskService.removeParticipant(taskId, userId);
        return success ? ResponseEntity.ok(Result.ok("\u79fb\u9664\u53c2\u4e0e\u8005\u6210\u529f", null))
            : ResponseEntity.ok(Result.fail("\u79fb\u9664\u53c2\u4e0e\u8005\u5931\u8d25"));
    }

    @GetMapping("/tasks/{taskId}/comments")
    @ResponseBody
    public ResponseEntity<Result<List<TaskCommentVO>>> getComments(@PathVariable Long taskId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return ResponseEntity.ok(Result.forbidden());
        }
        return ResponseEntity.ok(Result.ok(MSG_SUCCESS, teamTaskService.getComments(taskId)));
    }

    @PostMapping("/tasks/{taskId}/comments")
    @ResponseBody
    public ResponseEntity<Result<TaskCommentVO>> addComment(@PathVariable Long taskId,
                                                             @RequestBody TaskCommentDTO dto,
                                                             HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        if (dto == null || dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("\u8bc4\u8bba\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a"));
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return ResponseEntity.ok(Result.forbidden());
        }
        TaskCommentVO comment = teamTaskService.addComment(taskId, user.getId(), dto.getContent(), dto.getParentId());
        return comment != null ? ResponseEntity.ok(Result.ok("\u8bc4\u8bba\u6210\u529f", comment))
            : ResponseEntity.ok(Result.fail("\u8bc4\u8bba\u5931\u8d25"));
    }

    @PutMapping("/tasks/{taskId}/review")
    @ResponseBody
    public ResponseEntity<Result<Void>> reviewTask(@PathVariable Long taskId,
                                                    @RequestBody Map<String, String> body,
                                                    HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        ResponseEntity<Result<Void>> guard = requireLeaderAndTaskEnabledVoid(team, user);
        if (guard != null) {
            return guard;
        }
        String action = body == null ? "" : body.getOrDefault("action", "");
        if ("COMPLETED".equals(action)) {
            task.setStatus("COMPLETED");
            task.setUpdatedAt(new Date());
            task.setCompletedAt(new Date());
            teamTaskService.updateById(task);
            return ResponseEntity.ok(Result.ok("\u4efb\u52a1\u5df2\u786e\u8ba4\u5b8c\u6210", null));
        }
        if ("RETURNED".equals(action)) {
            task.setStatus("IN_PROGRESS");
            task.setUpdatedAt(new Date());
            teamTaskService.updateById(task);
            return ResponseEntity.ok(Result.ok("\u5df2\u9000\u56de\u4fee\u6539", null));
        }
        return ResponseEntity.ok(Result.badRequest("\u65e0\u6548\u64cd\u4f5c"));
    }

    @GetMapping("/tasks/{taskId}/submissions")
    @ResponseBody
    public ResponseEntity<Result<List<Map<String, Object>>>> getTaskSubmissions(@PathVariable Long taskId,
                                                                                 HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!canAccessTask(team, task, user)) {
            return ResponseEntity.ok(Result.forbidden());
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
        return ResponseEntity.ok(Result.ok(MSG_SUCCESS, rows));
    }

    @PostMapping("/tasks/{taskId}/submit")
    @ResponseBody
    public ResponseEntity<Result<Void>> submitTask(@PathVariable Long taskId,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamTask task = teamTaskService.getById(taskId);
        if (task == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TASK_NOT_FOUND));
        }
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!isTaskFeatureEnabled(team)) {
            return ResponseEntity.ok(Result.badRequest(MSG_TASK_DISABLED));
        }
        if (task.getAssignedTo() == null || !task.getAssignedTo().equals(user.getId())) {
            return ResponseEntity.ok(Result.fail(403, "\u4ec5\u88ab\u6307\u6d3e\u7684\u961f\u5458\u53ef\u4ee5\u63d0\u4ea4"));
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
        return ResponseEntity.ok(Result.ok("\u63d0\u4ea4\u6210\u529f\uff0c\u7b49\u5f85\u961f\u957f\u5ba1\u6838", null));
    }

    private ResponseEntity<Result<List<TeamTaskVO>>> getVisibleTasks(Long teamId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        TeamDemand team = teamDemandService.getById(teamId);
        if (team == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TEAM_NOT_FOUND));
        }
        if (!isTaskFeatureEnabled(team)) {
            return ResponseEntity.ok(Result.badRequest(MSG_TASK_DISABLED));
        }
        if (!isTeamParticipant(team, user)) {
            return ResponseEntity.ok(Result.forbidden());
        }
        List<TeamTaskVO> tasks = teamTaskService.getTasksByTeam(teamId);
        if (!isTeamLeader(team, user)) {
            tasks = tasks.stream()
                .filter(task -> task.getAssignedTo() != null && task.getAssignedTo().equals(user.getId()))
                .collect(Collectors.toList());
        }
        return ResponseEntity.ok(Result.ok(MSG_SUCCESS, tasks));
    }

    private ResponseEntity<Result<TeamTaskVO>> requireLeaderAndTaskEnabled(TeamDemand team, User user) {
        if (team == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TEAM_NOT_FOUND));
        }
        if (!isTaskFeatureEnabled(team)) {
            return ResponseEntity.ok(Result.badRequest(MSG_TASK_DISABLED));
        }
        if (!isTeamLeader(team, user)) {
            return ResponseEntity.ok(Result.forbidden());
        }
        return null;
    }

    private ResponseEntity<Result<Void>> requireLeaderAndTaskEnabledVoid(TeamDemand team, User user) {
        if (team == null) {
            return ResponseEntity.ok(Result.notFound(MSG_TEAM_NOT_FOUND));
        }
        if (!isTaskFeatureEnabled(team)) {
            return ResponseEntity.ok(Result.badRequest(MSG_TASK_DISABLED));
        }
        if (!isTeamLeader(team, user)) {
            return ResponseEntity.ok(Result.forbidden());
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
