package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.dto.CompetitionRequest;
import cn.ilink.entity.Competition;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.User;
import cn.ilink.entity.TeamDemand;
import cn.ilink.service.impl.CompetitionServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.service.AdminAuditService;
import cn.ilink.service.kg.KgIndexService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/competitions")
public class AdminCompetitionController {
    private final CompetitionServiceImpl competitionService;
    private final AdminAuditService adminAuditService;
    private final KgIndexService kgIndexService;
    private final TeamDemandServiceImpl teamDemandService;

    public AdminCompetitionController(CompetitionServiceImpl competitionService,
                                      AdminAuditService adminAuditService,
                                      KgIndexService kgIndexService,
                                      TeamDemandServiceImpl teamDemandService) {
        this.competitionService = competitionService;
        this.adminAuditService = adminAuditService;
        this.kgIndexService = kgIndexService;
        this.teamDemandService = teamDemandService;
    }

    @GetMapping
    public ResponseEntity<Result<?>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String track,
                                          HttpSession session) {
        if (!isAdmin(session)) return Result.forbidden().toResponseEntity();
        LambdaQueryWrapper<Competition> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(Competition::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        if (track != null && !track.isBlank()) {
            wrapper.eq(Competition::getTrack, track.trim());
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(Competition::getName, kw)
                .or().like(Competition::getOrganizer, kw)
                .or().like(Competition::getTags, kw)
                .or().like(Competition::getDescription, kw));
        }
        wrapper.orderByAsc(Competition::getId);
        List<Map<String, Object>> rows = competitionService.list(wrapper)
            .stream().map(competitionService::toView).collect(Collectors.toList());
        return Result.ok("获取成功", rows).toResponseEntity();
    }

    @PostMapping
    public ResponseEntity<Result<?>> create(@RequestBody CompetitionRequest request, HttpSession session,
                                            HttpServletRequest servletRequest) {
        User admin = currentAdmin(session);
        if (admin == null) return Result.forbidden().toResponseEntity();
        try {
            Competition created = competitionService.createCompetition(request);
            adminAuditService.recordSafely(admin, "CREATE", "COMPETITION", created.getId(),
                "name=" + created.getName(), servletRequest);
            // 竞赛目录入知识图谱索引（失败不影响创建，enqueue 内部已兜底）
            kgIndexService.enqueue("COMPETITION", created.getId(), created.getName(),
                created.getDescription(), KgEntity.SCOPE_GLOBAL, 0L, 0L);
            return Result.ok("创建成功", competitionService.toView(created)).toResponseEntity();
        } catch (DuplicateKeyException e) {
            return Result.badRequest("竞赛名称已存在").toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<?>> update(@PathVariable Long id, @RequestBody CompetitionRequest request,
                                            HttpSession session, HttpServletRequest servletRequest) {
        User admin = currentAdmin(session);
        if (admin == null) return Result.forbidden().toResponseEntity();
        try {
            Competition updated = competitionService.updateCompetition(id, request);
            adminAuditService.recordSafely(admin, "UPDATE", "COMPETITION", id,
                "name=" + updated.getName() + ", status=" + updated.getStatus(), servletRequest);
            return Result.ok("更新成功", competitionService.toView(updated)).toResponseEntity();
        } catch (NoSuchElementException e) {
            return Result.notFound(e.getMessage()).toResponseEntity();
        } catch (DuplicateKeyException e) {
            return Result.badRequest("竞赛名称已存在").toResponseEntity();
        } catch (IllegalArgumentException e) {
            return Result.badRequest(e.getMessage()).toResponseEntity();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<?>> delete(@PathVariable Long id, HttpSession session,
                                            HttpServletRequest servletRequest) {
        User admin = currentAdmin(session);
        if (admin == null) return Result.forbidden().toResponseEntity();
        // 修复 D-P2-7：全库无外键，删除前检查 team_demand 引用，避免存量组队需求的竞赛信息悬空
        long referenced = teamDemandService.count(
            new LambdaQueryWrapper<TeamDemand>().eq(TeamDemand::getCompetitionId, id));
        if (referenced > 0) {
            return Result.badRequest("该竞赛已被 " + referenced + " 个组队需求引用，请先处理相关组队需求")
                .toResponseEntity();
        }
        if (!competitionService.removeById(id)) {
            return Result.notFound("竞赛不存在").toResponseEntity();
        }
        adminAuditService.recordSafely(admin, "DELETE", "COMPETITION", id, "", servletRequest);
        return Result.ok("删除成功", null).toResponseEntity();
    }

    private boolean isAdmin(HttpSession session) {
        return currentAdmin(session) != null;
    }

    private User currentAdmin(HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        return ControllerUtils.isAdmin(user) ? user : null;
    }
}
