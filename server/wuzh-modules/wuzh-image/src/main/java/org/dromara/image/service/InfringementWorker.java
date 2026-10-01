package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.ImageStorage;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Component
public class InfringementWorker {
    private static final int AI_IMAGE_MAX_DIMENSION = 1536;
    private final InfringementScanItemMapper items;
    private final ImageAssetMapper assets;
    private final ImageStorage storage;
    private final RelayImageClient relay;
    private final InfringementCallLogger callLogger;
    private final ThreadPoolTaskExecutor imageRelayInfringementExecutor;
    private final LocalDateTime processStartedAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    public InfringementWorker(InfringementScanItemMapper items, ImageAssetMapper assets, ImageStorage storage,
                              RelayImageClient relay, InfringementCallLogger callLogger,
                              @Qualifier("imageRelayInfringementExecutor") ThreadPoolTaskExecutor relayExecutor) {
        this.items = items;
        this.assets = assets;
        this.storage = storage;
        this.relay = relay;
        this.callLogger = callLogger;
        this.imageRelayInfringementExecutor = relayExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        int recovered = items.update(null, Wrappers.<InfringementScanItem>lambdaUpdate()
            .eq(InfringementScanItem::getStatus, InfringementService.PROCESSING)
            .eq(InfringementScanItem::getExecutionMode, "RELAY")
            .eq(InfringementScanItem::getCallType, "IMAGE_ANALYSIS")
            .isNull(InfringementScanItem::getProviderJobId)
            .lt(InfringementScanItem::getStartedAt, processStartedAt)
            .set(InfringementScanItem::getStatus, InfringementService.PENDING)
            .set(InfringementScanItem::getStartedAt, null));
        if (recovered > 0) log.info("重启后恢复图片分析任务 count={}", recovered);
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
        AnalysisImage input;
        try {
            input = readWithOneKeyRefresh(image);
        } catch (RuntimeException exception) {
            fail(item, "IMAGE_READ_FAILED", "图片读取失败");
            log.warn("侵权检测读取失败 itemId={}", item.getId(), exception);
            return;
        }
        String requestId = UUID.randomUUID().toString();
        RelayImageClient.AnalysisResponse result;
        LocalDateTime callStartedAt = LocalDateTime.now();
        try {
            result = relay.analyze(input.bytes(), input.contentType());
        } catch (RelayImageClient.RelayException exception) {
            callLogger.failure(item, requestId, exception.code(), exception.getMessage(), exception.rawAiResponse(),
                exception.usage(), exception.rawProviderResponse(), callStartedAt, LocalDateTime.now());
            fail(item, requestId, exception.code(), exception.getMessage());
            return;
        } catch (RuntimeException exception) {
            callLogger.failure(item, requestId, "ARK_RESPONSE_ERROR", "火山方舟响应异常或超时",
                callStartedAt, LocalDateTime.now());
            fail(item, requestId, "ARK_RESPONSE_ERROR", "火山方舟响应异常或超时");
            log.warn("图片分析火山方舟响应失败 itemId={}", item.getId(), exception);
            return;
        }
        if (!"relay_image_analysis_v1".equals(result.promptVersion()) || result.imageDescription() == null
            || result.imageDescription().isBlank()) {
            callLogger.failure(item, requestId, "INVALID_ANALYSIS_RESULT", "图片分析结果不可识别",
                result.rawAiResponse(), result.usage(), result.rawProviderResponse(), callStartedAt, LocalDateTime.now());
            fail(item, requestId, "INVALID_ANALYSIS_RESULT", "图片分析结果不可识别");
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

    private AnalysisImage readWithOneKeyRefresh(ImageAsset image) {
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

    private AnalysisImage read(String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("图片存储地址为空");
        try (ImageStorage.LocalFile file = storage.open(key)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(SscdFeatureExtractor.readOriented(file.path(), AI_IMAGE_MAX_DIMENSION), "jpeg", output);
            return new AnalysisImage(output.toByteArray(), "image/jpeg");
        } catch (Exception exception) {
            throw new IllegalStateException("图片读取失败", exception);
        }
    }

    private record AnalysisImage(byte[] bytes, String contentType) { }

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
