package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.image.domain.*;
import org.dromara.image.mapper.*;
import org.dromara.image.similarity.*;
import org.dromara.image.storage.ImageStorage;
import org.dromara.system.api.UserService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageAdmissionWorker {
    private final ImageBatchMapper batches;
    private final ImageAssetMapper assets;
    private final ImageStorage storage;
    private final QdrantVectorStore vectors;
    private final ImageLibraryLock libraryLock;
    private final ImageDuplicateChecker checker;
    private final InfringementService infringement;
    private final ImageRecordMapper records;
    private final UserService users;
    private final ImageDataLock dataLock;

    @Scheduled(fixedDelay = 2000, scheduler = "imageAdmissionScheduler")
    public void dispatch() {
        var ready = batches.selectList(Wrappers.<ImageBatch>lambdaQuery()
            .in(ImageBatch::getStatus, "CONFIRMING", "WAIT_REVIEW")
            .inSql(ImageBatch::getId, "SELECT DISTINCT batch_id FROM img_asset WHERE status = 'APPROVING' AND del_flag = '0'")
            .orderByAsc(ImageBatch::getId).last("LIMIT 10"));
        for (ImageBatch batch : ready) {
            try {
                assets.selectList(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getBatchId, batch.getId())
                    .eq(ImageAsset::getStatus, "APPROVING").orderByAsc(ImageAsset::getId).last("LIMIT 20")).forEach(this::approve);
                dataLock.read(() -> libraryLock.run(() -> { finishBatch(batch.getId()); return null; }));
            } catch (RuntimeException exception) { log.error("入库调度失败 batchId={}", batch.getId(), exception); }
        }
        // 重启时接续最后一张入库成功、但批次尚未结束的任务。
        for (ImageBatch batch : batches.selectList(Wrappers.<ImageBatch>lambdaQuery()
            .eq(ImageBatch::getStatus, "CONFIRMING").orderByAsc(ImageBatch::getId).last("LIMIT 10"))) {
            dataLock.read(() -> libraryLock.run(() -> { finishBatch(batch.getId()); return null; }));
        }
    }

    public void approve(ImageAsset pending) {
        dataLock.read(() -> {
            try {
                libraryLock.run(() -> { approveLocked(pending.getId()); return null; });
            } catch (RuntimeException exception) {
                libraryLock.run(() -> {
                    ImageAsset image = assets.selectById(pending.getId());
                    // 提交结果不确定时先查库；绝不能删除已提交图片的向量。
                    if (image == null || "APPROVED".equals(image.getStatus()) || !"APPROVING".equals(image.getStatus())) return null;
                    try {
                        vectors.delete(List.of(image.getId()));
                    } catch (RuntimeException cleanup) { log.error("回滚未入库向量失败 imageId={}", image.getId(), cleanup); }
                    try {
                        String target = ImageStorage.libraryKey(image.getId(), image.getBatchId(), image.getSha256(),
                            image.getStorageKey(), image.getCreateTime());
                        if (!target.equals(image.getStorageKey())) {
                            storage.delete(target);
                            storage.delete(target + ".thumb.jpg");
                        }
                        if (image.getReplacesImageId() != null) {
                            ImageAsset old = assets.selectById(image.getReplacesImageId());
                            if (old != null && "APPROVED".equals(old.getStatus())) {
                                String archive = "replaced/" + old.getId() + "/" + image.getId() + "/"
                                    + old.getStorageKey().substring(old.getStorageKey().lastIndexOf('/') + 1);
                                storage.delete(archive);
                                storage.delete(archive + ".thumb.jpg");
                            }
                        }
                    } catch (RuntimeException cleanup) { log.error("回滚未入库文件失败 imageId={}", image.getId(), cleanup); }
                    assets.update(null, Wrappers.<ImageAsset>lambdaUpdate().eq(ImageAsset::getId, image.getId())
                        .eq(ImageAsset::getStatus, "APPROVING").set(ImageAsset::getStatus, "APPROVAL_FAILED")
                        .set(ImageAsset::getErrorMessage, "入库失败：" + exception.getMessage()));
                    finishBatch(image.getBatchId());
                    return null;
                });
                log.error("图片入库失败 imageId={}", pending.getId(), exception);
            }
        });
    }

    private void approveLocked(long id) {
        ImageAsset image = assets.selectById(id);
        if (image == null || !"APPROVING".equals(image.getStatus())) return;
        ImageBatch batch = batches.selectById(image.getBatchId());
        if (batch == null) throw new ServiceException("图片批次不存在");
        var latest = checker.check(image, image.getReplacesImageId());
        if (ImageDuplicateChecker.needsReview(image, latest, batch.getSimilarityThreshold())) {
            var top = latest.getFirst();
            ImageAsset candidate = assets.selectById(top.imageId());
            boolean exact = candidate != null && image.getSha256().equals(candidate.getSha256());
            assets.update(null, Wrappers.<ImageAsset>lambdaUpdate().eq(ImageAsset::getId, id)
                .set(ImageAsset::getStatus, "WAIT_REVIEW").set(ImageAsset::getManuallyApproved, false)
                .set(ImageAsset::getSimilarity, top.similarity()).set(ImageAsset::getDuplicateFlag, true)
                .set(ImageAsset::getDuplicateOf, top.imageId()).set(ImageAsset::getMatchMethod, exact ? "EXACT_HASH" : "SSCD")
                .set(ImageAsset::getSimilarMatchesJson, JsonUtils.toJsonString(latest))
                .set(ImageAsset::getErrorMessage, exact ? "最终查重发现完全相同的已入库图片，不能重复入库" : "图库已更新，请根据最新相似图片重新审核"));
            finishBatch(batch.getId());
            return;
        }
        String source = image.getStorageKey();
        String thumbnail = image.getThumbnailKey();
        String target = ImageStorage.libraryKey(id, image.getBatchId(), image.getSha256(), source, image.getCreateTime());
        String targetThumbnail = thumbnail == null ? null : target + ".thumb.jpg";
        storage.copy(source, target);
        if (thumbnail != null) storage.copy(thumbnail, targetThumbnail);
        upsert(image);
        if (image.getReplacesImageId() != null) archiveOld(image, target);
        int updated = assets.update(null, Wrappers.<ImageAsset>lambdaUpdate().eq(ImageAsset::getId, id)
            .eq(ImageAsset::getStatus, "APPROVING").set(ImageAsset::getStorageKey, target)
            .set(ImageAsset::getThumbnailKey, targetThumbnail).set(ImageAsset::getCleanupKey, source)
            .set(ImageAsset::getCleanupThumbnailKey, thumbnail).set(ImageAsset::getStatus, "APPROVED")
            .set(ImageAsset::getApprovedAt, LocalDateTime.now()).set(ImageAsset::getErrorMessage, null));
        if (updated != 1) throw new ServiceException("图片入库状态保存失败");
        image.setStatus("APPROVED");
        finishBatch(batch.getId());
    }

    private void archiveOld(ImageAsset replacement, String target) {
        ImageAsset old = assets.selectById(replacement.getReplacesImageId());
        if (old == null || !"APPROVED".equals(old.getStatus())) throw new ServiceException("原图片已删除或被替换");
        String archive = "replaced/" + old.getId() + "/" + replacement.getId() + "/" + old.getStorageKey().substring(old.getStorageKey().lastIndexOf('/') + 1);
        String archiveThumbnail = old.getThumbnailKey() == null ? null : archive + ".thumb.jpg";
        storage.copy(old.getStorageKey(), archive);
        if (archiveThumbnail != null) storage.copy(old.getThumbnailKey(), archiveThumbnail);
        int archived = assets.update(null, Wrappers.<ImageAsset>lambdaUpdate().eq(ImageAsset::getId, old.getId())
            .eq(ImageAsset::getStatus, "APPROVED").set(ImageAsset::getStatus, "REPLACED")
            .set(ImageAsset::getStorageKey, archive).set(ImageAsset::getThumbnailKey, archiveThumbnail)
            .set(ImageAsset::getCleanupKey, old.getStorageKey()).set(ImageAsset::getCleanupThumbnailKey, old.getThumbnailKey()));
        if (archived != 1) throw new ServiceException("原图片替换状态保存失败");
        ImageRecords.ReplacementLog entry = new ImageRecords.ReplacementLog();
        entry.setId(IdWorker.getId()); entry.setOldImageId(old.getId()); entry.setNewImageId(replacement.getId());
        entry.setUserId(replacement.getCreateBy()); entry.setUserName(users.selectUserNameById(replacement.getCreateBy()));
        entry.setOldStorageKey(archive); entry.setNewStorageKey(target);
        records.insertReplacement(entry);
    }

    private void upsert(ImageAsset image) {
        vectors.upsert(image.getId(), image.getBatchId(), SscdFeatureExtractor.decode(image.getGlobalVector()),
            SscdFeatureExtractor.decode(image.getCenterVector()));
    }

    private void finishBatch(long id) {
        ImageBatch batch = batches.lockBatch(id);
        if (batch == null || !("CONFIRMING".equals(batch.getStatus()) || "WAIT_REVIEW".equals(batch.getStatus()))) return;
        if (count(id, "APPROVING") > 0) return;
        long failed = count(id, "APPROVAL_FAILED") + count(id, "FAILED");
        long review = count(id, "WAIT_REVIEW");
        String status = failed > 0 ? "FAILED" : review > 0 ? "WAIT_REVIEW" : "COMPLETED";
        // 同批次本轮入库完成后，在事务内持久化检测任务，保留原有批量检测分组。
        var admitted = assets.selectList(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getBatchId, id)
            .eq(ImageAsset::getStatus, "APPROVED").isNull(ImageAsset::getInfringementScanItemId).orderByAsc(ImageAsset::getId));
        if (!admitted.isEmpty()) infringement.createInitialTask(batch, admitted);
        batches.update(null, Wrappers.<ImageBatch>lambdaUpdate().eq(ImageBatch::getId, id)
            .set(ImageBatch::getStatus, status).set(ImageBatch::getFailedCount, Math.toIntExact(failed))
            .set(ImageBatch::getErrorMessage, failed > 0 ? failed + "张图片入库失败，请重试" : null));
        if ("COMPLETED".equals(status)) vectors.deleteBatch(id);
    }

    private long count(long batchId, String status) {
        return assets.selectCount(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getBatchId, batchId).eq(ImageAsset::getStatus, status));
    }
}
