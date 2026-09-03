package cn.ilink.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal endpoint used only to exercise the real security filter chain in a MVC slice.
 */
@RestController
public class SecurityProbeController {

    @GetMapping("/api/community/posts")
    public String publicCommunityPosts() {
        return "[]";
    }

    @GetMapping({"/team-detail.html", "/teacher-detail.html", "/asset-detail.html", "/community-article.html"})
    public String publicDetailPage() {
        return "detail";
    }

    @GetMapping("/internal-probe")
    public String internalProbe() {
        return "private";
    }
}
