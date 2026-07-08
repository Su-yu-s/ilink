package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.User;
import cn.ilink.entity.UserSkill;
import cn.ilink.service.UserSkillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;

@RestController
@RequestMapping("/api/user/skills")
public class UserSkillController {

    @Autowired
    private UserSkillService userSkillService;

    /** 公开查询某用户的技能列表（不需要登录，用于组队审批查看申请人资料） */
    @GetMapping("/public/{userId}")
    public ResponseEntity<Result<?>> getPublicUserSkills(@PathVariable Long userId) {
        List<UserSkill> skills = userSkillService.getUserSkills(userId);
        return Result.ok(skills).toResponseEntity();
    }

    @GetMapping
    public ResponseEntity<Result<?>> getUserSkills(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        List<UserSkill> skills = userSkillService.getUserSkills(user.getId());
        return Result.ok(skills).toResponseEntity();
    }

    @PostMapping
    public ResponseEntity<Result<?>> addSkill(@RequestBody UserSkill skill, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        if (skill == null || skill.getSkillName() == null || skill.getSkillName().trim().isEmpty()) {
            return Result.badRequest("技能名称不能为空").toResponseEntity();
        }

        if (skill.getSkillName().length() > 64) {
            return Result.badRequest("技能名称不能超过64个字符").toResponseEntity();
        }

        if (userSkillService.isSkillExists(user.getId(), skill.getSkillName().trim())) {
            return Result.badRequest("该技能已存在").toResponseEntity();
        }

        skill.setSkillName(skill.getSkillName().trim());
        UserSkill added = userSkillService.addSkill(user.getId(), skill);
        
        if (added != null) {
            return Result.ok(added).toResponseEntity();
        } else {
            return Result.fail(500, "添加技能失败").toResponseEntity();
        }
    }

    @DeleteMapping("/{skillId}")
    public ResponseEntity<Result<?>> deleteSkill(@PathVariable Long skillId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        if (skillId == null) {
            return Result.badRequest("技能ID不能为空").toResponseEntity();
        }

        boolean success = userSkillService.deleteSkill(user.getId(), skillId);
        
        if (success) {
            return Result.ok("技能已删除").toResponseEntity();
        } else {
            return Result.fail(404, "技能不存在或无权限删除").toResponseEntity();
        }
    }
}
