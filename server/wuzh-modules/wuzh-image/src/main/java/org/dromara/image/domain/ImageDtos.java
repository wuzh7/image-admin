package org.dromara.image.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ImageDtos {

    private ImageDtos() {
    }

    public record CreateBatchRequest(
        @DecimalMin("0.50") @DecimalMax("0.99") BigDecimal similarityThreshold,
        @NotNull Long categoryId
    ) {
    }

    public record SaveCategoryRequest(
        @NotBlank @Size(max = 50) String name,
        @NotNull @Min(0) @Max(9999) Integer sortOrder,
        @NotNull @Pattern(regexp = "[01]") String status
    ) {
    }

    public record ConfirmBatchRequest(
        @NotNull @Size(max = 1000) List<Long> approvedIds
    ) {
    }

    @Data
    public static class ImageQuery {
        private String keyword;
        private Long categoryId;
        private Long batchId;
        private String aspectRatio;
    }

    public record CategoryView(Long id, String name, Integer sortOrder, String status) {
    }

    public record BatchView(
        Long id,
        Long categoryId,
        String categoryName,
        String status,
        BigDecimal similarityThreshold,
        Integer totalCount,
        Integer processedCount,
        Integer uniqueCount,
        Integer duplicateCount,
        Integer failedCount,
        String errorMessage,
        LocalDateTime createTime,
        List<ImageView> images
    ) {
    }

    public record BatchSummary(
        Long id,
        String creatorName,
        Long categoryId,
        String categoryName,
        String status,
        BigDecimal similarityThreshold,
        Integer totalCount,
        Integer processedCount,
        Integer uniqueCount,
        Integer duplicateCount,
        Integer failedCount,
        String errorMessage,
        LocalDateTime createTime
    ) {
    }

    public record ImageView(
        Long id,
        Long batchId,
        Long categoryId,
        String categoryName,
        String originalName,
        String displayName,
        String contentType,
        Long fileSize,
        Integer width,
        Integer height,
        String aspectRatio,
        String status,
        Boolean duplicate,
        Long duplicateOf,
        String duplicateName,
        String duplicateThumbnailUrl,
        String matchSource,
        BigDecimal similarity,
        String matchMethod,
        String thumbnailUrl,
        String originalUrl,
        boolean vectorIndexMissing,
        String errorMessage,
        LocalDateTime createTime
    ) {
    }

    public record HealthView(
        String status,
        String modelVersion,
        String modelSha256,
        boolean modelReady,
        String qdrantCollection,
        long qdrantPoints,
        long approvedImageCount,
        boolean indexCountMatched
    ) {
    }

    public record VectorRepairView(
        long approvedImageCount,
        long qdrantPointsBefore,
        int restoredPoints,
        int removedPoints
    ) {
    }
}
