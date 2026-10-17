package cn.ilink.controller;

import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.User;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.CommunityPostInteractionService;
import cn.ilink.service.HomeStatsService;
import cn.ilink.service.NotificationService;
import cn.ilink.service.UserService;
import cn.ilink.service.impl.CommunityCommentServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommunityController 基础接口测试。
 * 验证：无效分区的参数校验拦截 + 评论列表匿名可读 + 帖子详情404。
 * 注意：listPosts 接口因依赖 MyBatis-Plus Page 对象，mock 环境下无法正常工作（NPE），
 * 这是 WebMvcTest mock 的限制，属预期行为。
 */
@WebMvcTest(controllers = CommunityController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class CommunityControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CommunityPostServiceImpl communityPostService;

    @MockBean
    private CommunityPostInteractionService communityPostInteractionService;

    @MockBean
    private CommunityPostFavoriteMapper communityPostFavoriteMapper;

    @MockBean
    private CommunityCommentServiceImpl communityCommentService;

    @MockBean
    private UserService userService;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private cn.ilink.service.AdminDataService adminDataService;

    @MockBean
    private cn.ilink.mapper.CommunityCommentMapper communityCommentMapper;

    @MockBean
    private cn.ilink.service.kg.KgIndexService kgIndexService;

    @MockBean
    private LoginAttemptService loginAttemptService;

    @MockBean
    private HomeStatsService homeStatsService;

    @Test
    void listPostsRejectsInvalidCategory() throws Exception {
        mockMvc.perform(get("/api/community/posts")
                .param("page", "1").param("size", "5")
                .param("category", "invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void anonymousCanReadCommentsWithoutLogin() throws Exception {
        // 评论列表匿名可读：与 SecurityConfig 中 GET /api/community/posts/*/comments 的 permitAll
        // 以及社区「公开门户」定位一致。帖子不存在时返回 404，说明请求确实进到了业务逻辑，
        // 而不是在鉴权层被 401 拦下。
        when(communityPostService.getById(1L)).thenReturn(null);

        mockMvc.perform(get("/api/community/posts/1/comments"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void createPostEnqueuesKnowledgeIndexTask() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("tester");

        when(communityPostService.save(any(CommunityPost.class))).thenReturn(true);

        mockMvc.perform(post("/api/community/posts")
                .sessionAttr("user", user)
                .contentType("application/json")
                .content("{\"category\":\"tech\",\"title\":\"标题\","
                    + "\"content\":\"<p>正文内容，包含 <b>HTML</b> 标签</p>\",\"attachments\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        org.mockito.ArgumentCaptor<String> contentCaptor =
            org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kgIndexService).enqueue(
            org.mockito.ArgumentMatchers.eq("POST"), any(), any(), contentCaptor.capture(),
            org.mockito.ArgumentMatchers.eq("GLOBAL"), any(), any());
        // 入图谱的正文必须已去掉 HTML 标签
        org.junit.jupiter.api.Assertions.assertFalse(contentCaptor.getValue().contains("<"));
    }

    @Test
    void getPostReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/community/posts/99999"))
            .andExpect(status().isNotFound());
    }

    @Test
    void updatePostExplicitlyClearsLastAttachment() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("tester");

        CommunityPost post = new CommunityPost();
        post.setId(6L);
        post.setAuthorId(7L);
        post.setCategory("general");
        post.setTitle("原文章");
        post.setContent("<p>正文</p>");
        post.setAttachments("[{\"name\":\"old.pdf\",\"url\":\"/uploads/old.pdf\"}]");

        when(communityPostService.getById(6L)).thenReturn(post);
        when(communityPostService.updateById(any(CommunityPost.class))).thenReturn(true);
        when(userService.getById(7L)).thenReturn(user);

        mockMvc.perform(put("/api/community/posts/6")
                .sessionAttr("user", user)
                .contentType("application/json")
                .content("{\"category\":\"general\",\"title\":\"文章\",\"content\":\"<p>正文</p>\",\"attachments\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        org.mockito.ArgumentCaptor<CommunityPost> captor =
            org.mockito.ArgumentCaptor.forClass(CommunityPost.class);
        verify(communityPostService).updateById(captor.capture());
        assertEquals("[]", captor.getValue().getAttachments());
    }

    @Test
    void replyToReplyIsNormalizedToRootComment() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("tester");

        CommunityPost post = new CommunityPost();
        post.setId(1L);
        post.setAuthorId(9L);
        post.setTitle("文章");

        cn.ilink.entity.CommunityComment parentReply = new cn.ilink.entity.CommunityComment();
        parentReply.setId(11L);
        parentReply.setPostId(1L);
        parentReply.setParentId(10L);   // 它本身是 10 号顶层评论下的回复
        parentReply.setUserId(8L);

        when(communityPostService.getById(1L)).thenReturn(post);
        when(communityCommentService.getById(11L)).thenReturn(parentReply);
        when(communityCommentService.save(any(cn.ilink.entity.CommunityComment.class))).thenReturn(true);
        when(userService.listByIds(any())).thenReturn(java.util.List.of());

        mockMvc.perform(post("/api/community/posts/1/comments")
                .sessionAttr("user", user)
                .contentType("application/json")
                .content("{\"content\":\"收到\",\"parentId\":11}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        org.mockito.ArgumentCaptor<cn.ilink.entity.CommunityComment> captor =
            org.mockito.ArgumentCaptor.forClass(cn.ilink.entity.CommunityComment.class);
        verify(communityCommentService).save(captor.capture());
        // 回复的回复归一到顶层评论，保持单层结构
        assertEquals(10L, captor.getValue().getParentId());
        // 被回复者记录为 11 号评论的作者
        assertEquals(8L, captor.getValue().getReplyToUserId());
    }

    /**
     * 核实报告 P2-1：content 传对象/数字/布尔不能被 String.valueOf 强转成脏字符串落库。
     */
    @Test
    void commentContentMustBeString() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("tester");

        CommunityPost post = new CommunityPost();
        post.setId(1L);
        post.setAuthorId(9L);
        post.setTitle("文章");
        when(communityPostService.getById(1L)).thenReturn(post);

        mockMvc.perform(post("/api/community/posts/1/comments")
                .sessionAttr("user", user)
                .contentType("application/json")
                .content("{\"content\":{\"a\":1}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/community/posts/1/comments")
                .sessionAttr("user", user)
                .contentType("application/json")
                .content("{\"content\":12345}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(communityCommentService, never()).save(any(cn.ilink.entity.CommunityComment.class));
    }

    @Test
    void replyToCommentFromAnotherPostIsRejected() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("tester");

        CommunityPost post = new CommunityPost();
        post.setId(1L);
        post.setAuthorId(9L);
        post.setTitle("文章");

        cn.ilink.entity.CommunityComment foreign = new cn.ilink.entity.CommunityComment();
        foreign.setId(50L);
        foreign.setPostId(2L);   // 属于另一篇文章

        when(communityPostService.getById(1L)).thenReturn(post);
        when(communityCommentService.getById(50L)).thenReturn(foreign);

        mockMvc.perform(post("/api/community/posts/1/comments")
                .sessionAttr("user", user)
                .contentType("application/json")
                .content("{\"content\":\"越界回复\",\"parentId\":50}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verify(communityCommentService, org.mockito.Mockito.never())
            .save(any(cn.ilink.entity.CommunityComment.class));
    }

    @Test
    void updatePostPersistsOnlyRemainingAttachments() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("tester");

        CommunityPost post = new CommunityPost();
        post.setId(6L);
        post.setAuthorId(7L);
        post.setAttachments("[{\"name\":\"old.pdf\",\"url\":\"/uploads/old.pdf\"}]");

        when(communityPostService.getById(6L)).thenReturn(post);
        when(communityPostService.updateById(any(CommunityPost.class))).thenReturn(true);
        when(userService.getById(7L)).thenReturn(user);

        mockMvc.perform(put("/api/community/posts/6")
                .sessionAttr("user", user)
                .contentType("application/json")
                .content("{\"category\":\"general\",\"title\":\"文章\",\"content\":\"<p>正文</p>\","
                    + "\"attachments\":[{\"name\":\"keep.pdf\",\"url\":\"/uploads/keep.pdf\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        org.mockito.ArgumentCaptor<CommunityPost> captor =
            org.mockito.ArgumentCaptor.forClass(CommunityPost.class);
        verify(communityPostService).updateById(captor.capture());
        assertEquals(
            objectMapper.readTree("[{\"name\":\"keep.pdf\",\"url\":\"/uploads/keep.pdf\"}]"),
            objectMapper.readTree(captor.getValue().getAttachments())
        );
    }

    /** 文章接口仅发布者本人可操作；管理员一律 403（删除/编辑能力收敛在管理后台）。 */
    private CommunityPost postOf(long authorId) {
        CommunityPost post = new CommunityPost();
        post.setId(6L);
        post.setAuthorId(authorId);
        post.setTitle("别人的文章");
        return post;
    }

    @Test
    void deletePostRejectsNonAuthor() throws Exception {
        User stranger = new User();
        stranger.setId(99L);
        stranger.setUsername("stranger");
        stranger.setRole("STUDENT");
        when(communityPostService.getById(6L)).thenReturn(postOf(7L));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/community/posts/6")
                .sessionAttr("user", stranger))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void deletePostRejectsAdmin() throws Exception {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        when(communityPostService.getById(6L)).thenReturn(postOf(7L));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/community/posts/6")
                .sessionAttr("user", admin))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void forEditRejectsAdmin() throws Exception {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        when(communityPostService.getById(6L)).thenReturn(postOf(7L));

        mockMvc.perform(get("/api/community/posts/6/for-edit")
                .sessionAttr("user", admin))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }
}
