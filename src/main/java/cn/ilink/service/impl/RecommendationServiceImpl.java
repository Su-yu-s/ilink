package cn.ilink.service.impl;

import cn.ilink.dto.MatchResult;
import cn.ilink.dto.RecommendedTeamVO;
import cn.ilink.dto.RecommendedUserVO;
import cn.ilink.entity.RecommendationLog;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.UserMapper;
import cn.ilink.service.RecommendationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RecommendationServiceImpl extends ServiceImpl<cn.ilink.mapper.RecommendationLogMapper, RecommendationLog> implements RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationServiceImpl.class);

    private static final double SKILL_WEIGHT = 0.40;
    private static final double URGENCY_WEIGHT = 0.20;
    private static final double HISTORY_WEIGHT = 0.15;
    private static final double ACTIVITY_WEIGHT = 0.15;
    private static final double LOCATION_WEIGHT = 0.10;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TeamDemandMapper teamDemandMapper;

    @Override
    @Cacheable(value = "recommendedTeams", key = "{#userId, #limit}")
    public List<RecommendedTeamVO> getRecommendedTeams(Long userId, int limit) {
        int safeLimit = normalizeLimit(limit);
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Collections.emptyList();
        }

        // C-06: 限制查询数量，避免全表扫描
        int fetchLimit = Math.min(safeLimit * 10, 100);
        Page<TeamDemand> page = new Page<>(1, fetchLimit);
        List<TeamDemand> allTeams = teamDemandMapper.selectPage(page,
            new LambdaQueryWrapper<TeamDemand>()
                .eq(TeamDemand::getStatus, "OPEN")
                .orderByDesc(TeamDemand::getCreatedAt)
        ).getRecords();

        List<RecommendedTeamVO> recommendations = new ArrayList<>();

        for (TeamDemand team : allTeams) {
            if (team.getCreatorId() != null && team.getCreatorId().equals(userId)) {
                continue;
            }

            MatchResult matchResult = calculateMatchScore(userId, team.getId());
            RecommendedTeamVO vo = new RecommendedTeamVO();
            vo.setTeamId(team.getId());
            vo.setTeamName(team.getTitle());
            vo.setDescription(team.getDescription());
            vo.setMatchScore(matchResult.getTotalScore());
            vo.setMatchReasons(matchResult.getMatchReasons());
            recommendations.add(vo);
        }

        return recommendations.stream()
            .sorted(Comparator.comparing(RecommendedTeamVO::getMatchScore).reversed())
            .limit(safeLimit)
            .collect(Collectors.toList());
    }

    @Override
    @Cacheable(value = "recommendedUsers", key = "{#teamId, #limit}")
    public List<RecommendedUserVO> getRecommendedUsers(Long teamId, int limit) {
        int safeLimit = normalizeLimit(limit);
        TeamDemand team = teamDemandMapper.selectById(teamId);
        if (team == null) {
            return Collections.emptyList();
        }

        // C-06: 分页查询，避免加载所有用户
        Page<User> page = new Page<>(1, 200);
        List<User> allUsers = userMapper.selectPage(page,
            new LambdaQueryWrapper<User>()
                .ne(User::getId, team.getCreatorId())
                .orderByDesc(User::getCreatedAt)
        ).getRecords();

        List<RecommendedUserVO> recommendations = new ArrayList<>();

        for (User user : allUsers) {
            if (user.getId().equals(team.getCreatorId())) {
                continue;
            }

            MatchResult matchResult = calculateMatchScore(user.getId(), teamId);
            RecommendedUserVO vo = new RecommendedUserVO();
            vo.setUserId(user.getId());
            vo.setUsername(user.getUsername());
            vo.setRealName(user.getRealName());
            vo.setSchool(user.getSchool());
            vo.setAvatar(user.getAvatar());
            vo.setMatchScore(matchResult.getTotalScore());
            vo.setMatchReasons(matchResult.getMatchReasons());
            vo.setSkills(parseSkills(user.getBio()));
            vo.setRating(calculateUserRating(user.getId()));
            recommendations.add(vo);
        }

        return recommendations.stream()
            .sorted(Comparator.comparing(RecommendedUserVO::getMatchScore).reversed())
            .limit(safeLimit)
            .collect(Collectors.toList());
    }

    @Override
    public MatchResult calculateMatchScore(Long userId, Long teamId) {
        User user = userMapper.selectById(userId);
        TeamDemand team = teamDemandMapper.selectById(teamId);

        MatchResult result = new MatchResult();
        List<String> reasons = new ArrayList<>();
        if (user == null || team == null) {
            result.setTotalScore(0.0);
            result.setSkillScore(0.0);
            result.setUrgencyScore(0.0);
            result.setHistoryScore(0.0);
            result.setActivityScore(0.0);
            result.setMatchReasons(Collections.singletonList("用户或团队不存在"));
            return result;
        }

        double skillScore = calculateSkillScore(user, team, reasons);
        double urgencyScore = calculateUrgencyScore(team);
        double historyScore = calculateHistoryScore(userId, teamId);
        double activityScore = calculateActivityScore(userId);
        double locationScore = calculateLocationScore(user, team);

        double totalScore = skillScore * SKILL_WEIGHT
            + urgencyScore * URGENCY_WEIGHT
            + historyScore * HISTORY_WEIGHT
            + activityScore * ACTIVITY_WEIGHT
            + locationScore * LOCATION_WEIGHT;

        totalScore = Math.min(100.0, Math.max(0.0, totalScore));

        result.setTotalScore(totalScore);
        result.setSkillScore(skillScore);
        result.setUrgencyScore(urgencyScore);
        result.setHistoryScore(historyScore);
        result.setActivityScore(activityScore);
        result.setMatchReasons(reasons);

        recordRecommendation(userId, null, teamId, totalScore, String.join(",", reasons));

        return result;
    }

    @Override
    public void recordFeedback(Long logId, String action) {
        try {
            RecommendationLog log = getById(logId);
            if (log != null) {
                log.setAction(action);
                updateById(log);
            }
        } catch (DataAccessException e) {
            log.warn("推荐反馈记录失败，已跳过：{}", rootMessage(e));
        }
    }

    private double calculateSkillScore(User user, TeamDemand team, List<String> reasons) {
        double score = 50.0;

        if (user.getBio() == null || team.getRequiredSkills() == null) {
            reasons.add("技能信息不完整");
            return score;
        }

        String userBio = user.getBio().toLowerCase();
        String requiredSkills = team.getRequiredSkills().toLowerCase();

        String[] skills = requiredSkills.split("[,，;；、]");
        int matchCount = 0;

        for (String skill : skills) {
            skill = skill.trim();
            if (!skill.isEmpty() && userBio.contains(skill)) {
                matchCount++;
                reasons.add("匹配技能: " + skill);
            }
        }

        if (skills.length > 0) {
            double skillMatchRatio = (double) matchCount / skills.length;
            score = 50.0 + skillMatchRatio * 50.0;
        }

        return score;
    }

    private double calculateUrgencyScore(TeamDemand team) {
        if (team.getCreatedAt() == null) {
            return 50.0;
        }

        long daysSinceCreation = (System.currentTimeMillis() - team.getCreatedAt().getTime()) / (1000 * 60 * 60 * 24);

        if (daysSinceCreation < 3) {
            return 100.0;
        } else if (daysSinceCreation < 7) {
            return 80.0;
        } else if (daysSinceCreation < 14) {
            return 60.0;
        } else if (daysSinceCreation < 30) {
            return 40.0;
        } else {
            return 20.0;
        }
    }

    private double calculateHistoryScore(Long userId, Long teamId) {
        List<RecommendationLog> logs;
        try {
            // C-06: 使用 list + stream 替代 getOne()，避免多条记录时抛异常
            Page<RecommendationLog> page = new Page<>(1, 1);
            logs = page(page,
                new LambdaQueryWrapper<RecommendationLog>()
                    .eq(RecommendationLog::getUserId, userId)
                    .eq(RecommendationLog::getRecommendedTeamId, teamId)
                    .orderByDesc(RecommendationLog::getCreatedAt)
            ).getRecords();
        } catch (DataAccessException e) {
            log.warn("推荐日志读取失败，历史评分使用默认值：{}", rootMessage(e));
            return 100.0;
        }

        if (logs.isEmpty()) {
            return 100.0;
        }

        RecommendationLog existingLog = logs.get(0);
        if ("ACCEPTED".equals(existingLog.getAction())) {
            return 80.0;
        } else if ("VIEWED".equals(existingLog.getAction())) {
            return 60.0;
        } else if ("DISMISSED".equals(existingLog.getAction())) {
            return 30.0;
        }

        return 50.0;
    }

    private double calculateActivityScore(Long userId) {
        long recentDays = 30;
        long cutoffTime = System.currentTimeMillis() - (recentDays * 24 * 60 * 60 * 1000);

        long logCount;
        try {
            logCount = count(
                new LambdaQueryWrapper<RecommendationLog>()
                    .eq(RecommendationLog::getUserId, userId)
                    .ge(RecommendationLog::getCreatedAt, new Date(cutoffTime))
            );
        } catch (DataAccessException e) {
            log.warn("推荐日志统计失败，活跃度评分使用默认值：{}", rootMessage(e));
            return 20.0;
        }

        if (logCount >= 10) {
            return 100.0;
        } else if (logCount >= 5) {
            return 80.0;
        } else if (logCount >= 2) {
            return 60.0;
        } else if (logCount >= 1) {
            return 40.0;
        }

        return 20.0;
    }

    private double calculateLocationScore(User user, TeamDemand team) {
        if (user.getSchool() == null) {
            return 50.0;
        }

        return 80.0;
    }

    private List<String> parseSkills(String bio) {
        if (bio == null || bio.isEmpty()) {
            return Collections.emptyList();
        }

        return Arrays.stream(bio.split("[,，;；、\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .limit(5)
            .collect(Collectors.toList());
    }

    private Double calculateUserRating(Long userId) {
        List<RecommendationLog> logs;
        try {
            logs = list(
                new LambdaQueryWrapper<RecommendationLog>()
                    .eq(RecommendationLog::getRecommendedUserId, userId)
                    .eq(RecommendationLog::getAction, "ACCEPTED")
            );
        } catch (DataAccessException e) {
            log.warn("推荐日志评分读取失败，用户评分使用默认值：{}", rootMessage(e));
            return 4.5;
        }

        if (logs.isEmpty()) {
            return 4.5;
        }

        double rating = 4.5 + Math.min(logs.size() * 0.1, 0.5);
        return Math.round(rating * 10.0) / 10.0;
    }

    private void recordRecommendation(Long userId, Long recommendedUserId, Long teamId, Double score, String reasons) {
        try {
            RecommendationLog log = new RecommendationLog();
            log.setUserId(userId);
            log.setRecommendedUserId(recommendedUserId);
            log.setRecommendedTeamId(teamId);
            log.setMatchScore(score);
            log.setMatchReasons(reasons);
            log.setCreatedAt(new Date());
            save(log);
        } catch (DataAccessException e) {
            log.warn("推荐日志写入失败，已跳过：{}", rootMessage(e));
        }
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 20));
    }

    private String rootMessage(DataAccessException e) {
        Throwable root = e.getMostSpecificCause();
        return root != null && root.getMessage() != null ? root.getMessage() : e.getMessage();
    }
}
