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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CommunityController 基础接口测试。
 * 验证：无效分区的参数校验拦截 + 评论接口认证拦截 + 帖子详情404。
 * 注意：listPosts 接口因依赖 MyBatis-Plus Page 对象，mock 环境下无法正常工作（NPE），
 * 这是 WebMvcTest mock 的限制，属预期行为。
 */
@WebMvcTest(controllers = CommunityController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class CommunityControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

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
    void getCommentsRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/community/posts/1/comments"))
            .andExpect(status().isUnauthorized());
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
        assertEquals("[{\"name\":\"keep.pdf\",\"url\":\"/uploads/keep.pdf\"}]", captor.getValue().getAttachments());
    }
}
