package cn.ilink.service.kg;

import cn.ilink.entity.KgDocumentChunk;
import cn.ilink.entity.KgEntity;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamTask;
import cn.ilink.collab.TeamCollaborationAuthorization;
import cn.ilink.mapper.KgDocEntityMapper;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.mapper.TeamAgentSourceAuthorizationMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.service.TeamAccessService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import cn.ilink.teamagent.TeamAgentScope;
import cn.ilink.teamagent.TeamAgentScopeContext;
import cn.ilink.teamagent.TeamTaskPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamScopedKgScopeServiceTest {
    private static final long TEAM_ID = 20L;
    private static final long OTHER_TEAM_ID = 99L;
    private static final long USER_ID = 7L;

    private KgDocEntityMapper docEntityMapper;
    private TeamTaskMapper taskMapper;
    private TeamDemandMapper teamMapper;
    private TeamTaskPolicy taskPolicy;
    private TeamAgentSourceAuthorizationMapper sourceAuthorizationMapper;
    private KgDocumentChunkMapper chunkMapper;
    private KgScopeService service;

    @BeforeEach
    void setUp() {
        docEntityMapper = mock(KgDocEntityMapper.class);
        taskMapper = mock(TeamTaskMapper.class);
        teamMapper = mock(TeamDemandMapper.class);
        taskPolicy = mock(TeamTaskPolicy.class);
        sourceAuthorizationMapper = mock(TeamAgentSourceAuthorizationMapper.class);
        chunkMapper = mock(KgDocumentChunkMapper.class);
        service = new KgScopeService(
            mock(TeamAccessService.class),
            mock(TeamDemandServiceImpl.class),
            mock(TeamApplicationServiceImpl.class),
            docEntityMapper,
            taskMapper,
            teamMapper,
            taskPolicy,
            sourceAuthorizationMapper,
            chunkMapper);
    }

    @Test
    void boundScopeAllowsGlobalAndCurrentTeamOnlyAndAlwaysExcludesPrivate() {
        try (TeamAgentScopeContext.BoundScope ignored =
                 TeamAgentScopeContext.bind(customScope())) {
            assertTrue(service.canSee(KgEntity.SCOPE_GLOBAL, null, null, USER_ID));
            assertTrue(service.canSee(KgEntity.SCOPE_TEAM, TEAM_ID, null, USER_ID));
            assertFalse(service.canSee(KgEntity.SCOPE_TEAM, OTHER_TEAM_ID, null, USER_ID));
            assertFalse(service.canSee(KgEntity.SCOPE_PRIVATE, null, USER_ID, USER_ID));
            assertFalse(service.canSee(KgEntity.SCOPE_GLOBAL, null, null, 8L));
        }
    }

    @Test
    void taskDocumentUsesObjectLevelPolicyForTheCurrentTeam() {
        TeamAgentScope scope = customScope();
        TeamDemand team = new TeamDemand();
        team.setId(TEAM_ID);
        team.setStatus("TEAMING");
        TeamTask hidden = task(71L, TEAM_ID);
        TeamTask visible = task(72L, TEAM_ID);
        when(teamMapper.selectById(TEAM_ID)).thenReturn(team);
        when(taskMapper.selectById(71L)).thenReturn(hidden);
        when(taskMapper.selectById(72L)).thenReturn(visible);
        when(taskPolicy.canReadTask(any(), any(), any())).thenAnswer(invocation ->
            ((TeamTask) invocation.getArgument(1)).getId().equals(72L));

        assertFalse(service.canSeeDocument("TASK", 71L, scope));
        assertTrue(service.canSeeDocument("TASK", 72L, scope));
    }

    @Test
    void taskFromAnotherTeamCannotEnterTheCurrentTeamScope() {
        TeamTask foreign = task(73L, OTHER_TEAM_ID);
        when(taskMapper.selectById(73L)).thenReturn(foreign);

        assertFalse(service.canSeeDocument("TASK", 73L, customScope()));
        verify(taskPolicy, never()).canReadTask(any(), any(), any());
    }

    @Test
    void customCompetitionNeverBorrowsCatalogLinkedGlobalSources() {
        TeamAgentScope custom = customScope();
        when(chunkMapper.selectOne(any())).thenReturn(null);

        assertFalse(service.canSeeDocument("ARTICLE", 6L, custom));
        verify(sourceAuthorizationMapper, never())
            .countCompetitionLinkedSource(any(), anyLong(), anyLong());
    }

    @Test
    void catalogScopeStillRequiresAnExplicitCompetitionLinkedSource() {
        TeamAgentScope catalog = new TeamAgentScope(
            USER_ID, TEAM_ID, "星火队", "TEAMING",
            TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.RELATION_ONLY,
            14, "全国大学生算法竞赛", null, null, Instant.now());
        when(chunkMapper.selectOne(any())).thenReturn(null);
        when(sourceAuthorizationMapper.countCompetitionLinkedSource("ARTICLE", 6L, 14L))
            .thenReturn(1L);

        assertTrue(service.canSeeDocument("ARTICLE", 6L, catalog));
    }

    @Test
    void teamPrivateSourceMustBelongToTheBoundTeam() {
        TeamAgentScope scope = customScope();
        KgDocumentChunk current = chunk("TEAM", TEAM_ID);
        KgDocumentChunk foreign = chunk("TEAM", OTHER_TEAM_ID);
        KgDocumentChunk privateRow = chunk("PRIVATE", TEAM_ID);

        when(chunkMapper.selectOne(any()))
            .thenReturn(current, foreign, privateRow);

        assertTrue(service.canSeeDocument("ARTICLE", 1L, scope));
        assertFalse(service.canSeeDocument("ARTICLE", 2L, scope));
        assertFalse(service.canSeeDocument("ARTICLE", 3L, scope));
    }

    @Test
    void restrictedDocumentWithoutExplicitGrantIsHiddenFromAgent() {
        TeamCollaborationAuthorization authorization = mock(TeamCollaborationAuthorization.class);
        ReflectionTestUtils.setField(service, "collaborationAuthorization", provider(authorization));
        when(authorization.canViewAsset("DOCUMENT", 1L, TEAM_ID, USER_ID)).thenReturn(false);
        when(authorization.canViewAsset("DOCUMENT", 2L, TEAM_ID, USER_ID)).thenReturn(true);

        assertFalse(service.canSeeDocument("TEAM_DOCUMENT", 1L, customScope()));
        assertTrue(service.canSeeDocument("TEAM_DOCUMENT", 2L, customScope()));
        assertFalse(service.canSeeDocument("TEAM_DOCUMENT", 1L, otherTeamScope()));
    }

    @Test
    void restrictedSpreadsheetRequiresExplicitViewGrant() {
        TeamCollaborationAuthorization authorization = mock(TeamCollaborationAuthorization.class);
        ReflectionTestUtils.setField(service, "collaborationAuthorization", provider(authorization));
        when(authorization.canViewAsset("SPREADSHEET", 3L, TEAM_ID, USER_ID)).thenReturn(false);
        when(authorization.canViewAsset("SPREADSHEET", 4L, TEAM_ID, USER_ID)).thenReturn(true);

        assertFalse(service.canSeeDocument("TEAM_SPREADSHEET", 3L, customScope()));
        assertTrue(service.canSeeDocument("TEAM_SPREADSHEET", 4L, customScope()));
        assertFalse(service.canSeeDocument("TEAM_SPREADSHEET", 3L, otherTeamScope()));
    }

    private TeamAgentScope customScope() {
        return new TeamAgentScope(
            USER_ID, TEAM_ID, "星火队", "TEAMING",
            TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.RELATION_ONLY,
            null, null, "自定义算法赛", null, Instant.now());
    }

    private TeamAgentScope otherTeamScope() {
        return new TeamAgentScope(
            USER_ID, OTHER_TEAM_ID, "其他队", "TEAMING",
            TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.RELATION_ONLY,
            null, null, "自定义算法赛", null, Instant.now());
    }

    private static ObjectProvider<TeamCollaborationAuthorization> provider(
        TeamCollaborationAuthorization authorization) {
        return new ObjectProvider<>() {
            @Override
            public TeamCollaborationAuthorization getObject() {
                return authorization;
            }

            @Override
            public TeamCollaborationAuthorization getObject(Object... args) {
                return authorization;
            }

            @Override
            public TeamCollaborationAuthorization getIfAvailable() {
                return authorization;
            }

            @Override
            public TeamCollaborationAuthorization getIfUnique() {
                return authorization;
            }
        };
    }

    private TeamTask task(long id, long teamId) {
        TeamTask task = new TeamTask();
        task.setId(id);
        task.setTeamId(teamId);
        task.setAssignedTo(USER_ID);
        task.setCreatedBy(1L);
        task.setStatus("IN_PROGRESS");
        return task;
    }

    private KgDocumentChunk chunk(String scope, long teamId) {
        KgDocumentChunk chunk = new KgDocumentChunk();
        chunk.setScope(scope);
        chunk.setTeamId(teamId);
        return chunk;
    }
}
