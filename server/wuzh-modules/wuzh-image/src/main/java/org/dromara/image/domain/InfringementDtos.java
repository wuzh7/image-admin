package org.dromara.image.domain;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public final class InfringementDtos {
    private InfringementDtos() {
    }

    public record RetryResult(Long taskId, int imageCount) {
    }

    public record TaskSummary(
        Long id,
        String taskNo,
        String taskType,
        Long sourceBatchId,
        String creatorName,
        String infringementMode,
        String status,
        long totalCount,
        long completedCount,
        long highRiskCount,
        long lowRiskCount,
        long needsReviewCount,
        long failedCount,
        LocalDateTime createTime
    ) {
    }

    public record ItemView(
        Long id,
        Long imageId,
        String imageName,
        String thumbnailUrl,
        String imageStatus,
        String status,
        String riskLevel,
        String riskReason,
        Integer confidence,
        List<String> detectedElements,
        String imageDescription,
        String model,
        String promptVersion,
        String executionMode,
        Boolean searchUsed,
        Integer searchQueryCount,
        Integer attemptCount,
        String errorMessage,
        LocalDateTime finishedAt,
        List<String> subjects,
        List<String> scenes,
        List<String> themes,
        List<String> visualStyles,
        List<String> colors,
        List<String> caseNumbers
    ) {
    }

    public record CallLogView(
        Long id, Long taskId, Long scanItemId, Long batchId, Long imageId, String imageName,
        String requestId, String providerJobId, Integer providerRequestIndex,
        String executionMode, String model, String status, String riskLevel, String callType,
        String rawAiResponse, String usageMetadata,
        Integer promptTokenCount, Integer cachedContentTokenCount, Integer candidatesTokenCount,
        Integer thoughtsTokenCount, Integer toolUsePromptTokenCount, Integer totalTokenCount,
        Boolean searchUsed, Integer searchQueryCount, Long durationMs,
        String errorCode, String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt
    ) {
    }

    public record CallResponseView(Long id, String rawProviderResponse) {
    }

    @Data
    public static class TaskStats {
        private Long taskId;
        private Long totalCount;
        private Long successCount;
        private Long processingCount;
        private Long highRiskCount;
        private Long lowRiskCount;
        private Long needsReviewCount;
        private Long failedCount;
    }

    @Data
    public static class ItemRow {
        private Long id;
        private Long imageId;
        private String imageName;
        private String imageStatus;
        private String status;
        private String riskLevel;
        private String riskReason;
        private String caseNumbersJson;
        private String imageDescription;
        private String rawAiResponse;
        private String model;
        private String promptVersion;
        private String executionMode;
        private Boolean searchUsed;
        private Integer searchQueryCount;
        private Integer attemptCount;
        private String errorMessage;
        private LocalDateTime finishedAt;
    }

    @Data
    public static class CallLogRow {
        private String callType;
        private Long id;
        private Long taskId;
        private Long scanItemId;
        private Long batchId;
        private Long imageId;
        private String imageName;
        private String requestId;
        private String providerJobId;
        private Integer providerRequestIndex;
        private String executionMode;
        private String model;
        private String status;
        private String riskLevel;
        private String rawAiResponse;
        private String usageMetadata;
        private Integer promptTokenCount;
        private Integer cachedContentTokenCount;
        private Integer candidatesTokenCount;
        private Integer thoughtsTokenCount;
        private Integer toolUsePromptTokenCount;
        private Integer totalTokenCount;
        private Boolean searchUsed;
        private Integer searchQueryCount;
        private Long durationMs;
        private String errorCode;
        private String errorMessage;
        private LocalDateTime startedAt;
        private LocalDateTime finishedAt;
    }
}
