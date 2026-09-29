package org.dromara.image.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ExternalInfringementDtos;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
class ExternalInfringementServiceTest {
    private final InfringementScanItemMapper items = mock(InfringementScanItemMapper.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final ImageProperties properties = new ImageProperties();
    private final ExternalInfringementService service = new ExternalInfringementService(items, storage, properties);

    @Test
    void claimsAtMostSelectedRowsWithOneTokenAndConfiguredExpiry() {
        properties.getInfringement().setExternalLeaseMinutes(30);
        var first = row(1, 11);
        var second = row(2, 12);
        when(items.selectExternalUnclaimed(any())).thenReturn(List.of(first, second));
        when(items.claimExternal(anyList(), anyString(), any())).thenReturn(2);
        when(storage.signGetUrl(anyString(), eq(Duration.ofMinutes(30)))).thenReturn(Optional.of("https://oss.example/test"));

        var result = service.claim();

        assertEquals(List.of("11", "12"), result.stream().map(ExternalInfringementDtos.Claim::imageId).toList());
        assertEquals(result.getFirst().claimToken(), result.getLast().claimToken());
        assertEquals("https://oss.example/test", result.getFirst().imageUrl());
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(items).claimExternal(ids.capture(), eq(result.getFirst().claimToken()), any(LocalDateTime.class));
        assertEquals(List.of(1L, 2L), ids.getValue());
    }

    @Test
    void completionRejectsStaleTokenAndAcceptsIdenticalRetry() {
        var result = new ExternalInfringementDtos.Result("11", "claim-1", "needs_review", "疑似侵权",
            List.of("26-cv-1"), List.of("https://example.com/evidence"));
        InfringementScanItem current = new InfringementScanItem();
        current.setExternalClaimToken("claim-2");
        when(items.selectCurrentExternal(11)).thenReturn(current);
        assertEquals(409, assertThrows(ServiceException.class, () -> service.complete(result)).getCode());

        current.setExternalClaimToken("claim-1");
        current.setExternalCompletedAt(LocalDateTime.now());
        current.setRiskLevel("NEEDS_REVIEW");
        current.setRiskReason("疑似侵权");
        current.setCaseNumbersJson("[\"26-cv-1\"]");
        current.setVerificationUrlsJson("[\"https://example.com/evidence\"]");
        assertDoesNotThrow(() -> service.complete(result));
    }

    @Test
    void completionWritesExistingRiskFieldsAndRejectsBadUrl() {
        var result = new ExternalInfringementDtos.Result("11", "claim-1", "needs_review", "疑似侵权",
            List.of("26-cv-1"), List.of("https://example.com/evidence"));
        when(items.completeExternal(eq(11L), eq("claim-1"), eq("NEEDS_REVIEW"), eq("疑似侵权"),
            eq("[\"26-cv-1\"]"), eq("[\"https://example.com/evidence\"]"))).thenReturn(1);
        assertDoesNotThrow(() -> service.complete(result));
        assertThrows(ServiceException.class, () -> service.complete(new ExternalInfringementDtos.Result(
            "11", "claim-1", "low", "", List.of(), List.of("javascript:alert(1)"))));
    }

    private static ExternalInfringementDtos.ClaimRow row(long id, long imageId) {
        var row = new ExternalInfringementDtos.ClaimRow();
        row.setId(id);
        row.setImageId(imageId);
        row.setStorageKey("library/" + imageId + ".jpg");
        return row;
    }
}
