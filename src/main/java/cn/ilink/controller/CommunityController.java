package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.CommunityComment;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.CommunityPostFavorite;
import cn.ilink.entity.User;
import cn.ilink.service.CommunityCommentService;
import cn.ilink.service.CommunityPostInteractionService;
import cn.ilink.service.CommunityPostService;
import cn.ilink.service.UserService;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.util.HtmlSanitizer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/api/community")
public class CommunityController {

    private static final Set<String> CATEGORIES = Set.of("general", "tech", "competition", "resource");
    private static final int TITLE_MAX = 200;
    private static final int CONTENT_MAX = 200000;
    private static final int ATTACHMENTS_MAX = 10;
    private static final int ATTACHMENT_NAME_MAX = 200;
    private static final Pattern UPLOADS_URL = Pattern.compile("^/uploads/[A-Za-z0-9._-]+$");
    private static final int COMMENT_MAX = 2000;
    private static final int EXCERPT_LEN = 220;

    @Autowired
    private CommunityPostService communityPostService;

    @Autowired
    private CommunityPostInteractionService communityPostInteractionService;

    @Autowired
    private CommunityPostFavoriteMapper communityPostFavoriteMapper;

    @Autowired
    private CommunityCommentService communityCommentService;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/posts")
    @ResponseBody
    public ResponseEntity<Result<?>> listPosts(
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "10") Integer size,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String keyword,
        HttpSession session
    ) {
        LambdaQueryWrapper<CommunityPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(CommunityPost::getCreatedAt);

        if (category != null && !category.trim().isEmpty()) {
            String c = category.trim();
            if (!CATEGORIES.contains(c)) {
                return ResponseEntity.ok(Result.badRequest("无效的分区参数"));
            }
            wrapper.eq(CommunityPost::getCategory, c);
        }

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(CommunityPost::getTitle, kw)
                .or().like(CommunityPost::getContent, kw));
        }

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<CommunityPost> pageObj = new Page<>(safePage, safeSize);
        Page<CommunityPost> result = communityPostService.page(pageObj, wrapper);
        List<CommunityPost> records = result.getRecords();
        long total = result.getTotal();

        Map<Long, User> authorMap = loadAuthors(records.stream().map(CommunityPost::getAuthorId).collect(Collectors.toSet()));
        User viewer = (User) session.getAttribute("user");
        List<Long> postIds = records.stream().map(CommunityPost::getId).collect(Collectors.toList());
        Map<Long, Boolean> likedMap = viewer != null
            ? communityPostInteractionService.batchLikedStatus(viewer.getId(), postIds)
            : Collections.emptyMap();
        Map<Long, Boolean> favoritedMap = viewer != null
            ? communityPostInteractionService.batchFavoritedStatus(viewer.getId(), postIds)
            : Collections.emptyMap();
        List<Map<String, Object>> views = records.stream()
            .map(p -> toListItem(p, authorMap.get(p.getAuthorId()), viewer, likedMap, favoritedMap))
            .collect(Collectors.toList());

        return ResponseEntity.ok(Result.ok("获取成功", views).withPagination(safePage, safeSize, (int) total));
    }

    /**
     * 当前用户发布的文章（个人中心「我的文章」列表，不增加阅读量）
     */
    @GetMapping("/my-posts")
    @ResponseBody
    public ResponseEntity<Result<?>> listMyPosts(
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer size,
        HttpSession session
    ) {
        User viewer = ControllerUtils.requireUser(session);
        if (viewer == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        LambdaQueryWrapper<CommunityPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommunityPost::getAuthorId, viewer.getId()).orderByDesc(CommunityPost::getCreatedAt);

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<CommunityPost> pageObj = new Page<>(safePage, safeSize);
        Page<CommunityPost> result = communityPostService.page(pageObj, wrapper);
        List<CommunityPost> records = result.getRecords();
        long total = result.getTotal();

        List<Long> postIds = records.stream().map(CommunityPost::getId).collect(Collectors.toList());
        Map<Long, Boolean> likedMap = communityPostInteractionService.batchLikedStatus(viewer.getId(), postIds);
        Map<Long, Boolean> favoritedMap = communityPostInteractionService.batchFavoritedStatus(viewer.getId(), postIds);
        List<Map<String, Object>> views = records.stream()
            .map(p -> toListItem(p, viewer, viewer, likedMap, favoritedMap))
            .collect(Collectors.toList());

        return ResponseEntity.ok(Result.ok("获取成功", views).withPagination(safePage, safeSize, (int) total));
    }

    /**
     * 当前用户收藏的文章（收藏夹列表）
     */
    @GetMapping("/my-favorites")
    @ResponseBody
    public ResponseEntity<Result<?>> listMyFavorites(
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(defaultValue = "20") Integer size,
        HttpSession session
    ) {
        User viewer = ControllerUtils.requireUser(session);
        if (viewer == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        LambdaQueryWrapper<CommunityPostFavorite> favWrapper = new LambdaQueryWrapper<>();
        favWrapper.eq(CommunityPostFavorite::getUserId, viewer.getId())
            .orderByDesc(CommunityPostFavorite::getCreatedAt);

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<CommunityPostFavorite> favPage = new Page<>(safePage, safeSize);
        Page<CommunityPostFavorite> favResult = communityPostFavoriteMapper.selectPage(favPage, favWrapper);
        List<CommunityPostFavorite> favRecords = favResult.getRecords();
        long total = favResult.getTotal();

        List<Long> postIds = favRecords.stream()
            .map(CommunityPostFavorite::getPostId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (postIds.isEmpty()) {
            return ResponseEntity.ok(Result.ok("获取成功", Collections.emptyList()).withPagination(safePage, safeSize, total));
        }

        List<CommunityPost> posts = communityPostService.list(
            new LambdaQueryWrapper<CommunityPost>().in(CommunityPost::getId, postIds)
        );
        Map<Long, CommunityPost> postMap = posts.stream()
            .collect(Collectors.toMap(CommunityPost::getId, p -> p, (a, b) -> a));

        List<Long> orderedIds = postIds.stream().filter(postMap::containsKey).collect(Collectors.toList());
        List<CommunityPost> ordered = orderedIds.stream().map(postMap::get).collect(Collectors.toList());

        Map<Long, User> authorMap = loadAuthors(
            ordered.stream().map(CommunityPost::getAuthorId).collect(Collectors.toSet())
        );

        List<Long> allPostIds = ordered.stream().map(CommunityPost::getId).collect(Collectors.toList());
        Map<Long, Boolean> likedMap = communityPostInteractionService.batchLikedStatus(viewer.getId(), allPostIds);
        Map<Long, Boolean> favoritedMap = communityPostInteractionService.batchFavoritedStatus(viewer.getId(), allPostIds);
        List<Map<String, Object>> views = ordered.stream()
            .map(p -> toListItem(p, authorMap.get(p.getAuthorId()), viewer, likedMap, favoritedMap))
            .collect(Collectors.toList());

        return ResponseEntity.ok(Result.ok("获取成功", views).withPagination(safePage, safeSize, total));
    }

    /**
     * 编辑页拉取正文：不增加阅读量；仅作者或管理员
     */
    @GetMapping("/posts/{id}/for-edit")
    @ResponseBody
    public ResponseEntity<Result<?>> getPostForEdit(@PathVariable Long id, HttpSession session) {
        User viewer = ControllerUtils.requireUser(session);
        if (viewer == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        CommunityPost post = communityPostService.getById(id);
        if (post == null) {
            return ResponseEntity.ok(Result.notFound("文章不存在"));
        }
        if (!ControllerUtils.isAdmin(viewer) && !viewer.getId().equals(post.getAuthorId())) {
            return ResponseEntity.ok(Result.fail(403, "无权编辑该文章"));
        }
        User author = userService.getById(post.getAuthorId());
        return ResponseEntity.ok(Result.ok("获取成功", toDetail(post, author, viewer)));
    }

    @PutMapping("/posts/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> updatePost(
        @PathVariable Long id,
        @RequestBody Map<String, Object> body,
        HttpSession session
    ) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        CommunityPost existing = communityPostService.getById(id);
        if (existing == null) {
            return ResponseEntity.ok(Result.notFound("文章不存在"));
        }
        if (!ControllerUtils.isAdmin(user) && !user.getId().equals(existing.getAuthorId())) {
            return ResponseEntity.ok(Result.fail(403, "无权修改该文章"));
        }

        String category = body.get("category") != null ? String.valueOf(body.get("category")).trim() : "";
        String title = body.get("title") != null ? String.valueOf(body.get("title")).trim() : "";
        String rawContent = body.get("content") != null ? String.valueOf(body.get("content")) : "";
        String content = HtmlSanitizer.communityPost(rawContent);

        if (!CATEGORIES.contains(category)) {
            return ResponseEntity.ok(Result.badRequest("请选择有效分区"));
        }
        if (title.isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("标题不能为空"));
        }
        if (title.length() > TITLE_MAX) {
            return ResponseEntity.ok(Result.badRequest("标题过长"));
        }
        if (Jsoup.parse(content).text().trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("正文不能为空"));
        }
        if (content.length() > CONTENT_MAX) {
            return ResponseEntity.ok(Result.badRequest("正文过长"));
        }

        String attachmentsJson;
        try {
            attachmentsJson = validateAndSerializeAttachments(body.get("attachments"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.ok(Result.badRequest(ex.getMessage()));
        }

        existing.setCategory(category);
        existing.setTitle(title);
        existing.setContent(content);
        existing.setAttachments(attachmentsJson);

        if (communityPostService.updateById(existing)) {
            CommunityPost updated = communityPostService.getById(id);
            User author = userService.getById(updated.getAuthorId());
            return ResponseEntity.ok(Result.ok("保存成功", toDetail(updated, author, user)));
        } else {
            return ResponseEntity.ok(Result.fail("保存失败"));
        }
    }

    @PostMapping("/posts")
    @ResponseBody
    public ResponseEntity<Result<?>> createPost(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        String category = body.get("category") != null ? String.valueOf(body.get("category")).trim() : "";
        String title = body.get("title") != null ? String.valueOf(body.get("title")).trim() : "";
        String rawContent = body.get("content") != null ? String.valueOf(body.get("content")) : "";
        String content = HtmlSanitizer.communityPost(rawContent);

        if (!CATEGORIES.contains(category)) {
            return ResponseEntity.ok(Result.badRequest("请选择有效分区"));
        }
        if (title.isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("标题不能为空"));
        }
        if (title.length() > TITLE_MAX) {
            return ResponseEntity.ok(Result.badRequest("标题过长"));
        }
        if (Jsoup.parse(content).text().trim().isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("正文不能为空"));
        }
        if (content.length() > CONTENT_MAX) {
            return ResponseEntity.ok(Result.badRequest("正文过长"));
        }

        String attachmentsJson;
        try {
            attachmentsJson = validateAndSerializeAttachments(body.get("attachments"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.ok(Result.badRequest(ex.getMessage()));
        }

        CommunityPost post = new CommunityPost();
        post.setAuthorId(user.getId());
        post.setCategory(category);
        post.setTitle(title);
        post.setContent(content);
        post.setAttachments(attachmentsJson);
        post.setViewCount(0);
        post.setLikeCount(0);
        post.setFavoriteCount(0);
        post.setCreatedAt(new Date());

        if (communityPostService.save(post)) {
            return ResponseEntity.ok(Result.ok("发布成功", toDetail(post, user, user)));
        } else {
            return ResponseEntity.ok(Result.fail("发布失败"));
        }
    }

    @GetMapping("/posts/{postId}/comments")
    @ResponseBody
    public ResponseEntity<Result<?>> listComments(@PathVariable Long postId, HttpSession session) {
        User currentUser = (User) session.getAttribute("user");
        if (currentUser == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        CommunityPost post = communityPostService.getById(postId);
        if (post == null) {
            return ResponseEntity.ok(Result.notFound("文章不存在"));
        }

        List<CommunityComment> comments = communityCommentService.list(
            new LambdaQueryWrapper<CommunityComment>()
                .eq(CommunityComment::getPostId, postId)
                .orderByAsc(CommunityComment::getCreatedAt)
        );

        Set<Long> uids = comments.stream().map(CommunityComment::getUserId).collect(Collectors.toSet());
        Map<Long, User> authorMap = loadAuthors(uids);
        List<Map<String, Object>> list = comments.stream()
            .map(c -> toCommentView(c, authorMap.get(c.getUserId()), currentUser))
            .collect(Collectors.toList());

        return ResponseEntity.ok(Result.ok("获取成功", list));
    }

    @PostMapping("/posts/{postId}/comments")
    @ResponseBody
    public ResponseEntity<Result<?>> addComment(
        @PathVariable Long postId,
        @RequestBody Map<String, Object> body,
        HttpSession session
    ) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        String content = body.get("content") != null ? String.valueOf(body.get("content")).trim() : "";
        if (content.isEmpty()) {
            return ResponseEntity.ok(Result.badRequest("评论内容不能为空"));
        }
        if (content.length() > COMMENT_MAX) {
            return ResponseEntity.ok(Result.badRequest("评论过长"));
        }

        CommunityPost post = communityPostService.getById(postId);
        if (post == null) {
            return ResponseEntity.ok(Result.notFound("文章不存在"));
        }

        CommunityComment comment = new CommunityComment();
        comment.setPostId(postId);
        comment.setUserId(user.getId());
        comment.setContent(content);
        comment.setCreatedAt(new Date());

        if (communityCommentService.save(comment)) {
            return ResponseEntity.ok(Result.ok("评论成功", toCommentView(comment, user, user)));
        } else {
            return ResponseEntity.ok(Result.fail("评论失败"));
        }
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteComment(@PathVariable Long commentId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }

        CommunityComment comment = communityCommentService.getById(commentId);
        if (comment == null) {
            return ResponseEntity.ok(Result.notFound("评论不存在"));
        }

        if (!ControllerUtils.isAdmin(user) && !user.getId().equals(comment.getUserId())) {
            return ResponseEntity.ok(Result.fail(403, "无权删除该评论"));
        }

        if (communityCommentService.removeById(commentId)) {
            return ResponseEntity.ok(Result.ok("已删除", null));
        } else {
            return ResponseEntity.ok(Result.fail("删除失败"));
        }
    }

    @GetMapping("/posts/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> getPost(@PathVariable Long id, HttpSession session) {
        CommunityPost post = communityPostService.getById(id);
        if (post == null) {
            return ResponseEntity.ok(Result.notFound("文章不存在"));
        }

        communityPostService.update(
            new LambdaUpdateWrapper<CommunityPost>()
                .setSql("view_count = IFNULL(view_count, 0) + 1")
                .eq(CommunityPost::getId, id)
        );
        post = communityPostService.getById(id);

        User author = userService.getById(post.getAuthorId());
        User viewer = (User) session.getAttribute("user");
        return ResponseEntity.ok(Result.ok("获取成功", toDetail(post, author, viewer)));
    }

    @DeleteMapping("/posts/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deletePost(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        CommunityPost post = communityPostService.getById(id);
        if (post == null) {
            return ResponseEntity.ok(Result.notFound("帖子不存在"));
        }
        if (!ControllerUtils.isAdmin(user) && !user.getId().equals(post.getAuthorId())) {
            return ResponseEntity.ok(Result.fail(403, "无权删除该帖"));
        }
        if (communityPostService.removeById(id)) {
            return ResponseEntity.ok(Result.ok("已删除", null));
        } else {
            return ResponseEntity.ok(Result.fail("删除失败"));
        }
    }

    @PostMapping("/posts/{id}/like")
    @ResponseBody
    public ResponseEntity<Result<?>> toggleLike(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        Map<String, Object> data = communityPostInteractionService.toggleLike(id, user.getId());
        if (data == null) {
            return ResponseEntity.ok(Result.notFound("文章不存在"));
        }
        return ResponseEntity.ok(Result.ok("ok", data));
    }

    @PostMapping("/posts/{id}/favorite")
    @ResponseBody
    public ResponseEntity<Result<?>> toggleFavorite(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return ResponseEntity.ok(Result.unauthorized());
        }
        Map<String, Object> data = communityPostInteractionService.toggleFavorite(id, user.getId());
        if (data == null) {
            return ResponseEntity.ok(Result.notFound("文章不存在"));
        }
        return ResponseEntity.ok(Result.ok("ok", data));
    }

    private Map<Long, User> loadAuthors(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userService.listByIds(ids);
        return users.stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    /**
     * 列表/详情中的作者头像：以数据库为准；若库中为空且当前登录者就是作者，则回退会话中的头像，
     * 避免导航栏已显示新头像而社区卡片仍显示首字母占位（会话与库短暂不一致时）。
     */
    private static String resolveAuthorAvatar(User author, User viewer) {
        if (author == null) {
            return null;
        }
        String fromDb = author.getAvatar();
        if (fromDb != null && !fromDb.trim().isEmpty()) {
            return fromDb.trim();
        }
        if (viewer != null && author.getId() != null && author.getId().equals(viewer.getId())) {
            String fromSession = viewer.getAvatar();
            if (fromSession != null && !fromSession.trim().isEmpty()) {
                return fromSession.trim();
            }
        }
        return null;
    }

    private static String authorDisplay(User u) {
        if (u == null) {
            return "未知用户";
        }
        if (u.getUsername() != null && !u.getUsername().trim().isEmpty()) {
            return u.getUsername().trim();
        }
        if (u.getRealName() != null && !u.getRealName().trim().isEmpty()) {
            return u.getRealName().trim();
        }
        return "用户#" + u.getId();
    }

    private static int viewCountOf(CommunityPost p) {
        return p.getViewCount() == null ? 0 : p.getViewCount();
    }

    private static int likeCountOf(CommunityPost p) {
        return p.getLikeCount() == null ? 0 : p.getLikeCount();
    }

    private static int favoriteCountOf(CommunityPost p) {
        return p.getFavoriteCount() == null ? 0 : p.getFavoriteCount();
    }

    private Map<String, Object> toListItem(CommunityPost p, User author, User viewer,
                                            Map<Long, Boolean> likedMap, Map<Long, Boolean> favoritedMap) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("category", p.getCategory());
        m.put("title", p.getTitle());
        String excerpt = HtmlSanitizer.plainTextExcerpt(p.getContent() == null ? "" : p.getContent(), EXCERPT_LEN);
        m.put("excerpt", excerpt);
        m.put("authorId", p.getAuthorId());
        m.put("authorDisplay", authorDisplay(author));
        m.put("authorAvatar", resolveAuthorAvatar(author, viewer));
        m.put("viewCount", viewCountOf(p));
        m.put("likeCount", likeCountOf(p));
        m.put("favoriteCount", favoriteCountOf(p));
        if (viewer != null) {
            m.put("liked", likedMap.getOrDefault(p.getId(), false));
            m.put("favorited", favoritedMap.getOrDefault(p.getId(), false));
        } else {
            m.put("liked", false);
            m.put("favorited", false);
        }
        m.put("createdAt", p.getCreatedAt());
        return m;
    }

    private Map<String, Object> toDetail(CommunityPost p, User author, User viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("category", p.getCategory());
        m.put("title", p.getTitle());
        m.put("content", p.getContent());
        m.put("attachments", parseAttachmentsList(p.getAttachments()));
        m.put("authorId", p.getAuthorId());
        m.put("authorDisplay", authorDisplay(author));
        m.put("authorAvatar", resolveAuthorAvatar(author, viewer));
        m.put("viewCount", viewCountOf(p));
        m.put("createdAt", p.getCreatedAt());
        enrichInteraction(p, viewer, m);
        return m;
    }

    private void enrichInteraction(CommunityPost p, User viewer, Map<String, Object> m) {
        m.put("likeCount", likeCountOf(p));
        m.put("favoriteCount", favoriteCountOf(p));
        if (viewer != null) {
            m.put("liked", communityPostInteractionService.hasLiked(p.getId(), viewer.getId()));
            m.put("favorited", communityPostInteractionService.hasFavorited(p.getId(), viewer.getId()));
        } else {
            m.put("liked", false);
            m.put("favorited", false);
        }
    }

    private Map<String, Object> toCommentView(CommunityComment c, User author, User currentUser) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("postId", c.getPostId());
        m.put("userId", c.getUserId());
        m.put("authorDisplay", authorDisplay(author));
        m.put("authorAvatar", resolveAuthorAvatar(author, currentUser));
        m.put("content", c.getContent());
        m.put("createdAt", c.getCreatedAt());
        boolean canDelete = currentUser != null && (
            "ADMIN".equals(currentUser.getRole()) || currentUser.getId().equals(c.getUserId())
        );
        m.put("canDelete", canDelete);
        return m;
    }

    private List<Map<String, Object>> parseAttachmentsList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String validateAndSerializeAttachments(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("附件列表格式无效");
        }
        List<?> list = (List<?>) raw;
        if (list.size() > ATTACHMENTS_MAX) {
            throw new IllegalArgumentException("附件最多 " + ATTACHMENTS_MAX + " 个");
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> row = (Map<?, ?>) o;
            Object nameObj = row.get("name");
            Object urlObj = row.get("url");
            if (nameObj == null || urlObj == null) {
                continue;
            }
            String name = String.valueOf(nameObj).trim();
            String url = String.valueOf(urlObj).trim();
            if (name.isEmpty() || url.isEmpty()) {
                continue;
            }
            if (name.length() > ATTACHMENT_NAME_MAX) {
                throw new IllegalArgumentException("附件名称过长");
            }
            if (!UPLOADS_URL.matcher(url).matches()) {
                throw new IllegalArgumentException("附件地址无效，请重新上传");
            }
            out.add(Map.of("name", name, "url", url));
        }
        if (out.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalArgumentException("附件保存失败");
        }
    }
}
