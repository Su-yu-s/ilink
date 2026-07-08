package cn.ilink.service.impl;

import cn.ilink.dto.ProjectMilestoneDTO;
import cn.ilink.entity.ProjectMilestone;
import cn.ilink.mapper.ProjectMilestoneMapper;
import cn.ilink.service.ProjectMilestoneService;
import cn.ilink.service.UserService;
import cn.ilink.vo.ProjectMilestoneVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProjectMilestoneServiceImpl extends ServiceImpl<ProjectMilestoneMapper, ProjectMilestone> implements ProjectMilestoneService {

    @Autowired
    private UserService userService;

    @Override
    public List<ProjectMilestoneVO> getByTeam(Long teamId) {
        LambdaQueryWrapper<ProjectMilestone> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectMilestone::getTeamId, teamId)
               .orderByAsc(ProjectMilestone::getDueDate);
        List<ProjectMilestone> milestones = this.list(wrapper);
        return milestones.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public ProjectMilestoneVO getById(Long id) {
        ProjectMilestone milestone = this.baseMapper.selectById(id);
        if (milestone == null) {
            return null;
        }
        return convertToVO(milestone);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean create(ProjectMilestoneDTO dto, Long userId) {
        ProjectMilestone milestone = new ProjectMilestone();
        milestone.setTeamId(dto.getTeamId());
        milestone.setMilestoneName(dto.getMilestoneName());
        milestone.setMilestoneDescription(dto.getMilestoneDescription());
        milestone.setDueDate(dto.getDueDate());
        milestone.setCompletionRate(dto.getCompletionRate() != null ? dto.getCompletionRate() : 0);
        milestone.setDeliverables(dto.getDeliverables());
        milestone.setCreatedBy(userId);
        milestone.setCreatedAt(new Date());
        milestone.setUpdatedAt(new Date());

        String status = determineStatus(dto.getDueDate(), dto.getCompletionRate());
        milestone.setStatus(status);

        return this.save(milestone);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(Long id, ProjectMilestoneDTO dto) {
        ProjectMilestone milestone = this.baseMapper.selectById(id);
        if (milestone == null) {
            return false;
        }
        if (dto.getMilestoneName() != null) {
            milestone.setMilestoneName(dto.getMilestoneName());
        }
        if (dto.getMilestoneDescription() != null) {
            milestone.setMilestoneDescription(dto.getMilestoneDescription());
        }
        if (dto.getDueDate() != null) {
            milestone.setDueDate(dto.getDueDate());
        }
        if (dto.getCompletionRate() != null) {
            milestone.setCompletionRate(dto.getCompletionRate());
        }
        if (dto.getDeliverables() != null) {
            milestone.setDeliverables(dto.getDeliverables());
        }

        String status = determineStatus(milestone.getDueDate(), milestone.getCompletionRate());
        milestone.setStatus(status);

        if ("COMPLETED".equals(status)) {
            milestone.setCompletedDate(new Date());
        }

        milestone.setUpdatedAt(new Date());
        return this.updateById(milestone);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProgress(Long id, int progress) {
        ProjectMilestone milestone = this.baseMapper.selectById(id);
        if (milestone == null) {
            return false;
        }
        milestone.setCompletionRate(Math.min(100, Math.max(0, progress)));

        if (progress >= 100) {
            milestone.setStatus("COMPLETED");
            milestone.setCompletedDate(new Date());
        } else {
            milestone.setStatus(determineStatus(milestone.getDueDate(), progress));
        }

        milestone.setUpdatedAt(new Date());
        return this.updateById(milestone);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        return this.removeById(id);
    }

    private ProjectMilestoneVO convertToVO(ProjectMilestone milestone) {
        ProjectMilestoneVO vo = new ProjectMilestoneVO();
        vo.setId(milestone.getId());
        vo.setTeamId(milestone.getTeamId());
        vo.setMilestoneName(milestone.getMilestoneName());
        vo.setMilestoneDescription(milestone.getMilestoneDescription());
        vo.setDueDate(milestone.getDueDate());
        vo.setCompletedDate(milestone.getCompletedDate());
        vo.setCompletionRate(milestone.getCompletionRate());
        vo.setDeliverables(milestone.getDeliverables());
        vo.setCreatedBy(milestone.getCreatedBy());
        vo.setCreatedAt(milestone.getCreatedAt());
        vo.setUpdatedAt(milestone.getUpdatedAt());
        vo.setStatus(milestone.getStatus());

        if (milestone.getStatus() != null) {
            try {
                ProjectMilestone.MilestoneStatus status = ProjectMilestone.MilestoneStatus.valueOf(milestone.getStatus().toUpperCase());
                vo.setStatusDesc(status.getDescription());
            } catch (IllegalArgumentException e) {
                vo.setStatusDesc(milestone.getStatus());
            }
        }

        if (milestone.getCreatedBy() != null) {
            var creator = userService.getById(milestone.getCreatedBy());
            if (creator != null) {
                vo.setCreatorName(creator.getRealName() != null ? creator.getRealName() : creator.getUsername());
            }
        }

        return vo;
    }

    private String determineStatus(Date dueDate, Integer completionRate) {
        if (completionRate != null && completionRate >= 100) {
            return "COMPLETED";
        }
        Date now = new Date();
        if (dueDate != null && now.after(dueDate) && (completionRate == null || completionRate < 100)) {
            return "DELAYED";
        }
        if (completionRate != null && completionRate > 0) {
            return "IN_PROGRESS";
        }
        return "PENDING";
    }
}
