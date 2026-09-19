package cn.ilink.service;

import cn.ilink.entity.Asset;
import cn.ilink.dto.UploadedFileInfo;
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

    /**
     * 成果创建/更新的入参。
     * 附件、封面各自带「换新」与「清空」两种意图，位置参数已经超过 8 个，
     * 继续加下去极易传错，所以收敛成一个显式载荷对象。
     */
    public static class AssetPayload {
        private final Long userId;
        private final String title;
        private final String description;
        private String category;
        private MultipartFile file;
        private boolean removeFile;
        private MultipartFile cover;
        private boolean removeCover;

        public AssetPayload(Long userId, String title, String description) {
            this.userId = userId;
            this.title = title;
            this.description = description;
        }

        public AssetPayload category(String value) {
            this.category = value;
            return this;
        }

        public AssetPayload file(MultipartFile value) {
            this.file = value;
            return this;
        }

        public AssetPayload removeFile(boolean value) {
            this.removeFile = value;
            return this;
        }

        public AssetPayload cover(MultipartFile value) {
            this.cover = value;
            return this;
        }

        public AssetPayload removeCover(boolean value) {
            this.removeCover = value;
            return this;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset createAsset(Long userId, String title, String description, MultipartFile file) throws IOException {
        return createAsset(new AssetPayload(userId, title, description).file(file));
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset createAsset(Long userId, String title, String description, String category,
                             MultipartFile file) throws IOException {
        return createAsset(new AssetPayload(userId, title, description).category(category).file(file));
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset createAsset(AssetPayload payload) throws IOException {
        String newFileUrl = null;
        String originalFileName = null;
        String newCoverUrl = null;
        try {
            // 两个上传都放在 try 里：任一失败时另一个已落盘的文件也要被清掉，不留孤儿
            if (payload.file != null) {
                UploadedFileInfo uploaded = fileService.uploadWithMetadata(payload.file, "assets");
                newFileUrl = uploaded.getUrl();
                originalFileName = uploaded.getOriginalName();
            }
            if (payload.cover != null) {
                newCoverUrl = fileService.uploadWithMetadata(payload.cover, "covers").getUrl();
            }

            Asset asset = new Asset();
            asset.setTitle(payload.title);
            asset.setDescription(payload.description);
            asset.setCategory(payload.category == null ? "其他" : payload.category);
            asset.setFileUrl(newFileUrl);
            asset.setOriginalFileName(originalFileName);
            asset.setCoverUrl(newCoverUrl);
            asset.setUserId(payload.userId);
            asset.setViewCount(0);
            asset.setDownloadCount(0);
            if (!assetService.save(asset)) {
                throw new IllegalStateException("成果保存失败");
            }
            deleteOnRollback(newFileUrl);
            deleteOnRollback(newCoverUrl);
            return asset;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(newFileUrl);
            deleteQuietly(newCoverUrl);
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset updateOwnedAsset(Long assetId, Long userId, String title, String description,
                                  MultipartFile file) throws IOException {
        return updateOwnedAsset(assetId, new AssetPayload(userId, title, description).file(file));
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset updateOwnedAsset(Long assetId, Long userId, String title, String description,
                                  String category, MultipartFile file) throws IOException {
        return updateOwnedAsset(assetId,
            new AssetPayload(userId, title, description).category(category).file(file));
    }

    @Transactional(rollbackFor = Exception.class)
    public Asset updateOwnedAsset(Long assetId, Long userId, String title, String description,
                                  String category, MultipartFile file, boolean removeFile) throws IOException {
        return updateOwnedAsset(assetId, new AssetPayload(userId, title, description)
            .category(category).file(file).removeFile(removeFile));
    }

    /**
     * 更新成果。附件与封面各自独立：
     * 本次传了新文件就以新文件为准，只有没传新文件时才看对应的 removeXxx 决定是否清空。
     */
    @Transactional(rollbackFor = Exception.class)
    public Asset updateOwnedAsset(Long assetId, AssetPayload payload) throws IOException {
        Asset asset = requireAsset(assetId);
        requireOwner(asset, payload.userId);

        String oldFileUrl = asset.getFileUrl();
        String oldCoverUrl = asset.getCoverUrl();
        String newFileUrl = null;
        String newCoverUrl = null;
        try {
            if (payload.file != null) {
                UploadedFileInfo uploaded = fileService.uploadWithMetadata(payload.file, "assets");
                newFileUrl = uploaded.getUrl();
                asset.setOriginalFileName(uploaded.getOriginalName());
            }
            if (payload.cover != null) {
                newCoverUrl = fileService.uploadWithMetadata(payload.cover, "covers").getUrl();
            }

            boolean clearFile = newFileUrl == null && payload.removeFile;
            boolean clearCover = newCoverUrl == null && payload.removeCover;

            asset.setTitle(payload.title);
            asset.setDescription(payload.description);
            if (payload.category != null) {
                asset.setCategory(payload.category);
            }
            if (newFileUrl != null) {
                asset.setFileUrl(newFileUrl);
            } else if (clearFile) {
                asset.setFileUrl(null);
                asset.setOriginalFileName(null);
            }
            if (newCoverUrl != null) {
                asset.setCoverUrl(newCoverUrl);
            } else if (clearCover) {
                asset.setCoverUrl(null);
            }

            if (!assetService.updateById(asset)) {
                throw new IllegalStateException("成果保存失败");
            }
            // MyBatis-Plus 的 updateById 默认跳过 null 字段，清空必须单独显式写库，
            // 否则列会原样留在库里——用户看到的仍是「删了但又回来了」。
            if (clearFile) {
                assetService.clearFileColumns(asset.getId());
            }
            if (clearCover) {
                assetService.clearCoverColumn(asset.getId());
            }

            if (newFileUrl != null) {
                replaceAfterCommit(oldFileUrl, newFileUrl);
            } else if (clearFile) {
                deleteAfterCommit(oldFileUrl);
            }
            if (newCoverUrl != null) {
                replaceAfterCommit(oldCoverUrl, newCoverUrl);
            } else if (clearCover) {
                deleteAfterCommit(oldCoverUrl);
            }
            return asset;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(newFileUrl);
            deleteQuietly(newCoverUrl);
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
            throw new IllegalStateException("成果删除失败");
        }
        deleteAfterCommit(asset.getFileUrl());
        deleteAfterCommit(asset.getCoverUrl());
        return asset;
    }

    private Asset requireAsset(Long assetId) {
        Asset asset = assetService.getById(assetId);
        if (asset == null) {
            throw new NoSuchElementException("成果不存在");
        }
        return asset;
    }

    private void requireOwner(Asset asset, Long userId) {
        if (userId == null || !Objects.equals(asset.getUserId(), userId)) {
            throw new AccessDeniedException("无权操作该成果");
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
            log.warn("受管附件删除失败: {}", fileUrl, e);
        }
    }
}
