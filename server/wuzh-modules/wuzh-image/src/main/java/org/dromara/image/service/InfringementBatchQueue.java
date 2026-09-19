package org.dromara.image.service;

import lombok.RequiredArgsConstructor;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class InfringementBatchQueue {
    private final InfringementScanItemMapper items;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<InfringementScanItem> claimNext(String requestId, int limit, int maxActiveJobs) {
        // ponytail: reuse one existing task row as the global mutex; add a lease table only if queue throughput requires it.
        if (items.lockBatchQueue() == null || items.countActiveBatchJobs() >= maxActiveJobs) return List.of();
        Long taskId = items.selectOldestPendingBatchTaskForUpdate();
        if (taskId == null) return List.of();
        List<InfringementScanItem> claimed = items.selectPendingBatchForUpdate(taskId, limit);
        if (claimed.isEmpty()) return List.of();
        List<Long> ids = claimed.stream().map(InfringementScanItem::getId).toList();
        if (items.claimPendingBatch(ids, requestId) != ids.size()) {
            throw new IllegalStateException("Batch队列领取数量不一致");
        }
        return claimed;
    }
}
