package cn.ilink.controller;

import cn.ilink.service.HomeStatsService;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.web.csrf.CsrfToken;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.Set;

/**
 * 将 /xxx.html 映射到 Thymeleaf 模板（与前端导航链接一致）。
 */
@Controller
public class PageController {

    @Autowired(required = false)
    private HomeStatsService homeStatsService;

    @GetMapping("/chat.html")
    public String legacyChat() {
        return "redirect:/community.html";
    }

    /** 原「个人主页」已并入个人中心 */
    @GetMapping("/home.html")
    public String homeHtmlRedirect() {
        return "redirect:/profile.html";
    }

    /** 原「成果展示」已并入个人中心 · 成果与荣誉（独立页） */
    /**
     * 文章详情（路径型 URL，避免 ?id= 被缓存/代理弄丢）。
     * 不用正则约束 id，避免个别环境下 PathPattern 与正则组合不生效；非法 id 由前端与 API 处理。
     */
    @GetMapping("/community/article/{id}")
    public String communityArticleByPath(@SuppressWarnings("unused") @PathVariable Long id,
                                         Model model, HttpSession session) {
        model.addAttribute("currentUser", session.getAttribute("user"));
        return "community-article";
    }

    private static final Set<String> ALLOWED_PAGES = Set.of(
        "index",
        "login",
        "register",
        "home",
        "profile",
        "profile-edit",
        "profile-honors",
        "profile-posts",
        "profile-favorites",
        "profile-password",
        "profile-article-edit",
        "user-profile",
        "team-market",
        "team-detail",
        "team-publish",
        "team-workspace",
        "teacher-wall",
        "teacher-detail",
        "asset-detail",
        "community",
        "community-article",
        "competitions",
        "gallery",
        "admin",
        "404"
    );

    @GetMapping("/{page}.html")
    public String page(@PathVariable String page, Model model, HttpSession session, HttpServletRequest request) {
        if (!ALLOWED_PAGES.contains(page)) {
            return "redirect:/";
        }
        model.addAttribute("currentUser", session.getAttribute("user"));
        if ("index".equals(page)) {
            Map<String, Long> stats = resolveIndexStats();
            model.addAttribute("userCount", stats.get("userCount"));
            model.addAttribute("teamCount", stats.get("teamCount"));
            model.addAttribute("assetCount", stats.get("assetCount"));
            model.addAttribute("teacherCount", stats.get("teacherCount"));
        }
        // C-37: 添加 CSRF token 到模型，供 Thymeleaf 模板使用
        // 直接从 request 属性或 session 中获取 CSRF token
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            // MockMvc 测试或未启用过滤器时，手动从 session 获取
            token = (CsrfToken) session.getAttribute("_csrf");
        }
        if (token != null) {
            model.addAttribute("_csrf", token);
        }
        return page;
    }

    private Map<String, Long> resolveIndexStats() {
        if (homeStatsService != null) {
            return homeStatsService.resolveIndexStats();
        }
        return Map.of(
            "userCount", 0L,
            "teamCount", 0L,
            "assetCount", 0L,
            "teacherCount", 0L
        );
    }
}
