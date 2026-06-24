package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.ProjectMilestoneDTO;
import cn.ilink.entity.ProjectMilestone;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.User;
import cn.ilink.service.ProjectMilestoneService;
import cn.ilink.service.TeamApplicationService;
import cn.ilink.vo.ProjectMilestoneVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/api")
public class ProjectMilestoneController {

    @Autowired
    private ProjectMilestoneService projectMilestoneService;

    @Autowired
    private TeamApplicationService teamApplicationService;

    @GetMapping("/team/{teamId}/milestones")
    @ResponseBody
    public ResponseEntity<Result<List<ProjectMilestoneVO>>> getMilestonesByTeam(@PathVariable Long teamId) {
        List<ProjectMilestoneVO> milestones = projectMilestoneService.getByTeam(teamId);
        return ResponseEntity.ok(Result.ok("获取成功", milestones));
    }

    @GetMapping("/milestones/{id}")
    @ResponseBody
    public ResponseEntity<Result<ProjectMilestoneVO>> getMilestoneById(@PathVariable Long id) {
        ProjectMilestoneVO milestone = projectMilestoneService.getById(id);
        if (milestone != null) {
            return ResponseEntity.ok(Result.ok("获取成功", milestone));
        } else {
            return ResponseEntity.ok(Result.notFound("里程碑不存在"));
        }
    }

    @PostMapping("/team/{teamId}/milestones")
    @ResponseBody
    public ResponseEntity<Result<ProjectMilestoneVO>> createMilestone(@PathVariable Long teamId,
                                                                      @RequestBody ProjectMilestoneDTO dto,
                                                                      HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        // C-04: 校验用户是否为团队成员
        if (!isTeamMember(teamId, user)) {
            return ResponseEntity.ok(Result.fail(403, "您不是该团队成员，无法创建里程碑"));
        }

        if (dto.getMilestoneName() == null || dto.getMilestoneName().trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("里程碑名称不能为空"));
        }

        dto.setTeamId(teamId);
        boolean success = projectMilestoneService.create(dto, user.getId());
        if (success) {
            List<ProjectMilestoneVO> milestones = projectMilestoneService.getByTeam(teamId);
            ProjectMilestoneVO created = milestones.stream()
                    .filter(m -> m.getMilestoneName().equals(dto.getMilestoneName()))
                    .findFirst()
                    .orElse(null);
            return ResponseEntity.ok(Result.ok("里程碑创建成功", created));
        } else {
            return ResponseEntity.ok(Result.fail("里程碑创建失败"));
        }
    }

    @PutMapping("/milestones/{id}")
    @ResponseBody
    public ResponseEntity<Result<ProjectMilestoneVO>> updateMilestone(@PathVariable Long id,
                                                                      @RequestBody ProjectMilestoneDTO dto,
                                                                      HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        ProjectMilestoneVO milestone = projectMilestoneService.getById(id);
        if (milestone == null) {
            return ResponseEntity.ok(Result.notFound("里程碑不存在"));
        }
        // C-04: 校验用户是否为团队成员
        if (!isTeamMember(milestone.getTeamId(), user)) {
            return ResponseEntity.ok(Result.fail(403, "您不是该团队成员，无权编辑里程碑"));
        }

        boolean success = projectMilestoneService.update(id, dto);
        if (success) {
            ProjectMilestoneVO updated = projectMilestoneService.getById(id);
            return ResponseEntity.ok(Result.ok("里程碑更新成功", updated));
        } else {
            return ResponseEntity.ok(Result.notFound("里程碑不存在"));
        }
    }

    @PutMapping("/milestones/{id}/progress")
    @ResponseBody
    public ResponseEntity<Result<Void>> updateMilestoneProgress(@PathVariable Long id,
                                                               @RequestBody Map<String, Integer> body,
                                                               HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        Integer progress = body.get("progress");
        if (progress == null) {
            return ResponseEntity.ok(Result.badRequest("进度不能为空"));
        }

        ProjectMilestoneVO milestone = projectMilestoneService.getById(id);
        if (milestone == null) {
            return ResponseEntity.ok(Result.notFound("里程碑不存在"));
        }
        // C-04: 校验用户是否为团队成员
        if (!isTeamMember(milestone.getTeamId(), user)) {
            return ResponseEntity.ok(Result.fail(403, "您不是该团队成员，无权修改里程碑进度"));
        }

        boolean success = projectMilestoneService.updateProgress(id, progress);
        if (success) {
            return ResponseEntity.ok(Result.ok("进度更新成功", null));
        } else {
            return ResponseEntity.ok(Result.notFound("里程碑不存在"));
        }
    }

    @DeleteMapping("/milestones/{id}")
    @ResponseBody
    public ResponseEntity<Result<Void>> deleteMilestone(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        ProjectMilestoneVO milestone = projectMilestoneService.getById(id);
        if (milestone == null) {
            return ResponseEntity.ok(Result.notFound("里程碑不存在"));
        }
        // C-04: 校验用户是否为团队成员
        if (!isTeamMember(milestone.getTeamId(), user)) {
            return ResponseEntity.ok(Result.fail(403, "您不是该团队成员，无权删除里程碑"));
        }

        boolean success = projectMilestoneService.delete(id);
        if (success) {
            return ResponseEntity.ok(Result.ok("里程碑删除成功", null));
        } else {
            return ResponseEntity.ok(Result.notFound("里程碑不存在"));
        }
    }

    /** C-04: 校验用户是否为指定团队的成员 */
    private boolean isTeamMember(Long teamId, User user) {
        if (teamId == null) return false;
        LambdaQueryWrapper<TeamApplication> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TeamApplication::getTeamId, teamId)
               .eq(TeamApplication::getUserId, user.getId())
               .eq(TeamApplication::getStatus, "APPROVED");
        return teamApplicationService.count(wrapper) > 0;
    }
}
