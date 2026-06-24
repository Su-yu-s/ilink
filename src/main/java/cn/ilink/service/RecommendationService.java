package cn.ilink.service;

import cn.ilink.dto.MatchResult;
import cn.ilink.dto.RecommendedTeamVO;
import cn.ilink.dto.RecommendedUserVO;
import com.baomidou.mybatisplus.extension.service.IService;
import cn.ilink.entity.RecommendationLog;
import java.util.List;

public interface RecommendationService extends IService<RecommendationLog> {
    List<RecommendedTeamVO> getRecommendedTeams(Long userId, int limit);
    List<RecommendedUserVO> getRecommendedUsers(Long teamId, int limit);
    MatchResult calculateMatchScore(Long userId, Long teamId);
    void recordFeedback(Long logId, String action);
}
