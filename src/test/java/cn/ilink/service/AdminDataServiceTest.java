package cn.ilink.service;

import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.mapper.ChatMessageMapper;
import cn.ilink.mapper.CommunityCommentMapper;
import cn.ilink.mapper.CommunityPostFavoriteMapper;
import cn.ilink.mapper.CommunityPostLikeMapper;
import cn.ilink.mapper.NotificationMapper;
import cn.ilink.mapper.ProjectMilestoneMapper;
import cn.ilink.mapper.RecommendationLogMapper;
import cn.ilink.mapper.TaskCommentMapper;
import cn.ilink.mapper.TaskParticipantMapper;
import cn.ilink.mapper.TaskSubmissionMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.mapper.UserSkillMapper;
import cn.ilink.service.impl.AssetServiceImpl;
import cn.ilink.service.impl.CommunityPostServiceImpl;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminDataServiceTest {

    private UserService userService;
    private TeamDemandServiceImpl teamDemandService;
    private TeamApplicationServiceImpl teamApplicationService;
    private TeacherApplicationServiceImpl teacherApplicationService;
    private ProjectApplicationServiceImpl projectApplicationService;
    private AssetServiceImpl assetService;
    private AssetLifecycleService assetLifecycleService;
    private CommunityPostServiceImpl communityPostService;
    private CommunityCommentMapper communityCommentMapper;
    private CommunityPostLikeMapper communityPostLikeMapper;
    private CommunityPostFavoriteMapper communityPostFavoriteMapper;
    private TeamTaskMapper teamTaskMapper;
    private TaskParticipantMapper taskParticipantMapper;
    private TaskCommentMapper taskCommentMapper;
    private TaskSubmissionMapper taskSubmissionMapper;
    private ProjectMilestoneMapper projectMilestoneMapper;
    private ChatMessageMapper chatMessageMapper;
    private RecommendationLogMapper recommendationLogMapper;
    private NotificationMapper notificationMapper;
    private UserSkillMapper userSkillMapper;
    private AdminDataService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        teamDemandService = mock(TeamDemandServiceImpl.class);
        teamApplicationService = mock(TeamApplicationServiceImpl.class);
        teacherApplicationService = mock(TeacherApplicationServiceImpl.class);
        projectApplicationService = mock(ProjectApplicationServiceImpl.class);
        assetService = mock(AssetServiceImpl.class);
        assetLifecycleService = mock(AssetLifecycleService.class);
        communityPostService = mock(CommunityPostServiceImpl.class);
        communityCommentMapper = mock(CommunityCommentMapper.class);
        communityPostLikeMapper = mock(CommunityPostLikeMapper.class);
        communityPostFavoriteMapper = mock(CommunityPostFavoriteMapper.class);
        teamTaskMapper = mock(TeamTaskMapper.class);
        taskParticipantMapper = mock(TaskParticipantMapper.class);
        taskCommentMapper = mock(TaskCommentMapper.class);
        taskSubmissionMapper = mock(TaskSubmissionMapper.class);
        projectMilestoneMapper = mock(ProjectMilestoneMapper.class);
        chatMessageMapper = mock(ChatMessageMapper.class);
        recommendationLogMapper = mock(RecommendationLogMapper.class);
        notificationMapper = mock(NotificationMapper.class);
        userSkillMapper = mock(UserSkillMapper.class);
        service = new AdminDataService(userService, teamDemandService, teamApplicationService,
            teacherApplicationService, projectApplicationService, assetService, assetLifecycleService,
            communityPostService, communityCommentMapper, communityPostLikeMapper,
            communityPostFavoriteMapper, teamTaskMapper, taskParticipantMapper, taskCommentMapper,
            taskSubmissionMapper, projectMilestoneMapper, chatMessageMapper, recommendationLogMapper,
            notificationMapper, userSkillMapper);
    }

    @Test
    void deletingTeamRemovesTaskChildrenAndTeamOwnedData() {
        TeamDemand team = new TeamDemand();
        team.setId(7L);
        TeamTask first = new TeamTask();
        first.setId(11L);
        TeamTask second = new TeamTask();
        second.setId(12L);
        when(teamDemandService.getById(7L)).thenReturn(team);
        when(teamTaskMapper.selectList(any())).thenReturn(List.of(first, second));
        when(teamDemandService.removeById(7L)).thenReturn(true);

        service.deleteTeam(7L);

        verify(taskSubmissionMapper).delete(any());
        verify(taskCommentMapper).delete(any());
        verify(taskParticipantMapper).delete(any());
        verify(teamTaskMapper).deleteBatchIds(argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(11L, 12L))));
        verify(projectMilestoneMapper).delete(any());
        verify(chatMessageMapper).delete(any());
        verify(teamApplicationService).remove(any(Wrapper.class));
        verify(recommendationLogMapper).delete(any());
        verify(notificationMapper).delete(any());
        verify(teamDemandService).removeById(7L);
    }

    @Test
    void deletingMissingTeamDoesNotTouchChildren() {
        when(teamDemandService.getById(404L)).thenReturn(null);

        assertThrows(NoSuchElementException.class, () -> service.deleteTeam(404L));

        verify(teamTaskMapper, never()).selectList(any());
        verify(teamDemandService, never()).removeById(404L);
    }

    @Test
    void deletingAssetUsesLifecycleServiceSoFileIsRemovedToo() {
        service.deleteAsset(21L);

        verify(assetLifecycleService).deleteAssetAsAdmin(21L);
    }
}
