package cn.ilink.service;

import cn.ilink.entity.Asset;
import cn.ilink.service.impl.AssetServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Objects;

@Service
public class AssetLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AssetLifecycleService.class);

    private final AssetServiceImpl assetService;
    private final FileService fileService;

    public AssetLifecycleService(AssetServiceImpl assetService, FileService fileService) {
        this.assetService = assetService;
        this.fileService = fileService;
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset createAsset(Long userId, String title, String description, MultipartFile file) throws IOException {
        return createAsset(userId, title, description, "其他", file);
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset createAsset(Long userId, String title, String description, String category,
                             MultipartFile file) throws IOException {
        String newFileUrl = file == null ? null : fileService.upload(file, "assets");
        try {
            Asset asset = new Asset();
            asset.setTitle(title);
            asset.setDescription(description);
            asset.setCategory(category);
            asset.setFileUrl(newFileUrl);
            asset.setUserId(userId);
            asset.setViewCount(0);
            asset.setDownloadCount(0);
            if (!assetService.save(asset)) {
                throw new IllegalStateException("\u6210\u679c\u4fdd\u5b58\u5931\u8d25");
            }
            deleteOnRollback(newFileUrl);
            return asset;
        } catch (RuntimeException e) {
            deleteQuietly(newFileUrl);
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset updateOwnedAsset(Long assetId, Long userId, String title, String description,
                                  MultipartFile file) throws IOException {
        return updateOwnedAsset(assetId, userId, title, description, null, file);
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset updateOwnedAsset(Long assetId, Long userId, String title, String description,
                                  String category, MultipartFile file) throws IOException {
        Asset asset = requireAsset(assetId);
        requireOwner(asset, userId);

        String oldFileUrl = asset.getFileUrl();
        String newFileUrl = file == null ? null : fileService.upload(file, "assets");
        try {
            asset.setTitle(title);
            asset.setDescription(description);
            if (category != null) {
                asset.setCategory(category);
            }
            if (newFileUrl != null) {
                asset.setFileUrl(newFileUrl);
            }
            if (!assetService.updateById(asset)) {
                throw new IllegalStateException("\u6210\u679c\u4fdd\u5b58\u5931\u8d25");
            }
            if (newFileUrl != null) {
                replaceAfterCommit(oldFileUrl, newFileUrl);
            }
            return asset;
        } catch (RuntimeException e) {
            deleteQuietly(newFileUrl);
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset deleteOwnedAsset(Long assetId, Long userId) {
        Asset asset = requireAsset(assetId);
        requireOwner(asset, userId);
        return removeAsset(asset);
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset deleteAssetAsAdmin(Long assetId) {
        return removeAsset(requireAsset(assetId));
    }

    private Asset removeAsset(Asset asset) {
        if (!assetService.removeById(asset.getId())) {
            throw new IllegalStateException("\u6210\u679c\u5220\u9664\u5931\u8d25");
        }
        deleteAfterCommit(asset.getFileUrl());
        return asset;
    }

    private Asset requireAsset(Long assetId) {
        Asset asset = assetService.getById(assetId);
        if (asset == null) {
            throw new NoSuchElementException("\u6210\u679c\u4e0d\u5b58\u5728");
        }
        return asset;
    }

    private void requireOwner(Asset asset, Long userId) {
        if (userId == null || !Objects.equals(asset.getUserId(), userId)) {
            throw new AccessDeniedException("\u65e0\u6743\u64cd\u4f5c\u8be5\u6210\u679c");
        }
    }

    private void replaceAfterCommit(String oldFileUrl, String newFileUrl) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(oldFileUrl);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        deleteQuietly(newFileUrl);
                    }
                }
            });
            return;
        }
        deleteQuietly(oldFileUrl);
    }

    private void deleteOnRollback(String fileUrl) {
        if (fileUrl == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(fileUrl);
                }
            }
        });
    }

    private void deleteAfterCommit(String fileUrl) {
        if (fileUrl == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(fileUrl);
                }
            });
            return;
        }
        deleteQuietly(fileUrl);
    }

    private void deleteQuietly(String fileUrl) {
        if (fileUrl == null) {
            return;
        }
        try {
            fileService.delete(fileUrl);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("\u53d7\u7ba1\u9644\u4ef6\u5220\u9664\u5931\u8d25: {}", fileUrl, e);
        }
    }
}
