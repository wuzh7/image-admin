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
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
            .orderByAsc(ImageBatch::getCreateTime));
        batches.forEach(batch -> submit(batch.getId()));
    }

    public void recover() {
        List<ImageBatch> batches = batchMapper.selectList(Wrappers.<ImageBatch>lambdaQuery()
            .in(ImageBatch::getStatus, BatchStatus.QUEUED.name(), BatchStatus.CHECKING.name())
            .orderByAsc(ImageBatch::getCreateTime));
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
        try {
            batch.setStatus(BatchStatus.CHECKING.name());
            batch.setErrorMessage(null);
            batchMapper.updateById(batch);

            List<ImageAsset> assets = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
                .eq(ImageAsset::getBatchId, batchId)
                .orderByAsc(ImageAsset::getId));
            List<ImageAsset> pending = assets.stream()
                .filter(asset -> ImageStatus.UPLOADED.name().equals(asset.getStatus())
                    || ImageStatus.CHECKING.name().equals(asset.getStatus()))
                .toList();
            Map<String, ImageAsset> exactByHash = loadApprovedExactMatches(pending);
            List<BatchCandidate> batchCandidates = new ArrayList<>();
            Progress progress = new Progress();
            for (ImageAsset asset : assets) {
                if (isProcessed(asset)) {
                    progress.add(asset);
                    if (!ImageStatus.FAILED.name().equals(asset.getStatus())) {
                        addBatchCandidate(asset, null, exactByHash, batchCandidates);
                    }
                }
            }
            updateProgress(batch, progress);

            for (ImageAsset asset : pending) {
                SscdFeatureExtractor.Features features = processImage(batch, asset, exactByHash, batchCandidates);
                progress.add(asset);
                if (!ImageStatus.FAILED.name().equals(asset.getStatus())) {
                    addBatchCandidate(asset, features, exactByHash, batchCandidates);
                }
                updateProgress(batch, progress);
            }
            finishBatch(batch, progress);
        } catch (Exception exception) {
            log.error("图片批次执行失败 batchId={}", batchId, exception);
            batch = batchMapper.selectById(batchId);
            if (batch != null) {
                batch.setStatus(BatchStatus.FAILED.name());
                batch.setErrorMessage(message(exception));
                batchMapper.updateById(batch);
            }
        }
    }

    private SscdFeatureExtractor.Features processImage(ImageBatch batch, ImageAsset asset,
                                                        Map<String, ImageAsset> exactByHash,
                                                        List<BatchCandidate> batchCandidates) {
        asset.setStatus(ImageStatus.CHECKING.name());
        asset.setErrorMessage(null);
        assetMapper.updateById(asset);
        SscdFeatureExtractor.Features features = null;
        try {
            try (ImageStorage.LocalFile local = storage.open(asset.getStorageKey())) {
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
                asset.setGlobalVector(exact.getGlobalVector());
                asset.setCenterVector(exact.getCenterVector());
                asset.setFeatureVersion(exact.getFeatureVersion());
                asset.setDuplicateFlag(true);
                asset.setDuplicateOf(exact.getId());
                asset.setSimilarity(BigDecimal.ONE.setScale(5));
                asset.setMatchMethod("EXACT_HASH");
            } else {
                Path path = local.path();
                features = extractor.extract(path, true);
                asset.setGlobalVector(SscdFeatureExtractor.encode(features.globalVector()));
                asset.setCenterVector(SscdFeatureExtractor.encode(features.centerVector()));
                asset.setFeatureVersion(SscdFeatureExtractor.FEATURE_VERSION);
                Match match = better(findBatchMatch(batchCandidates, features), findApprovedMatch(features));
                BigDecimal finalScore = score(match.score());
                asset.setDuplicateOf(match.imageId());
                asset.setSimilarity(finalScore);
                asset.setDuplicateFlag(match.imageId() != null
                    && finalScore.compareTo(batch.getSimilarityThreshold()) >= 0);
                asset.setMatchMethod("SSCD");
            }
            asset.setStatus(ImageStatus.WAIT_REVIEW.name());
            asset.setDeletedAt(null);
            asset.setErrorMessage(null);
            }
        } catch (Exception exception) {
            log.warn("图片检测失败 imageId={}: {}", asset.getId(), exception.getMessage());
            asset.setStatus(ImageStatus.FAILED.name());
            asset.setDuplicateFlag(null);
            asset.setErrorMessage(message(exception));
        }
        assetMapper.updateById(asset);
        return ImageStatus.FAILED.name().equals(asset.getStatus()) ? null : features;
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

    private Match findApprovedMatch(SscdFeatureExtractor.Features features) {
        Set<Long> candidateIds = new LinkedHashSet<>(vectorStore.recall(features.queryVectors(), RECALL_LIMIT));
        if (candidateIds.isEmpty()) {
            return new Match(null, 0);
        }
        List<ImageAsset> candidates = assetMapper.selectByIds(candidateIds);
        double bestScore = 0;
        Long bestId = null;
        for (ImageAsset candidate : candidates) {
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

    private Match findBatchMatch(List<BatchCandidate> candidates, SscdFeatureExtractor.Features features) {
        Match best = new Match(null, 0);
        for (BatchCandidate candidate : candidates) {
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
        return best;
    }

    private static Match better(Match left, Match right) {
        return left.score() >= right.score() ? left : right;
    }

    private static void addBatchCandidate(ImageAsset asset, SscdFeatureExtractor.Features features,
                                          Map<String, ImageAsset> exactByHash,
                                          List<BatchCandidate> candidates) {
        if (asset.getGlobalVector() == null || asset.getCenterVector() == null) {
            return;
        }
        exactByHash.putIfAbsent(asset.getSha256(), asset);
        candidates.add(new BatchCandidate(asset, features));
    }

    private void updateProgress(ImageBatch batch, Progress progress) {
        batch.setProcessedCount(progress.processed);
        batch.setDuplicateCount(progress.duplicate);
        batch.setFailedCount(progress.failed);
        batchMapper.updateById(batch);
    }

    private void finishBatch(ImageBatch batch, Progress progress) {
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
        batchMapper.updateById(batch);
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

    private record Match(Long imageId, double score) {
    }

    private record BatchCandidate(ImageAsset asset, SscdFeatureExtractor.Features features) {
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
