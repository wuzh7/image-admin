package org.dromara.image.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.storage.ImageStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageMaintenance {

    private final ImageProperties properties;
    private final ImageAssetMapper assetMapper;
    private final ImageStorage storage;

    @Scheduled(fixedDelay = 10000, scheduler = "imageCleanupScheduler")
    public void cleanupPending() {
        for (ImageAsset asset : assetMapper.selectSourceCleanup()) {
            try {
                storage.delete(asset.getCleanupKey());
                storage.delete(asset.getCleanupThumbnailKey());
                assetMapper.clearSourceCleanup(asset.getId());
            } catch (RuntimeException exception) { log.warn("清理入库源文件失败 imageId={}", asset.getId()); }
        }
        for (ImageAsset asset : assetMapper.selectPendingDeletes()) {
            try {
                storage.delete(asset.getStorageKey());
                storage.delete(asset.getThumbnailKey());
                assetMapper.clearStorageKeys(asset.getId());
            } catch (RuntimeException exception) { log.warn("删除图片失败 imageId={}", asset.getId()); }
        }
    }

    @Scheduled(cron = "0 20 3 * * *", scheduler = "imageCleanupScheduler")
    public void cleanupExpiredFiles() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getDeleteRetentionDays());
        for (ImageAsset asset : assetMapper.selectCleanupCandidates(cutoff)) {
            try {
                storage.delete(asset.getStorageKey());
                storage.delete(asset.getThumbnailKey());
                assetMapper.clearStorageKeys(asset.getId());
            } catch (RuntimeException exception) {
                log.warn("清理过期图片失败 imageId={}", asset.getId(), exception);
            }
        }
    }

}
