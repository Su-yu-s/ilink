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

    @GetMapping
    public ResponseEntity<Result<?>> getUserSkills(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        List<UserSkill> skills = userSkillService.getUserSkills(user.getId());
        return ResponseEntity.ok(Result.ok(skills));
    }

    @PostMapping
    public ResponseEntity<Result<?>> addSkill(@RequestBody UserSkill skill, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        if (skill == null || skill.getSkillName() == null || skill.getSkillName().trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("技能名称不能为空"));
        }

        if (skill.getSkillName().length() > 64) {
            return ResponseEntity.ok(Result.badRequest("技能名称不能超过64个字符"));
        }

        if (userSkillService.isSkillExists(user.getId(), skill.getSkillName().trim())) {
            return ResponseEntity.ok(Result.badRequest("该技能已存在"));
        }

        skill.setSkillName(skill.getSkillName().trim());
        UserSkill added = userSkillService.addSkill(user.getId(), skill);
        
        if (added != null) {
            return ResponseEntity.ok(Result.ok(added));
        } else {
            return ResponseEntity.ok(Result.fail(500, "添加技能失败"));
        }
    }

    @DeleteMapping("/{skillId}")
    public ResponseEntity<Result<?>> deleteSkill(@PathVariable Long skillId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        if (skillId == null) {
            return ResponseEntity.ok(Result.badRequest("技能ID不能为空"));
        }

        boolean success = userSkillService.deleteSkill(user.getId(), skillId);
        
        if (success) {
            return ResponseEntity.ok(Result.ok("技能已删除"));
        } else {
            return ResponseEntity.ok(Result.fail(404, "技能不存在或无权限删除"));
        }
    }
}
