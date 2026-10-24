package cn.ilink.service;

import cn.ilink.entity.TeamLibraryFile;
import cn.ilink.mapper.TeamLibraryFileMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资料库移动（右键「移动到…」）的边界：防成环、同名拒绝、根目录 parent_id 置空。
 * move 只用到 TeamLibraryFileMapper，其余依赖不参与，保持为 null。
 */
@ExtendWith(MockitoExtension.class)
class TeamLibraryServiceTest {

    @Mock
    private TeamLibraryFileMapper libraryFileMapper;

    @Mock
    private cn.ilink.collab.TeamCollaborationIndexBridge collaborationIndexBridge;

    @InjectMocks
    private TeamLibraryService libraryService;

    /** LambdaUpdateWrapper.set 会解析列名，纯单测下需要先注册实体的 TableInfo */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            TeamLibraryFile.class);
    }

    private static TeamLibraryFile node(Long id, Long parentId, String name, boolean folder, Long uploaderId) {
        TeamLibraryFile file = new TeamLibraryFile();
        file.setId(id);
        file.setTeamId(3L);
        file.setParentId(parentId);
        file.setName(name);
        file.setIsFolder(folder);
        file.setUploaderId(uploaderId);
        file.setIsDeleted(false);
        file.setIsPurged(false);
        return file;
    }

    @Test
    void movingFolderIntoItsOwnSubtreeIsRejected() {
        // 5 是文件夹，目标是它的子孙 8（8 的父节点是 5）→ 会成环
        when(libraryFileMapper.selectById(5L)).thenReturn(node(5L, null, "竞赛", true, 7L));
        when(libraryFileMapper.selectById(8L)).thenReturn(node(8L, 5L, "子目录", true, 7L));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> libraryService.move(5L, 7L, false, 8L));
        assertTrue(error.getMessage().contains("不能把文件夹移动到它自己或它的子目录里"));
        verify(libraryFileMapper, never()).update(any(), any());
    }

    @Test
    void movingIntoCurrentParentIsRejected() {
        when(libraryFileMapper.selectById(5L)).thenReturn(node(5L, 8L, "竞赛", true, 7L));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> libraryService.move(5L, 7L, false, 8L));
        assertEquals("已在目标目录中", error.getMessage());
        verify(libraryFileMapper, never()).update(any(), any());
    }

    @Test
    void movingOntoSameNameInTargetIsRejected() {
        when(libraryFileMapper.selectById(50L)).thenReturn(node(50L, null, "方案.pdf", false, 7L));
        when(libraryFileMapper.selectById(11L)).thenReturn(node(11L, null, "竞赛", true, 9L));
        when(libraryFileMapper.selectOne(any())).thenReturn(node(51L, 11L, "方案.pdf", false, 9L));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> libraryService.move(50L, 7L, false, 11L));
        assertEquals("目标目录下已存在同名文件或文件夹", error.getMessage());
        verify(libraryFileMapper, never()).update(any(), any());
    }

    @Test
    void movingToRootSetsParentIdToNull() {
        // 根目录的 parent_id 是 NULL：updateById 会跳过 null 字段，必须走 UpdateWrapper 显式 set
        when(libraryFileMapper.selectById(50L)).thenReturn(node(50L, 11L, "方案.pdf", false, 7L));
        when(libraryFileMapper.selectOne(any())).thenReturn(null);

        libraryService.move(50L, 7L, false, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<TeamLibraryFile>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(libraryFileMapper).update(any(), captor.capture());
        verify(libraryFileMapper, never()).updateById(any());
        LambdaUpdateWrapper<TeamLibraryFile> wrapper = (LambdaUpdateWrapper<TeamLibraryFile>) captor.getValue();
        assertEquals(1, wrapper.getParamNameValuePairs().size(), "应只 set 一个字段（parent_id）");
        assertNull(wrapper.getParamNameValuePairs().values().iterator().next(), "parent_id 应被显式置为 null");
    }

    @Test
    void memberCannotMoveOthersFile() {
        when(libraryFileMapper.selectById(50L)).thenReturn(node(50L, 11L, "方案.pdf", false, 9L));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> libraryService.move(50L, 7L, false, null));
        assertEquals("只有文件上传者或队长可以执行此操作", error.getMessage());
        verify(libraryFileMapper, never()).update(any(), any());
    }

    private static TeamLibraryFile indexedNode(Long id, String name, String indexStatus) {
        TeamLibraryFile file = node(id, null, name, false, 7L);
        file.setFileUrl("/uploads/library/" + name);
        file.setIndexStatus(indexStatus);
        file.setContentRevision(3L);
        return file;
    }

    @Test
    void failedIndexCanBeRetriedByUploader() {
        TeamLibraryFile file = indexedNode(60L, "方案.pdf", TeamLibraryFile.INDEX_FAILED);
        when(libraryFileMapper.selectById(60L)).thenReturn(file);

        libraryService.reindex(60L, 7L, false);

        verify(collaborationIndexBridge).enqueueUpsert(file);
    }

    @Test
    void skippedAndStaleIndexAreAlsoRetryable() {
        TeamLibraryFile skipped = indexedNode(61L, "扫描件.tiff", TeamLibraryFile.INDEX_SKIPPED);
        TeamLibraryFile stale = indexedNode(62L, "旧表格.xlsx", TeamLibraryFile.INDEX_STALE);
        when(libraryFileMapper.selectById(61L)).thenReturn(skipped);
        when(libraryFileMapper.selectById(62L)).thenReturn(stale);

        libraryService.reindex(61L, 7L, true);
        libraryService.reindex(62L, 7L, true);

        verify(collaborationIndexBridge).enqueueUpsert(skipped);
        verify(collaborationIndexBridge).enqueueUpsert(stale);
    }

    @Test
    void retryingWhileIndexIsQueuedOrRunningIsRejected() {
        for (String status : new String[]{TeamLibraryFile.INDEX_PENDING, TeamLibraryFile.INDEX_INDEXING}) {
            TeamLibraryFile file = indexedNode(63L, "方案.pdf", status);
            when(libraryFileMapper.selectById(63L)).thenReturn(file);

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> libraryService.reindex(63L, 7L, false));
            assertEquals("索引正在进行中，请稍后查看结果", error.getMessage());
        }
        verify(collaborationIndexBridge, never()).enqueueUpsert(any());
    }

    @Test
    void folderAndTrashedFileCannotBeReindexed() {
        TeamLibraryFile folder = node(64L, null, "竞赛", true, 7L);
        when(libraryFileMapper.selectById(64L)).thenReturn(folder);
        IllegalArgumentException folderError = assertThrows(IllegalArgumentException.class,
            () -> libraryService.reindex(64L, 7L, false));
        assertEquals("文件夹不需要建立检索索引", folderError.getMessage());

        TeamLibraryFile trashed = indexedNode(65L, "回收.pdf", TeamLibraryFile.INDEX_FAILED);
        trashed.setIsDeleted(true);
        when(libraryFileMapper.selectById(65L)).thenReturn(trashed);
        IllegalArgumentException trashError = assertThrows(IllegalArgumentException.class,
            () -> libraryService.reindex(65L, 7L, false));
        assertEquals("回收站中的文件需要先从回收站恢复", trashError.getMessage());

        verify(collaborationIndexBridge, never()).enqueueUpsert(any());
    }

    @Test
    void memberCannotReindexOthersFile() {
        when(libraryFileMapper.selectById(66L))
            .thenReturn(indexedNode(66L, "他人文件.pdf", TeamLibraryFile.INDEX_FAILED));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> libraryService.reindex(66L, 8L, false));
        assertEquals("只有文件上传者或队长可以执行此操作", error.getMessage());
        verify(collaborationIndexBridge, never()).enqueueUpsert(any());
    }
}
