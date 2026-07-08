package cn.ilink.service;

import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class HomeStatsService {

    @Autowired(required = false)
    private UserService userService;

    @Autowired(required = false)
    private TeamDemandServiceImpl teamDemandService;

    @Autowired(required = false)
    private TeacherApplicationServiceImpl teacherApplicationService;

    @Autowired(required = false)
    private AssetServiceImpl assetService;

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
