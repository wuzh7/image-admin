package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.storage.ImageStorage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Component
public class InfringementWorker {
    private final InfringementScanItemMapper items;
    private final ImageAssetMapper assets;
    private final ImageStorage storage;
    private final GeminiGatewayClient gateway;
    private final InfringementCallLogger callLogger;
    private final ThreadPoolTaskExecutor imageRelayInfringementExecutor;
    private final ThreadPoolTaskExecutor imageInfringementExecutor;

    public InfringementWorker(InfringementScanItemMapper items, ImageAssetMapper assets, ImageStorage storage,
                              GeminiGatewayClient gateway, InfringementCallLogger callLogger,
                              @Qualifier("imageRelayInfringementExecutor") ThreadPoolTaskExecutor relayExecutor,
                              @Qualifier("imageInfringementExecutor") ThreadPoolTaskExecutor officialExecutor) {
        this.items = items;
        this.assets = assets;
        this.storage = storage;
        this.gateway = gateway;
        this.callLogger = callLogger;
        this.imageRelayInfringementExecutor = relayExecutor;
        this.imageInfringementExecutor = officialExecutor;
    }

    @Scheduled(fixedDelay = 200, scheduler = "imageInfringementScheduler")
    public void dispatch() {
        LocalDateTime stale = LocalDateTime.now().minusMinutes(20);
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .eq(InfringementScanItem::getCallType, "CASE_LOOKUP")
            .lt(InfringementScanItem::getStartedAt, stale)
            .set(InfringementScanItem::getStatus, InfringementService.SUCCESS)
            .set(InfringementScanItem::getRiskLevel, "NEEDS_REVIEW")
            .set(InfringementScanItem::getErrorCode, "CASE_LOOKUP_TIMEOUT")
            .set(InfringementScanItem::getErrorMessage, "案号补查超时，保留疑似侵权结果")
            .set(InfringementScanItem::getFinishedAt, LocalDateTime.now()));
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .eq(InfringementScanItem::getCallType, "RISK_SCAN")
            .ne(InfringementScanItem::getExecutionMode, "OFFICIAL_BATCH")
            .isNull(InfringementScanItem::getProviderJobId)
            .lt(InfringementScanItem::getStartedAt, stale)
            .set(InfringementScanItem::getStatus, InfringementService.FAILED)
            .set(InfringementScanItem::getRiskLevel, null)
            .set(InfringementScanItem::getRiskReason, null)
            .set(InfringementScanItem::getImageDescription, null)
            .set(InfringementScanItem::getSearchUsed, null)
            .set(InfringementScanItem::getSearchQueryCount, null)
            .set(InfringementScanItem::getErrorCode, "PROCESSING_TIMEOUT")
            .set(InfringementScanItem::getErrorMessage, "检测进程超时")
            .set(InfringementScanItem::getStartedAt, null)
            .set(InfringementScanItem::getFinishedAt, LocalDateTime.now()));
        dispatch(imageRelayInfringementExecutor, true);
        dispatch(imageInfringementExecutor, false);
    }

    private void dispatch(ThreadPoolTaskExecutor executor, boolean relay) {
        int availableWorkers = executor.getMaxPoolSize() - executor.getActiveCount();
        if (availableWorkers <= 0) return;
        var pending = relay ? items.selectPendingRelay(availableWorkers) : items.selectPendingOfficial(availableWorkers);
        for (InfringementScanItem item : pending) {
            int claimed = items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
                .eq(InfringementScanItem::getId, item.getId())
                .eq(InfringementScanItem::getStatus, InfringementService.PENDING)
                .eq(InfringementScanItem::getCallType, item.getCallType())
                .set(InfringementScanItem::getStatus, InfringementService.PROCESSING)
                .set(InfringementScanItem::getAttemptCount, item.getAttemptCount() + 1)
                .set(InfringementScanItem::getStartedAt, LocalDateTime.now()));
            if (claimed == 1) {
                item.setAttemptCount(item.getAttemptCount() + 1);
                executor.execute(() -> process(item));
            }
        }
    }

    void process(InfringementScanItem item) {
        ImageAsset image = assets.selectById(item.getImageId());
        if (image == null || !Objects.equals(image.getInfringementScanItemId(), item.getId())) {
            fail(item, "IMAGE_NOT_CURRENT", "图片不存在或检测记录已被替换");
            return;
        }
        byte[] bytes;
        try {
            bytes = readWithOneKeyRefresh(image);
        } catch (RuntimeException exception) {
            if ("CASE_LOOKUP".equals(item.getCallType())) {
                finishLookup(item, java.util.List.of(), false, 0, "IMAGE_READ_FAILED", "案号补查图片读取失败", null);
                return;
            }
            fail(item, "IMAGE_READ_FAILED", "图片读取失败");
            log.warn("侵权检测读取失败 itemId={}", item.getId(), exception);
            return;
        }
        if ("CASE_LOOKUP".equals(item.getCallType())) {
            lookupCases(item, image, bytes);
            return;
        }
        String requestId = UUID.randomUUID().toString();
        GeminiGatewayClient.GatewayResponse result;
        LocalDateTime callStartedAt = LocalDateTime.now();
        try {
            result = gateway.analyze(requestId, bytes,
                image.getContentType(), image.getOriginalName(), item.getInfringementMode());
        } catch (GeminiGatewayClient.GatewayException exception) {
            callLogger.failure(item, requestId, exception.code(), exception.getMessage(), exception.rawAiResponse(),
                exception.usage(), exception.rawProviderResponse(), callStartedAt, LocalDateTime.now());
            fail(item, requestId, exception.code(), exception.getMessage());
            return;
        } catch (RuntimeException exception) {
            callLogger.failure(item, requestId, "GATEWAY_RESPONSE_ERROR", "AI Gateway 响应异常或超时",
                callStartedAt, LocalDateTime.now());
            fail(item, requestId, "GATEWAY_RESPONSE_ERROR", "AI Gateway 响应异常或超时");
            log.warn("侵权检测 Gateway 响应失败 itemId={}", item.getId(), exception);
            return;
        }
        if (!"HIGH".equals(result.riskLevel()) && !"LOW".equals(result.riskLevel())
            && !"NEEDS_REVIEW".equals(result.riskLevel())) {
            callLogger.failure(item, requestId, "INVALID_RISK_LEVEL", "Gateway 风险等级不可识别",
                result.rawAiResponse(), result.usage(), result.rawProviderResponse(), callStartedAt, LocalDateTime.now());
            fail(item, requestId, "INVALID_RISK_LEVEL", "Gateway 风险等级不可识别");
            return;
        }
        LocalDateTime callFinishedAt = LocalDateTime.now();
        callLogger.success(item, requestId, result, callStartedAt, callFinishedAt);
        boolean relayPipeline = "RELAY".equals(item.getExecutionMode())
            && "relay_pipeline_v1".equals(result.promptVersion());
        String riskLevel = relayPipeline ? result.riskLevel() : InfringementRiskRules.riskLevel(result);
        boolean lookup = !relayPipeline && InfringementRiskRules.needsCaseLookup(result);
        if (!relayPipeline && !lookup && "LOW".equals(riskLevel) && !result.searchUsed()
            && item.getAttemptCount() < 2) {
            items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
                .eq(InfringementScanItem::getId, item.getId())
                .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
                .set(InfringementScanItem::getStatus, InfringementService.PENDING)
                .set(InfringementScanItem::getLastRequestId, requestId)
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
            .set(InfringementScanItem::getRiskReason, !relayPipeline && "LOW".equals(riskLevel) && !lookup ? null : result.riskReason())
            .set(InfringementScanItem::getCaseNumbersJson, InfringementRiskRules.casesJson(result.caseNumbers()))
            .set(InfringementScanItem::getImageDescription, result.imageDescription())
            .set(InfringementScanItem::getRawAiResponse, result.rawAiResponse())
            .set(InfringementScanItem::getModel, result.model())
            .set(InfringementScanItem::getPromptVersion, result.promptVersion())
            .set(InfringementScanItem::getSearchUsed, result.searchUsed())
            .set(InfringementScanItem::getSearchQueryCount, result.searchQueryCount())
            .set(InfringementScanItem::getLastRequestId, requestId)
            .set(InfringementScanItem::getErrorCode, null)
            .set(InfringementScanItem::getErrorMessage, null)
            .set(InfringementScanItem::getStartedAt, lookup ? null : callStartedAt)
            .set(InfringementScanItem::getFinishedAt, lookup ? null : LocalDateTime.now()));
    }

    private void lookupCases(InfringementScanItem item, ImageAsset image, byte[] bytes) {
        String requestId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            String mode = "RELAY".equals(item.getExecutionMode()) ? "RELAY" : "OFFICIAL_STANDARD";
            var result = gateway.lookupCases(requestId, bytes, image.getContentType(), image.getOriginalName(),
                mode, item.getRawAiResponse());
            var cases = result.caseNumbers().stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
            callLogger.success(item, requestId, new GeminiGatewayClient.GatewayResponse(requestId,
                cases.isEmpty() ? item.getRiskLevel() : "HIGH", item.getRiskReason(), item.getImageDescription(),
                result.rawAiResponse(), result.model(), item.getPromptVersion(), result.searchUsed(),
                result.searchQueryCount(), result.usage(), cases, result.rawProviderResponse()), startedAt, LocalDateTime.now());
            finishLookup(item, cases, result.searchUsed(), result.searchQueryCount(), null, null, requestId);
        } catch (GeminiGatewayClient.GatewayException exception) {
            callLogger.failure(item, requestId, exception.code(), exception.getMessage(), exception.rawAiResponse(),
                exception.usage(), exception.rawProviderResponse(), startedAt, LocalDateTime.now());
            finishLookup(item, java.util.List.of(), false, 0, exception.code(), exception.getMessage(), requestId);
        } catch (RuntimeException exception) {
            callLogger.failure(item, requestId, "CASE_LOOKUP_ERROR", "案号补查失败", startedAt, LocalDateTime.now());
            finishLookup(item, java.util.List.of(), false, 0, "CASE_LOOKUP_ERROR", "案号补查失败", requestId);
            log.warn("案号补查失败 itemId={}", item.getId(), exception);
        }
    }

    private void finishLookup(InfringementScanItem item, java.util.List<String> cases, boolean searchUsed,
                              int queryCount, String errorCode, String errorMessage, String requestId) {
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getId, item.getId())
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .eq(InfringementScanItem::getCallType, "CASE_LOOKUP")
            .set(InfringementScanItem::getStatus, InfringementService.SUCCESS)
            .set(!cases.isEmpty(), InfringementScanItem::getRiskLevel, "HIGH")
            .set(!cases.isEmpty(), InfringementScanItem::getCaseNumbersJson, InfringementRiskRules.casesJson(cases))
            .set(InfringementScanItem::getSearchUsed, Boolean.TRUE.equals(item.getSearchUsed()) || searchUsed)
            .set(InfringementScanItem::getSearchQueryCount, (item.getSearchQueryCount() == null ? 0 : item.getSearchQueryCount()) + queryCount)
            .set(InfringementScanItem::getErrorCode, errorCode)
            .set(InfringementScanItem::getErrorMessage, errorMessage)
            .set(requestId != null, InfringementScanItem::getLastRequestId, requestId)
            .set(InfringementScanItem::getFinishedAt, LocalDateTime.now()));
    }

    private byte[] readWithOneKeyRefresh(ImageAsset image) {
        try {
            return read(image.getStorageKey());
        } catch (RuntimeException first) {
            ImageAsset current = assets.selectById(image.getId());
            if (current != null && !Objects.equals(current.getStorageKey(), image.getStorageKey())) {
                return read(current.getStorageKey());
            }
            throw first;
        }
    }

    private byte[] read(String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("图片存储地址为空");
        try (ImageStorage.LocalFile file = storage.open(key)) {
            return Files.readAllBytes(file.path());
        } catch (IOException exception) {
            throw new IllegalStateException("图片读取失败", exception);
        }
    }

    private void fail(InfringementScanItem item, String code, String message) {
        fail(item, null, code, message);
    }

    private void fail(InfringementScanItem item, String requestId, String code, String message) {
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getId, item.getId())
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .set(InfringementScanItem::getStatus, InfringementService.FAILED)
            .set(InfringementScanItem::getRiskLevel, null)
            .set(InfringementScanItem::getRiskReason, null)
            .set(InfringementScanItem::getImageDescription, null)
            .set(InfringementScanItem::getSearchUsed, null)
            .set(InfringementScanItem::getSearchQueryCount, null)
            .set(InfringementScanItem::getLastRequestId, requestId)
            .set(InfringementScanItem::getErrorCode, code)
            .set(InfringementScanItem::getErrorMessage, message)
            .set(InfringementScanItem::getStartedAt, null)
            .set(InfringementScanItem::getFinishedAt, LocalDateTime.now()));
    }
}
