package cn.ilink.controller;

import cn.ilink.entity.Asset;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.User;
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「我的发布」摘要提取的回归测试。
 *
 * <p>文章正文以一段不可见的 Markdown 标记开头（base64 保存整篇源码）。
 * 早期实现在 SQL 层用 LEFT(content, 400) 截断，会切在标记中间，导致
 * 列表里直接显示半截 {@code <span ...} 的 HTML。这里锁住两点：
 * 完整标记要被剥掉、被截断的标记也不能漏到摘要里。</p>
 */
@WebMvcTest(controllers = ProfilePublishController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProfilePublishControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssetServiceImpl assetService;

    @MockBean
    private CommunityPostServiceImpl communityPostService;

    private User loginUser() {
        User user = new User();
        user.setId(7L);
        return user;
    }

    private CommunityPost post(long id, String content, Date createdAt) {
        CommunityPost post = new CommunityPost();
        post.setId(id);
        post.setAuthorId(7L);
        post.setTitle("文章 " + id);
        post.setCategory("技术讨论");
        post.setContent(content);
        post.setViewCount(1);
        post.setLikeCount(0);
        post.setCreatedAt(createdAt);
        return post;
    }

    private Asset asset(long id, String description) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setUserId(7L);
        asset.setTitle("作品 " + id);
        asset.setCategory("项目");
        asset.setDescription(description);
        asset.setViewCount(3);
        asset.setCreatedAt(new Date(1_700_000_000_000L));
        return asset;
    }

    @Test
    void summariesNeverLeakMarkdownMarkerMarkup() throws Exception {
        String marker = "<span class=\"ilink-markdown-source\" data-ilink-markdown=\""
            + "Q0NfQ2hpbmVzZSDov47mnaXkuoblhajpnaLph43mnoTvvJo=\" hidden aria-hidden=\"true\"></span>";
        // 被 SQL 截断的标记：没有闭合的 ">"，早期实现正是把它当正文吐了出来
        String truncated = "<span class=\"ilink-markdown-source\" data-ilink-markdown=\"Q0NfQ2hpbmVzZSDov47mnaXkuobl";

        CommunityPost normal = post(1L, marker + "<p>第一段正文内容</p><table><tr><td>单元格</td></tr></table>",
            new Date(1_700_000_200_000L));
        CommunityPost broken = post(2L, truncated, new Date(1_700_000_100_000L));
        Asset work = asset(3L, marker + "<p>作品说明文字</p>");

        when(communityPostService.list(any(Wrapper.class)))
            .thenReturn(Arrays.asList(normal, broken));
        when(assetService.list(any(Wrapper.class)))
            .thenReturn(Collections.singletonList(work));

        mockMvc.perform(get("/api/profile/publications")
                .param("page", "1")
                .param("size", "10")
                .sessionAttr("user", loginUser()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].summary", containsString("第一段正文内容")))
            .andExpect(jsonPath("$.data[0].summary", not(containsString("<span"))))
            .andExpect(jsonPath("$.data[1].summary", not(containsString("<span"))))
            .andExpect(jsonPath("$.data[1].summary", not(containsString("data-ilink-markdown"))))
            .andExpect(jsonPath("$.data[2].summary", containsString("作品说明文字")))
            .andExpect(jsonPath("$.data[2].summary", not(containsString("<span"))));
    }
}
