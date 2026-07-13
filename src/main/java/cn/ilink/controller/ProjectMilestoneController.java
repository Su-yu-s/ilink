package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.ProjectMilestoneDTO;
import cn.ilink.entity.User;
import cn.ilink.service.ProjectMilestoneService;
import cn.ilink.service.TeamAccessService;
import cn.ilink.vo.ProjectMilestoneVO;
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
    private TeamAccessService teamAccessService;

    @GetMapping("/team/{teamId}/milestones")
    @ResponseBody
    public ResponseEntity<Result<?>> getMilestonesByTeam(@PathVariable Long teamId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        if (!isTeamMember(teamId, user)) {
            return Result.forbidden().toResponseEntity();
        }
        List<ProjectMilestoneVO> milestones = projectMilestoneService.getByTeam(teamId);
        return Result.ok("获取成功", milestones).toResponseEntity();
    }

    @GetMapping("/milestones/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> getMilestoneById(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        ProjectMilestoneVO milestone = projectMilestoneService.getById(id);
        if (milestone != null) {
            if (!isTeamMember(milestone.getTeamId(), user)) {
                return Result.forbidden().toResponseEntity();
            }
            return Result.ok("获取成功", milestone).toResponseEntity();
        } else {
            return Result.notFound("里程碑不存在").toResponseEntity();
        }
    }

    @PostMapping("/team/{teamId}/milestones")
    @ResponseBody
    public ResponseEntity<Result<?>> createMilestone(@PathVariable Long teamId,
                                                                      @RequestBody ProjectMilestoneDTO dto,
                                                                      HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        // C-04: 校验用户是否为团队成员
        if (!isTeamMember(teamId, user)) {
            return Result.fail(403, "您不是该团队成员，无法创建里程碑").toResponseEntity();
        }

        if (dto.getMilestoneName() == null || dto.getMilestoneName().trim().isEmpty()) {
            return Result.badRequest("里程碑名称不能为空").toResponseEntity();
        }

        dto.setTeamId(teamId);
        boolean success = projectMilestoneService.create(dto, user.getId());
        if (success) {
            List<ProjectMilestoneVO> milestones = projectMilestoneService.getByTeam(teamId);
            ProjectMilestoneVO created = milestones.stream()
                    .filter(m -> m.getMilestoneName().equals(dto.getMilestoneName()))
                    .findFirst()
                    .orElse(null);
            return Result.ok("里程碑创建成功", created).toResponseEntity();
        } else {
            return Result.fail("里程碑创建失败").toResponseEntity();
        }
    }

    @PutMapping("/milestones/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> updateMilestone(@PathVariable Long id,
                                                                      @RequestBody ProjectMilestoneDTO dto,
                                                                      HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        ProjectMilestoneVO milestone = projectMilestoneService.getById(id);
        if (milestone == null) {
            return Result.notFound("里程碑不存在").toResponseEntity();
        }
        // C-04: 校验用户是否为团队成员
        if (!isTeamMember(milestone.getTeamId(), user)) {
            return Result.fail(403, "您不是该团队成员，无权编辑里程碑").toResponseEntity();
        }

        boolean success = projectMilestoneService.update(id, dto);
        if (success) {
            ProjectMilestoneVO updated = projectMilestoneService.getById(id);
            return Result.ok("里程碑更新成功", updated).toResponseEntity();
        } else {
            return Result.notFound("里程碑不存在").toResponseEntity();
        }
    }

    @PutMapping("/milestones/{id}/progress")
    @ResponseBody
    public ResponseEntity<Result<?>> updateMilestoneProgress(@PathVariable Long id,
                                                               @RequestBody Map<String, Integer> body,
                                                               HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        Integer progress = body.get("progress");
        if (progress == null) {
            return Result.badRequest("进度不能为空").toResponseEntity();
        }

        ProjectMilestoneVO milestone = projectMilestoneService.getById(id);
        if (milestone == null) {
            return Result.notFound("里程碑不存在").toResponseEntity();
        }
        // C-04: 校验用户是否为团队成员
        if (!isTeamMember(milestone.getTeamId(), user)) {
            return Result.fail(403, "您不是该团队成员，无权修改里程碑进度").toResponseEntity();
        }

        boolean success = projectMilestoneService.updateProgress(id, progress);
        if (success) {
            return Result.ok("进度更新成功", null).toResponseEntity();
        } else {
            return Result.notFound("里程碑不存在").toResponseEntity();
        }
    }

    @DeleteMapping("/milestones/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteMilestone(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        ProjectMilestoneVO milestone = projectMilestoneService.getById(id);
        if (milestone == null) {
            return Result.notFound("里程碑不存在").toResponseEntity();
        }
        // C-04: 校验用户是否为团队成员
        if (!isTeamMember(milestone.getTeamId(), user)) {
            return Result.fail(403, "您不是该团队成员，无权删除里程碑").toResponseEntity();
        }

        boolean success = projectMilestoneService.delete(id);
        if (success) {
            return Result.ok("里程碑删除成功", null).toResponseEntity();
        } else {
            return Result.notFound("里程碑不存在").toResponseEntity();
        }
    }

    /** C-04: 校验用户是否为指定团队的成员 */
    private boolean isTeamMember(Long teamId, User user) {
        return user != null && teamAccessService.isTeamParticipant(teamId, user.getId());
    }
}
