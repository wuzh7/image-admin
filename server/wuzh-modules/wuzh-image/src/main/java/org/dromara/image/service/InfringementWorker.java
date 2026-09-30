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

    public InfringementWorker(InfringementScanItemMapper items, ImageAssetMapper assets, ImageStorage storage,
                              GeminiGatewayClient gateway, InfringementCallLogger callLogger,
                              @Qualifier("imageRelayInfringementExecutor") ThreadPoolTaskExecutor relayExecutor) {
        this.items = items;
        this.assets = assets;
        this.storage = storage;
        this.gateway = gateway;
        this.callLogger = callLogger;
        this.imageRelayInfringementExecutor = relayExecutor;
    }

    @Scheduled(fixedDelay = 200, scheduler = "imageInfringementScheduler")
    public void dispatch() {
        LocalDateTime stale = LocalDateTime.now().minusMinutes(20);
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .eq(InfringementScanItem::getExecutionMode, "RELAY")
            .isNull(InfringementScanItem::getProviderJobId)
            .lt(InfringementScanItem::getStartedAt, stale)
            .set(InfringementScanItem::getStatus, InfringementService.FAILED)
            .set(InfringementScanItem::getImageDescription, null)
            .set(InfringementScanItem::getSearchUsed, null)
            .set(InfringementScanItem::getSearchQueryCount, null)
            .set(InfringementScanItem::getErrorCode, "PROCESSING_TIMEOUT")
            .set(InfringementScanItem::getErrorMessage, "检测进程超时")
            .set(InfringementScanItem::getStartedAt, null)
            .set(InfringementScanItem::getFinishedAt, LocalDateTime.now()));
        dispatch(imageRelayInfringementExecutor);
    }

    private void dispatch(ThreadPoolTaskExecutor executor) {
        int availableWorkers = executor.getMaxPoolSize() - executor.getActiveCount();
        if (availableWorkers <= 0) return;
        var pending = items.selectPendingRelay(availableWorkers);
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
            fail(item, "IMAGE_READ_FAILED", "图片读取失败");
            log.warn("侵权检测读取失败 itemId={}", item.getId(), exception);
            return;
        }
        String requestId = UUID.randomUUID().toString();
        GeminiGatewayClient.GatewayResponse result;
        LocalDateTime callStartedAt = LocalDateTime.now();
        try {
            result = gateway.analyze(requestId, bytes,
                image.getContentType(), image.getOriginalName(), "RELAY");
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
        if (!"relay_image_analysis_v1".equals(result.promptVersion()) || result.imageDescription() == null
            || result.imageDescription().isBlank() || result.riskLevel() != null) {
            callLogger.failure(item, requestId, "INVALID_ANALYSIS_RESULT", "Gateway 图片分析结果不可识别",
                result.rawAiResponse(), result.usage(), result.rawProviderResponse(), callStartedAt, LocalDateTime.now());
            fail(item, requestId, "INVALID_ANALYSIS_RESULT", "Gateway 图片分析结果不可识别");
            return;
        }
        callLogger.success(item, requestId, result, callStartedAt, LocalDateTime.now());
        items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getId, item.getId())
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .eq(InfringementScanItem::getCallType, item.getCallType())
            .set(InfringementScanItem::getStatus, InfringementService.SUCCESS)
            .set(InfringementScanItem::getImageDescription, result.imageDescription())
            .set(InfringementScanItem::getRawAiResponse, result.rawAiResponse())
            .set(InfringementScanItem::getModel, result.model())
            .set(InfringementScanItem::getPromptVersion, result.promptVersion())
            .set(InfringementScanItem::getSearchUsed, false)
            .set(InfringementScanItem::getSearchQueryCount, 0)
            .set(InfringementScanItem::getLastRequestId, requestId)
            .set(InfringementScanItem::getErrorCode, null)
            .set(InfringementScanItem::getErrorMessage, null)
            .set(InfringementScanItem::getStartedAt, callStartedAt)
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
