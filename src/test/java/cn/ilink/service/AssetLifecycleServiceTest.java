package cn.ilink.service;

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
        when(fileService.upload(file, "assets")).thenReturn("/uploads/assets/work.pdf");
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
        when(fileService.upload(file, "assets")).thenReturn("/uploads/assets/new.pdf");
        when(assetService.updateById(asset)).thenReturn(true);

        Asset updated = lifecycleService.updateOwnedAsset(1L, 7L, "新标题", "新说明", file);

        assertEquals("/uploads/assets/new.pdf", updated.getFileUrl());
        verify(assetService).updateById(asset);
        verify(fileService).delete("/uploads/assets/old.pdf");
        verify(fileService, never()).delete("/uploads/assets/new.pdf");
    }

    @Test
    void deleteRejectsNonOwnerWithoutChangingDatabase() {
        Asset asset = asset(1L, 7L, "/uploads/assets/work.pdf");
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
}
