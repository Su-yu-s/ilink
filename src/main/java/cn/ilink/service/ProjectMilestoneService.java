package cn.ilink.service;

import cn.ilink.dto.ProjectMilestoneDTO;
import cn.ilink.entity.ProjectMilestone;
import cn.ilink.vo.ProjectMilestoneVO;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

public interface ProjectMilestoneService extends IService<ProjectMilestone> {
    List<ProjectMilestoneVO> getByTeam(Long teamId);
    ProjectMilestoneVO getById(Long id);
    boolean create(ProjectMilestoneDTO dto, Long userId);
    boolean update(Long id, ProjectMilestoneDTO dto);
    boolean updateProgress(Long id, int progress);
    boolean delete(Long id);
}
