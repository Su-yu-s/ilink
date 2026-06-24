package cn.ilink.service.impl;

import cn.ilink.entity.UserSkill;
import cn.ilink.mapper.UserSkillMapper;
import cn.ilink.service.UserSkillService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserSkillServiceImpl extends ServiceImpl<UserSkillMapper, UserSkill> implements UserSkillService {

    @Override
    public List<UserSkill> getUserSkills(Long userId) {
        if (userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<UserSkill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSkill::getUserId, userId);
        wrapper.orderByDesc(UserSkill::getSkillLevel);
        wrapper.orderByDesc(UserSkill::getUpdatedAt);
        return list(wrapper);
    }

    @Override
    public UserSkill addSkill(Long userId, UserSkill skill) {
        if (userId == null || skill == null) {
            return null;
        }
        skill.setUserId(userId);
        if (save(skill)) {
            return skill;
        }
        return null;
    }

    @Override
    @Transactional
    public boolean deleteSkill(Long userId, Long skillId) {
        if (userId == null || skillId == null) {
            return false;
        }
        UserSkill skill = getById(skillId);
        if (skill == null || !skill.getUserId().equals(userId)) {
            return false;
        }
        return removeById(skillId);
    }

    @Override
    public boolean isSkillExists(Long userId, String skillName) {
        if (userId == null || skillName == null) {
            return false;
        }
        LambdaQueryWrapper<UserSkill> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserSkill::getUserId, userId);
        wrapper.eq(UserSkill::getSkillName, skillName);
        return count(wrapper) > 0;
    }
}
