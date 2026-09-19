package cn.ilink.service;

import cn.ilink.dto.UploadedFileInfo;
import cn.ilink.entity.Asset;
import cn.ilink.service.impl.AssetServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetLifecycleServiceTest {

    private AssetServiceImpl assetService;
    private FileService fileService;
    private AssetLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetServiceImpl.class);
        fileService = mock(FileService.class);
        lifecycleService = new AssetLifecycleService(assetService, fileService);
    }

    @Test
    void createDeletesNewFileWhenDatabaseSaveFails() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "work.pdf", "application/pdf", "%PDF".getBytes());
        when(fileService.uploadWithMetadata(file, "assets"))
            .thenReturn(uploaded("/uploads/assets/work.pdf", "work.pdf", file));
        when(assetService.save(any(Asset.class))).thenReturn(false);

        assertThrows(IllegalStateException.class,
            () -> lifecycleService.createAsset(7L, "成果", "说明", file));

        verify(fileService).delete("/uploads/assets/work.pdf");
    }

    @Test
    void updateStoresReplacementBeforeDeletingOldFile() throws Exception {
        Asset asset = asset(1L, 7L, "/uploads/assets/old.pdf");
        MockMultipartFile file = new MockMultipartFile("file", "new.pdf", "application/pdf", "%PDF".getBytes());
        when(assetService.getById(1L)).thenReturn(asset);
        when(fileService.uploadWithMetadata(file, "assets"))
            .thenReturn(uploaded("/uploads/assets/new.pdf", "new.pdf", file));
        when(assetService.updateById(asset)).thenReturn(true);

        Asset updated = lifecycleService.updateOwnedAsset(1L, 7L, "新标题", "新说明", file);

        assertEquals("/uploads/assets/new.pdf", updated.getFileUrl());
        assertEquals("new.pdf", updated.getOriginalFileName());
        verify(assetService).updateById(asset);
        verify(fileService).delete("/uploads/assets/old.pdf");
        verify(fileService, never()).delete("/uploads/assets/new.pdf");
    }

    @Test
    void removeFile_clearsAttachmentColumnsAndDeletesStoredFile() throws Exception {
        Asset asset = asset(1L, 7L, "/uploads/assets/old.pdf");
        when(assetService.getById(1L)).thenReturn(asset);
        when(assetService.updateById(asset)).thenReturn(true);

        Asset updated = lifecycleService.updateOwnedAsset(1L, 7L, "标题", "说明", null, null, true);

        assertEquals(null, updated.getFileUrl());
        assertEquals(null, updated.getOriginalFileName());
        verify(assetService).updateById(asset);
        // MyBatis-Plus 的 updateById 会跳过 null 字段，清空必须走这条显式 UPDATE
        verify(assetService).clearFileColumns(1L);
        verify(fileService).delete("/uploads/assets/old.pdf");
    }

    @Test
    void removeFile_isIgnoredWhenANewFileIsUploaded() throws Exception {
        Asset asset = asset(1L, 7L, "/uploads/assets/old.pdf");
        MockMultipartFile file = new MockMultipartFile("file", "new.pdf", "application/pdf", "%PDF".getBytes());
        when(assetService.getById(1L)).thenReturn(asset);
        when(fileService.uploadWithMetadata(file, "assets"))
            .thenReturn(uploaded("/uploads/assets/new.pdf", "new.pdf", file));
        when(assetService.updateById(asset)).thenReturn(true);

        Asset updated = lifecycleService.updateOwnedAsset(1L, 7L, "标题", "说明", null, file, true);

        // 传了新文件时以新文件为准，不能被 removeFile 抹掉
        assertEquals("/uploads/assets/new.pdf", updated.getFileUrl());
        verify(assetService, never()).clearFileColumns(any());
        verify(fileService).delete("/uploads/assets/old.pdf");
        verify(fileService, never()).delete("/uploads/assets/new.pdf");
    }

    @Test
    void updateWithoutRemoveFileKeepsExistingAttachment() throws Exception {
        Asset asset = asset(1L, 7L, "/uploads/assets/old.pdf");
        when(assetService.getById(1L)).thenReturn(asset);
        when(assetService.updateById(asset)).thenReturn(true);

        Asset updated = lifecycleService.updateOwnedAsset(1L, 7L, "标题", "说明", null, null, false);

        assertEquals("/uploads/assets/old.pdf", updated.getFileUrl());
        verify(fileService, never()).delete("/uploads/assets/old.pdf");
    }

    @Test
    void removeFile_onAssetWithoutAttachmentIsANoOp() throws Exception {
        Asset asset = asset(1L, 7L, null);
        when(assetService.getById(1L)).thenReturn(asset);
        when(assetService.updateById(asset)).thenReturn(true);

        lifecycleService.updateOwnedAsset(1L, 7L, "标题", "说明", null, null, true);

        verify(fileService, never()).delete(any());
    }

    @Test
    void createAsset_uploadsCoverWithItsOwnBizType() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "work.pdf", "application/pdf", "%PDF".getBytes());
        MockMultipartFile cover = new MockMultipartFile("cover", "cover.png", "image/png", "PNG".getBytes());
        when(fileService.uploadWithMetadata(file, "assets"))
            .thenReturn(uploaded("/uploads/assets/work.pdf", "work.pdf", file));
        when(fileService.uploadWithMetadata(cover, "covers"))
            .thenReturn(uploaded("/uploads/covers/a.png", "cover.png", cover));
        when(assetService.save(any(Asset.class))).thenReturn(true);

        Asset created = lifecycleService.createAsset(
            new AssetLifecycleService.AssetPayload(7L, "成果", "说明").file(file).cover(cover));

        assertEquals("/uploads/assets/work.pdf", created.getFileUrl());
        assertEquals("/uploads/covers/a.png", created.getCoverUrl());
        verify(fileService).uploadWithMetadata(cover, "covers");
    }

    @Test
    void createAsset_cleansUpBothUploadsWhenSaveFails() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "work.pdf", "application/pdf", "%PDF".getBytes());
        MockMultipartFile cover = new MockMultipartFile("cover", "cover.png", "image/png", "PNG".getBytes());
        when(fileService.uploadWithMetadata(file, "assets"))
            .thenReturn(uploaded("/uploads/assets/work.pdf", "work.pdf", file));
        when(fileService.uploadWithMetadata(cover, "covers"))
            .thenReturn(uploaded("/uploads/covers/a.png", "cover.png", cover));
        when(assetService.save(any(Asset.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> lifecycleService.createAsset(
            new AssetLifecycleService.AssetPayload(7L, "成果", "说明").file(file).cover(cover)));

        // 两个上传都不能留下孤儿文件
        verify(fileService).delete("/uploads/assets/work.pdf");
        verify(fileService).delete("/uploads/covers/a.png");
    }

    @Test
    void removeCover_clearsCoverColumnAndDeletesStoredFile() throws Exception {
        Asset asset = asset(1L, 7L, "/uploads/assets/old.pdf");
        asset.setCoverUrl("/uploads/covers/old.png");
        when(assetService.getById(1L)).thenReturn(asset);
        when(assetService.updateById(asset)).thenReturn(true);

        Asset updated = lifecycleService.updateOwnedAsset(1L,
            new AssetLifecycleService.AssetPayload(7L, "标题", "说明").removeCover(true));

        assertEquals(null, updated.getCoverUrl());
        verify(assetService).clearCoverColumn(1L);
        verify(fileService).delete("/uploads/covers/old.png");
        // 只移除封面，附件不受影响
        verify(assetService, never()).clearFileColumns(any());
        verify(fileService, never()).delete("/uploads/assets/old.pdf");
    }

    @Test
    void updateWithNewCover_replacesOldCoverAndClearsNothing() throws Exception {
        Asset asset = asset(1L, 7L, "/uploads/assets/old.pdf");
        asset.setCoverUrl("/uploads/covers/old.png");
        MockMultipartFile cover = new MockMultipartFile("cover", "new.png", "image/png", "PNG".getBytes());
        when(assetService.getById(1L)).thenReturn(asset);
        when(fileService.uploadWithMetadata(cover, "covers"))
            .thenReturn(uploaded("/uploads/covers/new.png", "new.png", cover));
        when(assetService.updateById(asset)).thenReturn(true);

        Asset updated = lifecycleService.updateOwnedAsset(1L,
            new AssetLifecycleService.AssetPayload(7L, "标题", "说明").cover(cover).removeCover(true));

        // 传了新封面时以新封面为准，不能被 removeCover 抹掉
        assertEquals("/uploads/covers/new.png", updated.getCoverUrl());
        verify(assetService, never()).clearCoverColumn(any());
        verify(fileService).delete("/uploads/covers/old.png");
        verify(fileService, never()).delete("/uploads/covers/new.png");
    }

    @Test
    void deleteAsset_alsoRemovesCover() throws Exception {
        Asset asset = asset(1L, 7L, "/uploads/assets/work.pdf");
        asset.setCoverUrl("/uploads/covers/work.png");
        when(assetService.getById(1L)).thenReturn(asset);
        when(assetService.removeById(1L)).thenReturn(true);

        lifecycleService.deleteOwnedAsset(1L, 7L);

        verify(fileService).delete("/uploads/assets/work.pdf");
        verify(fileService).delete("/uploads/covers/work.png");
    }

    @Test
    void deleteRejectsNonOwnerWithoutChangingDatabase() {        Asset asset = asset(1L, 7L, "/uploads/assets/work.pdf");
        when(assetService.getById(1L)).thenReturn(asset);

        assertThrows(AccessDeniedException.class,
            () -> lifecycleService.deleteOwnedAsset(1L, 8L));

        verify(assetService, never()).removeById(1L);
    }

    @Test
    void deleteRemovesDatabaseRecordBeforeManagedFile() throws Exception {
        Asset asset = asset(1L, 7L, "/uploads/assets/work.pdf");
        when(assetService.getById(1L)).thenReturn(asset);
        when(assetService.removeById(1L)).thenReturn(true);

        lifecycleService.deleteOwnedAsset(1L, 7L);

        verify(assetService).removeById(1L);
        verify(fileService).delete("/uploads/assets/work.pdf");
    }

    private Asset asset(Long id, Long ownerId, String fileUrl) {
        Asset asset = new Asset();
        asset.setId(id);
        asset.setUserId(ownerId);
        asset.setFileUrl(fileUrl);
        return asset;
    }

    private UploadedFileInfo uploaded(String url, String originalName, MockMultipartFile file) {
        return new UploadedFileInfo(url, originalName, file.getSize(), file.getContentType());
    }
}
