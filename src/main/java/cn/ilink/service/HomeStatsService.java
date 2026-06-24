package cn.ilink.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class HomeStatsService {

    @Autowired(required = false)
    private UserService userService;

    @Autowired(required = false)
    private TeamDemandService teamDemandService;

    @Autowired(required = false)
    private TeacherApplicationService teacherApplicationService;

    @Autowired(required = false)
    private AssetService assetService;

    public Map<String, Long> resolveIndexStats() {
        long userCount = safeCount(userService);
        long teamCount = safeCount(teamDemandService);
        long teacherCount = safeCount(teacherApplicationService);
        long assetCount = safeCount(assetService);
        return Map.of(
            "userCount", userCount,
            "teamCount", teamCount,
            "assetCount", assetCount,
            "teacherCount", teacherCount
        );
    }

    private long safeCount(IService<?> service) {
        if (service == null) return 0L;
        try {
            return service.count();
        } catch (Exception e) {
            return 0L;
        }
    }
}
