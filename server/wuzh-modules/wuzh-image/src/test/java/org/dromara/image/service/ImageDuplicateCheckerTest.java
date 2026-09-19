package org.dromara.image.service;

import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageSimilarityMatch;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tags({@Tag("dev"), @Tag("local")})
class ImageDuplicateCheckerTest {
    @org.junit.jupiter.api.BeforeAll static void initializeJsonMapper() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("jsonMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        new org.dromara.common.core.utils.SpringUtils().postProcessBeanFactory(beans);
    }

    private final BigDecimal threshold = new BigDecimal("0.6");
    private List<ImageSimilarityMatch> matches(long id, String score) {
        return List.of(new ImageSimilarityMatch(id, new BigDecimal(score), "LIBRARY"));
    }
    @Test void manualApprovalCannotBypassNewOrNearIdenticalMatch() {
        ImageAsset image = new ImageAsset(); image.setManuallyApproved(true);
        image.setSimilarMatchesJson("[{\"imageId\":8,\"similarity\":0.8,\"source\":\"LIBRARY\"}]");
        assertFalse(ImageDuplicateChecker.needsReview(image, matches(8, "0.8"), threshold));
        assertTrue(ImageDuplicateChecker.needsReview(image, matches(9, "0.8"), threshold));
        assertTrue(ImageDuplicateChecker.needsReview(image, matches(8, "0.96"), threshold));
        assertTrue(ImageDuplicateChecker.needsReview(image, matches(8, "1"), threshold));
        assertFalse(ImageDuplicateChecker.needsReview(image, matches(9, "0.59"), threshold));
    }
    @Test void automaticAdmissionMustReviewAnyMatchAtThreshold() {
        ImageAsset image = new ImageAsset();
        assertTrue(ImageDuplicateChecker.needsReview(image, matches(8, "0.6"), threshold));
        assertFalse(ImageDuplicateChecker.needsReview(image, List.of(), threshold));
    }
    @Test void queryVectorSnapshotRoundTripsCurrentViewCountWithoutTruncation() {
        var views = java.util.stream.IntStream.range(0, SscdFeatureExtractor.queryViewCount()).mapToObj(index -> {
            float[] vector = new float[SscdFeatureExtractor.VECTOR_DIMENSION]; vector[0] = index; return vector;
        }).toList();
        var restored = SscdFeatureExtractor.decodeQueries(SscdFeatureExtractor.encodeQueries(views));
        assertEquals(views.size(), restored.size());
        for (int index = 0; index < views.size(); index++) assertArrayEquals(views.get(index), restored.get(index));
        assertThrows(IllegalArgumentException.class, () -> SscdFeatureExtractor.decodeQueries(new byte[3]));
    }
}
