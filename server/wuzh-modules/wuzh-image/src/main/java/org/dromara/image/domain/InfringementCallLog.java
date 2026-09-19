package org.dromara.image.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("infringement_call_log")
public class InfringementCallLog {
    @TableId
    private Long id;
    private Long taskId;
    private Long scanItemId;
    private Long batchId;
    private Long imageId;
    private String requestId;
    private String providerJobId;
    private Integer providerRequestIndex;
    private String executionMode;
    private String model;
    private String status;
    private String riskLevel;
    private String callType;
    private String rawAiResponse;
    private String rawProviderResponse;
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
