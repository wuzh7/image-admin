package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.image.domain.BatchStatus;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageBatch;
import org.dromara.image.domain.ImageStatus;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.ImageStorage;
import org.dromara.image.storage.LocalImageStorage;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ImageAspectRatio;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ImageTaskRunner {

    private static final int RECALL_LIMIT = 30;
    private final ImageBatchMapper batchMapper;
    private final ImageAssetMapper assetMapper;
    private final ImageStorage storage;
    private final ImageProperties properties;
    private final SscdFeatureExtractor extractor;
    private final QdrantVectorStore vectorStore;
    private final TaskExecutor executor;
    private final Set<Long> activeBatches = ConcurrentHashMap.newKeySet();

    public ImageTaskRunner(ImageBatchMapper batchMapper, ImageAssetMapper assetMapper,
                           ImageStorage storage, SscdFeatureExtractor extractor,
                           QdrantVectorStore vectorStore,
                           @Qualifier("imageTaskExecutor") TaskExecutor executor, ImageProperties properties) {
        this.properties = properties;
        this.batchMapper = batchMapper;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.extractor = extractor;
        this.vectorStore = vectorStore;
        this.executor = executor;
    }

    public void submit(long batchId) {
        if (!activeBatches.add(batchId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    processBatch(batchId);
                } finally {
                    activeBatches.remove(batchId);
                }
            });
        } catch (RuntimeException exception) {
            activeBatches.remove(batchId);
            log.warn("图片批次暂未进入执行队列，将由后台调度重试 batchId={}: {}", batchId, exception.getMessage());
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void dispatchQueued() {
        List<ImageBatch> batches = batchMapper.selectList(Wrappers.<ImageBatch>lambdaQuery()
            .eq(ImageBatch::getStatus, BatchStatus.QUEUED.name())
            .orderByAsc(ImageBatch::getCreateTime)
            .orderByAsc(ImageBatch::getId));
        batches.forEach(batch -> submit(batch.getId()));
    }

    public void recover() {
        List<ImageBatch> batches = batchMapper.selectList(Wrappers.<ImageBatch>lambdaQuery()
            .in(ImageBatch::getStatus, BatchStatus.QUEUED.name(), BatchStatus.CHECKING.name())
            .orderByAsc(ImageBatch::getCreateTime)
            .orderByAsc(ImageBatch::getId));
        batches.forEach(batch -> submit(batch.getId()));
    }

    public boolean modelReady() {
        return extractor.isReady();
    }

    public String modelSha256() throws Exception {
        return extractor.modelSha256();
    }

    private void processBatch(long batchId) {
        ImageBatch batch = batchMapper.selectById(batchId);
        if (batch == null || (!BatchStatus.QUEUED.name().equals(batch.getStatus())
            && !BatchStatus.CHECKING.name().equals(batch.getStatus()))) {
            return;
        }
        BatchTiming timing = new BatchTiming();
        try {
            long started = System.nanoTime();
            batch.setStatus(BatchStatus.CHECKING.name());
            batch.setErrorMessage(null);
            batchMapper.updateById(batch);
            timing.persistNanos += System.nanoTime() - started;

            started = System.nanoTime();
            List<ImageAsset> assets = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
                .eq(ImageAsset::getBatchId, batchId)
                .orderByAsc(ImageAsset::getId));
            List<ImageAsset> pending = assets.stream()
                .filter(asset -> ImageStatus.UPLOADED.name().equals(asset.getStatus())
                    || ImageStatus.CHECKING.name().equals(asset.getStatus()))
                .toList();
            Map<String, ImageAsset> exactByHash = loadApprovedExactMatches(pending);
            timing.persistNanos += System.nanoTime() - started;
            Map<Long, BatchCandidate> batchCandidates = new HashMap<>();
            Progress progress = new Progress();
            for (ImageAsset asset : assets) {
                if (isProcessed(asset)) {
                    progress.add(asset);
                    if (!ImageStatus.FAILED.name().equals(asset.getStatus())) {
                        addBatchCandidate(asset, null, exactByHash, batchCandidates);
                    }
                }
            }
            updateProgress(batch, progress, timing);

            int featureBatchSize = Math.max(1, Math.min(8, properties.getFeatureBatchSize()));
            timing.featureBatchSize = featureBatchSize;
            timing.imageCount = pending.size();
            for (int offset = 0; offset < pending.size(); offset += featureBatchSize) {
                List<ImageAsset> group = new ArrayList<>(
                    pending.subList(offset, Math.min(offset + featureBatchSize, pending.size())));
                group.forEach(asset -> {
                    asset.setStatus(ImageStatus.CHECKING.name());
                    asset.setErrorMessage(null);
                });
                started = System.nanoTime();
                assetMapper.updateById(group);
                timing.persistNanos += System.nanoTime() - started;
                List<PreparedImage> featurePending = new ArrayList<>();
                List<DuplicatePending> duplicatePending = new ArrayList<>();
                Map<String, PreparedImage> ownersByHash = new HashMap<>();
                try {
                    for (ImageAsset asset : group) {
                        started = System.nanoTime();
                        PreparedImage prepared = prepareImage(asset, exactByHash);
                        timing.prepareNanos += System.nanoTime() - started;
                        if (prepared.local() != null) {
                            PreparedImage owner = ownersByHash.putIfAbsent(asset.getSha256(), prepared);
                            if (owner == null) {
                                featurePending.add(prepared);
                            } else {
                                try { prepared.local().close(); } catch (Exception ignored) { }
                                duplicatePending.add(new DuplicatePending(asset, owner.asset()));
                            }
                        } else {
                            progress.add(asset);
                            if (!ImageStatus.FAILED.name().equals(asset.getStatus())) {
                                addBatchCandidate(asset, null, exactByHash, batchCandidates);
                            }
                        }
                    }
                    processFeatureBatch(batch, featurePending, exactByHash, batchCandidates, progress, timing);
                    for (DuplicatePending duplicate : duplicatePending) {
                        ImageAsset asset = duplicate.asset();
                        ImageAsset owner = duplicate.owner();
                        if (ImageStatus.WAIT_REVIEW.name().equals(owner.getStatus())) {
                            copyExactResult(asset, owner);
                            addBatchCandidate(asset, null, exactByHash, batchCandidates);
                        } else {
                            fail(asset, new ServiceException("完全重复图片的原图处理失败"));
                        }
                        progress.add(asset);
                    }
                } finally {
                    closeLocalFiles(featurePending);
                }
                updateProgress(batch, progress, timing);
            }
            finishBatch(batch, progress, timing);
        } catch (Exception exception) {
            log.error("图片批次执行失败 batchId={}", batchId, exception);
            batch = batchMapper.selectById(batchId);
            if (batch != null) {
                batch.setStatus(BatchStatus.FAILED.name());
                batch.setErrorMessage(message(exception));
                batchMapper.updateById(batch);
            }
        } finally {
            timing.log(batchId);
        }
    }

    private PreparedImage prepareImage(ImageAsset asset, Map<String, ImageAsset> exactByHash) {
        ImageStorage.LocalFile local = null;
        try {
            local = storage.open(asset.getStorageKey());
            var validated = LocalImageStorage.validate(local.path(), asset.getOriginalName(), properties);
            if (!validated.contentType().equals(asset.getContentType()) || validated.fileSize() != asset.getFileSize()) {
                throw new ServiceException("图片内容与上传记录不一致");
            }
            asset.setSha256(validated.sha256());
            asset.setWidth(validated.width());
            asset.setHeight(validated.height());
            asset.setAspectRatio(ImageAspectRatio.format(validated.width(), validated.height()));
            ImageAsset exact = exactByHash.get(asset.getSha256());
            if (exact == null) {
                List<ImageAsset> matches = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
                    .eq(ImageAsset::getSha256, asset.getSha256()).eq(ImageAsset::getStatus, ImageStatus.APPROVED.name())
                    .isNotNull(ImageAsset::getGlobalVector).isNotNull(ImageAsset::getCenterVector).last("LIMIT 1"));
                if (!matches.isEmpty()) exact = matches.getFirst();
            }
            if (exact != null && exact.getGlobalVector() != null && exact.getCenterVector() != null) {
                copyExactResult(asset, exact);
                local.close();
                return new PreparedImage(asset, null);
            }
            return new PreparedImage(asset, local);
        } catch (Exception exception) {
            if (local != null) try { local.close(); } catch (Exception ignored) { }
            log.warn("图片检测失败 imageId={}: {}", asset.getId(), exception.getMessage());
            asset.setStatus(ImageStatus.FAILED.name());
            asset.setDuplicateFlag(null);
            asset.setErrorMessage(message(exception));
            assetMapper.updateById(asset);
            return new PreparedImage(asset, null);
        }
    }

    private void processFeatureBatch(ImageBatch batch, List<PreparedImage> pending,
                                     Map<String, ImageAsset> exactByHash,
                                     Map<Long, BatchCandidate> batchCandidates, Progress progress,
                                     BatchTiming timing) {
        if (pending.isEmpty()) return;
        List<SscdFeatureExtractor.Features> features;
        List<List<Long>> approvedRecall;
        List<List<Long>> batchRecall;
        Map<Long, ImageAsset> approvedCandidates;
        try {
            long started = System.nanoTime();
            features = extractor.extractBatch(
                pending.stream().map(item -> item.local().path()).toList(), true);
            timing.inferenceNanos += System.nanoTime() - started;

            List<List<float[]>> queryGroups = features.stream()
                .map(SscdFeatureExtractor.Features::queryVectors).toList();
            started = System.nanoTime();
            approvedRecall = vectorStore.recallMany(queryGroups, RECALL_LIMIT);
            batchRecall = vectorStore.recallBatchMany(batch.getId(), queryGroups, RECALL_LIMIT);
            timing.recallNanos += System.nanoTime() - started;

            started = System.nanoTime();
            approvedCandidates = loadApprovedCandidates(approvedRecall);
            timing.persistNanos += System.nanoTime() - started;
        } catch (Exception exception) {
            pending.forEach(item -> {
                fail(item.asset(), exception);
                progress.add(item.asset());
            });
            return;
        }

        List<FeatureResult> successful = new ArrayList<>(pending.size());
        List<BatchCandidate> earlierInGroup = new ArrayList<>(pending.size());
        long started = System.nanoTime();
        for (int index = 0; index < pending.size(); index++) {
            ImageAsset asset = pending.get(index).asset();
            SscdFeatureExtractor.Features current = features.get(index);
            try {
                asset.setGlobalVector(SscdFeatureExtractor.encode(current.globalVector()));
                asset.setCenterVector(SscdFeatureExtractor.encode(current.centerVector()));
                asset.setFeatureVersion(SscdFeatureExtractor.FEATURE_VERSION);
                Match match = better(
                    findBatchMatch(batchRecall.get(index), batchCandidates, current),
                    findApprovedMatch(approvedRecall.get(index), approvedCandidates, current));
                match = better(match, findInMemoryMatch(earlierInGroup, current));
                BigDecimal finalScore = score(match.score());
                asset.setDuplicateOf(match.imageId());
                asset.setSimilarity(finalScore);
                asset.setDuplicateFlag(match.imageId() != null
                    && finalScore.compareTo(batch.getSimilarityThreshold()) >= 0);
                asset.setMatchMethod("SSCD");
                asset.setStatus(ImageStatus.WAIT_REVIEW.name());
                asset.setDeletedAt(null);
                asset.setErrorMessage(null);
                FeatureResult result = new FeatureResult(asset, current);
                successful.add(result);
                earlierInGroup.add(new BatchCandidate(asset, current));
            } catch (Exception exception) {
                fail(asset, exception);
                progress.add(asset);
            }
        }
        timing.refineNanos += System.nanoTime() - started;

        if (successful.isEmpty()) {
            return;
        }
        List<Long> stagingIds = successful.stream().map(item -> item.asset().getId()).toList();
        try {
            started = System.nanoTime();
            assetMapper.updateById(successful.stream().map(FeatureResult::asset).toList());
            timing.persistNanos += System.nanoTime() - started;

            started = System.nanoTime();
            vectorStore.upsertBatch(successful.stream()
                .map(item -> new QdrantVectorStore.StagingPoint(
                    item.asset().getId(), item.asset().getBatchId(), item.features()))
                .toList());
            timing.indexNanos += System.nanoTime() - started;
            for (FeatureResult result : successful) {
                addBatchCandidate(result.asset(), result.features(), exactByHash, batchCandidates);
                progress.add(result.asset());
            }
        } catch (Exception exception) {
            try {
                vectorStore.deleteBatchPoints(stagingIds);
            } catch (Exception cleanupException) {
                log.error("清理无效批内向量失败 imageIds={}", stagingIds, cleanupException);
            }
            successful.forEach(result -> {
                try {
                    fail(result.asset(), exception);
                    progress.add(result.asset());
                } catch (Exception persistException) {
                    log.error("保存图片失败状态失败 imageId={}", result.asset().getId(), persistException);
                }
            });
        }
    }

    static void closeLocalFiles(List<PreparedImage> pending) {
        pending.forEach(item -> {
            try { item.local().close(); } catch (Exception ignored) { }
        });
    }

    private void fail(ImageAsset asset, Exception exception) {
        log.warn("图片检测失败 imageId={}: {}", asset.getId(), exception.getMessage());
        asset.setStatus(ImageStatus.FAILED.name());
        asset.setDuplicateFlag(null);
        asset.setErrorMessage(message(exception));
        assetMapper.updateById(asset);
    }

    private void copyExactResult(ImageAsset asset, ImageAsset exact) {
        asset.setGlobalVector(exact.getGlobalVector());
        asset.setCenterVector(exact.getCenterVector());
        asset.setFeatureVersion(exact.getFeatureVersion());
        asset.setDuplicateFlag(true);
        asset.setDuplicateOf(exact.getId());
        asset.setSimilarity(BigDecimal.ONE.setScale(5));
        asset.setMatchMethod("EXACT_HASH");
        asset.setStatus(ImageStatus.WAIT_REVIEW.name());
        asset.setDeletedAt(null);
        asset.setErrorMessage(null);
        assetMapper.updateById(asset);
    }

    private Map<String, ImageAsset> loadApprovedExactMatches(List<ImageAsset> pending) {
        Set<String> hashes = new LinkedHashSet<>();
        pending.forEach(asset -> hashes.add(asset.getSha256()));
        Map<String, ImageAsset> result = new HashMap<>();
        if (hashes.isEmpty()) {
            return result;
        }
        List<ImageAsset> approved = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
            .in(ImageAsset::getSha256, hashes)
            .eq(ImageAsset::getStatus, ImageStatus.APPROVED.name())
            .isNotNull(ImageAsset::getGlobalVector)
            .isNotNull(ImageAsset::getCenterVector)
            .orderByAsc(ImageAsset::getId)
        );
        approved.forEach(asset -> result.putIfAbsent(asset.getSha256(), asset));
        return result;
    }

    private Match findApprovedMatch(List<Long> recalledIds, Map<Long, ImageAsset> candidatesById,
                                    SscdFeatureExtractor.Features features) {
        Set<Long> candidateIds = new LinkedHashSet<>(recalledIds);
        if (candidateIds.isEmpty()) {
            return new Match(null, 0);
        }
        double bestScore = 0;
        Long bestId = null;
        for (Long candidateId : candidateIds) {
            ImageAsset candidate = candidatesById.get(candidateId);
            if (candidate == null) continue;
            if (!ImageStatus.APPROVED.name().equals(candidate.getStatus())
                || candidate.getGlobalVector() == null || candidate.getCenterVector() == null) {
                continue;
            }
            double current = SscdFeatureExtractor.bestScore(features.queryVectors(),
                SscdFeatureExtractor.decode(candidate.getGlobalVector()),
                SscdFeatureExtractor.decode(candidate.getCenterVector()));
            if (current > bestScore) {
                bestScore = current;
                bestId = candidate.getId();
            }
        }
        return new Match(bestId, bestScore);
    }

    static Match findBatchMatch(List<Long> recalledIds, Map<Long, BatchCandidate> candidates,
                                SscdFeatureExtractor.Features features) {
        Match best = new Match(null, 0);
        Set<Long> recalled = new LinkedHashSet<>(recalledIds);
        for (Long candidateId : recalledIds) {
            BatchCandidate candidate = candidates.get(candidateId);
            if (candidate == null) continue;
            double current;
            if (candidate.features() != null) {
                current = SscdFeatureExtractor.symmetricBestScore(features, candidate.features());
            } else {
                current = SscdFeatureExtractor.bestScore(features.queryVectors(),
                    SscdFeatureExtractor.decode(candidate.asset().getGlobalVector()),
                    SscdFeatureExtractor.decode(candidate.asset().getCenterVector()));
            }
            if (current > best.score()) {
                best = new Match(candidate.asset().getId(), current);
            }
        }
        for (BatchCandidate candidate : candidates.values()) {
            if (candidate.features() != null || recalled.contains(candidate.asset().getId())) continue;
            double current = SscdFeatureExtractor.bestScore(features.queryVectors(),
                SscdFeatureExtractor.decode(candidate.asset().getGlobalVector()),
                SscdFeatureExtractor.decode(candidate.asset().getCenterVector()));
            if (current > best.score()) best = new Match(candidate.asset().getId(), current);
        }
        return best;
    }

    private static Match findInMemoryMatch(List<BatchCandidate> candidates,
                                           SscdFeatureExtractor.Features features) {
        Match best = new Match(null, 0);
        for (BatchCandidate candidate : candidates) {
            double current = SscdFeatureExtractor.symmetricBestScore(features, candidate.features());
            if (current > best.score()) best = new Match(candidate.asset().getId(), current);
        }
        return best;
    }

    private Map<Long, ImageAsset> loadApprovedCandidates(List<List<Long>> recalledGroups) {
        Set<Long> ids = new LinkedHashSet<>();
        recalledGroups.forEach(ids::addAll);
        if (ids.isEmpty()) return Map.of();
        Map<Long, ImageAsset> result = new HashMap<>();
        assetMapper.selectByIds(ids).forEach(asset -> result.put(asset.getId(), asset));
        return result;
    }

    private static Match better(Match left, Match right) {
        return left.score() >= right.score() ? left : right;
    }

    private void addBatchCandidate(ImageAsset asset, SscdFeatureExtractor.Features features,
                                   Map<String, ImageAsset> exactByHash,
                                   Map<Long, BatchCandidate> candidates) {
        if (asset.getGlobalVector() == null || asset.getCenterVector() == null) {
            return;
        }
        exactByHash.putIfAbsent(asset.getSha256(), asset);
        candidates.put(asset.getId(), new BatchCandidate(asset, features));
    }

    private void updateProgress(ImageBatch batch, Progress progress, BatchTiming timing) {
        long started = System.nanoTime();
        batch.setProcessedCount(progress.processed);
        batch.setDuplicateCount(progress.duplicate);
        batch.setFailedCount(progress.failed);
        batchMapper.updateById(batch);
        timing.persistNanos += System.nanoTime() - started;
    }

    private void finishBatch(ImageBatch batch, Progress progress, BatchTiming timing) {
        if (progress.failed > 0) {
            batch.setStatus(BatchStatus.FAILED.name());
            batch.setErrorMessage("有" + progress.failed + "张图片检测失败，请查看原因后重试");
        } else if (progress.review > 0) {
            batch.setStatus(BatchStatus.WAIT_REVIEW.name());
            batch.setErrorMessage(null);
        } else {
            batch.setStatus(BatchStatus.COMPLETED.name());
            batch.setErrorMessage(null);
        }
        long started = System.nanoTime();
        batchMapper.updateById(batch);
        timing.persistNanos += System.nanoTime() - started;
    }

    private static BigDecimal score(double value) {
        return BigDecimal.valueOf(Math.max(0, Math.min(1, value))).setScale(5, RoundingMode.HALF_UP);
    }

    private static String message(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 1900 ? message.substring(0, 1900) : message;
    }

    private static boolean isProcessed(ImageAsset asset) {
        return ImageStatus.WAIT_REVIEW.name().equals(asset.getStatus())
            || ImageStatus.APPROVED.name().equals(asset.getStatus())
            || ImageStatus.REJECTED.name().equals(asset.getStatus())
            || ImageStatus.FAILED.name().equals(asset.getStatus());
    }

    record Match(Long imageId, double score) {
    }

    record BatchCandidate(ImageAsset asset, SscdFeatureExtractor.Features features) {
    }

    record PreparedImage(ImageAsset asset, ImageStorage.LocalFile local) {
    }

    private record DuplicatePending(ImageAsset asset, ImageAsset owner) {
    }

    private record FeatureResult(ImageAsset asset, SscdFeatureExtractor.Features features) {
    }

    private static final class BatchTiming {
        private final long startedNanos = System.nanoTime();
        private int imageCount;
        private int featureBatchSize = 1;
        private long prepareNanos;
        private long inferenceNanos;
        private long recallNanos;
        private long refineNanos;
        private long indexNanos;
        private long persistNanos;

        private void log(long batchId) {
            long totalNanos = System.nanoTime() - startedNanos;
            double imagesPerMinute = imageCount == 0 ? 0
                : imageCount * 60_000_000_000d / totalNanos;
            log.info(String.format(Locale.ROOT,
                "图片批次性能 batchId=%d images=%d featureBatchSize=%d totalMs=%d prepareMs=%d "
                    + "inferenceMs=%d recallMs=%d refineMs=%d indexMs=%d persistMs=%d imagesPerMinute=%.2f",
                batchId, imageCount, featureBatchSize, millis(totalNanos), millis(prepareNanos),
                millis(inferenceNanos), millis(recallNanos), millis(refineNanos), millis(indexNanos),
                millis(persistNanos), imagesPerMinute));
        }

        private static long millis(long nanos) {
            return nanos / 1_000_000;
        }
    }

    private static final class Progress {
        private int processed;
        private int duplicate;
        private int failed;
        private int review;

        private void add(ImageAsset asset) {
            if (ImageStatus.WAIT_REVIEW.name().equals(asset.getStatus())
                || ImageStatus.APPROVED.name().equals(asset.getStatus())
                || ImageStatus.REJECTED.name().equals(asset.getStatus())) {
                processed++;
                if (ImageStatus.WAIT_REVIEW.name().equals(asset.getStatus())) {
                    review++;
                }
                if (Boolean.TRUE.equals(asset.getDuplicateFlag())) {
                    duplicate++;
                }
            } else if (ImageStatus.FAILED.name().equals(asset.getStatus())) {
                processed++;
                failed++;
            }
        }
    }
}
