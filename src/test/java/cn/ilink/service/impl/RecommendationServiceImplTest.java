package cn.ilink.service.impl;

import cn.ilink.entity.RecommendationLog;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.RecommendationLogMapper;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationServiceImplTest {

    private RecommendationLogMapper logMapper;
    private TeamDemandMapper teamMapper;
    private RecommendationServiceImpl service;

    @BeforeEach
    void setUp() {
        logMapper = mock(RecommendationLogMapper.class);
        teamMapper = mock(TeamDemandMapper.class);
        service = new RecommendationServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", logMapper);
        ReflectionTestUtils.setField(service, "teamDemandMapper", teamMapper);
        ReflectionTestUtils.setField(service, "teamApplicationMapper", mock(TeamApplicationMapper.class));
        ReflectionTestUtils.setField(service, "userMapper", mock(UserMapper.class));
    }

    @Test
    void feedbackActionIsNormalizedAndSavedForOwner() {
        RecommendationLog recommendation = recommendation(3L, 7L);
        when(logMapper.selectById(3L)).thenReturn(recommendation);
        when(logMapper.updateById(recommendation)).thenReturn(1);

        service.recordFeedback(3L, 7L, "viewed");

        assertEquals("VIEWED", recommendation.getAction());
        verify(logMapper).updateById(recommendation);
    }

    @Test
    void userCannotChangeAnotherUsersRecommendationLog() {
        when(logMapper.selectById(3L)).thenReturn(recommendation(3L, 8L));

        assertThrows(AccessDeniedException.class,
            () -> service.recordFeedback(3L, 7L, "DISMISSED"));

        verify(logMapper, never()).updateById(any());
    }

    @Test
    void arbitraryFeedbackActionIsRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> service.recordFeedback(3L, 7L, "DELETE"));

        verify(logMapper, never()).selectById(any());
    }

    @Test
    void onlyTeamLeaderCanRequestCandidateRecommendations() {
        TeamDemand team = new TeamDemand();
        team.setId(5L);
        team.setCreatorId(9L);
        when(teamMapper.selectById(5L)).thenReturn(team);

        assertThrows(AccessDeniedException.class,
            () -> service.getRecommendedUsers(7L, 5L, 6));
    }

    private RecommendationLog recommendation(Long id, Long userId) {
        RecommendationLog recommendation = new RecommendationLog();
        recommendation.setId(id);
        recommendation.setUserId(userId);
        return recommendation;
    }
}
