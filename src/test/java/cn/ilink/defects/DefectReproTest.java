package cn.ilink.defects;

import cn.ilink.controller.TeamController;
import cn.ilink.dto.RecommendedUserVO;
import cn.ilink.entity.CommunityPost;
import cn.ilink.entity.RecommendationLog;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.User;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.mapper.CommunityPostLikeMapper;
import cn.ilink.mapper.RecommendationLogMapper;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.UserMapper;
import cn.ilink.service.NotificationService;
import cn.ilink.service.RecommendationService;
import cn.ilink.service.UserService;
import cn.ilink.service.impl.CommunityPostInteractionServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.impl.RecommendationServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺陷回归测试：以下 4 个缺陷在全面测试中曾被复现测试证实（TESTING_REPORT.md 第六章），
 * 修复后断言已翻转为【修复后的正确行为】；任何一项失败即说明修复被回退。
 */
class DefectReproTest {

    // =========================================================================
    // D1（P1 安全，已修复）：推荐接口缓存 key 现包含 requesterId。
    //   修复前：非队长用户 B 命中队长的缓存结果，鉴权被绕过；
    //   修复后：B 的请求 key 不同 → 缓存未命中 → 方法体执行 → 抛 AccessDeniedException。
    // =========================================================================
    @Test
    void D1_recommendationCacheNoLongerBypassesOwnerCheck() {
        TeamDemandMapper teamDemandMapper = mock(TeamDemandMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        TeamApplicationMapper teamApplicationMapper = mock(TeamApplicationMapper.class);
        RecommendationLogMapper recLogMapper = mock(RecommendationLogMapper.class);

        TeamDemand team = new TeamDemand();
        team.setId(1L);
        team.setCreatorId(100L);
        team.setTitle("测试队伍");
        when(teamDemandMapper.selectById(1L)).thenReturn(team);
        Page<User> emptyPage = new Page<>(1, 200);
        emptyPage.setRecords(Collections.emptyList());
        when(userMapper.selectPage(any(), any())).thenReturn(emptyPage);

        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.register(CachingConfig.class);
        ctx.registerBean(TeamDemandMapper.class, () -> teamDemandMapper);
        ctx.registerBean(UserMapper.class, () -> userMapper);
        ctx.registerBean(TeamApplicationMapper.class, () -> teamApplicationMapper);
        ctx.registerBean(RecommendationLogMapper.class, () -> recLogMapper);
        ctx.registerBean(RecommendationServiceImpl.class);
        ctx.refresh();
        try {
            RecommendationService service = ctx.getBean(RecommendationService.class);

            // 队长 A 首次调用，正常，并把结果写入缓存（key 含 requesterId）
            List<RecommendedUserVO> first = assertDoesNotThrow(
                () -> service.getRecommendedUsers(100L, 1L, 10));
            assertNotNull(first);

            // 非队长用户 B 调用同一 teamId：key 不同导致缓存未命中，方法体的队长校验生效
            assertThrows(AccessDeniedException.class,
                () -> service.getRecommendedUsers(200L, 1L, 10),
                "修复回退：非队长用户本应抛 AccessDeniedException");
        } finally {
            ctx.close();
        }
    }

    // =========================================================================
    // D2（P2 并发正确性，已修复）：unlike 改为 delete 优先、按影响行数扣减。
    //   修复前：第二次 delete 影响 0 行仍执行扣减，like_count 双扣；
    //   修复后：delete 影响 0 行时不扣减，update 只执行一次。
    // =========================================================================
    @Test
    void D2_unlikeNoLongerDeductsWhenNoRowDeleted() {
        CommunityPostInteractionServiceImpl service = new CommunityPostInteractionServiceImpl();
        CommunityPostLikeMapper likeMapper = mock(CommunityPostLikeMapper.class);
        CommunityPostFavoriteMapper favMapper = mock(CommunityPostFavoriteMapper.class);
        CommunityPostServiceImpl postService = mock(CommunityPostServiceImpl.class);
        NotificationService notificationService = mock(NotificationService.class);
        UserService userService = mock(UserService.class);
        ReflectionTestUtils.setField(service, "communityPostLikeMapper", likeMapper);
        ReflectionTestUtils.setField(service, "communityPostFavoriteMapper", favMapper);
        ReflectionTestUtils.setField(service, "communityPostService", postService);
        ReflectionTestUtils.setField(service, "notificationService", notificationService);
        ReflectionTestUtils.setField(service, "userService", userService);

        CommunityPost post = new CommunityPost();
        post.setId(1L);
        post.setAuthorId(99L);
        post.setLikeCount(5);
        when(postService.getById(1L)).thenReturn(post);
        // P2-2 修复后事务首句为 getOne(... FOR UPDATE) 锁定读，此处提供返回以进入业务分支
        when(postService.getOne(any())).thenReturn(post);
        // 模拟竞态窗口：两次请求都"看到"已点赞（修复后 selectCount 已删除，此 stub 仅为防御）
        when(likeMapper.selectCount(any())).thenReturn(1L);
        // 第一个请求删到 1 行；第二个请求删到 0 行（行已被第一个请求删除）
        when(likeMapper.delete(any(Wrapper.class))).thenReturn(1, 0);
        // 并发窗口内第二个请求的 insert 会撞唯一键（与真实 InnoDB 行为一致）
        when(likeMapper.insert(any(cn.ilink.entity.CommunityPostLike.class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_post_user_like"));
        when(postService.update(any(Wrapper.class))).thenReturn(true);

        service.toggleLike(1L, 7L);
        service.toggleLike(1L, 7L);

        // 修复后：第二次 delete 影响 0 行时不再执行扣减
        verify(postService, times(1)).update(any(Wrapper.class));
        verify(likeMapper, never()).selectCount(any());
    }

    // =========================================================================
    // D3（P3 健壮性，已修复）：joinTeam 的 message 为 JSON null 时不再 NPE。
    // =========================================================================
    @Test
    void D3_joinTeamNullMessageReturnsOk() {
        cn.ilink.service.impl.TeamDemandServiceImpl teamDemandService =
            mock(cn.ilink.service.impl.TeamDemandServiceImpl.class);
        cn.ilink.service.impl.TeamApplicationServiceImpl teamApplicationService =
            mock(cn.ilink.service.impl.TeamApplicationServiceImpl.class);
        cn.ilink.service.TeamMembershipService membershipService =
            mock(cn.ilink.service.TeamMembershipService.class);
        TeamController controller = new TeamController(
            teamDemandService,
            mock(cn.ilink.mapper.TeamDemandMapper.class),
            teamApplicationService,
            mock(UserService.class),
            mock(NotificationService.class),
            mock(cn.ilink.mapper.UserSkillMapper.class),
            mock(cn.ilink.service.TeamApplicationWorkflowService.class),
            membershipService,
            mock(cn.ilink.service.TeamInviteService.class),
            mock(cn.ilink.service.impl.CompetitionServiceImpl.class),
            mock(cn.ilink.service.TeamLibraryService.class),
            mock(cn.ilink.teamagent.TeamAgentLifecycleService.class),
            mock(cn.ilink.service.TeamAgentDataCleanupService.class),
            mock(cn.ilink.collab.TeamCollaborationCleanupService.class));

        TeamDemand team = new TeamDemand();
        team.setId(9L);
        team.setStatus("OPEN");
        team.setRequiredMemberCount(3);
        team.setCreatorId(1L);
        when(teamDemandService.getById(9L)).thenReturn(team);
        when(membershipService.isFull(team)).thenReturn(false);
        when(teamApplicationService.getOne(any())).thenReturn(null);
        when(teamApplicationService.save(any())).thenReturn(true);

        User joiner = new User();
        joiner.setId(2L);
        joiner.setRole("STUDENT");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", joiner);

        // JSON {"teamId":9,"message":null} 反序列化后 containsKey=true、get=null
        Map<String, Object> body = new HashMap<>();
        body.put("teamId", "9");
        body.put("message", null);

        // 修复后：null message 按空串处理，申请正常提交（200）
        ResponseEntity<cn.ilink.common.Result<?>> response =
            assertDoesNotThrow(() -> controller.joinTeam(body, session));
        assertEquals(200, response.getBody().getCode());
    }

    // =========================================================================
    // D4（P1 性能，已修复）：推荐接口评分改为批量 IN 查询。
    //   修复前：50 候选用户 → 250 条 SQL（每人 5 条），且每次请求写 50 行日志；
    //   修复后：User 复用不再重查、评分 3 次批量查询、日志只为实际返回的 20 条写入。
    // =========================================================================
    @Test
    void D4_recommendationBatchQueries() {
        TeamDemandMapper teamDemandMapper = mock(TeamDemandMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        TeamApplicationMapper teamApplicationMapper = mock(TeamApplicationMapper.class);
        RecommendationLogMapper recLogMapper = mock(RecommendationLogMapper.class);

        TeamDemand team = new TeamDemand();
        team.setId(1L);
        team.setCreatorId(100L);
        team.setRequiredSkills("Java");
        team.setCreatedAt(new Date());
        when(teamDemandMapper.selectById(1L)).thenReturn(team);

        List<User> candidates = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            User u = new User();
            u.setId((long) i);
            u.setUsername("user" + i);
            u.setRole("STUDENT");
            u.setBio("Java,Python");
            u.setSchool("测试大学");
            candidates.add(u);
        }
        when(userMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<User> p = inv.getArgument(0);
            p.setRecords(candidates);
            return p;
        });
        when(teamApplicationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(recLogMapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(recLogMapper.selectCount(any())).thenReturn(0L);
        when(recLogMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(recLogMapper.selectMaps(any())).thenReturn(Collections.emptyList());
        when(recLogMapper.insert(any(RecommendationLog.class))).thenReturn(1);

        RecommendationServiceImpl service = new RecommendationServiceImpl();
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "teamDemandMapper", teamDemandMapper);
        ReflectionTestUtils.setField(service, "teamApplicationMapper", teamApplicationMapper);
        ReflectionTestUtils.setField(service, "baseMapper", recLogMapper);

        List<RecommendedUserVO> result = service.getRecommendedUsers(100L, 1L, 20);
        assertEquals(20, result.size());

        // 修复后：不再循环内重查 User；历史/评分合并为 2 次 selectList、活跃度 1 次 selectMaps；
        // 推荐日志只为排序截断后返回的 20 个候选写入（原先 200 候选 = 200 条 INSERT）
        verify(userMapper, never()).selectById(anyLong());
        verify(recLogMapper, times(2)).selectList(any());
        verify(recLogMapper, times(1)).selectMaps(any());
        verify(recLogMapper, times(20)).insert(any());
    }

    @Configuration
    @EnableCaching
    static class CachingConfig {
        @Bean
        public CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("recommendedUsers");
        }
    }
}
