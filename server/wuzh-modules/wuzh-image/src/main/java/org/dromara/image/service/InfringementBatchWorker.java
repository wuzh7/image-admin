package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.storage.ImageStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class InfringementBatchWorker {
    private static final int MAX_GROUP_IMAGES = 200;
    private static final int MAX_ACTIVE_JOBS = 2;
    private static final Duration SIGNED_URL_EXPIRY = Duration.ofHours(72);

    private final InfringementScanItemMapper items;
    private final ImageAssetMapper assets;
    private final ImageStorage storage;
    private final GeminiGatewayClient gateway;
    private final InfringementBatchQueue queue;
    private final InfringementCallLogger callLogger;

    @Scheduled(fixedDelay = 2000, scheduler = "imageInfringementBatchScheduler")
    public void submit() {
        failStaleSubmissions();
        String requestId = UUID.randomUUID().toString();
        List<InfringementScanItem> pending = queue.claimNext(requestId, MAX_GROUP_IMAGES, MAX_ACTIVE_JOBS);
        if (pending.isEmpty()) return;
        List<ClaimedImage> claimed = new ArrayList<>();
        for (InfringementScanItem item : pending) {
            ImageAsset image = assets.selectById(item.getImageId());
            if (image == null || !Objects.equals(image.getInfringementScanItemId(), item.getId())) {
                failProcessing(item, "IMAGE_NOT_CURRENT", "图片不存在或检测记录已被替换");
                continue;
            }
            String url;
            try {
                url = storage.signGetUrl(image.getStorageKey(), SIGNED_URL_EXPIRY).orElse(null);
            } catch (RuntimeException exception) {
                url = null;
                log.warn("Batch图片签名失败 itemId={}", item.getId(), exception);
            }
            if (url == null || url.isBlank()) {
                failProcessing(item, "BATCH_IMAGE_URL_FAILED", "无法生成Batch图片访问地址");
                continue;
            }
            claimed.add(new ClaimedImage(item,
                new GeminiGatewayClient.BatchImage(url, image.getContentType()), claimed.size()));
        }
        if (claimed.isEmpty()) return;
        try {
            for (ClaimedImage value : claimed) {
                if (items.setBatchRequestIndex(value.item().getId(), requestId, value.index()) != 1) {
                    throw new IllegalStateException("Batch请求序号保存失败");
                }
            }
            GeminiGatewayClient.BatchCreateResponse response = gateway.createBatch(requestId,
                claimed.stream().map(ClaimedImage::image).toList());
            if (items.bindBatchJob(requestId, response.providerJobId()) != claimed.size()) {
                throw new IllegalStateException("Batch作业绑定数量不一致");
            }
        } catch (GeminiGatewayClient.GatewayException exception) {
            claimed.forEach(value -> {
                logFailure(value.item(), requestId, exception.code(), exception.getMessage(),
                    exception.rawAiResponse(), exception.usage(), exception.rawProviderResponse());
                failProcessing(value.item(), exception.code(), exception.getMessage());
            });
        } catch (RuntimeException exception) {
            claimed.forEach(value -> {
                logFailure(value.item(), requestId, "BATCH_SUBMIT_UNKNOWN", "Batch提交结果未知，请手动重检");
                failProcessing(value.item(), "BATCH_SUBMIT_UNKNOWN", "Batch提交结果未知，请手动重检");
            });
            log.warn("Batch侵权检测提交失败 taskId={}", pending.getFirst().getTaskId(), exception);
        }
    }

    private void failStaleSubmissions() {
        LocalDateTime stale = LocalDateTime.now().minusMinutes(20);
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .eq(InfringementScanItem::getExecutionMode, "OFFICIAL_BATCH")
            .eq(InfringementScanItem::getCallType, "RISK_SCAN")
            .isNull(InfringementScanItem::getProviderJobId)
            .lt(InfringementScanItem::getStartedAt, stale)
            .set(InfringementScanItem::getStatus, InfringementService.FAILED)
            .set(InfringementScanItem::getRiskLevel, null)
            .set(InfringementScanItem::getRiskReason, null)
            .set(InfringementScanItem::getImageDescription, null)
            .set(InfringementScanItem::getSearchUsed, null)
            .set(InfringementScanItem::getSearchQueryCount, null)
            .set(InfringementScanItem::getErrorCode, "BATCH_SUBMIT_TIMEOUT")
            .set(InfringementScanItem::getErrorMessage, "Batch提交超时，请手动重检")
            .set(InfringementScanItem::getStartedAt, null)
            .set(InfringementScanItem::getFinishedAt, LocalDateTime.now()));
    }

    @Scheduled(fixedDelay = 30000, scheduler = "imageInfringementBatchPollScheduler")
    public void poll() {
        for (String jobId : items.selectProcessingBatchJobs(20)) {
            try {
                apply(jobId, gateway.getBatch(UUID.randomUUID().toString(), jobId));
            } catch (GeminiGatewayClient.GatewayException exception) {
                if (exception.retryable()) {
                    log.warn("Batch侵权检测状态查询暂时失败 jobId={}", jobId, exception);
                    continue;
                }
                items.selectProcessingBatchItems(jobId)
                    .forEach(item -> {
                        logFailure(item, item.getLastRequestId(), exception.code(), exception.getMessage(),
                            exception.rawAiResponse(), exception.usage(), exception.rawProviderResponse());
                        failProcessing(item, exception.code(), exception.getMessage());
                    });
                log.warn("Batch侵权检测状态查询永久失败 jobId={}", jobId, exception);
            } catch (RuntimeException exception) {
                log.warn("Batch侵权检测状态查询失败 jobId={}", jobId, exception);
            }
        }
    }

    void apply(String jobId, GeminiGatewayClient.BatchStatusResponse response) {
        if (List.of("QUEUED", "PENDING", "RUNNING", "CANCELLING", "PAUSED", "UPDATING", "UNKNOWN")
            .contains(response.state())) return;
        List<InfringementScanItem> jobItems = items.selectProcessingBatchItems(jobId);
        if (!List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED").contains(response.state())) {
            String message = response.errorMessage() == null || response.errorMessage().isBlank()
                ? "Gemini Batch作业" + response.state() : response.errorMessage();
            jobItems.forEach(item -> {
                logFailure(item, item.getLastRequestId(), "GEMINI_BATCH_" + response.state(), message,
                    null, null, response.rawProviderResponse());
                failProcessing(item, "GEMINI_BATCH_" + response.state(), message);
            });
            return;
        }
        Map<Integer, GeminiGatewayClient.BatchItemResponse> results = new HashMap<>();
        if (response.items() != null) response.items().forEach(result -> results.put(result.index(), result));
        for (InfringementScanItem item : jobItems) {
            GeminiGatewayClient.BatchItemResponse batchItem = results.get(item.getProviderRequestIndex());
            if (batchItem == null || batchItem.result() == null) {
                String code = batchItem == null ? "BATCH_RESULT_MISSING" : batchItem.errorCode();
                String message = batchItem == null ? "Gemini Batch未返回该图片结果" : batchItem.errorMessage();
                logFailure(item, item.getLastRequestId(), code, message,
                    batchItem == null ? null : batchItem.rawAiResponse(),
                    batchItem == null ? null : batchItem.usage(),
                    batchItem == null ? response.rawProviderResponse() : batchItem.rawProviderResponse());
                failProcessing(item, code, message);
            } else {
                complete(item, batchItem.result());
            }
        }
    }

    private void complete(InfringementScanItem item, GeminiGatewayClient.GatewayResponse result) {
        if (!List.of("HIGH", "LOW", "NEEDS_REVIEW").contains(result.riskLevel())) {
            logFailure(item, item.getLastRequestId(), "INVALID_RISK_LEVEL", "Gateway 风险等级不可识别",
                result.rawAiResponse(), result.usage(), result.rawProviderResponse());
            failProcessing(item, "INVALID_RISK_LEVEL", "Gateway 风险等级不可识别");
            return;
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        callLogger.success(item, item.getLastRequestId(), result, item.getStartedAt(), finishedAt);
        String riskLevel = InfringementRiskRules.riskLevel(result);
        boolean lookup = InfringementRiskRules.needsCaseLookup(result);
        if (!lookup && "LOW".equals(riskLevel) && !result.searchUsed() && item.getAttemptCount() < 2) {
            items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
                .eq(InfringementScanItem::getId, item.getId())
                .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
                .set(InfringementScanItem::getStatus, InfringementService.PENDING)
                .set(InfringementScanItem::getProviderJobId, null)
                .set(InfringementScanItem::getProviderRequestIndex, null)
                .set(InfringementScanItem::getStartedAt, null)
                .set(InfringementScanItem::getFinishedAt, null));
            return;
        }
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getId, item.getId())
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .eq(InfringementScanItem::getCallType, "RISK_SCAN")
            .set(InfringementScanItem::getStatus, lookup ? InfringementService.PENDING : InfringementService.SUCCESS)
            .set(InfringementScanItem::getCallType, lookup ? "CASE_LOOKUP" : "RISK_SCAN")
            .set(InfringementScanItem::getRiskLevel, riskLevel)
            .set(InfringementScanItem::getRiskReason, "LOW".equals(riskLevel) && !lookup ? null : result.riskReason())
            .set(InfringementScanItem::getCaseNumbersJson, InfringementRiskRules.casesJson(result.caseNumbers()))
            .set(InfringementScanItem::getImageDescription, result.imageDescription())
            .set(InfringementScanItem::getRawAiResponse, result.rawAiResponse())
            .set(InfringementScanItem::getModel, result.model())
            .set(InfringementScanItem::getPromptVersion, result.promptVersion())
            .set(InfringementScanItem::getSearchUsed, result.searchUsed())
            .set(InfringementScanItem::getSearchQueryCount, result.searchQueryCount())
            .set(InfringementScanItem::getErrorCode, null)
            .set(InfringementScanItem::getErrorMessage, null)
            .set(InfringementScanItem::getProviderJobId, lookup ? null : item.getProviderJobId())
            .set(InfringementScanItem::getProviderRequestIndex, lookup ? null : item.getProviderRequestIndex())
            .set(InfringementScanItem::getStartedAt, lookup ? null : item.getStartedAt())
            .set(InfringementScanItem::getFinishedAt, lookup ? null : finishedAt));
    }

    private void logFailure(InfringementScanItem item, String requestId, String code, String message) {
        callLogger.failure(item, requestId, code, errorMessage(message), item.getStartedAt(), LocalDateTime.now());
    }

    private void logFailure(InfringementScanItem item, String requestId, String code, String message,
                            String rawAiResponse, GeminiGatewayClient.UsageMetadata usage, String rawProviderResponse) {
        callLogger.failure(item, requestId, code, errorMessage(message), rawAiResponse, usage,
            rawProviderResponse, item.getStartedAt(), LocalDateTime.now());
    }

    private void failProcessing(InfringementScanItem item, String code, String message) {
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getId, item.getId())
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .set(InfringementScanItem::getStatus, InfringementService.FAILED)
            .set(InfringementScanItem::getRiskLevel, null)
            .set(InfringementScanItem::getRiskReason, null)
            .set(InfringementScanItem::getImageDescription, null)
            .set(InfringementScanItem::getSearchUsed, null)
            .set(InfringementScanItem::getSearchQueryCount, null)
            .set(InfringementScanItem::getErrorCode, code == null ? "GEMINI_BATCH_ITEM_FAILED" : code)
            .set(InfringementScanItem::getErrorMessage, errorMessage(message))
            .set(InfringementScanItem::getStartedAt, null)
            .set(InfringementScanItem::getFinishedAt, LocalDateTime.now()));
    }

    private static String errorMessage(String message) {
        String value = message == null || message.isBlank() ? "Gemini Batch处理失败" : message;
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    private record ClaimedImage(InfringementScanItem item, GeminiGatewayClient.BatchImage image, int index) {
    }
}
