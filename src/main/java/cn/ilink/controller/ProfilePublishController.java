package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.Asset;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.User;
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 个人中心「我的发布」聚合接口。
 *
 * <p>成果（asset）和社区文章（community_post）分属两张表、两套服务，
 * 但个人中心里它们是同一件事：用户发过的东西。这里把两边拉平成一个
 * 统一的行结构，按发布时间倒序合并后再分页，前端只认一种数据形状。</p>
 *
 * <p>合并放在内存里做：单个用户自己的内容量级很小（上限各 500 条），
 * 为此在数据库层写 UNION 反而会让两套查询条件互相牵制。</p>
 */
@RestController
@RequestMapping("/api/profile")
public class ProfilePublishController {

    /** 单边最多取多少条，防止极端账号把内存撑爆 */
    private static final int MAX_PER_SOURCE = 500;

    private static final int SUMMARY_LENGTH = 120;

    @Autowired
    private AssetServiceImpl assetService;

    @Autowired
    private CommunityPostServiceImpl communityPostService;

    @GetMapping("/publications")
    @ResponseBody
    public ResponseEntity<Result<?>> listPublications(
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "10") Integer size,
        @RequestParam(required = false, defaultValue = "all") String type,
        @RequestParam(required = false) String keyword,
        HttpSession session
    ) {
        User viewer = ControllerUtils.requireUser(session);
        if (viewer == null) {
            return Result.unauthorized().toResponseEntity();
        }

        // 页码要有上限：int 乘法 (page-1)*size 在极端页码下会溢出成负数，
        // 后面 subList 就会抛 IndexOutOfBoundsException 变成 500。
        int safePage = Math.min(100_000, Math.max(1, page == null ? 1 : page));
        int safeSize = Math.min(50, Math.max(1, size == null ? 10 : size));

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean wantWork = !"article".equalsIgnoreCase(type);
        boolean wantArticle = !"work".equalsIgnoreCase(type);
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();

        if (wantWork) {
            rows.addAll(loadAssets(viewer.getId()));
        }
        if (wantArticle) {
            rows.addAll(loadPosts(viewer.getId()));
        }

        if (!kw.isEmpty()) {
            // 摘要参与搜索，必须先补齐；搜索是低频操作，允许为此读正文
            fillSummaries(rows);
            rows = rows.stream()
                .filter(r -> String.valueOf(r.get("title")).toLowerCase().contains(kw)
                    || String.valueOf(r.get("summary")).toLowerCase().contains(kw))
                .collect(Collectors.toList());
        }

        rows.sort(Comparator.comparing(
            (Map<String, Object> r) -> (java.util.Date) r.get("createdAt"),
            Comparator.nullsLast(Comparator.reverseOrder())
        ));

        int total = rows.size();
        // 用 long 计算偏移，避免 (safePage - 1) * safeSize 在 int 下溢出为负数
        long offset = (long) (safePage - 1) * safeSize;
        int from = (int) Math.min(offset, total);
        int to = Math.min(from + safeSize, total);
        List<Map<String, Object>> pageRows = new ArrayList<>(rows.subList(from, to));
        if (kw.isEmpty()) {
            // 只有当前页需要摘要，正文按页读，避免把每位用户的全部正文拉回内存
            fillSummaries(pageRows);
        }

        return Result.ok("获取成功", pageRows)
            .withPagination(safePage, safeSize, total)
            .toResponseEntity();
    }

    private List<Map<String, Object>> loadAssets(Long userId) {
        // 用 QueryWrapper 而不是 LambdaQueryWrapper：只有前者支持字符串列名。
        // 这里不再带 LEFT(description, 400)：摘要正文开头有一段不可见的
        // Markdown 标记（base64 存整篇源码），SQL 层定长截断会把标记切一半，
        // 露出半截 HTML。摘要统一交给 fillSummaries 按页补算。
        QueryWrapper<Asset> wrapper = new QueryWrapper<>();
        wrapper.select("id", "title", "category", "cover_url", "view_count", "created_at")
            .eq("user_id", userId)
            .orderByDesc("created_at")
            .orderByDesc("id")
            .last("LIMIT " + MAX_PER_SOURCE);

        return assetService.list(wrapper).stream().map(a -> {
            Map<String, Object> row = baseRow(
                a.getId(), "work", a.getTitle(), "",
                a.getCategory(), a.getCoverUrl(), a.getCreatedAt());
            row.put("viewCount", a.getViewCount() == null ? 0 : a.getViewCount());
            row.put("likeCount", 0);
            row.put("detailUrl", "/asset-detail.html?id=" + a.getId());
            row.put("editUrl", "/profile-asset-edit.html?id=" + a.getId());
            return row;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> loadPosts(Long userId) {
        // 正文上限 20 万字，与 loadAssets 同理：不在这里取正文
        QueryWrapper<CommunityPost> wrapper = new QueryWrapper<>();
        wrapper.select("id", "title", "category", "view_count", "like_count", "created_at")
            .eq("author_id", userId)
            .orderByDesc("created_at")
            .orderByDesc("id")
            .last("LIMIT " + MAX_PER_SOURCE);

        return communityPostService.list(wrapper).stream().map(p -> {
            Map<String, Object> row = baseRow(
                p.getId(), "article", p.getTitle(), "",
                p.getCategory(), null, p.getCreatedAt());
            row.put("viewCount", p.getViewCount() == null ? 0 : p.getViewCount());
            row.put("likeCount", p.getLikeCount() == null ? 0 : p.getLikeCount());
            row.put("detailUrl", "/community-article.html?id=" + p.getId());
            row.put("editUrl", "/profile-article-edit.html?id=" + p.getId());
            return row;
        }).collect(Collectors.toList());
    }

    /**
     * 按需补齐摘要。
     *
     * <p>文章正文以一段不可见的 Markdown 标记开头（base64 保存整篇源码），
     * 长度随正文增长，任何定长的 SQL 截断都会切在标记中间，导致半截 HTML
     * 直接显示在列表里。因此正文只对「当前页」或「搜索」这两条路径读取，
     * 剥掉标记后再压缩成纯文本。</p>
     */
    private void fillSummaries(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> assetIds = new ArrayList<>();
        List<Long> postIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long id = asLong(row.get("id"));
            if (id == null) {
                continue;
            }
            if ("work".equals(row.get("type"))) {
                assetIds.add(id);
            } else if ("article".equals(row.get("type"))) {
                postIds.add(id);
            }
        }

        if (!assetIds.isEmpty()) {
            QueryWrapper<Asset> wrapper = new QueryWrapper<>();
            wrapper.select("id", "description").in("id", assetIds);
            Map<Long, String> byId = new HashMap<>();
            for (Asset asset : assetService.list(wrapper)) {
                byId.put(asset.getId(), asset.getDescription());
            }
            for (Map<String, Object> row : rows) {
                if ("work".equals(row.get("type"))) {
                    row.put("summary", shorten(plainText(byId.get(asLong(row.get("id"))))));
                }
            }
        }

        if (!postIds.isEmpty()) {
            QueryWrapper<CommunityPost> wrapper = new QueryWrapper<>();
            wrapper.select("id", "content").in("id", postIds);
            Map<Long, String> byId = new HashMap<>();
            for (CommunityPost post : communityPostService.list(wrapper)) {
                byId.put(post.getId(), post.getContent());
            }
            for (Map<String, Object> row : rows) {
                if ("article".equals(row.get("type"))) {
                    row.put("summary", shorten(plainText(byId.get(asLong(row.get("id"))))));
                }
            }
        }
    }

    private Long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private Map<String, Object> baseRow(Long id, String type, String title, String summary,
                                        String category, String coverUrl, java.util.Date createdAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("type", type);
        row.put("title", title == null ? "" : title);
        row.put("summary", shorten(plainText(summary)));
        row.put("category", category == null ? "" : category);
        row.put("coverUrl", coverUrl == null ? "" : coverUrl);
        row.put("createdAt", createdAt);
        return row;
    }

    /** 去掉 Markdown 标记与 HTML 标签，只留可读文本用于列表摘要 */
    private String plainText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw
            .replaceAll("(?s)```.*?```", " ")
            .replaceAll("<[^>]+>", " ")
            // 保险：万一传进来的是被截断的标签（没有闭合的 ">"），也一并丢掉，
            // 否则会像以前那样把半截 <span ... 当正文显示出来
            .replaceAll("<[a-zA-Z/][^>]*$", " ")
            .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ")
            .replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
            .replaceAll("[#*_>`~|-]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        // 正文里的 &amp; &#39; 这类实体转回可读字符
        return HtmlUtils.htmlUnescape(text);
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SUMMARY_LENGTH ? text : text.substring(0, SUMMARY_LENGTH) + "…";
    }
}
