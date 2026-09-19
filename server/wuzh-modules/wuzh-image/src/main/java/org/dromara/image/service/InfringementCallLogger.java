package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.image.domain.InfringementCallLog;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.InfringementCallLogMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class InfringementCallLogger {
    private final InfringementCallLogMapper logs;

    public void success(InfringementScanItem item, String requestId,
                        GeminiGatewayClient.GatewayResponse result,
                        LocalDateTime startedAt, LocalDateTime finishedAt) {
        InfringementCallLog log = base(item, requestId, startedAt, finishedAt);
        log.setStatus("SUCCESS");
        log.setModel(result.model());
        log.setRiskLevel(normalizeRisk(result.riskLevel()));
        log.setRawAiResponse(result.rawAiResponse());
        log.setRawProviderResponse(result.rawProviderResponse());
        log.setSearchUsed(result.searchUsed());
        log.setSearchQueryCount(result.searchQueryCount());
        applyUsage(log, result.usage());
        insert(log);
    }

    public void failure(InfringementScanItem item, String requestId, String code, String message,
                        LocalDateTime startedAt, LocalDateTime finishedAt) {
        failure(item, requestId, code, message, null, null, startedAt, finishedAt);
    }

    public void failure(InfringementScanItem item, String requestId, String code, String message,
                        String rawAiResponse, GeminiGatewayClient.UsageMetadata usage,
                        LocalDateTime startedAt, LocalDateTime finishedAt) {
        failure(item, requestId, code, message, rawAiResponse, usage, null, startedAt, finishedAt);
    }

    public void failure(InfringementScanItem item, String requestId, String code, String message,
                        String rawAiResponse, GeminiGatewayClient.UsageMetadata usage, String rawProviderResponse,
                        LocalDateTime startedAt, LocalDateTime finishedAt) {
        InfringementCallLog log = base(item, requestId, startedAt, finishedAt);
        log.setStatus("FAILED");
        log.setErrorCode(code);
        log.setErrorMessage(limit(message, 1000));
        log.setRawAiResponse(rawAiResponse);
        log.setRawProviderResponse(rawProviderResponse);
        applyUsage(log, usage);
        insert(log);
    }

    private static void applyUsage(InfringementCallLog log, GeminiGatewayClient.UsageMetadata usage) {
        if (usage == null) return;
        log.setUsageMetadata(usage.rawJson());
        log.setPromptTokenCount(usage.promptTokenCount());
        log.setCachedContentTokenCount(usage.cachedContentTokenCount());
        log.setCandidatesTokenCount(usage.candidatesTokenCount());
        log.setThoughtsTokenCount(usage.thoughtsTokenCount());
        log.setToolUsePromptTokenCount(usage.toolUsePromptTokenCount());
        log.setTotalTokenCount(usage.totalTokenCount());
    }

    private void insert(InfringementCallLog callLog) {
        try {
            logs.insert(callLog);
        } catch (RuntimeException exception) {
            log.error("Gemini调用日志写入失败 taskId={} imageId={} requestId={}", callLog.getTaskId(),
                callLog.getImageId(), callLog.getRequestId(), exception);
        }
    }

    private static InfringementCallLog base(InfringementScanItem item, String requestId,
                                             LocalDateTime startedAt, LocalDateTime finishedAt) {
        InfringementCallLog log = new InfringementCallLog();
        log.setId(IdWorker.getId());
        log.setTaskId(item.getTaskId());
        log.setScanItemId(item.getId());
        log.setBatchId(item.getBatchId());
        log.setImageId(item.getImageId());
        log.setRequestId(requestId);
        log.setProviderJobId(item.getProviderJobId());
        log.setProviderRequestIndex(item.getProviderRequestIndex());
        log.setExecutionMode(item.getExecutionMode());
        log.setCallType(item.getCallType() == null ? "RISK_SCAN" : item.getCallType());
        if ("CASE_LOOKUP".equals(item.getCallType())) {
            log.setExecutionMode("RELAY".equals(item.getExecutionMode()) ? "RELAY" : "OFFICIAL_STANDARD");
            log.setProviderJobId(null);
            log.setProviderRequestIndex(null);
        }
        log.setStartedAt(startedAt);
        log.setFinishedAt(finishedAt);
        if (startedAt != null && finishedAt != null) {
            log.setDurationMs(Math.max(0, Duration.between(startedAt, finishedAt).toMillis()));
        }
        return log;
    }

    static String normalizeRisk(String riskLevel) {
        return "NEEDS_REVIEW".equalsIgnoreCase(riskLevel) ? "NEEDS_REVIEW" : riskLevel;
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
