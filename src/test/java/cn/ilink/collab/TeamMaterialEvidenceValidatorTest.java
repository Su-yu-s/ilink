package cn.ilink.collab;

import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.entity.TeamTask;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.mapper.TeamTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 材料证据来源校验：来源必须存在、属于当前团队、尚未删除，文档/表格还必须
 * 通过可见范围与 ACL 读取授权。越权和已删除来源都不能被挂进材料矩阵。
 */
class TeamMaterialEvidenceValidatorTest {

    private final TeamLibraryFileMapper libraryMapper = mock(TeamLibraryFileMapper.class);
    private final TeamCollabDocumentMapper documentMapper = mock(TeamCollabDocumentMapper.class);
    private final TeamCollabSpreadsheetMapper spreadsheetMapper =
        mock(TeamCollabSpreadsheetMapper.class);
    private final TeamTaskMapper taskMapper = mock(TeamTaskMapper.class);
    private final TeamCollaborationAuthorization authorization =
        mock(TeamCollaborationAuthorization.class);
    private final TeamMaterialEvidenceValidator validator = new TeamMaterialEvidenceValidator(
        libraryMapper, documentMapper, spreadsheetMapper, taskMapper, authorization);

    private static TeamCollabException captureCode(Runnable call) {
        return assertThrows(TeamCollabException.class, call::run);
    }

    @Test
    void crossTeamFileIsRejectedBeforeItCanBeLinked() {
        TeamLibraryFile file = new TeamLibraryFile();
        file.setId(80L);
        file.setTeamId(99L);          // 别的团队
        file.setName("他人方案.pdf");
        file.setIsDeleted(false);
        when(libraryMapper.selectById(80L)).thenReturn(file);

        TeamCollabException error = captureCode(
            () -> validator.validate(20L, 7L, "FILE", 80L, null));

        assertEquals(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID, error.code());
        // 失败信息不得泄露来源名称
        assertTrue(!error.getMessage().contains("他人方案"), error.getMessage());
    }

    @Test
    void softDeletedAndPurgedLibraryFilesCannotBeEvidence() {
        TeamLibraryFile deleted = new TeamLibraryFile();
        deleted.setId(81L);
        deleted.setTeamId(20L);
        deleted.setName("报名表.pdf");
        deleted.setIsDeleted(true);
        when(libraryMapper.selectById(81L)).thenReturn(deleted);

        assertEquals(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
            captureCode(() -> validator.validate(20L, 7L, "FILE", 81L, null)).code());

        TeamLibraryFile purged = new TeamLibraryFile();
        purged.setId(82L);
        purged.setTeamId(20L);
        purged.setName("报名表.pdf");
        purged.setIsDeleted(false);
        purged.setIsPurged(true);
        when(libraryMapper.selectById(82L)).thenReturn(purged);

        assertEquals(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
            captureCode(() -> validator.validate(20L, 7L, "FILE", 82L, null)).code());
    }

    @Test
    void missingAssetAndUnknownTypeFailClosed() {
        when(documentMapper.selectById(90L)).thenReturn(null);
        assertEquals(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
            captureCode(() -> validator.validate(20L, 7L, "DOCUMENT", 90L, null)).code());

        // 未知类型必须当作不可用，而不是放行
        assertEquals(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
            captureCode(() -> validator.validate(20L, 7L, "PASSWORD", 1L, null)).code());
    }

    @Test
    void deletedDocumentIsRejectedButLiveDocumentNeedsReadAuthorization() {
        TeamCollabDocument gone = new TeamCollabDocument();
        gone.setId(91L);
        gone.setTeamId(20L);
        gone.setTitle("已删除文档");
        gone.setVersion(4);
        gone.setDeletedAt(new Date());
        when(documentMapper.selectById(91L)).thenReturn(gone);

        assertEquals(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
            captureCode(() -> validator.validate(20L, 7L, "DOCUMENT", 91L, null)).code());

        TeamCollabDocument live = new TeamCollabDocument();
        live.setId(92L);
        live.setTeamId(20L);
        live.setTitle("团队方案");
        live.setVersion(3);
        when(documentMapper.selectById(92L)).thenReturn(live);
        when(authorization.requireAsset(20L, "DOCUMENT", 92L, 7L,
            TeamCollaborationAuthorization.Permission.VIEW))
            .thenThrow(new TeamCollabException(
                TeamCollabException.Code.ASSET_FORBIDDEN, "无权读取该文档"));

        TeamCollabException error = captureCode(
            () -> validator.validate(20L, 7L, "DOCUMENT", 92L, null));
        assertEquals(TeamCollabException.Code.ASSET_FORBIDDEN, error.code());
    }

    @Test
    void restrictedSpreadsheetKeepsItsViewCheckAndReturnsItsVersion() {
        TeamCollabSpreadsheet sheet = new TeamCollabSpreadsheet();
        sheet.setId(93L);
        sheet.setTeamId(20L);
        sheet.setTitle("材料清单");
        sheet.setVersion(6);
        sheet.setDeletedAt(null);
        when(spreadsheetMapper.selectById(93L)).thenReturn(sheet);

        TeamMaterialMatrixService.EvidenceSnapshot snapshot =
            validator.validate(20L, 7L, "SPREADSHEET", 93L, null);

        assertEquals("材料清单", snapshot.label());
        assertEquals(6, snapshot.version());
        assertTrue(!snapshot.deleted());
        verify(authorization).requireAsset(20L, "SPREADSHEET", 93L, 7L,
            TeamCollaborationAuthorization.Permission.VIEW);
    }

    @Test
    void taskEvidenceNeedsNoAssetGrantButStillNeedsTeamOwnership() {
        TeamTask foreign = new TeamTask();
        foreign.setId(94L);
        foreign.setTeamId(99L);
        foreign.setTaskTitle("别人团队的任务");
        when(taskMapper.selectById(94L)).thenReturn(foreign);

        assertEquals(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
            captureCode(() -> validator.validate(20L, 7L, "TASK", 94L, null)).code());

        TeamTask own = new TeamTask();
        own.setId(95L);
        own.setTeamId(20L);
        own.setTaskTitle("提交报名表");
        own.setVersion(2);
        when(taskMapper.selectById(95L)).thenReturn(own);

        TeamMaterialMatrixService.EvidenceSnapshot snapshot =
            validator.validate(20L, 7L, "TASK", 95L, null);
        assertEquals("提交报名表", snapshot.label());
        // 任务不是协作资产，不应触发资产级授权检查
        verify(authorization, never()).requireAsset(20L, "TASK", 95L, 7L,
            TeamCollaborationAuthorization.Permission.VIEW);
    }

    @Test
    void nullCoordinatesDescribeAsDeletedWithoutTouchingTheDatabase() {
        TeamMaterialMatrixService.EvidenceSnapshot snapshot =
            validator.describe(20L, "DOCUMENT", null);
        assertTrue(snapshot.deleted());
        verify(documentMapper, never()).selectById(any());
    }
}
