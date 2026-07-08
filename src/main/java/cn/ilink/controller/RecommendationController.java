package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.MatchResult;
import cn.ilink.dto.RecommendedTeamVO;
import cn.ilink.dto.RecommendedUserVO;
import cn.ilink.entity.User;
import cn.ilink.service.RecommendationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;

@Controller
@RequestMapping("/api/recommendations")
public class RecommendationController {

    @Autowired
    private RecommendationService recommendationService;

    @GetMapping("/teams")
    @ResponseBody
    public ResponseEntity<Result<?>> getRecommendedTeams(
            @RequestParam(defaultValue = "10") int limit,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        List<RecommendedTeamVO> teams = recommendationService.getRecommendedTeams(user.getId(), limit);
        return Result.ok(teams).toResponseEntity();
    }

    @GetMapping("/users")
    @ResponseBody
    public ResponseEntity<Result<?>> getRecommendedUsers(
            @RequestParam Long teamId,
            @RequestParam(defaultValue = "10") int limit,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        List<RecommendedUserVO> users = recommendationService.getRecommendedUsers(teamId, limit);
        return Result.ok(users).toResponseEntity();
    }

    @GetMapping("/match")
    @ResponseBody
    public ResponseEntity<Result<?>> calculateMatchScore(
            @RequestParam Long teamId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        MatchResult result = recommendationService.calculateMatchScore(user.getId(), teamId);
        return Result.ok(result).toResponseEntity();
    }

    @PostMapping("/feedback/{logId}")
    @ResponseBody
    public ResponseEntity<Result<?>> recordFeedback(
            @PathVariable Long logId,
            @RequestParam String action,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        recommendationService.recordFeedback(logId, action);
        return Result.ok("反馈已记录", null).toResponseEntity();
    }
}
