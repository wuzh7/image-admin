package org.dromara.image.domain;

import java.math.BigDecimal;

public enum ImageStatus {
    UPLOADING,
    UPLOADED,
    CHECKING,
    WAIT_REVIEW,
    APPROVING,
    APPROVAL_FAILED,
    DELETE_PENDING,
    APPROVED,
    REJECTED,
    REPLACED,
    FAILED;

    public static ImageStatus forSimilarity(BigDecimal similarity, BigDecimal reviewThreshold) {
        return similarity.compareTo(reviewThreshold) < 0 ? APPROVING : WAIT_REVIEW;
    }
}
