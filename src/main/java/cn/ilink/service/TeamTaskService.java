package cn.ilink.service;

import cn.ilink.dto.TeamTaskDTO;
import cn.ilink.entity.TeamTask;
import cn.ilink.vo.TeamTaskVO;
import cn.ilink.vo.TaskCommentVO;
import cn.ilink.vo.TaskParticipantVO;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

public interface TeamTaskService extends IService<TeamTask> {
    List<TeamTaskVO> getTasksByTeam(Long teamId);
    TeamTaskVO getTaskById(Long taskId);
    boolean createTask(TeamTaskDTO dto, Long userId);
    boolean updateTask(Long taskId, TeamTaskDTO dto);
    boolean updateTaskStatus(Long taskId, String status);
    boolean deleteTask(Long taskId);
    boolean assignTask(Long taskId, Long userId);
    List<TaskParticipantVO> getParticipants(Long taskId);
    boolean addParticipant(Long taskId, Long userId, String role);
    boolean removeParticipant(Long taskId, Long userId);
    List<TaskCommentVO> getComments(Long taskId);
    TaskCommentVO addComment(Long taskId, Long userId, String content, Long parentId);
}
