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
    FAILED;

    public static ImageStatus forSimilarity(BigDecimal similarity, BigDecimal reviewThreshold) {
        return WAIT_REVIEW;
    }
}
