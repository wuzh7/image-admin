package org.dromara.image.service;

import org.dromara.image.domain.InfringementCallLog;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.InfringementCallLogMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Tag("local")
class InfringementCallLoggerTest {
    @Test
    void storesCompleteFailedResponseWithoutMarkingItLowRisk() {
        var mapper = mock(InfringementCallLogMapper.class);
        var logger = new InfringementCallLogger(mapper);
        var now = LocalDateTime.now();
        logger.failure(new InfringementScanItem(), "failed-request", "UPSTREAM", "failed", "bad model text",
            null, "{\"error\":{\"message\":\"failed\"},\"trace\":\"keep\"}", now, now);
        var captor = ArgumentCaptor.forClass(InfringementCallLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals(null, captor.getValue().getRiskLevel());
        assertEquals("bad model text", captor.getValue().getRawAiResponse());
        assertEquals("{\"error\":{\"message\":\"failed\"},\"trace\":\"keep\"}", captor.getValue().getRawProviderResponse());
    }
    @Test
    void recordsBatchFollowupAsStandardAndPreservesNeedsReview() {
        var mapper = mock(InfringementCallLogMapper.class);
        var logger = new InfringementCallLogger(mapper);
        var item = new InfringementScanItem();
        item.setExecutionMode("OFFICIAL_BATCH");
        item.setCallType("CASE_LOOKUP");
        item.setProviderJobId("batches/first");
        var now = LocalDateTime.now();
        logger.success(item, "lookup", new GeminiGatewayClient.GatewayResponse("lookup", "NEEDS_REVIEW",
            "疑似作品", "lion", "[]", "model", "gemini_prompt", true, 1), now, now.plusSeconds(1));
        var captor = ArgumentCaptor.forClass(InfringementCallLog.class);
        verify(mapper).insert(captor.capture());
        assertEquals("CASE_LOOKUP", captor.getValue().getCallType());
        assertEquals("OFFICIAL_STANDARD", captor.getValue().getExecutionMode());
        assertEquals("NEEDS_REVIEW", captor.getValue().getRiskLevel());
        assertEquals(null, captor.getValue().getProviderJobId());
    }

    @Test
    void storesRawResponseAndEveryUsageCounter() {
        InfringementCallLogMapper mapper = mock(InfringementCallLogMapper.class);
        InfringementCallLogger logger = new InfringementCallLogger(mapper);
        InfringementScanItem item = new InfringementScanItem();
        item.setId(1L);
        item.setTaskId(2L);
        item.setBatchId(3L);
        item.setImageId(4L);
        item.setExecutionMode("RELAY");
        GeminiGatewayClient.UsageMetadata usage = new GeminiGatewayClient.UsageMetadata(10, 2, 3, 4, 5, 24,
            "{\"totalTokenCount\":24}");
        GeminiGatewayClient.GatewayResponse response = new GeminiGatewayClient.GatewayResponse("request", "LOW",
            null, "description", "raw", "model", "gemini_prompt", false, 0, usage)
            .withRawProviderResponse("{\"candidates\":[],\"providerExtra\":\"preserve\"}");
        LocalDateTime startedAt = LocalDateTime.now();

        logger.success(item, "request", response, startedAt, startedAt.plusSeconds(2));

        ArgumentCaptor<InfringementCallLog> captor = ArgumentCaptor.forClass(InfringementCallLog.class);
        verify(mapper).insert(captor.capture());
        InfringementCallLog log = captor.getValue();
        assertEquals("raw", log.getRawAiResponse());
        assertEquals(response.rawProviderResponse(), log.getRawProviderResponse());
        assertEquals(10, log.getPromptTokenCount());
        assertEquals(5, log.getToolUsePromptTokenCount());
        assertEquals(24, log.getTotalTokenCount());
        assertEquals(2000, log.getDurationMs());
    }
}
