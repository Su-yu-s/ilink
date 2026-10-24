package cn.ilink.collab;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamCollabAcl;
import cn.ilink.entity.TeamDemand;
import cn.ilink.mapper.TeamApplicationMapper;
import cn.ilink.mapper.TeamCollabAclMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamDemandMapper;
import cn.ilink.service.TeamMembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The authorization boundary has to rebuild every role and visibility fact from
 * durable rows. These cases pin the three membership shapes that a forged request
 * body could otherwise pretend to be: a stranger, a pending applicant and a
 * member who has been removed from the team.
 */
class TeamCollaborationAuthorizationMatrixTest {
    private TeamDemandMapper teamMapper;
    private TeamApplicationMapper applicationMapper;
    private TeamCollabAclMapper aclMapper;
    private TeamCollaborationAuthorization authorization;

    @BeforeEach
    void setUp() {
        teamMapper = mock(TeamDemandMapper.class);
        applicationMapper = mock(TeamApplicationMapper.class);
        aclMapper = mock(TeamCollabAclMapper.class);
        when(aclMapper.selectList(any())).thenReturn(List.of());
        authorization = new TeamCollaborationAuthorization(
            teamMapper,
            applicationMapper,
            mock(TeamCollabDocumentMapper.class),
            mock(TeamCollabSpreadsheetMapper.class),
            aclMapper);
    }

    @Test
    void aStrangerWithNoApplicationIsRejected() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(null);

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> authorization.requireTeam(20L, 7L));

        assertEquals(TeamCollabException.Code.ASSET_FORBIDDEN, error.code());
    }

    @Test
    void aPendingApplicantIsNotAMemberYet() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(null);

        assertThrows(TeamCollabException.class,
            () -> authorization.requireWritableTeam(20L, 7L));
    }

    @Test
    void aRemovedMemberLosesAccessEvenThoughTheRowStillExists() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(null);

        assertFalse(authorization.canViewAsset("DOCUMENT", 9L, 20L, 7L));
        assertFalse(authorization.canViewTeamFile(51L, 20L, 7L));
    }

    @Test
    void onlyAnApprovedMembershipRowGrantsTheStudentRole() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(membership("APPROVED", "STUDENT"));

        TeamCollaborationAuthorization.TeamContext context =
            authorization.requireTeam(20L, 7L);

        assertEquals(TeamCollaborationAuthorization.TeamRole.STUDENT, context.role());
        assertTrue(context.canWrite());
        assertTrue(context.canExport());
    }

    @Test
    void anApprovedMentorRowGrantsTheMentorRole() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 99L));
        when(applicationMapper.selectOne(any()))
            .thenReturn(membership("APPROVED", TeamMembershipService.ROLE_MENTOR));

        TeamCollaborationAuthorization.TeamContext context =
            authorization.requireTeam(20L, 7L);

        assertEquals(TeamCollaborationAuthorization.TeamRole.MENTOR, context.role());
        assertTrue(context.isMentor());
    }

    @Test
    void theCreatorKeepsTheLeaderRoleWithoutAnApplicationRow() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 7L));

        TeamCollaborationAuthorization.TeamContext context =
            authorization.requireTeam(20L, 7L);

        assertEquals(TeamCollaborationAuthorization.TeamRole.LEADER, context.role());
        assertTrue(context.isLeader());
    }

    @Test
    void aDissolvedTeamIsReportedAsNotFoundForEveryRole() {
        when(teamMapper.selectById(20L)).thenReturn(team("DISSOLVED", 7L));

        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> authorization.requireTeam(20L, 7L));

        assertEquals(TeamCollabException.Code.TEAM_NOT_FOUND, error.code());
    }

    @Test
    void anOpenTeamIsReadableButNotWritable() {
        when(teamMapper.selectById(20L)).thenReturn(team("OPEN", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(membership("APPROVED", "STUDENT"));

        TeamCollaborationAuthorization.TeamContext context =
            authorization.requireTeam(20L, 7L);

        assertFalse(context.canWrite());
        assertTrue(context.canExport());
        TeamCollabException error = assertThrows(TeamCollabException.class,
            () -> authorization.requireWritableTeam(20L, 7L));
        assertEquals(TeamCollabException.Code.TEAM_READ_ONLY, error.code());
    }

    @Test
    void aClosedTeamKeepsExportButRejectsWrites() {
        when(teamMapper.selectById(20L)).thenReturn(team("CLOSED", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(membership("APPROVED", "STUDENT"));

        TeamCollaborationAuthorization.TeamContext context =
            authorization.requireTeam(20L, 7L);

        assertFalse(context.canWrite());
        assertTrue(context.canExport());
    }

    @Test
    void aRestrictedAssetWithoutAnExplicitGrantHasNoPermission() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(membership("APPROVED", "STUDENT"));
        TeamCollaborationAuthorization.TeamContext context =
            authorization.requireTeam(20L, 7L);

        TeamCollaborationAuthorization.Acl acl = authorization.acl(
            context, "DOCUMENT", 9L, "RESTRICTED", 99L);

        assertEquals(null, acl.permission());
        assertFalse(acl.canManage());
    }

    @Test
    void anExplicitGrantOnARestrictedAssetIsHonoured() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(membership("APPROVED", "STUDENT"));
        TeamCollabAcl grant = new TeamCollabAcl();
        grant.setPermission("VIEW");
        when(aclMapper.selectList(any())).thenReturn(List.of(grant));
        TeamCollaborationAuthorization.TeamContext context =
            authorization.requireTeam(20L, 7L);

        TeamCollaborationAuthorization.Acl acl = authorization.acl(
            context, "DOCUMENT", 9L, "RESTRICTED", 99L);

        assertEquals(TeamCollaborationAuthorization.Permission.VIEW, acl.permission());
        assertFalse(acl.canManage());
    }

    @Test
    void aTeamVisibleAssetIsEditableByAnyMemberWhileTheTeamIsWritable() {
        when(teamMapper.selectById(20L)).thenReturn(team("TEAMING", 99L));
        when(applicationMapper.selectOne(any())).thenReturn(membership("APPROVED", "STUDENT"));
        TeamCollaborationAuthorization.TeamContext context =
            authorization.requireTeam(20L, 7L);

        TeamCollaborationAuthorization.Acl acl = authorization.acl(
            context, "DOCUMENT", 9L, "TEAM", 99L);

        assertEquals(TeamCollaborationAuthorization.Permission.EDIT, acl.permission());
        assertFalse(acl.canManage());
    }

    private TeamDemand team(String status, Long creatorId) {
        TeamDemand row = new TeamDemand();
        row.setId(20L);
        row.setTitle("系统队");
        row.setStatus(status);
        row.setCreatorId(creatorId);
        return row;
    }

    private TeamApplication membership(String status, String role) {
        TeamApplication row = new TeamApplication();
        row.setTeamId(20L);
        row.setUserId(7L);
        row.setStatus(status);
        row.setMemberRole(role);
        return row;
    }
}
