package org.dromara.image.service;

import org.dromara.image.domain.ImageStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

@org.junit.jupiter.api.Tag("local")
class ImageTaskRunnerTest {

    @Test
    void everyValidDetectionResultWaitsForHumanReview() {
        assertEquals(ImageStatus.WAIT_REVIEW,
            ImageStatus.forSimilarity(new BigDecimal("0.10"), new BigDecimal("0.60")));
        assertEquals(ImageStatus.WAIT_REVIEW,
            ImageStatus.forSimilarity(new BigDecimal("0.80"), new BigDecimal("0.60")));
        assertEquals(ImageStatus.WAIT_REVIEW,
            ImageStatus.forSimilarity(BigDecimal.ONE, new BigDecimal("0.60")));
    }
}
