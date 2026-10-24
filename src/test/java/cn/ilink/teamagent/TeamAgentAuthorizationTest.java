package cn.ilink.teamagent;

import cn.ilink.entity.Competition;
import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.CompetitionMapper;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamAgentAuthorizationTest {
    private static final long TEAM_ID = 20L;
    private static final long OWNER_ID = 1L;
    private static final long USER_ID = 7L;

    private TeamDemandMapper teamMapper;
    private TeamApplicationMapper applicationMapper;
    private CompetitionMapper competitionMapper;
    private TeamAgentAuthorization authorization;
    private TeamDemand team;

    @BeforeEach
    void setUp() {
        teamMapper = mock(TeamDemandMapper.class);
        applicationMapper = mock(TeamApplicationMapper.class);
        competitionMapper = mock(CompetitionMapper.class);
        authorization = new TeamAgentAuthorization(
            teamMapper, applicationMapper, competitionMapper, mock(UserService.class));
        team = new TeamDemand();
        team.setId(TEAM_ID);
        team.setTitle("星火队");
        team.setCreatorId(OWNER_ID);
        team.setStatus("TEAMING");
        team.setCustomCompetition("自定义算法赛");
        when(teamMapper.selectById(TEAM_ID)).thenReturn(team);
    }

    @Test
    void ownerGetsLeaderScopeAndCatalogCompetitionFromDurableRows() {
        team.setCompetitionId(14);
        team.setCustomCompetition(null);
        Competition competition = new Competition();
        competition.setId(14L);
        competition.setName("全国大学生算法竞赛");
        when(competitionMapper.selectById(14)).thenReturn(competition);

        TeamAgentScope scope = authorization.requireScope(TEAM_ID, OWNER_ID);

        assertEquals(TeamAgentScope.TeamRole.LEADER, scope.role());
        assertEquals(TeamAgentScope.TaskReadMode.ALL, scope.taskReadMode());
        assertEquals(14, scope.competitionId());
        assertEquals("全国大学生算法竞赛", scope.catalogCompetitionName());
        assertNull(scope.customCompetition());
        assertEquals(true, scope.actionsEnabled());
        assertEquals(false, scope.privateGraphAllowed());
    }

    @Test
    void approvedStudentGetsRelationOnlyScope() {
        TeamApplication student = application(USER_ID, "MEMBER");
        when(applicationMapper.selectOne(any())).thenReturn(student);

        TeamAgentScope scope = authorization.requireScope(TEAM_ID, USER_ID);

        assertEquals(TeamAgentScope.TeamRole.STUDENT, scope.role());
        assertEquals(TeamAgentScope.TaskReadMode.RELATION_ONLY, scope.taskReadMode());
        assertEquals("自定义算法赛", scope.customCompetition());
        assertNull(scope.competitionId());
        assertEquals(false, scope.actionsEnabled());
    }

    @Test
    void approvedMentorGetsReadAllButNoActionRights() {
        TeamApplication mentor = application(USER_ID, "MENTOR");
        when(applicationMapper.selectOne(any())).thenReturn(mentor);

        TeamAgentScope scope = authorization.requireScope(TEAM_ID, USER_ID);

        assertEquals(TeamAgentScope.TeamRole.MENTOR, scope.role());
        assertEquals(TeamAgentScope.TaskReadMode.ALL, scope.taskReadMode());
        assertEquals(false, scope.actionsEnabled());
    }

    @Test
    void nonMemberHasNoImplicitAdminOrTeamAccess() {
        when(applicationMapper.selectOne(any())).thenReturn(null);

        TeamAgentException error = assertThrows(TeamAgentException.class,
            () -> authorization.requireScope(TEAM_ID, USER_ID));

        assertEquals(TeamAgentErrorCode.TEAM_ACCESS_DENIED, error.getErrorCode());
    }

    @Test
    void dissolvedTeamAndMissingCatalogBindingFailClosed() {
        team.setStatus("DISSOLVED");
        assertEquals(TeamAgentErrorCode.TEAM_ACCESS_DENIED,
            assertThrows(TeamAgentException.class,
                () -> authorization.requireScope(TEAM_ID, OWNER_ID)).getErrorCode());

        team.setStatus("TEAMING");
        team.setCompetitionId(404);
        team.setCustomCompetition(null);
        when(competitionMapper.selectById(404)).thenReturn(null);
        assertEquals(TeamAgentErrorCode.TEAM_ACCESS_DENIED,
            assertThrows(TeamAgentException.class,
                () -> authorization.requireScope(TEAM_ID, OWNER_ID)).getErrorCode());
    }

    @Test
    void customCompetitionIsNeverGuessedAsACatalogCompetition() {
        TeamAgentScope scope = authorization.requireScope(TEAM_ID, OWNER_ID);

        assertNull(scope.competitionId());
        assertNull(scope.catalogCompetitionName());
        assertEquals("自定义算法赛", scope.customCompetition());
    }

    private TeamApplication application(long userId, String memberRole) {
        TeamApplication application = new TeamApplication();
        application.setTeamId(TEAM_ID);
        application.setUserId(userId);
        application.setStatus("APPROVED");
        application.setMemberRole(memberRole);
        return application;
    }
}
