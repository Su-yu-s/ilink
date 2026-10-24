package cn.ilink.collab;

import cn.ilink.entity.KgEntity;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.service.FileService;
import cn.ilink.service.kg.KgIndexService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

/** Bridges team library files into the existing revisioned KG queue. */
@Service
@Slf4j
public class TeamCollaborationIndexBridge {
    private final TeamLibraryFileMapper libraryMapper;
    private final FileService fileService;
    private final KgIndexService indexService;
    private final TeamFileTextExtractor extractor;

    public TeamCollaborationIndexBridge(TeamLibraryFileMapper libraryMapper,
                                        FileService fileService,
                                        KgIndexService indexService,
                                        TeamFileTextExtractor extractor) {
        this.libraryMapper = libraryMapper;
        this.fileService = fileService;
        this.indexService = indexService;
        this.extractor = extractor;
    }

    /** Queue a new revision after upload, rename, move or restore. */
    @Transactional(rollbackFor = Exception.class)
    public void enqueueUpsert(TeamLibraryFile file) {
        if (file == null || file.getId() == null || Boolean.TRUE.equals(file.getIsFolder())
            || Boolean.TRUE.equals(file.getIsDeleted()) || Boolean.TRUE.equals(file.getIsPurged())
            || file.getFileUrl() == null) {
            return;
        }
        long revision = (file.getContentRevision() == null ? 0L : file.getContentRevision()) + 1L;
        libraryMapper.update(null, new LambdaUpdateWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getId, file.getId())
            .set(TeamLibraryFile::getContentRevision, revision)
            .set(TeamLibraryFile::getIndexStatus, TeamLibraryFile.INDEX_PENDING)
            .set(TeamLibraryFile::getIndexError, null)
            .set(TeamLibraryFile::getIndexedAt, null));
        file.setContentRevision(revision);
        file.setIndexStatus(TeamLibraryFile.INDEX_PENDING);
        file.setIndexError(null);
        file.setIndexedAt(null);
        String title = file.getName();
        String marker = "[team-library-file:" + file.getId() + ":"
            + revision + "]";
        indexService.enqueueUpsert("TEAM_FILE", file.getId(), title, marker,
            KgEntity.SCOPE_TEAM, file.getTeamId(), file.getUploaderId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void enqueueDelete(TeamLibraryFile file) {
        if (file == null || file.getId() == null || Boolean.TRUE.equals(file.getIsFolder())) {
            return;
        }
        libraryMapper.update(null, new LambdaUpdateWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getId, file.getId())
            .set(TeamLibraryFile::getIndexStatus, TeamLibraryFile.INDEX_STALE)
            .set(TeamLibraryFile::getIndexError, null));
        indexService.enqueueDelete("TEAM_FILE", file.getId(), KgEntity.SCOPE_TEAM,
            file.getTeamId(), file.getUploaderId());
    }

    /** Worker entry: verifies current revision and source state then parses text. */
    public ExtractWorkerResult extractForWorker(Long fileId, Long expectedRevision, Long teamId,
                                                String leaseToken) {
        TeamLibraryFile file = libraryMapper.selectById(fileId);
        if (file == null || !java.util.Objects.equals(file.getTeamId(), teamId)
            || Boolean.TRUE.equals(file.getIsDeleted()) || Boolean.TRUE.equals(file.getIsPurged())
            || Boolean.TRUE.equals(file.getIsFolder())) {
            return ExtractWorkerResult.deleted();
        }
        if (expectedRevision != null && file.getContentRevision() != null
            && !expectedRevision.equals(file.getContentRevision())) {
            return ExtractWorkerResult.stale();
        }
        Path path;
        try {
            path = fileService.resolveStoredPath(file.getFileUrl());
            if (!Files.isRegularFile(path)) {
                return ExtractWorkerResult.failed("FILE_MISSING", "文件已不存在");
            }
        } catch (Exception e) {
            return ExtractWorkerResult.failed("FILE_PATH_INVALID", "文件路径无效");
        }
        TeamFileTextExtractor.ExtractResult result =
            extractor.extract(path, file.getOriginalFileName() == null
                ? file.getName() : file.getOriginalFileName());
        String content = result.content();
        if ("READY".equals(result.status())) {
            content = file.getName() + "\n"
                + (file.getOriginalFileName() == null ? "" : file.getOriginalFileName() + "\n")
                + content;
        }
        return new ExtractWorkerResult(result.status(), content, result.errorCode(),
            result.safeMessage(), file);
    }

    public void markIndexing(TeamLibraryFile file) {
        libraryMapper.update(null, new LambdaUpdateWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getId, file.getId())
            .set(TeamLibraryFile::getIndexStatus, TeamLibraryFile.INDEX_INDEXING)
            .set(TeamLibraryFile::getIndexError, null));
    }

    public void markReady(TeamLibraryFile file) {
        libraryMapper.update(null, new LambdaUpdateWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getId, file.getId())
            .eq(TeamLibraryFile::getContentRevision, file.getContentRevision())
            .set(TeamLibraryFile::getIndexStatus, TeamLibraryFile.INDEX_READY)
            .set(TeamLibraryFile::getIndexError, null)
            .set(TeamLibraryFile::getIndexedAt, new Date()));
    }

    public void markSkipped(TeamLibraryFile file, String code, String message) {
        libraryMapper.update(null, new LambdaUpdateWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getId, file.getId())
            .eq(TeamLibraryFile::getContentRevision, file.getContentRevision())
            .set(TeamLibraryFile::getIndexStatus, TeamLibraryFile.INDEX_SKIPPED)
            .set(TeamLibraryFile::getIndexError, safe(code, message))
            .set(TeamLibraryFile::getIndexedAt, new Date()));
    }

    public void markFailed(TeamLibraryFile file, String code, String message) {
        libraryMapper.update(null, new LambdaUpdateWrapper<TeamLibraryFile>()
            .eq(TeamLibraryFile::getId, file.getId())
            .eq(TeamLibraryFile::getContentRevision, file.getContentRevision())
            .set(TeamLibraryFile::getIndexStatus, TeamLibraryFile.INDEX_FAILED)
            .set(TeamLibraryFile::getIndexError, safe(code, message)));
    }

    private String safe(String code, String message) {
        String value = message == null ? code : message;
        value = value.replaceAll("[A-Za-z]:\\\\[^\\s]+", "[path]")
            .replaceAll("(?i)(password|secret|token)=[^&\\s]+", "$1=[redacted]");
        String full = (code == null ? "" : code + ": ") + value;
        return full.substring(0, Math.min(500, full.length()));
    }

    public record ExtractWorkerResult(String status, String content, String errorCode,
                                      String safeMessage, TeamLibraryFile file) {
        static ExtractWorkerResult deleted() {
            return new ExtractWorkerResult("DELETED", null, "SOURCE_DELETED",
                "资料已删除", null);
        }

        static ExtractWorkerResult stale() {
            return new ExtractWorkerResult("STALE", null, "SOURCE_STALE",
                "资料已有新修订", null);
        }

        static ExtractWorkerResult failed(String code, String message) {
            return new ExtractWorkerResult("FAILED", null, code, message, null);
        }
    }
}
