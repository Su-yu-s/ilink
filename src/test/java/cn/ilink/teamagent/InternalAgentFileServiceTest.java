package cn.ilink.teamagent;

import cn.ilink.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** agent 文件暂存：类型、大小、令牌与草案生成。 */
class InternalAgentFileServiceTest {
    private FileService fileService;
    private TeamAgentAuthorization authorization;
    private PendingAgentDrafts pendingDrafts;
    private cn.ilink.mapper.TeamLibraryFileMapper libraryFileMapper;
    private InternalAgentFileService service;

    @BeforeEach
    void setUp() {
        TeamAgentProperties properties = new TeamAgentProperties();
        properties.getInternal().setEnabled(true);
        properties.getInternal().setToken("test-token");
        fileService = mock(FileService.class);
        authorization = mock(TeamAgentAuthorization.class);
        pendingDrafts = new PendingAgentDrafts();
        libraryFileMapper = mock(cn.ilink.mapper.TeamLibraryFileMapper.class);
        service = new InternalAgentFileService(fileService, authorization, properties, pendingDrafts, libraryFileMapper);
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        ResponseEntity<?> response = service.upload(docx(), "wrong", 1L, 8L, 99L, null, null, null);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(fileService, never()).upload(any(), any());
    }

    @Test
    void macroAndUnknownExtensionsAreRejected() throws Exception {
        MockMultipartFile macro = new MockMultipartFile(
            "file", "report.docm", "application/octet-stream", new byte[] { 1, 2, 3 });
        ResponseEntity<?> response = service.upload(macro, "test-token", 1L, 8L, 99L, null, null, null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(fileService, never()).upload(any(), any());
    }

    @Test
    void oversizedFileIsRejectedBeforeStorage() throws Exception {
        MockMultipartFile huge = new MockMultipartFile(
            "file", "big.docx", "application/octet-stream", new byte[(int) (21L * 1024 * 1024)]);
        assertEquals(HttpStatus.BAD_REQUEST,
            service.upload(huge, "test-token", 1L, 8L, 99L, null, null, null).getStatusCode());
    }

    @Test
    void permissionDeniedDoesNotStoreFile() throws Exception {
        when(authorization.requireScope(1L, 8L))
            .thenThrow(new TeamAgentException(TeamAgentErrorCode.TEAM_ACCESS_DENIED, "不是成员"));
        assertEquals(HttpStatus.FORBIDDEN,
            service.upload(docx(), "test-token", 1L, 8L, 99L, null, null, null).getStatusCode());
        verify(fileService, never()).upload(any(), any());
    }

    @Test
    void acceptedFileCreatesLibraryDraftForLaterConfirmation() throws Exception {
        when(authorization.requireScope(1L, 8L)).thenReturn(scope());
        when(fileService.upload(any(), eq("library")))
            .thenReturn("/uploads/library/2026/11/03/generated.docx");

        ResponseEntity<?> response = service.upload(
            docx(), "test-token", 1L, 8L, 99L, 5L, "周报", "生成周报草稿");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<TeamAgentActionDraft> drafts = pendingDrafts.take(99L);
        assertEquals(1, drafts.size());
        TeamAgentActionDraft draft = drafts.get(0);
        assertEquals("CREATE_LIBRARY_FILE", draft.actionType());
        assertEquals("TEAM_FILE", draft.targetType());
        assertEquals("周报.docx", draft.after().get("fileName"));
        assertEquals("/uploads/library/2026/11/03/generated.docx", draft.after().get("fileUrl"));
        assertEquals(5L, draft.after().get("folderId"));
        assertTrue(pendingDrafts.take(99L).isEmpty(), "草案只能取出一次");
    }

    @Test
    void updateWithoutTargetFileIsRejected() throws Exception {
        when(authorization.requireScope(1L, 8L)).thenReturn(scope());
        ResponseEntity<?> response = service.upload(
            docx(), "test-token", 1L, 8L, 99L, null, null, null, "update", null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(fileService, never()).upload(any(), any());
    }

    @Test
    void updateTargetFromAnotherTeamIsRejected() throws Exception {
        when(authorization.requireScope(1L, 8L)).thenReturn(scope());
        cn.ilink.entity.TeamLibraryFile foreign = new cn.ilink.entity.TeamLibraryFile();
        foreign.setId(7L);
        foreign.setTeamId(2L);
        when(libraryFileMapper.selectById(7L)).thenReturn(foreign);

        ResponseEntity<?> response = service.upload(
            docx(), "test-token", 1L, 8L, 99L, null, null, null, "update", 7L);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(fileService, never()).upload(any(), any());
    }

    @Test
    void updateCreatesUpdateDraftAndKeepsOriginalName() throws Exception {
        when(authorization.requireScope(1L, 8L)).thenReturn(scope());
        cn.ilink.entity.TeamLibraryFile target = new cn.ilink.entity.TeamLibraryFile();
        target.setId(7L);
        target.setTeamId(1L);
        target.setName("周报.docx");
        target.setParentId(3L);
        target.setContentRevision(4L);
        when(libraryFileMapper.selectById(7L)).thenReturn(target);
        when(fileService.upload(any(), eq("library")))
            .thenReturn("/uploads/library/2026/11/03/v2.docx");

        ResponseEntity<?> response = service.upload(
            docx(), "test-token", 1L, 8L, 99L, null, null, "更新周报", "update", 7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        TeamAgentActionDraft draft = pendingDrafts.take(99L).get(0);
        assertEquals("UPDATE_LIBRARY_FILE", draft.actionType());
        assertEquals(7L, draft.targetId());
        assertEquals("周报.docx", draft.after().get("fileName"));
        assertEquals(3L, draft.after().get("folderId"));
    }

    private static MockMultipartFile docx() {
        return new MockMultipartFile("file", "周报.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[] { 80, 75, 3, 4 });
    }

    private static TeamAgentScope scope() {
        return new TeamAgentScope(8L, 1L, "测试团队", "TEAMING", TeamAgentScope.TeamRole.STUDENT,
            TeamAgentScope.TaskReadMode.ALL, null, null, null, null, java.time.Instant.now());
    }
}
