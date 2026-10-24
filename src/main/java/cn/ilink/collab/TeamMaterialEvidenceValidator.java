package cn.ilink.collab;

import cn.ilink.entity.TeamCollabDocument;
import cn.ilink.entity.TeamCollabSpreadsheet;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.entity.TeamTask;
import cn.ilink.mapper.TeamCollabDocumentMapper;
import cn.ilink.mapper.TeamCollabSpreadsheetMapper;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.mapper.TeamTaskMapper;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Validates that an evidence reference is currently readable in the team. */
@Service
public class TeamMaterialEvidenceValidator {
    private final TeamLibraryFileMapper libraryMapper;
    private final TeamCollabDocumentMapper documentMapper;
    private final TeamCollabSpreadsheetMapper spreadsheetMapper;
    private final TeamTaskMapper taskMapper;
    private final TeamCollaborationAuthorization authorization;

    public TeamMaterialEvidenceValidator(TeamLibraryFileMapper libraryMapper,
                                         TeamCollabDocumentMapper documentMapper,
                                         TeamCollabSpreadsheetMapper spreadsheetMapper,
                                         TeamTaskMapper taskMapper,
                                         TeamCollaborationAuthorization authorization) {
        this.libraryMapper = libraryMapper;
        this.documentMapper = documentMapper;
        this.spreadsheetMapper = spreadsheetMapper;
        this.taskMapper = taskMapper;
        this.authorization = authorization;
    }

    public TeamMaterialMatrixService.EvidenceSnapshot validate(Long teamId, Long userId,
                                                               String type, Long assetId,
                                                               Long chunkId) {
        TeamMaterialMatrixService.EvidenceSnapshot snapshot = describe(teamId, type, assetId);
        if (snapshot.deleted() || snapshot.label() == null) {
            throw new TeamCollabException(TeamCollabException.Code.MATERIAL_EVIDENCE_INVALID,
                "证据来源不存在或不属于当前团队");
        }
        if ("DOCUMENT".equals(type) || "SPREADSHEET".equals(type)) {
            authorization.requireAsset(teamId, type, assetId, userId,
                TeamCollaborationAuthorization.Permission.VIEW);
        }
        return snapshot;
    }

    public TeamMaterialMatrixService.EvidenceSnapshot describe(Long teamId, String type,
                                                               Long assetId) {
        if (teamId == null || assetId == null || type == null) {
            return new TeamMaterialMatrixService.EvidenceSnapshot(null, null, true, null);
        }
        return switch (type) {
            case "FILE" -> {
                TeamLibraryFile file = libraryMapper.selectById(assetId);
                if (file == null || !Objects.equals(file.getTeamId(), teamId)) {
                    yield new TeamMaterialMatrixService.EvidenceSnapshot(null, null, true, null);
                }
                yield new TeamMaterialMatrixService.EvidenceSnapshot(file.getName(), null,
                    Boolean.TRUE.equals(file.getIsDeleted())
                        || Boolean.TRUE.equals(file.getIsPurged()), file.getFileUrl());
            }
            case "DOCUMENT" -> {
                TeamCollabDocument doc = documentMapper.selectById(assetId);
                if (doc == null || !Objects.equals(doc.getTeamId(), teamId)) {
                    yield new TeamMaterialMatrixService.EvidenceSnapshot(null, null, true, null);
                }
                yield new TeamMaterialMatrixService.EvidenceSnapshot(doc.getTitle(),
                    doc.getVersion(), doc.getDeletedAt() != null, null);
            }
            case "SPREADSHEET" -> {
                TeamCollabSpreadsheet sheet = spreadsheetMapper.selectById(assetId);
                if (sheet == null || !Objects.equals(sheet.getTeamId(), teamId)) {
                    yield new TeamMaterialMatrixService.EvidenceSnapshot(null, null, true, null);
                }
                yield new TeamMaterialMatrixService.EvidenceSnapshot(sheet.getTitle(),
                    sheet.getVersion(), sheet.getDeletedAt() != null, null);
            }
            case "TASK" -> {
                TeamTask task = taskMapper.selectById(assetId);
                if (task == null || !Objects.equals(task.getTeamId(), teamId)) {
                    yield new TeamMaterialMatrixService.EvidenceSnapshot(null, null, true, null);
                }
                yield new TeamMaterialMatrixService.EvidenceSnapshot(task.getTaskTitle(),
                    task.getVersion(), false, null);
            }
            default -> new TeamMaterialMatrixService.EvidenceSnapshot(null, null, true, null);
        };
    }
}
