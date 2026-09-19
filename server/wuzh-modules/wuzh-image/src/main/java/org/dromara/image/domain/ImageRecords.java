package org.dromara.image.domain;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class ImageRecords {
    private ImageRecords() { }

    @Data
    public static class ReplacementLog {
        private Long id;
        private Long oldImageId;
        private Long newImageId;
        private Long userId;
        private String userName;
        private String oldStorageKey;
        private String newStorageKey;
        private LocalDateTime createTime;
    }

    @Data
    public static class DuplicateTodo {
        private Long id;
        private Long imageId;
        private Long matchedImageId;
        private BigDecimal similarity;
        private LocalDate scanDate;
        private String userName;
        private String matchedUserName;
        private boolean completed;
        private LocalDateTime createTime;
    }
}
