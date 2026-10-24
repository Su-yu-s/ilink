package cn.ilink.collab;

import cn.ilink.entity.TeamCollabAcl;
import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabDocumentVersion;
import cn.ilink.entity.TeamCollabEditLease;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamCollabSpreadsheetVersion;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamMaterialEvidence;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamCollabAclMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.mapper.TeamCollabEditLeaseMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetVersionMapper;
import cn.ilink.mapper.TeamMaterialEvidenceMapper;
import cn.ilink.service.UserService;
import cn.ilink.service.kg.KgIndexService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamCollaborationTrashServiceTest {
    private static final Long TEAM_ID = 3L;
    private static final Long USER_ID = 7L;
    private static final Long DOCUMENT_ID = 11L;

    private TeamCollaborationAuthorization authorization;
    private TeamCollaborationEventService eventService;
    private KgIndexService indexService;
    private UserService userService;
    private TeamCollabDocumentMapper documentMapper;
    private TeamCollabSpreadsheetMapper spreadsheetMapper;
    private TeamCollabDocumentVersionMapper documentVersionMapper;
    private TeamCollabSpreadsheetVersionMapper spreadsheetVersionMapper;
    private TeamCollabAclMapper aclMapper;
    private TeamCollabEditLeaseMapper leaseMapper;
    private TeamMaterialEvidenceMapper evidenceMapper;
    private TeamCollaborationTrashService service;

    @BeforeAll
    static void initTableInfo() {
        init(TeamCollabDocument.class);
        init(TeamCollabSpreadsheet.class);
        init(TeamCollabDocumentVersion.class);
        init(TeamCollabSpreadsheetVersion.class);
        init(TeamCollabAcl.class);
        init(TeamCollabEditLease.class);
        init(TeamMaterialEvidence.class);
    }

    @BeforeEach
    void setUp() {
        authorization = mock(TeamCollaborationAuthorization.class);
        eventService = mock(TeamCollaborationEventService.class);
        indexService = mock(KgIndexService.class);
        userService = mock(UserService.class);
        documentMapper = mock(TeamCollabDocumentMapper.class);
        spreadsheetMapper = mock(TeamCollabSpreadsheetMapper.class);
        documentVersionMapper = mock(TeamCollabDocumentVersionMapper.class);
        spreadsheetVersionMapper = mock(TeamCollabSpreadsheetVersionMapper.class);
        aclMapper = mock(TeamCollabAclMapper.class);
        leaseMapper = mock(TeamCollabEditLeaseMapper.class);
        evidenceMapper = mock(TeamMaterialEvidenceMapper.class);
        service = new TeamCollaborationTrashService(authorization, eventService, indexService,
            userService, documentMapper, spreadsheetMapper, documentVersionMapper,
            spreadsheetVersionMapper, aclMapper, leaseMapper, evidenceMapper, new ObjectMapper());
    }

    @Test
    void creatorRestoresDocumentAsANewVersionAndRequeuesItsIndex() {
        TeamCollabDocument deleted = document(USER_ID,
            Date.from(Instant.now().minus(1, ChronoUnit.DAYS)), 4);
        TeamCollabDocument restored = document(USER_ID, null, 5);
        when(authorization.requireWritableTeam(TEAM_ID, USER_ID)).thenReturn(team("STUDENT"));
        when(authorization.assetType("DOCUMENT")).thenReturn("DOCUMENT");
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(deleted, restored);
        when(documentMapper.update(any(), any())).thenReturn(1);

        TeamCollaborationTrashService.TrashAsset result =
            service.restore(TEAM_ID, "DOCUMENT", DOCUMENT_ID, USER_ID, 4);

        assertEquals(DOCUMENT_ID, result.assetId());
        assertEquals(5, result.version());
        assertNull(result.deletedAt());
        verify(documentMapper).update(any(), any());
        verify(eventService).append(eq(TEAM_ID), eq("DOCUMENT"), eq(DOCUMENT_ID),
            eq(USER_ID), eq("DOCUMENT_RESTORED_FROM_TRASH"), eq("SUCCESS"), eq(5),
            eq(null), any());
        verify(indexService).enqueueUpsert(eq("TEAM_DOCUMENT"), eq(DOCUMENT_ID),
            eq(restored.getTitle()), any(String.class), eq(cn.ilink.entity.KgEntity.SCOPE_TEAM),
            eq(TEAM_ID), eq(USER_ID));
    }

    @Test
    void onlyLeaderOrCreatorCanRestoreANonExpiredAsset() {
        when(authorization.requireWritableTeam(TEAM_ID, 8L)).thenReturn(team("STUDENT", 8L));
        when(authorization.assetType("DOCUMENT")).thenReturn("DOCUMENT");
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(99L,
            Date.from(Instant.now().minus(1, ChronoUnit.DAYS)), 4));

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> service.restore(TEAM_ID, "DOCUMENT", DOCUMENT_ID, 8L, 4));

        assertEquals(TeamCollabException.Code.ASSET_FORBIDDEN, error.code());
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void expiredTrashCannotBeRestored() {
        when(authorization.requireWritableTeam(TEAM_ID, USER_ID)).thenReturn(team("STUDENT"));
        when(authorization.assetType("DOCUMENT")).thenReturn("DOCUMENT");
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document(USER_ID,
            Date.from(Instant.now().minus(31, ChronoUnit.DAYS)), 4));

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> service.restore(TEAM_ID, "DOCUMENT", DOCUMENT_ID, USER_ID, 4));

        assertEquals(TeamCollabException.Code.ASSET_NOT_FOUND, error.code());
    }

    @Test
    void permanentDeleteRemovesVersionsAclLeaseEvidenceAndIndex() {
        TeamCollabDocument deleted = document(USER_ID,
            Date.from(Instant.now().minus(2, ChronoUnit.DAYS)), 6);
        when(authorization.requireWritableTeam(TEAM_ID, USER_ID)).thenReturn(team("STUDENT"));
        when(authorization.assetType("DOCUMENT")).thenReturn("DOCUMENT");
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(deleted);
        when(documentMapper.delete(any())).thenReturn(1);

        boolean result = service.purge(TEAM_ID, "DOCUMENT", DOCUMENT_ID, USER_ID, 6);

        assertEquals(true, result);
        verify(documentVersionMapper).delete(any());
        verify(aclMapper).delete(any());
        verify(leaseMapper).delete(any());
        verify(evidenceMapper).delete(any());
        verify(indexService).enqueueDelete(eq("TEAM_DOCUMENT"), eq(DOCUMENT_ID),
            eq(cn.ilink.entity.KgEntity.SCOPE_TEAM), eq(TEAM_ID), eq(USER_ID));
        verify(eventService).append(eq(TEAM_ID), eq("DOCUMENT"), eq(DOCUMENT_ID),
            eq(USER_ID), eq("DOCUMENT_PURGED"), eq("SUCCESS"), eq(6), eq(null), any());
    }

    @Test
    void trashViewOnlyReturnsAssetsCreatedByTheCurrentNonLeader() {
        TeamCollabDocument mine = document(USER_ID,
            Date.from(Instant.now().minus(1, ChronoUnit.DAYS)), 2);
        TeamCollabDocument teammate = document(9L,
            Date.from(Instant.now().minus(1, ChronoUnit.DAYS)), 2);
        when(authorization.requireTeam(TEAM_ID, USER_ID)).thenReturn(team("STUDENT"));
        when(documentMapper.selectList(any())).thenReturn(List.of(mine, teammate));
        when(spreadsheetMapper.selectList(any())).thenReturn(List.of());
        when(userService.getById(USER_ID)).thenReturn(user(USER_ID, "我"));

        List<TeamCollaborationTrashService.TrashAsset> rows = service.list(TEAM_ID, USER_ID);

        assertEquals(1, rows.size());
        assertEquals(DOCUMENT_ID, rows.get(0).assetId());
    }

    private static TeamCollabDocument document(Long creator, Date deletedAt, int version) {
        TeamCollabDocument row = new TeamCollabDocument();
        row.setId(DOCUMENT_ID);
        row.setTeamId(TEAM_ID);
        row.setTitle("比赛方案");
        row.setContentMarkdown("正文");
        row.setVisibility(TeamCollabDocument.VISIBILITY_TEAM);
        row.setStatus(TeamCollabDocument.STATUS_ARCHIVED);
        row.setVersion(version);
        row.setCreatorUserId(creator);
        row.setLastEditorUserId(creator);
        row.setCreatedAt(new Date());
        row.setUpdatedAt(new Date());
        row.setDeletedAt(deletedAt);
        return row;
    }

    private static TeamCollaborationAuthorization.TeamContext team(String role) {
        return team(role, USER_ID);
    }

    private static TeamCollaborationAuthorization.TeamContext team(String role, Long userId) {
        TeamDemand team = new TeamDemand();
        team.setId(TEAM_ID);
        team.setStatus("TEAMING");
        return new TeamCollaborationAuthorization.TeamContext(team, userId,
            TeamCollaborationAuthorization.TeamRole.valueOf(role), true, true);
    }

    private static User user(Long id, String name) {
        User user = new User();
        user.setId(id);
        user.setRealName(name);
        return user;
    }

    private static void init(Class<?> entityType) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(
            new MybatisConfiguration(), entityType.getSimpleName()), entityType);
    }
}
