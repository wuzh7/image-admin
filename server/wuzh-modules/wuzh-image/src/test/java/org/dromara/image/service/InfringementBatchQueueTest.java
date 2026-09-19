package org.dromara.image.service;

import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class InfringementBatchQueueTest {
    private final InfringementScanItemMapper items = mock(InfringementScanItemMapper.class);
    private final InfringementBatchQueue queue = new InfringementBatchQueue(items);

    @Test
    void fullGlobalCapacityLeavesPendingItemsUntouched() {
        when(items.lockBatchQueue()).thenReturn(1L);
        when(items.countActiveBatchJobs()).thenReturn(2);

        assertEquals(List.of(), queue.claimNext("request-1", 200, 2));

        verify(items, never()).selectOldestPendingBatchTaskForUpdate();
    }

    @Test
    void claimsAtMostOneFifoTaskGroup() {
        List<InfringementScanItem> pending = LongStream.rangeClosed(1, 200).mapToObj(id -> {
            InfringementScanItem item = new InfringementScanItem();
            item.setId(id);
            item.setTaskId(7L);
            return item;
        }).toList();
        List<Long> ids = pending.stream().map(InfringementScanItem::getId).toList();
        when(items.lockBatchQueue()).thenReturn(1L);
        when(items.countActiveBatchJobs()).thenReturn(1);
        when(items.selectOldestPendingBatchTaskForUpdate()).thenReturn(7L);
        when(items.selectPendingBatchForUpdate(7L, 200)).thenReturn(pending);
        when(items.claimPendingBatch(ids, "request-2")).thenReturn(200);

        assertEquals(pending, queue.claimNext("request-2", 200, 2));

        verify(items).claimPendingBatch(ids, "request-2");
    }
}
