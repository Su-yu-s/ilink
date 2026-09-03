package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.Competition;
import cn.ilink.service.impl.CompetitionServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/competitions")
public class CompetitionController {
    private final CompetitionServiceImpl competitionService;

    public CompetitionController(CompetitionServiceImpl competitionService) {
        this.competitionService = competitionService;
    }

    @GetMapping
    public ResponseEntity<Result<?>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String track) {
        LambdaQueryWrapper<Competition> wrapper = new LambdaQueryWrapper<Competition>()
            .eq(Competition::getStatus, "ACTIVE")
            .orderByAsc(Competition::getId);
        if (track != null && !track.isBlank()) {
            if (!CompetitionServiceImpl.TRACKS.contains(track.trim())) {
                return Result.badRequest("竞赛赛道不合法").toResponseEntity();
            }
            wrapper.eq(Competition::getTrack, track.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String term = keyword.trim();
            wrapper.and(w -> w.like(Competition::getName, term)
                .or().like(Competition::getOrganizer, term)
                .or().like(Competition::getTags, term)
                .or().like(Competition::getDescription, term));
        }
        int safePage = ControllerUtils.safePage(page);
        int safeSize = ControllerUtils.safeSize(size, 100);
        Page<Competition> result = competitionService.page(new Page<>(safePage, safeSize), wrapper);
        List<Map<String, Object>> rows = result.getRecords().stream()
            .map(competitionService::toView)
            .collect(Collectors.toList());
        return Result.ok("获取成功", rows)
            .withPagination(safePage, safeSize, result.getTotal())
            .toResponseEntity();
    }
}
