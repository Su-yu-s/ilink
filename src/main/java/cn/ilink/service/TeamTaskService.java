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
    TeamTaskVO createTask(TeamTaskDTO dto, Long userId);
    boolean updateTask(Long taskId, TeamTaskDTO dto);
    boolean updateTaskStatus(Long taskId, String status);
    boolean transitionTaskStatus(Long taskId, String expectedStatus, String targetStatus);
    boolean deleteTask(Long taskId);
    boolean assignTask(Long taskId, Long userId);
    List<TaskParticipantVO> getParticipants(Long taskId);
    /**
     * 把成员加入任务参与名单。
     *
     * <p><b>幂等</b>：该成员已在名单中时同样返回 {@code true}（未新增但语义成功），
     * 避免"先指派再手动添加"被上层当成失败而返回 500（核实报告 P1-3）。
     *
     * @return true 表示目标状态已达成（新增成功或本就在名单中）；false 仅表示写入失败
     */
    boolean addParticipant(Long taskId, Long userId, String role);
    boolean removeParticipant(Long taskId, Long userId);
    /** 成员退出/被移出团队时，删除其在团队全部任务下的参与者行（修复 D-P2-3 越权残留） */
    void removeParticipantFromTeamTasks(Long teamId, Long userId);
    List<TaskCommentVO> getComments(Long taskId);
    TaskCommentVO addComment(Long taskId, Long userId, String content, Long parentId);
}
