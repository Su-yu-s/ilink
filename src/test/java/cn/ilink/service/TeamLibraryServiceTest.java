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
}
