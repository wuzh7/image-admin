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
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class ImageDtos {

    private ImageDtos() {
    }

    public record CreateBatchRequest(
        @DecimalMin("0.50") @DecimalMax("0.99") BigDecimal similarityThreshold,
        @NotNull Long categoryId,
        @NotBlank @Pattern(regexp = "RELAY", message = "AI 分析渠道只支持中转站") String infringementMode
    ) {
    }

    public record SaveCategoryRequest(
        @NotBlank @Size(max = 50) String name,
        @Min(0) @Max(9999) Integer sortOrder,
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
        private Long imageId;
        private String userName;
        @Pattern(regexp = "HIGH|LOW|NEEDS_REVIEW")
        private String riskLevel;
        private LocalDateTime uploadStart;
        private LocalDateTime uploadEnd;
    }

    @Data
    @ExcelIgnoreUnannotated
    public static class LibraryExportRow {
        @ExcelProperty("图片ID") private Long imageId;
        @ExcelProperty("压缩包图片路径") private String zipPath;
        @ExcelProperty("原始文件名") private String originalName;
        @ExcelProperty("图片类目") private String categoryName;
        @ExcelProperty("批次ID") private Long batchId;
        @ExcelProperty("图片尺寸") private String dimensions;
        @ExcelProperty("图片比例") private String aspectRatio;
        @ExcelProperty("入库时间") private LocalDateTime approvedAt;
        @ExcelProperty("检测状态") private String infringementStatus;
        @ExcelProperty("风险等级") private String riskLevel;
        @ExcelProperty("风险原因/疑点") private String riskReason;
        @ExcelProperty("英文描述关键词") private String descriptionKeywords;
        @ExcelProperty("检测失败原因") private String infringementError;
    }

    public record CategoryView(Long id, String name, Integer sortOrder, String status) {
    }

    public record BatchView(
        Long id,
        Long categoryId,
        String categoryName,
        String status,
        String infringementMode,
        BigDecimal similarityThreshold,
        Integer totalCount,
        Integer processedCount,
        Integer uniqueCount,
        Integer duplicateCount,
        Integer failedCount,
        LocalDateTime detectionCompletedAt,
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
        String infringementMode,
        BigDecimal similarityThreshold,
        Integer totalCount,
        Integer processedCount,
        Integer uniqueCount,
        Integer duplicateCount,
        Integer failedCount,
        LocalDateTime detectionCompletedAt,
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
        String infringementDescriptionKeywords,
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
        List<SimilarImageView> similarImages,
        String matchMethod,
        String thumbnailUrl,
        String originalUrl,
        boolean vectorIndexMissing,
        String errorMessage,
        LocalDateTime approvedAt,
        String infringementStatus,
        String infringementPromptVersion,
        String infringementRiskLevel,
        String infringementRiskReason,
        List<String> infringementCaseNumbers,
        String infringementErrorMessage,
        Integer renderUseCount,
        LocalDateTime createTime,
        String uploaderName,
        List<String> subjects,
        List<String> scenes,
        List<String> themes,
        List<String> visualStyles,
        List<String> colors,
        boolean exported
    ) {
    }

    public record SimilarImageView(
        Long imageId,
        String name,
        String thumbnailUrl,
        String source,
        BigDecimal similarity
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
