package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.MatchResult;
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
        try {
            List<RecommendedUserVO> users = recommendationService.getRecommendedUsers(user.getId(), teamId, limit);
            return Result.ok(users).toResponseEntity();
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return Result.forbidden().toResponseEntity();
        }
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
        try {
            recommendationService.recordFeedback(logId, user.getId(), action);
            return Result.ok("反馈已记录", null).toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        } catch (java.util.NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return Result.forbidden().toResponseEntity();
        }
    }
}
