package cn.ilink.service;

import cn.ilink.entity.UserSkill;
import java.util.List;

public interface UserSkillService {
    List<UserSkill> getUserSkills(Long userId);
    UserSkill addSkill(Long userId, UserSkill skill);
    boolean deleteSkill(Long userId, Long skillId);
    boolean isSkillExists(Long userId, String skillName);
}
