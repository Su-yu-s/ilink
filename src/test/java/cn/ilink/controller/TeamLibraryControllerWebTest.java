package cn.ilink.controller;

import cn.ilink.entity.TeamApplication;
import cn.ilink.entity.TeamDemand;
import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.entity.User;
import cn.ilink.mapper.TeamLibraryFileMapper;
import cn.ilink.service.TeamLibraryService;
import cn.ilink.service.TeamTaskService;
import cn.ilink.service.impl.TeamApplicationServiceImpl;
import cn.ilink.service.impl.TeamDemandServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 团队资料库权限与同名冲突回归：
 * 读=团队成员；写=成员；处置（重命名/删除）=上传者或队长；回收站=仅队长。
 */
@WebMvcTest(controllers = TeamLibraryController.class)
@AutoConfigureMockMvc(addFilters = false)
class TeamLibraryControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TeamLibraryService libraryService;

    @MockBean
    private TeamLibraryFileMapper libraryFileMapper;

    @MockBean
    private TeamDemandServiceImpl teamDemandService;

    @MockBean
    private TeamApplicationServiceImpl teamApplicationService;

    @MockBean
    private TeamTaskService teamTaskService;

    private MockHttpSession leaderSession;
    private MockHttpSession memberSession;

    @BeforeEach
    void setUp() {
        TeamDemand team = new TeamDemand();
        team.setId(3L);
        team.setCreatorId(9L);
        given(teamDemandService.getById(3L)).willReturn(team);

        User leader = new User();
        leader.setId(9L);
        leaderSession = new MockHttpSession();
        leaderSession.setAttribute("user", leader);

        User member = new User();
        member.setId(7L);
        memberSession = new MockHttpSession();
        memberSession.setAttribute("user", member);

        // 成员 7 是已批准成员（非导师）
        given(teamApplicationService.count(any(Wrapper.class))).willReturn(1L);
    }

    private TeamLibraryFile uploadedFile(Long uploaderId) {
        TeamLibraryFile file = new TeamLibraryFile();
        file.setId(50L);
        file.setTeamId(3L);
        file.setName("方案.pdf");
        file.setIsFolder(false);
        file.setUploaderId(uploaderId);
        file.setIsDeleted(false);
        file.setIsPurged(false);
        return file;
    }

    @Test
    void memberCanReadLibraryTree() throws Exception {
        given(libraryService.getTree(eq(3L), eq(false))).willReturn(List.of());
        mockMvc.perform(get("/api/team/3/library").session(memberSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void outsiderCannotReadLibrary() throws Exception {
        User outsider = new User();
        outsider.setId(99L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", outsider);
        given(teamApplicationService.count(any(Wrapper.class))).willReturn(0L);

        mockMvc.perform(get("/api/team/3/library").session(session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void memberCannotOpenTrash() throws Exception {
        mockMvc.perform(get("/api/team/3/library?view=trash").session(memberSession))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
        verify(libraryService, never()).getTree(3L, true);
    }

    @Test
    void leaderCanOpenTrash() throws Exception {
        given(libraryService.getTree(eq(3L), eq(true))).willReturn(List.of());
        mockMvc.perform(get("/api/team/3/library?view=trash").session(leaderSession))
            .andExpect(status().isOk());
    }

    @Test
    void registerFileReturnsConflictWithoutStrategy() throws Exception {
        given(libraryService.registerFile(eq(3L), eq(7L), any(), eq("方案.pdf"), any(), any(), eq(null)))
            .willReturn(Map.of("conflict", true, "existing", Map.of("name", "方案.pdf")));

        mockMvc.perform(post("/api/team/3/library/files").session(memberSession)
                .contentType("application/json")
                .content("{\"name\":\"方案.pdf\",\"url\":\"/uploads/library/a.pdf\",\"size\":100}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conflict").value(true));
    }

    @Test
    void registerFileReturnsConflictObjectAtDataRoot() throws Exception {
        given(libraryService.registerFile(eq(3L), eq(7L), any(), eq("方案.pdf"), any(), any(), eq(null)))
            .willReturn(Map.of("conflict", true,
                "existing", Map.of("name", "方案.pdf", "fileSize", 100)));

        // 前端 request() 已解包 Result.data；冲突字段必须直接位于 data，而不是再嵌套 data.data。
        mockMvc.perform(post("/api/team/3/library/files").session(memberSession)
                .contentType("application/json")
                .content("{\"name\":\"方案.pdf\",\"url\":\"/uploads/library/a.pdf\",\"size\":100}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conflict").value(true))
            .andExpect(jsonPath("$.data.existing.name").value("方案.pdf"))
            .andExpect(jsonPath("$.data.data").doesNotExist());
    }

    @Test
    void deleteOthersFileSurfaces403() throws Exception {
        // 权限校验在 Service 层：抛 IllegalStateException 时 Controller 映射为 403
        given(libraryService.softDelete(eq(50L), eq(7L), eq(false)))
            .willThrow(new IllegalStateException("只有文件上传者或队长可以执行此操作"));

        mockMvc.perform(delete("/api/team/3/library/files/50").session(memberSession))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void memberCanDeleteOwnFile() throws Exception {
        given(libraryService.softDelete(eq(50L), eq(7L), eq(false))).willReturn(List.of());

        mockMvc.perform(delete("/api/team/3/library/files/50").session(memberSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void leaderCanDeleteAnyFile() throws Exception {
        given(libraryService.softDelete(eq(50L), eq(9L), eq(true))).willReturn(List.of());

        mockMvc.perform(delete("/api/team/3/library/files/50").session(leaderSession))
            .andExpect(status().isOk());
    }

    @Test
    void memberCannotPurge() throws Exception {
        given(libraryService.purge(eq(50L), eq(7L), eq(false)))
            .willThrow(new IllegalStateException("只有队长可以彻底删除文件"));

        mockMvc.perform(delete("/api/team/3/library/files/50?purge=true").session(memberSession))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void duplicateFolderNameRejected() throws Exception {
        given(libraryService.createFolder(eq(3L), eq(7L), any(), eq("竞赛资料")))
            .willThrow(new IllegalArgumentException("同目录下已存在同名文件或文件夹"));

        mockMvc.perform(post("/api/team/3/library/folders").session(memberSession)
                .contentType("application/json")
                .content("{\"name\":\"竞赛资料\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void movePassesTargetParentId() throws Exception {
        mockMvc.perform(put("/api/team/3/library/files/50/move").session(memberSession)
                .contentType("application/json")
                .content("{\"parentId\":11}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
        verify(libraryService).move(50L, 7L, false, 11L);
    }

    @Test
    void moveWithoutParentIdTargetsRoot() throws Exception {
        mockMvc.perform(put("/api/team/3/library/files/50/move").session(memberSession)
                .contentType("application/json")
                .content("{}"))
            .andExpect(status().isOk());
        verify(libraryService).move(50L, 7L, false, null);
    }

    @Test
    void moveIntoOwnSubtreeRejected() throws Exception {
        willThrow(new IllegalArgumentException("不能把文件夹移动到它自己或它的子目录里"))
            .given(libraryService).move(any(), any(), anyBoolean(), any());

        mockMvc.perform(put("/api/team/3/library/files/50/move").session(memberSession)
                .contentType("application/json")
                .content("{\"parentId\":50}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void moveOthersFileSurfaces403() throws Exception {
        willThrow(new IllegalStateException("只有文件上传者或队长可以执行此操作"))
            .given(libraryService).move(any(), any(), anyBoolean(), any());

        mockMvc.perform(put("/api/team/3/library/files/50/move").session(memberSession)
                .contentType("application/json")
                .content("{\"parentId\":11}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void leaderMoveUsesLeaderFlag() throws Exception {
        mockMvc.perform(put("/api/team/3/library/files/50/move").session(leaderSession)
                .contentType("application/json")
                .content("{\"parentId\":11}"))
            .andExpect(status().isOk());
        verify(libraryService).move(50L, 9L, true, 11L);
    }

    @Test
    void uploaderCanRequestReindex() throws Exception {
        mockMvc.perform(post("/api/team/3/library/files/50/reindex").session(memberSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
        verify(libraryService).reindex(50L, 7L, false);
    }

    @Test
    void leaderReindexUsesLeaderFlag() throws Exception {
        mockMvc.perform(post("/api/team/3/library/files/50/reindex").session(leaderSession))
            .andExpect(status().isOk());
        verify(libraryService).reindex(50L, 9L, true);
    }

    @Test
    void outsiderCannotRequestReindex() throws Exception {
        User outsider = new User();
        outsider.setId(99L);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", outsider);
        given(teamApplicationService.count(any(Wrapper.class))).willReturn(0L);

        mockMvc.perform(post("/api/team/3/library/files/50/reindex").session(session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
        verify(libraryService, never()).reindex(any(), any(), anyBoolean());
    }

    @Test
    void reindexWhileRunningSurfaces400() throws Exception {
        willThrow(new IllegalArgumentException("索引正在进行中，请稍后查看结果"))
            .given(libraryService).reindex(any(), any(), anyBoolean());

        mockMvc.perform(post("/api/team/3/library/files/50/reindex").session(memberSession))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("索引正在进行中，请稍后查看结果"));
    }

    @Test
    void reindexWithoutPermissionSurfaces403() throws Exception {
        willThrow(new IllegalStateException("只有文件上传者或队长可以执行此操作"))
            .given(libraryService).reindex(any(), any(), anyBoolean());

        mockMvc.perform(post("/api/team/3/library/files/50/reindex").session(memberSession))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }
}
