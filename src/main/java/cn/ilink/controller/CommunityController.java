package cn.ilink.controller;

import cn.ilink.common.ControllerUtils;
import cn.ilink.common.Result;
import cn.ilink.entity.CommunityComment;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.CommunityPostFavorite;
import cn.ilink.entity.User;
import cn.ilink.service.impl.CommunityCommentServiceImpl;
import cn.ilink.service.CommunityPostInteractionService;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.NotificationService;
import cn.ilink.service.UserService;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.util.HtmlSanitizer;
import cn.ilink.vo.CommunityCommentVO;
import cn.ilink.vo.CommunityPostDetailVO;
import cn.ilink.vo.CommunityPostListItemVO;
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
import static cn.ilink.common.ControllerUtils.safePage;
import static cn.ilink.common.ControllerUtils.safeSize;

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
    private CommunityPostServiceImpl communityPostService;

    @Autowired
    private CommunityPostInteractionService communityPostInteractionService;

    @Autowired
    private CommunityPostFavoriteMapper communityPostFavoriteMapper;

    @Autowired
    private CommunityCommentServiceImpl communityCommentService;

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationService notificationService;

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
                return Result.badRequest("无效的分区参数").toResponseEntity();
            }
            wrapper.eq(CommunityPost::getCategory, c);
        }

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(CommunityPost::getTitle, kw)
                .or().like(CommunityPost::getContent, kw));
        }

        int safePage = safePage(page);
        int safeSize = safeSize(size, 100);
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
        List<CommunityPostListItemVO> views = records.stream()
            .map(p -> toListItem(p, authorMap.get(p.getAuthorId()), viewer, likedMap, favoritedMap))
            .collect(Collectors.toList());

        return Result.ok("获取成功", views).withPagination(safePage, safeSize, (int) total).toResponseEntity();
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
            return Result.unauthorized().toResponseEntity();
        }

        LambdaQueryWrapper<CommunityPost> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CommunityPost::getAuthorId, viewer.getId()).orderByDesc(CommunityPost::getCreatedAt);

        int safePage = safePage(page);
        int safeSize = safeSize(size, 100);
        Page<CommunityPost> pageObj = new Page<>(safePage, safeSize);
        Page<CommunityPost> result = communityPostService.page(pageObj, wrapper);
        List<CommunityPost> records = result.getRecords();
        long total = result.getTotal();

        List<Long> postIds = records.stream().map(CommunityPost::getId).collect(Collectors.toList());
        Map<Long, Boolean> likedMap = communityPostInteractionService.batchLikedStatus(viewer.getId(), postIds);
        Map<Long, Boolean> favoritedMap = communityPostInteractionService.batchFavoritedStatus(viewer.getId(), postIds);
        List<CommunityPostListItemVO> views = records.stream()
            .map(p -> toListItem(p, viewer, viewer, likedMap, favoritedMap))
            .collect(Collectors.toList());

        return Result.ok("获取成功", views).withPagination(safePage, safeSize, (int) total).toResponseEntity();
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
            return Result.unauthorized().toResponseEntity();
        }

        LambdaQueryWrapper<CommunityPostFavorite> favWrapper = new LambdaQueryWrapper<>();
        favWrapper.eq(CommunityPostFavorite::getUserId, viewer.getId())
            .orderByDesc(CommunityPostFavorite::getCreatedAt);

        int safePage = safePage(page);
        int safeSize = safeSize(size, 100);
        Page<CommunityPostFavorite> favPage = new Page<>(safePage, safeSize);
        Page<CommunityPostFavorite> favResult = communityPostFavoriteMapper.selectPage(favPage, favWrapper);
        List<CommunityPostFavorite> favRecords = favResult.getRecords();
        long total = favResult.getTotal();

        List<Long> postIds = favRecords.stream()
            .map(CommunityPostFavorite::getPostId)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (postIds.isEmpty()) {
            return Result.ok("获取成功", Collections.emptyList()).withPagination(safePage, safeSize, total).toResponseEntity();
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
        List<CommunityPostListItemVO> views = ordered.stream()
            .map(p -> toListItem(p, authorMap.get(p.getAuthorId()), viewer, likedMap, favoritedMap))
            .collect(Collectors.toList());

        return Result.ok("获取成功", views).withPagination(safePage, safeSize, total).toResponseEntity();
    }

    /**
     * 编辑页拉取正文：不增加阅读量；仅作者或管理员
     */
    @GetMapping("/posts/{id}/for-edit")
    @ResponseBody
    public ResponseEntity<Result<?>> getPostForEdit(@PathVariable Long id, HttpSession session) {
        User viewer = ControllerUtils.requireUser(session);
        if (viewer == null) {
            return Result.unauthorized().toResponseEntity();
        }
        CommunityPost post = communityPostService.getById(id);
        if (post == null) {
            return Result.notFound("文章不存在").toResponseEntity();
        }
        if (!ControllerUtils.isAdmin(viewer) && !viewer.getId().equals(post.getAuthorId())) {
            return Result.fail(403, "无权编辑该文章").toResponseEntity();
        }
        User author = userService.getById(post.getAuthorId());
        return Result.ok("获取成功", toDetail(post, author, viewer)).toResponseEntity();
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
            return Result.unauthorized().toResponseEntity();
        }
        CommunityPost existing = communityPostService.getById(id);
        if (existing == null) {
            return Result.notFound("文章不存在").toResponseEntity();
        }
        if (!ControllerUtils.isAdmin(user) && !user.getId().equals(existing.getAuthorId())) {
            return Result.fail(403, "无权修改该文章").toResponseEntity();
        }

        String category = body.get("category") != null ? String.valueOf(body.get("category")).trim() : "";
        String title = body.get("title") != null ? String.valueOf(body.get("title")).trim() : "";
        String rawContent = body.get("content") != null ? String.valueOf(body.get("content")) : "";
        String content = HtmlSanitizer.communityPost(rawContent);

        if (!CATEGORIES.contains(category)) {
            return Result.badRequest("请选择有效分区").toResponseEntity();
        }
        if (title.isEmpty()) {
            return Result.badRequest("标题不能为空").toResponseEntity();
        }
        if (title.length() > TITLE_MAX) {
            return Result.badRequest("标题过长").toResponseEntity();
        }
        if (Jsoup.parse(content).text().trim().isEmpty()) {
            return Result.badRequest("正文不能为空").toResponseEntity();
        }
        if (content.length() > CONTENT_MAX) {
            return Result.badRequest("正文过长").toResponseEntity();
        }

        String attachmentsJson;
        try {
            attachmentsJson = validateAndSerializeAttachments(body.get("attachments"));
        } catch (IllegalArgumentException ex) {
            return Result.badRequest(ex.getMessage()).toResponseEntity();
        }

        existing.setCategory(category);
        existing.setTitle(title);
        existing.setContent(content);
        existing.setAttachments(attachmentsJson);

        if (communityPostService.updateById(existing)) {
            CommunityPost updated = communityPostService.getById(id);
            User author = userService.getById(updated.getAuthorId());
            return Result.ok("保存成功", toDetail(updated, author, user)).toResponseEntity();
        } else {
            return Result.fail("保存失败").toResponseEntity();
        }
    }

    @PostMapping("/posts")
    @ResponseBody
    public ResponseEntity<Result<?>> createPost(@RequestBody Map<String, Object> body, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        String category = body.get("category") != null ? String.valueOf(body.get("category")).trim() : "";
        String title = body.get("title") != null ? String.valueOf(body.get("title")).trim() : "";
        String rawContent = body.get("content") != null ? String.valueOf(body.get("content")) : "";
        String content = HtmlSanitizer.communityPost(rawContent);

        if (!CATEGORIES.contains(category)) {
            return Result.badRequest("请选择有效分区").toResponseEntity();
        }
        if (title.isEmpty()) {
            return Result.badRequest("标题不能为空").toResponseEntity();
        }
        if (title.length() > TITLE_MAX) {
            return Result.badRequest("标题过长").toResponseEntity();
        }
        if (Jsoup.parse(content).text().trim().isEmpty()) {
            return Result.badRequest("正文不能为空").toResponseEntity();
        }
        if (content.length() > CONTENT_MAX) {
            return Result.badRequest("正文过长").toResponseEntity();
        }

        String attachmentsJson;
        try {
            attachmentsJson = validateAndSerializeAttachments(body.get("attachments"));
        } catch (IllegalArgumentException ex) {
            return Result.badRequest(ex.getMessage()).toResponseEntity();
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
            return Result.ok("发布成功", toDetail(post, user, user)).toResponseEntity();
        } else {
            return Result.fail("发布失败").toResponseEntity();
        }
    }

    @GetMapping("/posts/{postId}/comments")
    @ResponseBody
    public ResponseEntity<Result<?>> listComments(@PathVariable Long postId, HttpSession session) {
        User currentUser = (User) session.getAttribute("user");
        if (currentUser == null) {
            return Result.unauthorized().toResponseEntity();
        }

        CommunityPost post = communityPostService.getById(postId);
        if (post == null) {
            return Result.notFound("文章不存在").toResponseEntity();
        }

        List<CommunityComment> comments = communityCommentService.list(
            new LambdaQueryWrapper<CommunityComment>()
                .eq(CommunityComment::getPostId, postId)
                .orderByAsc(CommunityComment::getCreatedAt)
        );

        Set<Long> uids = comments.stream().map(CommunityComment::getUserId).collect(Collectors.toSet());
        Map<Long, User> authorMap = loadAuthors(uids);
        List<CommunityCommentVO> list = comments.stream()
            .map(c -> toCommentView(c, authorMap.get(c.getUserId()), currentUser))
            .collect(Collectors.toList());

        return Result.ok("获取成功", list).toResponseEntity();
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
            return Result.unauthorized().toResponseEntity();
        }

        String content = body.get("content") != null ? String.valueOf(body.get("content")).trim() : "";
        if (content.isEmpty()) {
            return Result.badRequest("评论内容不能为空").toResponseEntity();
        }
        if (content.length() > COMMENT_MAX) {
            return Result.badRequest("评论过长").toResponseEntity();
        }

        CommunityPost post = communityPostService.getById(postId);
        if (post == null) {
            return Result.notFound("文章不存在").toResponseEntity();
        }

        CommunityComment comment = new CommunityComment();
        comment.setPostId(postId);
        comment.setUserId(user.getId());
        comment.setContent(content);
        comment.setCreatedAt(new Date());

        if (communityCommentService.save(comment)) {
            // 通知文章作者（排除作者自己评论）
            if (!post.getAuthorId().equals(user.getId())) {
                User author = userService.getById(post.getAuthorId());
                notificationService.create(
                    post.getAuthorId(),
                    user.getId(),
                    "COMMENT",
                    "你的文章被评论了",
                    author != null ? author.getUsername() : "某位用户" + " 评论了你的文章《" + post.getTitle() + "》：" + content,
                    postId
                );
            }
            return Result.ok("评论成功", toCommentView(comment, user, user)).toResponseEntity();
        } else {
            return Result.fail("评论失败").toResponseEntity();
        }
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseBody
    public ResponseEntity<Result<?>> deleteComment(@PathVariable Long commentId, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }

        CommunityComment comment = communityCommentService.getById(commentId);
        if (comment == null) {
            return Result.notFound("评论不存在").toResponseEntity();
        }

        if (!ControllerUtils.isAdmin(user) && !user.getId().equals(comment.getUserId())) {
            return Result.fail(403, "无权删除该评论").toResponseEntity();
        }

        if (communityCommentService.removeById(commentId)) {
            return Result.ok("已删除", null).toResponseEntity();
        } else {
            return Result.fail("删除失败").toResponseEntity();
        }
    }

    @GetMapping("/posts/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> getPost(@PathVariable Long id, HttpSession session) {
        CommunityPost post = communityPostService.getById(id);
        if (post == null) {
            return Result.notFound("文章不存在").toResponseEntity();
        }

        communityPostService.update(
            new LambdaUpdateWrapper<CommunityPost>()
                .setSql("view_count = IFNULL(view_count, 0) + 1")
                .eq(CommunityPost::getId, id)
        );
        post = communityPostService.getById(id);

        User author = userService.getById(post.getAuthorId());
        User viewer = (User) session.getAttribute("user");
        return Result.ok("获取成功", toDetail(post, author, viewer)).toResponseEntity();
    }

    @DeleteMapping("/posts/{id}")
    @ResponseBody
    public ResponseEntity<Result<?>> deletePost(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        CommunityPost post = communityPostService.getById(id);
        if (post == null) {
            return Result.notFound("帖子不存在").toResponseEntity();
        }
        if (!ControllerUtils.isAdmin(user) && !user.getId().equals(post.getAuthorId())) {
            return Result.fail(403, "无权删除该帖").toResponseEntity();
        }
        if (communityPostService.removeById(id)) {
            return Result.ok("已删除", null).toResponseEntity();
        } else {
            return Result.fail("删除失败").toResponseEntity();
        }
    }

    @PostMapping("/posts/{id}/like")
    @ResponseBody
    public ResponseEntity<Result<?>> toggleLike(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        Map<String, Object> data = communityPostInteractionService.toggleLike(id, user.getId());
        if (data == null) {
            return Result.notFound("文章不存在").toResponseEntity();
        }
        return Result.ok("ok", data).toResponseEntity();
    }

    @PostMapping("/posts/{id}/favorite")
    @ResponseBody
    public ResponseEntity<Result<?>> toggleFavorite(@PathVariable Long id, HttpSession session) {
        User user = ControllerUtils.requireUser(session);
        if (user == null) {
            return Result.unauthorized().toResponseEntity();
        }
        Map<String, Object> data = communityPostInteractionService.toggleFavorite(id, user.getId());
        if (data == null) {
            return Result.notFound("文章不存在").toResponseEntity();
        }
        return Result.ok("ok", data).toResponseEntity();
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

    private CommunityPostListItemVO toListItem(CommunityPost p, User author, User viewer,
                                            Map<Long, Boolean> likedMap, Map<Long, Boolean> favoritedMap) {
        CommunityPostListItemVO vo = new CommunityPostListItemVO();
        vo.setId(p.getId());
        vo.setCategory(p.getCategory());
        vo.setTitle(p.getTitle());
        String excerpt = HtmlSanitizer.plainTextExcerpt(p.getContent() == null ? "" : p.getContent(), EXCERPT_LEN);
        vo.setExcerpt(excerpt);
        vo.setAuthorId(p.getAuthorId());
        vo.setAuthorDisplay(authorDisplay(author));
        vo.setAuthorAvatar(resolveAuthorAvatar(author, viewer));
        vo.setViewCount((long) viewCountOf(p));
        vo.setLikeCount((long) likeCountOf(p));
        vo.setFavoriteCount((long) favoriteCountOf(p));
        if (viewer != null) {
            vo.setLiked(likedMap.getOrDefault(p.getId(), false));
            vo.setFavorited(favoritedMap.getOrDefault(p.getId(), false));
        } else {
            vo.setLiked(false);
            vo.setFavorited(false);
        }
        vo.setCreatedAt(p.getCreatedAt());
        return vo;
    }

    private CommunityPostDetailVO toDetail(CommunityPost p, User author, User viewer) {
        CommunityPostDetailVO vo = new CommunityPostDetailVO();
        vo.setId(p.getId());
        vo.setCategory(p.getCategory());
        vo.setTitle(p.getTitle());
        vo.setContent(p.getContent());
        vo.setAttachments(parseAttachmentsList(p.getAttachments()));
        vo.setAuthorId(p.getAuthorId());
        vo.setAuthorDisplay(authorDisplay(author));
        vo.setAuthorAvatar(resolveAuthorAvatar(author, viewer));
        vo.setViewCount((long) viewCountOf(p));
        vo.setCreatedAt(p.getCreatedAt());
        enrichInteraction(p, viewer, vo);
        return vo;
    }

    private void enrichInteraction(CommunityPost p, User viewer, CommunityPostDetailVO vo) {
        vo.setLikeCount((long) likeCountOf(p));
        vo.setFavoriteCount((long) favoriteCountOf(p));
        if (viewer != null) {
            vo.setLiked(communityPostInteractionService.hasLiked(p.getId(), viewer.getId()));
            vo.setFavorited(communityPostInteractionService.hasFavorited(p.getId(), viewer.getId()));
        } else {
            vo.setLiked(false);
            vo.setFavorited(false);
        }
    }

    private CommunityCommentVO toCommentView(CommunityComment c, User author, User currentUser) {
        CommunityCommentVO vo = new CommunityCommentVO();
        vo.setId(c.getId());
        vo.setPostId(c.getPostId());
        vo.setUserId(c.getUserId());
        vo.setAuthorDisplay(authorDisplay(author));
        vo.setAuthorAvatar(resolveAuthorAvatar(author, currentUser));
        vo.setContent(c.getContent());
        vo.setCreatedAt(c.getCreatedAt());
        boolean canDelete = currentUser != null && (
            "ADMIN".equals(currentUser.getRole()) || currentUser.getId().equals(c.getUserId())
        );
        vo.setCanDelete(canDelete);
        return vo;
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
