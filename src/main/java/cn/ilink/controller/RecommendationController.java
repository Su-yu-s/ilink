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
    public ResponseEntity<Result<List<RecommendedTeamVO>>> getRecommendedTeams(
            @RequestParam(defaultValue = "10") int limit,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        List<RecommendedTeamVO> teams = recommendationService.getRecommendedTeams(user.getId(), limit);
        return ResponseEntity.ok(Result.ok(teams));
    }

    @GetMapping("/users")
    @ResponseBody
    public ResponseEntity<Result<List<RecommendedUserVO>>> getRecommendedUsers(
            @RequestParam Long teamId,
            @RequestParam(defaultValue = "10") int limit,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        List<RecommendedUserVO> users = recommendationService.getRecommendedUsers(teamId, limit);
        return ResponseEntity.ok(Result.ok(users));
    }

    @GetMapping("/match")
    @ResponseBody
    public ResponseEntity<Result<MatchResult>> calculateMatchScore(
            @RequestParam Long teamId,
            HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        MatchResult result = recommendationService.calculateMatchScore(user.getId(), teamId);
        return ResponseEntity.ok(Result.ok(result));
    }

    @PostMapping("/feedback/{logId}")
    @ResponseBody
    public ResponseEntity<Result<Void>> recordFeedback(
            @PathVariable Long logId,
            @RequestParam String action) {
        recommendationService.recordFeedback(logId, action);
        return ResponseEntity.ok(Result.ok("反馈已记录", null));
    }
}
