package org.dromara.image.domain;

public enum BatchStatus {
    UPLOADING,
    QUEUED,
    CHECKING,
    WAIT_REVIEW,
    CONFIRMING,
    COMPLETED,
    FAILED
}
