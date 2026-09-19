package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageSimilarityMatch;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.ImageStorage;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ImageDuplicateChecker {
    private final ImageAssetMapper assets;
    private final QdrantVectorStore vectors;
    private final SscdFeatureExtractor extractor;
    private final ImageStorage storage;

    public List<ImageSimilarityMatch> check(ImageAsset image, Long excludedId) {
        List<ImageAsset> exact = assets.selectList(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getStatus, "APPROVED").eq(ImageAsset::getSha256, image.getSha256())
            .ne(ImageAsset::getId, image.getId()).ne(excludedId != null, ImageAsset::getId, excludedId)
            .orderByAsc(ImageAsset::getId).last("LIMIT 1"));
        if (!exact.isEmpty()) return List.of(new ImageSimilarityMatch(exact.getFirst().getId(), BigDecimal.ONE, "LIBRARY"));
        List<float[]> queries = queries(image);
        List<Long> recalled = vectors.recall(queries, 32).stream()
            .filter(id -> !Objects.equals(id, image.getId()) && !Objects.equals(id, excludedId)).toList();
        if (recalled.isEmpty()) return List.of();
        return assets.selectByIds(recalled).stream().filter(candidate -> "APPROVED".equals(candidate.getStatus()))
            .map(candidate -> new ImageSimilarityMatch(candidate.getId(), BigDecimal.valueOf(Math.max(0, Math.min(1,
                SscdFeatureExtractor.bestScore(queries, SscdFeatureExtractor.decode(candidate.getGlobalVector()),
                    SscdFeatureExtractor.decode(candidate.getCenterVector()))))).setScale(5, RoundingMode.HALF_UP), "LIBRARY"))
            .sorted(Comparator.comparing(ImageSimilarityMatch::similarity).reversed().thenComparing(ImageSimilarityMatch::imageId))
            .limit(30).toList();
    }

    private List<float[]> queries(ImageAsset image) {
        if (image.getQueryVectors() != null) return SscdFeatureExtractor.decodeQueries(image.getQueryVectors());
        // 已有图片未保存查询视图时，只补算一次，仍使用当前算法的完整视图。
        try (ImageStorage.LocalFile file = storage.open(image.getStorageKey())) {
            var features = extractor.extract(file.path(), true);
            image.setQueryVectors(SscdFeatureExtractor.encodeQueries(features.queryVectors()));
            assets.update(null, Wrappers.<ImageAsset>lambdaUpdate().eq(ImageAsset::getId, image.getId())
                .set(ImageAsset::getQueryVectors, image.getQueryVectors()));
            return features.queryVectors();
        } catch (Exception exception) {
            throw new org.dromara.common.core.exception.ServiceException("最终查重失败，请重试：" + exception.getMessage());
        }
    }

    static boolean needsReview(ImageAsset image, List<ImageSimilarityMatch> latest, BigDecimal threshold) {
        List<ImageSimilarityMatch> reviewed = image.getSimilarMatchesJson() == null ? List.of()
            : JsonUtils.parseObject(image.getSimilarMatchesJson(), new TypeReference<List<ImageSimilarityMatch>>() { });
        return latest.stream().filter(match -> match.similarity().compareTo(threshold) >= 0).anyMatch(match ->
            !Boolean.TRUE.equals(image.getManuallyApproved()) || match.similarity().compareTo(new BigDecimal("0.95")) > 0
                || reviewed == null || reviewed.stream().noneMatch(old -> old.imageId().equals(match.imageId())
                    && old.similarity().compareTo(threshold) >= 0));
    }
}
