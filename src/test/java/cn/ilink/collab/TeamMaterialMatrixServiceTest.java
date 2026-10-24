package cn.ilink.collab;

import cn.ilink.entity.Competition;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamMaterialEvidence;
import cn.ilink.entity.TeamMaterialRequirement;
import cn.ilink.mapper.CompetitionMapper;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabDocumentVersionMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetVersionMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.mapper.TeamMaterialEvidenceMapper;
import cn.ilink.mapper.TeamMaterialRequirementMapper;
import cn.ilink.mapper.TeamMaterialReviewMapper;
import cn.ilink.mapper.TeamTaskMapper;
import cn.ilink.mapper.KgDocumentChunkMapper;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamMaterialMatrixServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
            new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TeamMaterialRequirement.class);
        TableInfoHelper.initTableInfo(assistant, TeamMaterialEvidence.class);
    }

    @Test
    void matrixUsesCatalogCompetitionNameInsteadOfTeamTitle() {
        TeamMaterialRequirementMapper requirements = mock(TeamMaterialRequirementMapper.class);
        CompetitionMapper competitions = mock(CompetitionMapper.class);
        TeamCollaborationAuthorization authorization = mock(TeamCollaborationAuthorization.class);
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setTitle("团队项目标题");
        team.setCompetitionId(4);
        when(authorization.requireTeam(20L, 7L)).thenReturn(
            new TeamCollaborationAuthorization.TeamContext(team, 7L,
                TeamCollaborationAuthorization.TeamRole.LEADER, true, true));
        when(requirements.selectList(any())).thenReturn(List.of());
        Competition competition = new Competition();
        competition.setId(4L);
        competition.setName("全国大学生嵌入式竞赛");
        when(competitions.selectById(4)).thenReturn(competition);
        TeamMaterialMatrixService service = new TeamMaterialMatrixService(
            requirements,
            mock(TeamMaterialEvidenceMapper.class),
            mock(TeamMaterialReviewMapper.class),
            mock(TeamLibraryFileMapper.class),
            mock(TeamCollabDocumentMapper.class),
            mock(TeamCollabDocumentVersionMapper.class),
            mock(TeamCollabSpreadsheetMapper.class),
            mock(TeamCollabSpreadsheetVersionMapper.class),
            mock(TeamTaskMapper.class),
            mock(KgDocumentChunkMapper.class),
            competitions,
            authorization,
            mock(TeamMaterialEvidenceValidator.class),
            mock(UserService.class));

        TeamMaterialMatrixService.MatrixView matrix = service.matrix(20L, 7L,
            null, null, null);

        assertEquals("全国大学生嵌入式竞赛", matrix.competition().get("catalogName"));
        assertEquals("团队项目标题", team.getTitle());
    }

    @Test
    void ordinaryWritableMemberCanRequestReviewButCannotRecordTheVerdict() {
        Fixture fixture = fixture(TeamCollaborationAuthorization.TeamRole.STUDENT, true);
        when(fixture.requirements.selectList(any())).thenReturn(List.of(requirement()));
        when(fixture.evidence.selectList(any())).thenReturn(List.of());

        TeamMaterialMatrixService.RequirementView row =
            fixture.service.matrix(20L, 7L, null, null, null).requirements().get(0);

        assertTrue(row.canReview());
        assertFalse(row.canMaintain());
    }

    @Test
    void readOnlyTeamCannotMaintainOrRequestReview() {
        Fixture fixture = fixture(TeamCollaborationAuthorization.TeamRole.LEADER, false);
        when(fixture.requirements.selectList(any())).thenReturn(List.of(requirement()));
        when(fixture.evidence.selectList(any())).thenReturn(List.of());

        TeamMaterialMatrixService.RequirementView row =
            fixture.service.matrix(20L, 7L, null, null, null).requirements().get(0);

        assertFalse(row.canReview());
        assertFalse(row.canMaintain());
    }

    @Test
    void requirementCreatorCanRecordTheFinalVerdict() {
        Fixture fixture = fixture(TeamCollaborationAuthorization.TeamRole.STUDENT, true);
        TeamMaterialRequirement row = requirement();
        row.setCreatedBy(7L);
        when(fixture.requirements.selectByIdForUpdate(5L)).thenReturn(row);
        when(fixture.requirements.selectById(5L)).thenReturn(row);
        when(fixture.evidence.selectCount(any())).thenReturn(1L);
        when(fixture.evidence.selectList(any())).thenReturn(List.of());

        fixture.service.review(20L, 5L, 7L,
            new TeamMaterialMatrixService.ReviewRequest("MET", "材料已核对"));
    }

    @Test
    void ordinaryMemberCannotRecordAFinalVerdictForSomeoneElsesRequirement() {
        Fixture fixture = fixture(TeamCollaborationAuthorization.TeamRole.STUDENT, true);
        TeamMaterialRequirement row = requirement();
        row.setCreatedBy(8L);
        when(fixture.requirements.selectByIdForUpdate(5L)).thenReturn(row);
        when(fixture.evidence.selectCount(any())).thenReturn(1L);

        TeamCollabException error = org.junit.jupiter.api.Assertions.assertThrows(
            TeamCollabException.class,
            () -> fixture.service.review(20L, 5L, 7L,
                new TeamMaterialMatrixService.ReviewRequest("MET", "越权结论")));
        assertEquals(TeamCollabException.Code.ASSET_FORBIDDEN, error.code());
    }

    private Fixture fixture(TeamCollaborationAuthorization.TeamRole role, boolean canWrite) {
        TeamMaterialRequirementMapper requirements = mock(TeamMaterialRequirementMapper.class);
        TeamMaterialEvidenceMapper evidence = mock(TeamMaterialEvidenceMapper.class);
        TeamCollaborationAuthorization authorization = mock(TeamCollaborationAuthorization.class);
        TeamDemand team = new TeamDemand();
        team.setId(20L);
        team.setStatus(canWrite ? "TEAMING" : "CLOSED");
        TeamCollaborationAuthorization.TeamContext context =
            new TeamCollaborationAuthorization.TeamContext(team, 7L, role, canWrite, true);
        when(authorization.requireTeam(20L, 7L)).thenReturn(context);
        when(authorization.requireWritableTeam(20L, 7L)).thenReturn(context);
        TeamMaterialMatrixService service = new TeamMaterialMatrixService(
            requirements,
            evidence,
            mock(TeamMaterialReviewMapper.class),
            mock(TeamLibraryFileMapper.class),
            mock(TeamCollabDocumentMapper.class),
            mock(TeamCollabDocumentVersionMapper.class),
            mock(TeamCollabSpreadsheetMapper.class),
            mock(TeamCollabSpreadsheetVersionMapper.class),
            mock(TeamTaskMapper.class),
            mock(KgDocumentChunkMapper.class),
            mock(CompetitionMapper.class),
            authorization,
            mock(TeamMaterialEvidenceValidator.class),
            mock(UserService.class));
        return new Fixture(service, requirements, evidence);
    }

    private TeamMaterialRequirement requirement() {
        TeamMaterialRequirement row = new TeamMaterialRequirement();
        row.setId(5L);
        row.setTeamId(20L);
        row.setTitle("报名表");
        row.setRequired(true);
        row.setSourceType("MANUAL");
        row.setStatus("ACTIVE");
        row.setReviewStatus(TeamMaterialRequirement.REVIEW_UNCHECKED);
        row.setReviewVersion(0);
        row.setVersion(0);
        row.setCreatedBy(8L);
        return row;
    }

    private record Fixture(TeamMaterialMatrixService service,
                           TeamMaterialRequirementMapper requirements,
                           TeamMaterialEvidenceMapper evidence) {
    }
}
