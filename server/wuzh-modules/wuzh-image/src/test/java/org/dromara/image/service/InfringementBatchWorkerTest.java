package org.dromara.image.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Collection;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class InfringementBatchWorkerTest {
    private final InfringementScanItemMapper items = mock(InfringementScanItemMapper.class);
    private final InfringementCallLogger callLogger = mock(InfringementCallLogger.class);
    private final InfringementBatchWorker worker = new InfringementBatchWorker(items,
        mock(ImageAssetMapper.class), mock(ImageStorage.class), mock(GeminiGatewayClient.class),
        mock(InfringementBatchQueue.class), callLogger);

    @BeforeEach
    void initMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InfringementScanItem.class);
    }

    @Test
    void pollsOnADedicatedScheduler() throws Exception {
        Scheduled submit = InfringementBatchWorker.class.getDeclaredMethod("submit").getAnnotation(Scheduled.class);
        Scheduled poll = InfringementBatchWorker.class.getDeclaredMethod("poll").getAnnotation(Scheduled.class);

        assertNotEquals(submit.scheduler(), poll.scheduler());
        assertEquals("imageInfringementBatchPollScheduler", poll.scheduler());
    }

    @Test
    void waitingBatchIsOnlyPolledAndNeverResubmitted() {
        worker.apply("batches/1", new GeminiGatewayClient.BatchStatusResponse(
            "batches/1", "RUNNING", null, List.of()));

        verify(items, never()).selectProcessingBatchItems(any());
        verify(items, never()).update(isNull(), any());
    }

    @Test
    void completedBatchWritesTheMatchingImageResult() {
        InfringementScanItem item = new InfringementScanItem();
        item.setId(10L);
        item.setProviderRequestIndex(0);
        when(items.selectProcessingBatchItems("batches/1")).thenReturn(List.of(item));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });
        var result = new GeminiGatewayClient.GatewayResponse("request", "HIGH", "visible mark",
            "lion art", "raw", "gemini-3.8-flash", "prompt", true, 1, null, List.of("1:26-cv-12345"));

        worker.apply("batches/1", new GeminiGatewayClient.BatchStatusResponse(
            "batches/1", "SUCCEEDED", null,
            List.of(new GeminiGatewayClient.BatchItemResponse(0, result, null, null))));

        assertTrue(values[0].contains("SUCCESS"));
        assertTrue(values[0].contains("HIGH"));
        verify(callLogger).success(any(), any(), any(), any(), any());
    }

    @Test
    void lowBatchResultQueuesCaseLookupWithoutChangingTheFirstResult() {
        InfringementScanItem first = batchItem(10L, 1);
        when(items.selectProcessingBatchItems("batches/1")).thenReturn(List.of(first));
        final Collection<?>[] values = new Collection<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            return 1;
        });
        var result = new GeminiGatewayClient.GatewayResponse("request", "LOW", null,
            "lion art", "raw", "model", "prompt", false, 0);

        worker.apply("batches/1", new GeminiGatewayClient.BatchStatusResponse(
            "batches/1", "SUCCEEDED", null,
            List.of(new GeminiGatewayClient.BatchItemResponse(0, result, null, null))));

        assertTrue(values[0].contains("PENDING"));
        assertTrue(values[0].contains("CASE_LOOKUP"));
        assertTrue(values[0].contains("LOW"));
        assertFalse(values[0].contains("NEEDS_REVIEW"));
    }

    @Test
    void submitsOneSignedUrlGroupThroughTheTwoSlotQueue() {
        ImageAssetMapper assets = mock(ImageAssetMapper.class);
        ImageStorage storage = mock(ImageStorage.class);
        GeminiGatewayClient gateway = mock(GeminiGatewayClient.class);
        InfringementBatchQueue queue = mock(InfringementBatchQueue.class);
        InfringementBatchWorker submitter = new InfringementBatchWorker(items, assets, storage, gateway, queue,
            callLogger);
        InfringementScanItem item = new InfringementScanItem();
        item.setId(10L);
        item.setTaskId(7L);
        item.setImageId(20L);
        org.dromara.image.domain.ImageAsset image = new org.dromara.image.domain.ImageAsset();
        image.setId(20L);
        image.setInfringementScanItemId(10L);
        image.setStorageKey("library/20.png");
        image.setContentType("image/png");
        when(queue.claimNext(anyString(), eq(200), eq(2))).thenReturn(List.of(item));
        when(assets.selectById(20L)).thenReturn(image);
        when(storage.signGetUrl("library/20.png", Duration.ofHours(72)))
            .thenReturn(java.util.Optional.of("https://oss.example/20.png?signature=1"));
        when(items.setBatchRequestIndex(eq(10L), anyString(), eq(0))).thenReturn(1);
        when(gateway.createBatch(anyString(), any())).thenReturn(
            new GeminiGatewayClient.BatchCreateResponse("request", "batches/1", 1));
        when(items.bindBatchJob(anyString(), eq("batches/1"))).thenReturn(1);

        submitter.submit();

        verify(queue).claimNext(anyString(), eq(200), eq(2));
        verify(storage).signGetUrl("library/20.png", Duration.ofHours(72));
        verify(gateway).createBatch(anyString(), eq(List.of(
            new GeminiGatewayClient.BatchImage("https://oss.example/20.png?signature=1", "image/png"))));
        verify(items).bindBatchJob(anyString(), eq("batches/1"));
    }

    @Test
    void permanentPollingFailureEndsTheJobForManualRetry() {
        GeminiGatewayClient gateway = mock(GeminiGatewayClient.class);
        InfringementBatchWorker poller = new InfringementBatchWorker(items,
            mock(ImageAssetMapper.class), mock(ImageStorage.class), gateway,
            mock(InfringementBatchQueue.class), callLogger);
        InfringementScanItem item = new InfringementScanItem();
        item.setId(10L);
        when(items.selectProcessingBatchJobs(20)).thenReturn(List.of("batches/missing"));
        when(items.selectProcessingBatchItems("batches/missing")).thenReturn(List.of(item));
        when(gateway.getBatch(anyString(), eq("batches/missing")))
            .thenThrow(new GeminiGatewayClient.GatewayException("INVALID_JOB", "Batch作业不存在", false));

        poller.poll();

        verify(items).selectProcessingBatchItems("batches/missing");
        verify(items).update(isNull(), any());
    }

    @Test
    void retryablePollingFailureKeepsTheJobWaiting() {
        GeminiGatewayClient gateway = mock(GeminiGatewayClient.class);
        InfringementBatchWorker poller = new InfringementBatchWorker(items,
            mock(ImageAssetMapper.class), mock(ImageStorage.class), gateway,
            mock(InfringementBatchQueue.class), callLogger);
        when(items.selectProcessingBatchJobs(20)).thenReturn(List.of("batches/busy"));
        when(gateway.getBatch(anyString(), eq("batches/busy")))
            .thenThrow(new GeminiGatewayClient.GatewayException("UPSTREAM_BUSY", "服务暂时不可用", true));

        poller.poll();

        verify(items, never()).selectProcessingBatchItems("batches/busy");
        verify(items, never()).update(isNull(), any());
    }

    private static InfringementScanItem batchItem(long id, int attempts) {
        InfringementScanItem item = new InfringementScanItem();
        item.setId(id);
        item.setProviderRequestIndex(0);
        item.setAttemptCount(attempts);
        item.setExecutionMode("OFFICIAL_BATCH");
        return item;
    }

    @Test
    void suspiciousBatchResultQueuesLookupWithoutSubmittingAnotherBatch() {
        InfringementScanItem item = batchItem(10L, 1);
        when(items.selectProcessingBatchItems("batches/1")).thenReturn(List.of(item));
        final LambdaUpdateWrapper<?>[] saved = new LambdaUpdateWrapper<?>[1];
        when(items.update(isNull(), any())).thenAnswer(call -> { saved[0] = call.getArgument(1); return 1; });
        var result = new GeminiGatewayClient.GatewayResponse("request", "NEEDS_REVIEW", "疑似艺术作品",
            "lion art", "first response", "model", "prompt", true, 1);

        worker.apply("batches/1", new GeminiGatewayClient.BatchStatusResponse("batches/1", "SUCCEEDED", null,
            List.of(new GeminiGatewayClient.BatchItemResponse(0, result, null, null))));

        assertTrue(saved[0].getParamNameValuePairs().containsValue("PENDING"));
        assertTrue(saved[0].getParamNameValuePairs().containsValue("CASE_LOOKUP"));
        assertTrue(saved[0].getParamNameValuePairs().containsValue("NEEDS_REVIEW"));
        assertTrue(saved[0].getSqlSet().contains("provider_job_id"));
        assertTrue(saved[0].getParamNameValuePairs().containsValue("first response"));
    }
}
