package cn.ilink.service;

import cn.ilink.entity.ProjectApplication;
import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.User;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class UserRoleService {

    private static final Set<String> ALLOWED_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    private final UserService userService;
    private final TeacherApplicationServiceImpl teacherApplicationService;
    private final ProjectApplicationServiceImpl projectApplicationService;
    private final CacheManager cacheManager;

    public UserRoleService(UserService userService,
                           TeacherApplicationServiceImpl teacherApplicationService,
                           ProjectApplicationServiceImpl projectApplicationService,
                           CacheManager cacheManager) {
        this.userService = userService;
        this.teacherApplicationService = teacherApplicationService;
        this.projectApplicationService = projectApplicationService;
        this.cacheManager = cacheManager;
    }

    @Transactional(rollbackFor = Exception.class)
    public User changeRole(Long userId, String role) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new NoSuchElementException("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        user.setRole(normalizeRole(role));
        return updateUserAndRole(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public User updateUserAndRole(User user) {
        if (user == null || user.getId() == null) {
            throw new NoSuchElementException("\u7528\u6237\u4e0d\u5b58\u5728");
        }
        String role = normalizeRole(user.getRole());
        user.setRole(role);
        if (!userService.updateById(user)) {
            throw new IllegalStateException("\u7528\u6237\u66f4\u65b0\u5931\u8d25");
        }

        TeacherApplication profile = teacherApplicationService.getOne(
            new LambdaQueryWrapper<TeacherApplication>().eq(TeacherApplication::getUserId, user.getId()));
        if ("TEACHER".equals(role)) {
            if (profile == null) {
                profile = new TeacherApplication();
                profile.setUserId(user.getId());
                profile.setStatus("INCOMPLETE");
                profile.setCreatedAt(new Date());
                if (!teacherApplicationService.save(profile)) {
                    throw new IllegalStateException("\u5bfc\u5e08\u6863\u6848\u521b\u5efa\u5931\u8d25");
                }
            } else {
                String desiredStatus = isProfileComplete(user, profile) ? "APPROVED" : "INCOMPLETE";
                profile.setStatus(desiredStatus);
                if (!teacherApplicationService.updateById(profile)) {
                    throw new IllegalStateException("\u5bfc\u5e08\u6863\u6848\u72b6\u6001\u540c\u6b65\u5931\u8d25");
                }
            }
        } else if (profile != null && !"REVOKED".equals(profile.getStatus())) {
            profile.setStatus("REVOKED");
            if (!teacherApplicationService.updateById(profile)) {
                throw new IllegalStateException("\u5bfc\u5e08\u6863\u6848\u64a4\u9500\u5931\u8d25");
            }
            projectApplicationService.update(
                new UpdateWrapper<ProjectApplication>()
                    .eq("teacher_id", profile.getId())
                    .eq("status", "PENDING")
                    .set("status", "REJECTED"));
        }

        clearTeacherCache(profile);
        return user;
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase();
        if (!ALLOWED_ROLES.contains(normalized)) {
            throw new IllegalArgumentException("\u89d2\u8272\u975e\u6cd5\uff0c\u4ec5\u652f\u6301 STUDENT / TEACHER / ADMIN");
        }
        return normalized;
    }

    private boolean isProfileComplete(User user, TeacherApplication profile) {
        return hasText(user.getRealName())
            && hasText(user.getSchool())
            && hasText(user.getMajor())
            && hasText(profile.getProfessionalTitle())
            && hasText(profile.getResearchDirection())
            && hasText(profile.getIntroduction());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void clearTeacherCache(TeacherApplication profile) {
        if (profile == null || profile.getId() == null) {
            return;
        }
        Cache cache = cacheManager.getCache("teacherDetail");
        if (cache != null) {
            cache.evict(profile.getId());
        }
    }
}
