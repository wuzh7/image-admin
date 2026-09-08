package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.image.domain.*;
import org.dromara.image.mapper.*;
import org.dromara.image.similarity.*;
import org.dromara.image.storage.ImageStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageAdmissionWorker {
    private final ImageBatchMapper batches;
    private final ImageAssetMapper assets;
    private final ImageStorage storage;
    private final QdrantVectorStore vectors;
    private final PlatformTransactionManager transactions;

    // 单业务实例，专用调度器串行入库，不阻塞检测调度；数据库保留进度，重启继续。
    @Scheduled(fixedDelay = 2000, scheduler = "imageAdmissionScheduler")
    public void dispatch() {
        for (ImageBatch batch : batches.selectList(Wrappers.<ImageBatch>lambdaQuery()
            .eq(ImageBatch::getStatus, BatchStatus.CONFIRMING.name()).orderByAsc(ImageBatch::getId).last("LIMIT 10"))) {
            try {
                List<ImageAsset> pending = assets.selectList(Wrappers.<ImageAsset>lambdaQuery()
                    .eq(ImageAsset::getBatchId, batch.getId()).eq(ImageAsset::getStatus, ImageStatus.APPROVING.name())
                    .orderByAsc(ImageAsset::getId).last("LIMIT 20"));
                pending.forEach(this::approve);
                long remaining = assets.selectCount(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getBatchId, batch.getId())
                    .eq(ImageAsset::getStatus, ImageStatus.APPROVING.name()));
                if (remaining > 0) continue;
                long failed = assets.selectCount(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getBatchId, batch.getId())
                    .eq(ImageAsset::getStatus, ImageStatus.APPROVAL_FAILED.name()));
                int updated = batches.update(null, Wrappers.<ImageBatch>lambdaUpdate().eq(ImageBatch::getId, batch.getId())
                    .eq(ImageBatch::getStatus, BatchStatus.CONFIRMING.name())
                    .set(ImageBatch::getStatus, failed > 0 ? BatchStatus.FAILED.name() : BatchStatus.COMPLETED.name())
                    .set(ImageBatch::getFailedCount, Math.toIntExact(failed))
                    .set(ImageBatch::getErrorMessage, failed > 0 ? failed + "张图片入库失败，请重试" : null));
                if (updated == 1 && failed == 0) vectors.deleteBatch(batch.getId());
            } catch (RuntimeException exception) { log.error("入库调度失败 batchId={}", batch.getId(), exception); }
        }
    }

    public void approve(ImageAsset asset) {
        synchronized (vectors) {
        String source = asset.getStorageKey();
        String thumbnail = asset.getThumbnailKey();
        try {
            if ("EXACT_HASH".equals(asset.getMatchMethod()) || asset.getSimilarity() == null
                || asset.getSimilarity().compareTo(new BigDecimal("0.95")) > 0) {
                throw new ServiceException("图片不符合入库条件");
            }
            String target = ImageStorage.libraryKey(asset.getId(), asset.getSha256(), source, asset.getCreateTime());
            String targetThumbnail = thumbnail == null ? null : target + ".thumb.jpg";
            storage.copy(source, target);
            if (thumbnail != null) storage.copy(thumbnail, targetThumbnail);
            vectors.upsert(asset.getId(), asset.getBatchId(), SscdFeatureExtractor.decode(asset.getGlobalVector()),
                SscdFeatureExtractor.decode(asset.getCenterVector()));
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                int updated = assets.update(null, Wrappers.<ImageAsset>lambdaUpdate()
                    .eq(ImageAsset::getId, asset.getId()).eq(ImageAsset::getStatus, ImageStatus.APPROVING.name())
                    .set(ImageAsset::getStorageKey, target).set(ImageAsset::getThumbnailKey, targetThumbnail)
                    .set(ImageAsset::getCleanupKey, source).set(ImageAsset::getCleanupThumbnailKey, thumbnail)
                    .set(ImageAsset::getStatus, ImageStatus.APPROVED.name()).set(ImageAsset::getErrorMessage, null));
                if (updated != 1) throw new ServiceException("图片入库状态保存失败");
            });
        } catch (RuntimeException exception) {
            // 提交结果不确定时先查库；禁止删除已成功提交图片的向量。
            ImageAsset persisted = assets.selectById(asset.getId());
            if (persisted != null && ImageStatus.APPROVED.name().equals(persisted.getStatus())) return;
            try { vectors.delete(List.of(asset.getId())); }
            catch (RuntimeException cleanup) { log.error("回滚未入库向量失败 imageId={}", asset.getId(), cleanup); }
            assets.update(null, Wrappers.<ImageAsset>lambdaUpdate().eq(ImageAsset::getId, asset.getId())
                .eq(ImageAsset::getStatus, ImageStatus.APPROVING.name()).set(ImageAsset::getStatus, ImageStatus.APPROVAL_FAILED.name())
                .set(ImageAsset::getErrorMessage, "入库失败，请检查OSS和向量服务后重试"));
            log.error("图片入库失败 imageId={}", asset.getId(), exception);
        }
        }
    }
}
