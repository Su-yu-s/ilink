package cn.ilink.service.impl;

import cn.ilink.dto.TeamTaskDTO;
import cn.ilink.entity.TaskComment;
import cn.ilink.entity.TaskParticipant;
import cn.ilink.entity.TeamTask;
import cn.ilink.entity.User;
import cn.ilink.mapper.TaskCommentMapper;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.UserService;
import cn.ilink.vo.TaskCommentVO;
import cn.ilink.vo.TaskParticipantVO;
import cn.ilink.vo.TeamTaskVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TeamTaskServiceImpl extends ServiceImpl<TeamTaskMapper, TeamTask> implements TeamTaskService {

    @Autowired
    private TaskParticipantMapper taskParticipantMapper;

    @Autowired
    private TaskCommentMapper taskCommentMapper;

    @Autowired
    private UserService userService;

    @Override
    public List<TeamTaskVO> getTasksByTeam(Long teamId) {
        LambdaQueryWrapper<TeamTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TeamTask::getTeamId, teamId)
               .orderByDesc(TeamTask::getCreatedAt);
        List<TeamTask> tasks = this.list(wrapper);
        return tasks.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public TeamTaskVO getTaskById(Long taskId) {
        TeamTask task = this.getById(taskId);
        if (task == null) {
            return null;
        }
        return convertToVO(task);
    }

    @Override
    @Transactional
    public boolean createTask(TeamTaskDTO dto, Long userId) {
        TeamTask task = new TeamTask();
        task.setTeamId(dto.getTeamId());
        task.setTaskTitle(dto.getTaskTitle());
        task.setTaskDescription(dto.getTaskDescription());
        task.setTaskType(dto.getTaskType() != null ? dto.getTaskType() : "OTHER");
        task.setPriority(dto.getPriority() != null ? dto.getPriority() : 2);
        task.setStatus("PENDING");
        task.setEstimatedHours(dto.getEstimatedHours());
        task.setDeadline(dto.getDeadline());
        task.setAssignedTo(dto.getAssignedTo());
        task.setCreatedBy(userId);
        task.setCreatedAt(new Date());
        task.setUpdatedAt(new Date());

        boolean saved = this.save(task);
        if (saved && dto.getAssignedTo() != null) {
            TaskParticipant participant = new TaskParticipant();
            participant.setTaskId(task.getId());
            participant.setUserId(dto.getAssignedTo());
            participant.setRole("lead");
            participant.setStatus("active");
            participant.setJoinedAt(new Date());
            taskParticipantMapper.insert(participant);
        }
        TaskParticipant creator = new TaskParticipant();
        creator.setTaskId(task.getId());
        creator.setUserId(userId);
        creator.setRole("owner");
        creator.setStatus("active");
        creator.setJoinedAt(new Date());
        taskParticipantMapper.insert(creator);

        return saved;
    }

    @Override
    @Transactional
    public boolean updateTask(Long taskId, TeamTaskDTO dto) {
        TeamTask task = this.getById(taskId);
        if (task == null) {
            return false;
        }
        if (dto.getTaskTitle() != null) {
            task.setTaskTitle(dto.getTaskTitle());
        }
        if (dto.getTaskDescription() != null) {
            task.setTaskDescription(dto.getTaskDescription());
        }
        if (dto.getTaskType() != null) {
            task.setTaskType(dto.getTaskType());
        }
        if (dto.getPriority() != null) {
            task.setPriority(dto.getPriority());
        }
        if (dto.getEstimatedHours() != null) {
            task.setEstimatedHours(dto.getEstimatedHours());
        }
        if (dto.getDeadline() != null) {
            task.setDeadline(dto.getDeadline());
        }
        if (dto.getAssignedTo() != null) {
            task.setAssignedTo(dto.getAssignedTo());
        }
        task.setUpdatedAt(new Date());
        return this.updateById(task);
    }

    @Override
    @Transactional
    public boolean updateTaskStatus(Long taskId, String status) {
        TeamTask task = this.getById(taskId);
        if (task == null) {
            return false;
        }
        task.setStatus(status.toUpperCase());
        task.setUpdatedAt(new Date());
        if ("COMPLETED".equals(status.toUpperCase())) {
            task.setCompletedAt(new Date());
        }
        return this.updateById(task);
    }

    @Override
    @Transactional
    public boolean deleteTask(Long taskId) {
        LambdaQueryWrapper<TaskParticipant> participantWrapper = new LambdaQueryWrapper<>();
        participantWrapper.eq(TaskParticipant::getTaskId, taskId);
        taskParticipantMapper.delete(participantWrapper);

        LambdaQueryWrapper<TaskComment> commentWrapper = new LambdaQueryWrapper<>();
        commentWrapper.eq(TaskComment::getTaskId, taskId);
        taskCommentMapper.delete(commentWrapper);

        return this.removeById(taskId);
    }

    @Override
    @Transactional
    public boolean assignTask(Long taskId, Long userId) {
        TeamTask task = this.getById(taskId);
        if (task == null) {
            return false;
        }
        task.setAssignedTo(userId);
        task.setUpdatedAt(new Date());
        boolean updated = this.updateById(task);

        LambdaQueryWrapper<TaskParticipant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskParticipant::getTaskId, taskId)
               .eq(TaskParticipant::getUserId, userId);
        TaskParticipant existing = taskParticipantMapper.selectOne(wrapper);

        if (existing == null) {
            TaskParticipant participant = new TaskParticipant();
            participant.setTaskId(taskId);
            participant.setUserId(userId);
            participant.setRole("lead");
            participant.setStatus("active");
            participant.setJoinedAt(new Date());
            taskParticipantMapper.insert(participant);
        }
        return updated;
    }

    @Override
    public List<TaskParticipantVO> getParticipants(Long taskId) {
        LambdaQueryWrapper<TaskParticipant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskParticipant::getTaskId, taskId);
        List<TaskParticipant> participants = taskParticipantMapper.selectList(wrapper);

        Set<Long> userIds = participants.stream()
                .map(TaskParticipant::getUserId)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<User> users = userService.listByIds(userIds);
            for (User user : users) {
                userMap.put(user.getId(), user);
            }
        }

        return participants.stream().map(p -> convertToParticipantVO(p, userMap.get(p.getUserId())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public boolean addParticipant(Long taskId, Long userId, String role) {
        LambdaQueryWrapper<TaskParticipant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskParticipant::getTaskId, taskId)
               .eq(TaskParticipant::getUserId, userId);
        TaskParticipant existing = taskParticipantMapper.selectOne(wrapper);

        if (existing != null) {
            return false;
        }

        TaskParticipant participant = new TaskParticipant();
        participant.setTaskId(taskId);
        participant.setUserId(userId);
        participant.setRole(role != null ? role : "member");
        participant.setStatus("active");
        participant.setJoinedAt(new Date());
        return taskParticipantMapper.insert(participant) > 0;
    }

    @Override
    @Transactional
    public boolean removeParticipant(Long taskId, Long userId) {
        LambdaQueryWrapper<TaskParticipant> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskParticipant::getTaskId, taskId)
               .eq(TaskParticipant::getUserId, userId);
        return taskParticipantMapper.delete(wrapper) > 0;
    }

    @Override
    public List<TaskCommentVO> getComments(Long taskId) {
        LambdaQueryWrapper<TaskComment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TaskComment::getTaskId, taskId)
               .eq(TaskComment::getIsDeleted, 0)
               .orderByAsc(TaskComment::getCreatedAt);
        List<TaskComment> comments = taskCommentMapper.selectList(wrapper);

        Set<Long> userIds = comments.stream()
                .map(TaskComment::getUserId)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            List<User> users = userService.listByIds(userIds);
            for (User user : users) {
                userMap.put(user.getId(), user);
            }
        }

        List<TaskCommentVO> allComments = comments.stream()
                .map(c -> convertToCommentVO(c, userMap.get(c.getUserId())))
                .collect(Collectors.toList());

        List<TaskCommentVO> rootComments = allComments.stream()
                .filter(c -> c.getParentId() == null)
                .collect(Collectors.toList());

        Map<Long, List<TaskCommentVO>> repliesMap = allComments.stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(TaskCommentVO::getParentId));

        rootComments.forEach(root -> root.setReplies(repliesMap.getOrDefault(root.getId(), new ArrayList<>())));

        return rootComments;
    }

    @Override
    @Transactional
    public TaskCommentVO addComment(Long taskId, Long userId, String content, Long parentId) {
        TaskComment comment = new TaskComment();
        comment.setTaskId(taskId);
        comment.setUserId(userId);
        comment.setContent(content);
        comment.setParentId(parentId);
        comment.setCommentType(parentId != null ? "reply" : "comment");
        comment.setLikeCount(0);
        comment.setIsDeleted(0);
        comment.setCreatedAt(new Date());
        comment.setUpdatedAt(new Date());

        taskCommentMapper.insert(comment);
        User user = userService.getById(userId);
        return convertToCommentVO(comment, user);
    }

    private TeamTaskVO convertToVO(TeamTask task) {
        TeamTaskVO vo = new TeamTaskVO();
        vo.setId(task.getId());
        vo.setTeamId(task.getTeamId());
        vo.setTaskTitle(task.getTaskTitle());
        vo.setTaskDescription(task.getTaskDescription());
        vo.setTaskType(task.getTaskType());
        vo.setPriority(task.getPriority());
        vo.setStatus(task.getStatus());
        vo.setEstimatedHours(task.getEstimatedHours());
        vo.setActualHours(task.getActualHours());
        vo.setDeadline(task.getDeadline());
        vo.setAssignedTo(task.getAssignedTo());
        vo.setCreatedBy(task.getCreatedBy());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setUpdatedAt(task.getUpdatedAt());
        vo.setCompletedAt(task.getCompletedAt());

        if (task.getTaskType() != null) {
            try {
                TeamTask.TaskType type = TeamTask.TaskType.valueOf(task.getTaskType().toUpperCase());
                vo.setTaskTypeDesc(type.getDescription());
            } catch (IllegalArgumentException e) {
                vo.setTaskTypeDesc(task.getTaskType());
            }
        }

        if (task.getPriority() != null) {
            TeamTask.TaskPriority priority = null;
            switch (task.getPriority()) {
                case 1: priority = TeamTask.TaskPriority.LOW; break;
                case 2: priority = TeamTask.TaskPriority.MEDIUM; break;
                case 3: priority = TeamTask.TaskPriority.HIGH; break;
                case 4: priority = TeamTask.TaskPriority.URGENT; break;
            }
            if (priority != null) {
                vo.setPriorityDesc(priority.getDescription());
            }
        }

        if (task.getStatus() != null) {
            try {
                TeamTask.TaskStatus status = TeamTask.TaskStatus.valueOf(task.getStatus().toUpperCase());
                vo.setStatusDesc(status.getDescription());
            } catch (IllegalArgumentException e) {
                vo.setStatusDesc(task.getStatus());
            }
        }

        if (task.getAssignedTo() != null) {
            User assignee = userService.getById(task.getAssignedTo());
            if (assignee != null) {
                vo.setAssigneeName(assignee.getRealName() != null ? assignee.getRealName() : assignee.getUsername());
                vo.setAssigneeAvatar(assignee.getAvatar());
            }
        }

        if (task.getCreatedBy() != null) {
            User creator = userService.getById(task.getCreatedBy());
            if (creator != null) {
                vo.setCreatorName(creator.getRealName() != null ? creator.getRealName() : creator.getUsername());
                vo.setCreatorAvatar(creator.getAvatar());
            }
        }

        return vo;
    }

    private TaskParticipantVO convertToParticipantVO(TaskParticipant participant, User user) {
        TaskParticipantVO vo = new TaskParticipantVO();
        vo.setId(participant.getId());
        vo.setTaskId(participant.getTaskId());
        vo.setUserId(participant.getUserId());
        vo.setRole(participant.getRole());
        vo.setContributionHours(participant.getContributionHours() != null ? participant.getContributionHours().doubleValue() : null);
        vo.setContributionRate(participant.getContributionRate() != null ? participant.getContributionRate().doubleValue() : null);
        vo.setJoinedAt(participant.getJoinedAt());
        vo.setStatus(participant.getStatus());

        if (user != null) {
            vo.setUsername(user.getUsername());
            vo.setRealName(user.getRealName());
            vo.setAvatar(user.getAvatar());
            vo.setMajor(user.getMajor());
        }

        if (participant.getRole() != null) {
            try {
                TaskParticipant.ParticipantRole role = TaskParticipant.ParticipantRole.valueOf(participant.getRole().toUpperCase());
                vo.setRoleDesc(role.getDescription());
            } catch (IllegalArgumentException e) {
                vo.setRoleDesc(participant.getRole());
            }
        }

        return vo;
    }

    private TaskCommentVO convertToCommentVO(TaskComment comment, User user) {
        TaskCommentVO vo = new TaskCommentVO();
        vo.setId(comment.getId());
        vo.setTaskId(comment.getTaskId());
        vo.setParentId(comment.getParentId());
        vo.setUserId(comment.getUserId());
        vo.setContent(comment.getContent());
        vo.setCommentType(comment.getCommentType());
        vo.setLikeCount(comment.getLikeCount());
        vo.setCreatedAt(comment.getCreatedAt());
        vo.setUpdatedAt(comment.getUpdatedAt());
        vo.setReplies(new ArrayList<>());

        if (user != null) {
            vo.setUsername(user.getUsername());
            vo.setRealName(user.getRealName());
            vo.setAvatar(user.getAvatar());
        }

        if (comment.getCommentType() != null) {
            try {
                TaskComment.CommentType type = TaskComment.CommentType.valueOf(comment.getCommentType().toUpperCase());
                vo.setCommentTypeDesc(type.getDescription());
            } catch (IllegalArgumentException e) {
                vo.setCommentTypeDesc(comment.getCommentType());
            }
        }

        return vo;
    }
}
