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
        if (team == null) {
            return Result.notFound(MSG_TEAM_NOT_FOUND).toResponseEntity();
        }
        if (!isTaskFeatureEnabled(team)) {
            return Result.badRequest(MSG_TASK_DISABLED).toResponseEntity();
        }
        if (!isTeamLeader(team, user)) {
            // 非队长仅允许创建"分配给自己"的任务：被分配者本人拆解子任务/自建任务时使用
            boolean selfAssign = dto != null && dto.getAssignedTo() != null
                && Objects.equals(dto.getAssignedTo(), user.getId());
            if (!selfAssign) {
                return Result.forbidden().toResponseEntity();
            }
        }
        if (dto == null || dto.getTaskTitle() == null || dto.getTaskTitle().trim().isEmpty()) {
            return Result.badRequest("\u4efb\u52a1\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a").toResponseEntity();
        }
        String fieldError = validateTaskFields(dto);
        if (fieldError != null) {
            return Result.badRequest(fieldError).toResponseEntity();
        }
        if (dto.getAssignedTo() == null || !isTeamParticipantByUserId(teamId, dto.getAssignedTo())) {
            return Result.badRequest(MSG_VALID_ASSIGNEE).toResponseEntity();
        }
        // AI 拆解只允许一层：子任务不能再拆，否则任务会无限套娃
        if (dto.getParentTaskId() != null) {
            TeamTask parent = teamTaskService.getById(dto.getParentTaskId());
            if (parent == null || !Objects.equals(parent.getTeamId(), teamId)) {
                return Result.badRequest("父任务不存在").toResponseEntity();
            }
            if (parent.getParentTaskId() != null) {
                return Result.badRequest("子任务不能再拆解").toResponseEntity();
            }
        }
        dto.setTeamId(teamId);
        TeamTaskVO task = teamTaskService.createTask(dto, user.getId());
        // 任务入知识图谱索引队列：TEAM scope 仅团队成员可见（失败不影响创建）
        if (task != null && task.getId() != null) {
            kgIndexService.enqueue("TASK", task.getId(), dto.getTaskTitle(), dto.getTaskDescription(),
                cn.ilink.entity.KgEntity.SCOPE_TEAM, teamId, user.getId());
        }
        // 给被指派人发送任务分配通知（自己给自己分配的不通知）
        Long assigneeId = dto.getAssignedTo();
        if (!Objects.equals(assigneeId, user.getId())) {
            notificationService.create(
                assigneeId,
                user.getId(),
                "TASK_ASSIGNED",
                "新任务指派",
                DisplayNameResolver.resolve(user) + " 给你分配了任务「" + dto.getTaskTitle() + "」",
                null);
        }
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
        String fieldError = validateTaskFields(dto);
        if (fieldError != null) {
            return Result.badRequest(fieldError).toResponseEntity();
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
        // 状态推进是写操作，只认任务的直接关系人：导师可批阅但不应替队员推进进度
        if (!canOperateTask(team, task, user)) {
            return Result.forbidden().toResponseEntity();
        }
        String targetStatus = dto.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!"IN_PROGRESS".equals(targetStatus)) {
            return Result.badRequest("请通过提交与审核操作推进任务状态").toResponseEntity();
        }
        if (!"PENDING".equals(task.getStatus())) {
            return Result.badRequest("当前任务状态不能开始").toResponseEntity();
        }
        boolean success = teamTaskService.updateTaskStatus(taskId, targetStatus);
        return success ? Result.ok(MSG_SUCCESS, null).toResponseEntity()
            : Result.badRequest("任务状态已变化，请刷新后重试").toResponseEntity();
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
                String assigneeName = assignee != null ? DisplayNameResolver.resolve(assignee) : "队员";
                notificationService.create(
                    dto.getUserId(),
                    user.getId(),
                    "TASK_ASSIGNED",
                    "新任务指派",
                    DisplayNameResolver.resolve(user) + " 给你分配了任务「" + assignedTask.getTaskTitle() + "」",
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!isTaskFeatureEnabled(team)) {
            return Result.badRequest(MSG_TASK_DISABLED).toResponseEntity();
        }
        // 批阅权限：队长或导师（导师与队长平级，不区分权威）
        boolean isLeader = isTeamLeader(team, user);
        boolean isMentor = isApprovedMentor(team.getId(), user.getId());
        if (!isLeader && !isMentor) {
            return Result.fail(403, "只有队长或导师可以批阅任务").toResponseEntity();
        }
        if (!"REVIEW".equals(task.getStatus()) && !"COMMENT".equals(body == null ? "" : strOf(body.get("action")))) {
            return Result.badRequest("只有待审核任务可以执行审核").toResponseEntity();
        }
        String action = body == null ? "" : strOf(body.get("action"));
        if (!"COMPLETED".equals(action) && !"RETURNED".equals(action) && !"COMMENT".equals(action)) {
            return Result.badRequest("\u65e0\u6548\u64cd\u4f5c").toResponseEntity();
        }
        String overall = strOf(body.get("overall"));
        if (overall != null) overall = overall.trim();
        Object fileComments = body == null ? null : body.get("fileComments");

        // 驳回是否定动作，必须给出理由：整体点评或任一逐文件点评非空
        if ("RETURNED".equals(action) && (overall == null || overall.isEmpty()) && !hasAnyFileComment(fileComments)) {
            return Result.badRequest("驳回时必须至少填写一条点评（整体点评或针对文件的点评）").toResponseEntity();
        }

        // COMMENT 为补充点评，不改变任务状态；通过/驳回沿用原状态机
        if ("COMPLETED".equals(action)) {
            if (!teamTaskService.transitionTaskStatus(taskId, "REVIEW", "COMPLETED")) {
                return Result.badRequest("任务状态已变化，请刷新后重试").toResponseEntity();
            }
        } else if ("RETURNED".equals(action)) {
            if (!teamTaskService.transitionTaskStatus(taskId, "REVIEW", "IN_PROGRESS")) {
                return Result.badRequest("任务状态已变化，请刷新后重试").toResponseEntity();
            }
        }

        // 批阅留痕：时间线/逐文件点评/驳回理由的唯一数据源
        TaskReview review = new TaskReview();
        review.setTaskId(taskId);
        review.setSubmissionId(longOf(body == null ? null : body.get("submissionId")));
        review.setReviewerId(user.getId());
        review.setVerdict("COMMENT".equals(action) ? "COMMENT" : ("COMPLETED".equals(action) ? "PASS" : "REJECT"));
        review.setOverallComment(overall == null || overall.isEmpty() ? null : overall);
        review.setFileComments(fileComments == null ? null : serializeAttachments(fileComments));
        review.setCreatedAt(new Date());
        taskReviewMapper.insert(review);

        // 通知提交者（最近一次提交的人）
        TaskSubmission latest = taskSubmissionMapper.selectOne(new LambdaQueryWrapper<TaskSubmission>()
            .eq(TaskSubmission::getTaskId, taskId)
            .orderByDesc(TaskSubmission::getCreatedAt)
            .last("LIMIT 1"));
        Long submitterId = latest != null ? latest.getSubmitterId() : task.getAssignedTo();
        if (submitterId != null && !submitterId.equals(user.getId())) {
            String reviewerName = DisplayNameResolver.resolve(user);
            String verdictText;
            String content;
            if ("COMPLETED".equals(action)) {
                verdictText = "任务已通过";
                content = reviewerName + " 通过了任务「" + task.getTaskTitle() + "」"
                    + (overall == null || overall.isEmpty() ? "" : "：" + truncate(overall, 80));
            } else if ("RETURNED".equals(action)) {
                int commentCount = countFileComments(fileComments) + ((overall == null || overall.isEmpty()) ? 0 : 1);
                verdictText = "任务被驳回";
                content = reviewerName + " 驳回了任务「" + task.getTaskTitle() + "」，留下 " + commentCount + " 条修改建议";
            } else {
                verdictText = "收到新的批阅点评";
                content = reviewerName + " 对任务「" + task.getTaskTitle() + "」补充了批阅点评";
            }
            notificationService.create(submitterId, user.getId(), "TASK_REVIEWED", verdictText, content, null);
        }
        return Result.ok("COMMENT".equals(action) ? "点评已保存"
            : ("COMPLETED".equals(action) ? "\u4efb\u52a1\u5df2\u786e\u8ba4\u5b8c\u6210" : "\u5df2\u9000\u56de\u4fee\u6539"),
            null).toResponseEntity();
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!isTaskFeatureEnabled(team)) {
            return Result.badRequest(MSG_TASK_DISABLED).toResponseEntity();
        }
        if (task.getAssignedTo() == null || !task.getAssignedTo().equals(user.getId())) {
            return Result.fail(403, "\u4ec5\u88ab\u6307\u6d3e\u7684\u961f\u5458\u53ef\u4ee5\u5b8c\u6210\u4efb\u52a1").toResponseEntity();
        }
        if (Boolean.TRUE.equals(task.getRequiresReview())) {
            return Result.badRequest("该任务需要提交审阅，请先交付材料").toResponseEntity();
        }
        if (!teamTaskService.transitionTaskStatus(taskId, "IN_PROGRESS", "COMPLETED")) {
            return Result.badRequest("任务状态已变化，请刷新后重试").toResponseEntity();
        }
        return Result.ok("任务已完成", null).toResponseEntity();
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
        TeamDemand team = teamDemandService.getById(task.getTeamId());
        if (!isTaskFeatureEnabled(team)) {
            return Result.badRequest(MSG_TASK_DISABLED).toResponseEntity();
        }
        if (task.getAssignedTo() == null || !task.getAssignedTo().equals(user.getId())) {
            return Result.fail(403, "\u4ec5\u88ab\u6307\u6d3e\u7684\u961f\u5458\u53ef\u4ee5\u63d0\u4ea4").toResponseEntity();
        }
        if (!"IN_PROGRESS".equals(task.getStatus())) {
            return Result.badRequest("请先开始任务，再提交审核").toResponseEntity();
        }

        String content = body == null ? "" : String.valueOf(body.getOrDefault("remark", body.getOrDefault("content", "")));
        if (!teamTaskService.transitionTaskStatus(taskId, "IN_PROGRESS", "REVIEW")) {
            return Result.badRequest("任务状态已变化，请刷新后重试").toResponseEntity();
        }

        TaskSubmission submission = new TaskSubmission();
        submission.setTaskId(taskId);
        submission.setSubmitterId(user.getId());
        submission.setContent(content == null ? "" : content.trim());
        submission.setAttachments(serializeAttachments(body == null ? null : body.get("attachments")));
        submission.setCreatedAt(new Date());
        taskSubmissionMapper.insert(submission);

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
                DisplayNameResolver.resolve(user) + " 提交了任务「" + task.getTaskTitle() + "」，请审核",
                null);
        }
        // 导师也有批阅权，同样收到提交通知
        for (Long mentorId : approvedMentorIds(task.getTeamId())) {
            if (!mentorId.equals(user.getId()) && !mentorId.equals(leaderId)) {
                notificationService.create(
                    mentorId,
                    user.getId(),
                    "TASK_SUBMITTED",
                    "任务已提交",
                    DisplayNameResolver.resolve(user) + " 提交了任务「" + task.getTaskTitle() + "」，请批阅",
                    null);
            }
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
        if (!isTaskFeatureEnabled(team) || task == null || user == null) {
            return false;
        }
        // 导师与队长平级、有批阅权，必须能看任务的提交材料与批阅时间线，
        // 否则新加入的导师点开批阅工作台只会看到「这个提交没有附件材料」
        return canOperateTask(team, task, user) || isApprovedMentor(team.getId(), user.getId());
    }

    /** 任务的直接关系人：队长、被指派人、参与者。状态推进这类写操作只认这些人 */
    private boolean canOperateTask(TeamDemand team, TeamTask task, User user) {
        if (!isTaskFeatureEnabled(team) || task == null || user == null) {
            return false;
        }
        if (isTeamLeader(team, user)) {
            return true;
        }
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
