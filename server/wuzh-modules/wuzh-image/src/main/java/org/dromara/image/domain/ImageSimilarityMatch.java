package org.dromara.image.domain;

import java.math.BigDecimal;

public record ImageSimilarityMatch(Long imageId, BigDecimal similarity, String source) {
}
