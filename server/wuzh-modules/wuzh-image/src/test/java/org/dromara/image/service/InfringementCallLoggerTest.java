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
    void storesRawResponseAndEveryUsageCounter() {
        InfringementCallLogMapper mapper = mock(InfringementCallLogMapper.class);
        InfringementCallLogger logger = new InfringementCallLogger(mapper);
        InfringementScanItem item = new InfringementScanItem();
        item.setId(1L);
        item.setTaskId(2L);
        item.setBatchId(3L);
        item.setImageId(4L);
        item.setExecutionMode("RELAY");
        RelayImageClient.UsageMetadata usage = new RelayImageClient.UsageMetadata(10, 2, 3, 4, 5, 24,
            "{\"totalTokenCount\":24}");
        RelayImageClient.AnalysisResponse response = new RelayImageClient.AnalysisResponse("description", "raw",
            "model", "relay_image_analysis_v1", usage, "{\"candidates\":[],\"providerExtra\":\"preserve\"}");
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
