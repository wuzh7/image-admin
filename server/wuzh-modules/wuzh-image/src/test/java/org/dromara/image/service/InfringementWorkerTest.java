package org.dromara.image.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class InfringementWorkerTest {
    private final InfringementScanItemMapper items = mock(InfringementScanItemMapper.class);
    private final ImageAssetMapper assets = mock(ImageAssetMapper.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final GeminiGatewayClient gateway = mock(GeminiGatewayClient.class);
    private final InfringementCallLogger callLogger = mock(InfringementCallLogger.class);
    private final ThreadPoolTaskExecutor relayExecutor = mock(ThreadPoolTaskExecutor.class);
    private final ThreadPoolTaskExecutor officialExecutor = mock(ThreadPoolTaskExecutor.class);
    private final InfringementWorker worker = new InfringementWorker(items, assets, storage, gateway,
        callLogger, relayExecutor, officialExecutor);

    @BeforeEach
    void initMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InfringementScanItem.class);
    }

    @Test
    void lowDetectionQueuesCaseLookupWithoutChangingTheFirstResult(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 1);
        ImageAsset image = image(10L);
        when(assets.selectById(7L)).thenReturn(image);
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.analyze(any(), any(), any(), any(), any())).thenReturn(
            new GeminiGatewayClient.GatewayResponse("request", "LOW", null, "Simple pattern", "raw", "model", "prompt", true, 2));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });

        worker.process(item);

        assertTrue(values[0].contains("PENDING"));
        assertTrue(values[0].contains("CASE_LOOKUP"));
        assertTrue(values[0].contains("LOW"));
        assertFalse(values[0].contains("NEEDS_REVIEW"));
        verify(assets, never()).update(isNull(), any());
    }

    @Test
    void relayPipelineResultDoesNotQueueLegacyCaseLookup(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 1);
        when(assets.selectById(7L)).thenReturn(image(10L));
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.analyze(any(), any(), any(), any(), any())).thenReturn(
            new GeminiGatewayClient.GatewayResponse("request", "LOW", "保留第一条理由", "Simple pattern", "raw", "model",
                "relay_pipeline_v1", false, 0));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });

        worker.process(item);

        assertTrue(values[0].contains("SUCCESS"));
        assertFalse(values[0].contains("PENDING"));
        assertFalse(values[0].contains("CASE_LOOKUP"));
        assertTrue(values[0].contains("relay_pipeline_v1"));
        assertTrue(values[0].contains("保留第一条理由"));
    }

    @Test
    void queuesNeedsReviewForCaseLookup(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 1);
        when(assets.selectById(7L)).thenReturn(image(10L));
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.analyze(any(), any(), any(), any(), any())).thenReturn(
            new GeminiGatewayClient.GatewayResponse("request", "NEEDS_REVIEW", "可见CCCP文字", "lioness,red jacket", "raw", "model", "prompt", true, 1));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });

        worker.process(item);

        assertTrue(values[0].contains("PENDING"));
        assertTrue(values[0].contains("CASE_LOOKUP"));
        assertFalse(values[0].contains("LOW"));
        assertTrue(values[0].contains("NEEDS_REVIEW"));
        assertTrue(values[0].contains(true));
        assertTrue(values[0].contains(1));
    }

    @Test
    void imageReadFailureEndsTheSingleAttemptWithoutRiskValue() {
        InfringementScanItem item = item(10L, 1);
        ImageAsset image = image(10L);
        when(assets.selectById(7L)).thenReturn(image);
        when(storage.open("review/a.png")).thenThrow(new IllegalStateException("missing"));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });

        worker.process(item);

        assertTrue(values[0].contains("FAILED"));
        assertFalse(values[0].contains("LOW"));
    }

    @Test
    void refreshesStorageKeyOnceWhenAdmissionMovesTheImage(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 2);
        ImageAsset reviewImage = image(10L);
        ImageAsset libraryImage = image(10L);
        libraryImage.setStorageKey("library/a.png");
        when(assets.selectById(7L)).thenReturn(reviewImage, libraryImage);
        when(storage.open("review/a.png")).thenThrow(new IllegalStateException("moved"));
        when(storage.open("library/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.analyze(any(), any(), any(), any(), any())).thenReturn(
            new GeminiGatewayClient.GatewayResponse("request", "LOW", null, "Simple pattern", "raw", "model", "prompt", false, 0));

        worker.process(item);

        verify(storage).open("review/a.png");
        verify(storage).open("library/a.png");
        verify(gateway).analyze(any(), any(), any(), any(), any());
    }

    @Test
    void unsearchedLowIsReturnedToPendingOnce(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 1);
        when(assets.selectById(7L)).thenReturn(image(10L));
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.analyze(any(), any(), any(), any(), any())).thenReturn(
            new GeminiGatewayClient.GatewayResponse("request", "LOW", null, "Simple pattern", "raw", "model", "prompt", false, 0));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });

        worker.process(item);

        assertTrue(values[0].contains("PENDING"));
        assertFalse(values[0].contains("SUCCESS"));
        verify(callLogger).success(any(), any(), any(), any(), any());
    }

    @Test
    void secondUnsearchedLowStillQueuesCaseLookup(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 2);
        when(assets.selectById(7L)).thenReturn(image(10L));
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.analyze(any(), any(), any(), any(), any())).thenReturn(
            new GeminiGatewayClient.GatewayResponse("request", "LOW", null, "Simple pattern", "raw", "model", "prompt", false, 0));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });

        worker.process(item);

        assertTrue(values[0].contains("PENDING"));
        assertTrue(values[0].contains("CASE_LOOKUP"));
        assertTrue(values[0].contains("LOW"));
    }

    @Test
    void retryableGatewayFailureStillEndsAfterOneAttempt(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 1);
        when(assets.selectById(7L)).thenReturn(image(10L));
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.analyze(any(), any(), any(), any(), any())).thenThrow(
            new GeminiGatewayClient.GatewayException("GATEWAY_HTTP_429", "Gateway 请求失败", true));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });

        worker.process(item);

        assertTrue(values[0].contains("FAILED"));
        assertFalse(values[0].contains("PENDING"));
        assertFalse(values[0].contains("LOW"));
        assertTrue(values[0].stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .anyMatch(value -> value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    void doesNotClaimItemsWhenAllWorkersAreBusy() {
        when(relayExecutor.getMaxPoolSize()).thenReturn(40);
        when(relayExecutor.getActiveCount()).thenReturn(40);
        when(officialExecutor.getMaxPoolSize()).thenReturn(8);
        when(officialExecutor.getActiveCount()).thenReturn(8);

        worker.dispatch();

        verify(items, never()).selectPendingRelay(anyInt());
        verify(items, never()).selectPendingOfficial(anyInt());
    }

    @Test
    void onlySelectsEnoughItemsForAvailableWorkers() {
        when(relayExecutor.getMaxPoolSize()).thenReturn(40);
        when(relayExecutor.getActiveCount()).thenReturn(10);
        when(officialExecutor.getMaxPoolSize()).thenReturn(8);
        when(officialExecutor.getActiveCount()).thenReturn(6);
        when(items.selectPendingRelay(30)).thenReturn(java.util.List.of());
        when(items.selectPendingOfficial(2)).thenReturn(java.util.List.of());

        worker.dispatch();

        verify(items).selectPendingRelay(30);
        verify(items).selectPendingOfficial(2);
    }

    @Test
    void staleRecoveryFailsTheAttemptWithoutReturningItToPending() {
        List<Collection<?>> values = new ArrayList<>();
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            update.getSqlSegment();
            values.add(update.getParamNameValuePairs().values());
            return 1;
        });
        when(relayExecutor.getMaxPoolSize()).thenReturn(40);
        when(relayExecutor.getActiveCount()).thenReturn(40);
        when(officialExecutor.getMaxPoolSize()).thenReturn(8);
        when(officialExecutor.getActiveCount()).thenReturn(8);

        worker.dispatch();

        assertTrue(values.getFirst().contains("SUCCESS"));
        assertTrue(values.getFirst().contains("CASE_LOOKUP"));
        assertTrue(values.get(1).contains("FAILED"));
        assertFalse(values.stream().anyMatch(row -> row.contains("PENDING")));
    }

    @Test
    void doesNotSubmitAnItemWhenItsConditionalClaimLosesTheRace() {
        InfringementScanItem pending = item(10L, 0);
        pending.setStatus("PENDING");
        when(relayExecutor.getMaxPoolSize()).thenReturn(1);
        when(relayExecutor.getActiveCount()).thenReturn(0);
        when(officialExecutor.getMaxPoolSize()).thenReturn(1);
        when(officialExecutor.getActiveCount()).thenReturn(1);
        when(items.selectPendingRelay(1)).thenReturn(List.of(pending));
        when(items.update(isNull(), any())).thenReturn(0);

        worker.dispatch();

        verify(relayExecutor, never()).execute(any());
    }

    private static InfringementScanItem item(long id, int attempts) {
        InfringementScanItem item = new InfringementScanItem();
        item.setId(id);
        item.setImageId(7L);
        item.setStatus("PROCESSING");
        item.setAttemptCount(attempts);
        item.setInfringementMode("RELAY");
        item.setExecutionMode("RELAY");
        item.setCallType("RISK_SCAN");
        return item;
    }

    @Test
    void caseLookupPromotesToHighAndRetainsTheFirstAssessment(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 2);
        item.setCallType("CASE_LOOKUP");
        item.setExecutionMode("OFFICIAL_BATCH");
        item.setRawAiResponse("first assessment");
        item.setRiskReason("疑似既有艺术作品");
        when(assets.selectById(7L)).thenReturn(image(10L));
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.lookupCases(any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("OFFICIAL_STANDARD"),
            org.mockito.ArgumentMatchers.eq("first assessment"))).thenReturn(
            new GeminiGatewayClient.CaseLookupResponse("lookup", List.of("1:26-cv-12345"),
                "[\"1:26-cv-12345\"]", "model", true, 1, null));
        final LambdaUpdateWrapper<?>[] saved = new LambdaUpdateWrapper<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> { saved[0] = call.getArgument(1); return 1; });

        worker.process(item);

        assertTrue(saved[0].getParamNameValuePairs().containsValue("HIGH"));
        assertTrue(saved[0].getParamNameValuePairs().containsValue("[\"1:26-cv-12345\"]"));
        assertFalse(saved[0].getSqlSet().contains("raw_ai_response"));
        assertFalse(saved[0].getSqlSet().contains("risk_reason"));
        verify(gateway, never()).analyze(any(), any(), any(), any(), any());
        verify(callLogger).success(any(), any(), any(), any(), any());
    }

    @Test
    void emptyOrFailedCaseLookupPreservesTheFirstResultWithoutRetry(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        InfringementScanItem item = item(10L, 2);
        item.setCallType("CASE_LOOKUP");
        item.setRiskLevel("LOW");
        item.setCaseNumbersJson("[]");
        item.setRawAiResponse("first assessment");
        item.setRiskReason("疑似既有艺术作品");
        when(assets.selectById(7L)).thenReturn(image(10L));
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.lookupCases(any(), any(), any(), any(), any(), any()))
            .thenReturn(new GeminiGatewayClient.CaseLookupResponse("lookup", List.of(), "[]", "model", false, 0, null))
            .thenThrow(new GeminiGatewayClient.GatewayException("GATEWAY_HTTP_429", "限流", true));
        List<LambdaUpdateWrapper<?>> saved = new ArrayList<>();
        when(items.update(isNull(), any())).thenAnswer(call -> { saved.add(call.getArgument(1)); return 1; });

        worker.process(item);
        worker.process(item);

        for (var update : saved) {
            assertTrue(update.getParamNameValuePairs().containsValue("SUCCESS"));
            assertFalse(update.getSqlSet().contains("risk_level"));
            assertFalse(update.getSqlSet().contains("case_numbers_json"));
            assertFalse(update.getParamNameValuePairs().containsValue("PENDING"));
        }
        verify(callLogger).failure(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void firstResultWithCaseNumberIsSavedWithoutLookup(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
        when(assets.selectById(7L)).thenReturn(image(10L));
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(gateway.analyze(any(), any(), any(), any(), any())).thenReturn(
            new GeminiGatewayClient.GatewayResponse("request", "NEEDS_REVIEW", "既有作品", "lion art", "raw",
                "model", "prompt", true, 1, null, List.of("1:26-cv-12345")));
        final LambdaUpdateWrapper<?>[] saved = new LambdaUpdateWrapper<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> { saved[0] = call.getArgument(1); return 1; });

        worker.process(item(10L, 1));

        assertTrue(saved[0].getParamNameValuePairs().containsValue("HIGH"));
        assertTrue(saved[0].getParamNameValuePairs().containsValue("SUCCESS"));
        verify(gateway, never()).lookupCases(any(), any(), any(), any(), any(), any());
    }

    private static ImageAsset image(long currentItemId) {
        ImageAsset image = new ImageAsset();
        image.setId(7L);
        image.setInfringementScanItemId(currentItemId);
        image.setStorageKey("review/a.png");
        image.setContentType("image/png");
        image.setOriginalName("a.png");
        return image;
    }
}
