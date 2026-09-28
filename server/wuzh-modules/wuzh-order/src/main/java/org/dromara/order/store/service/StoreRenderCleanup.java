package org.dromara.order.store.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StoreRenderCleanup {
    private final StoreWorkflowService service;

    @Scheduled(fixedDelay = 60000)
    public void finishStoppedBatches() {
        for (Long batchId : service.stoppingBatchIds()) {
            try {
                service.finishStoppingBatch(batchId);
            } catch (Exception error) {
                log.error("结束渲染批次 {} 失败", batchId, error);
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        for (Long batchId : service.expiredDownloadedBatchIds()) {
            try {
                service.deleteExpiredResults(batchId);
            } catch (Exception error) {
                log.error("清理渲染批次 {} 失败", batchId, error);
            }
        }
        try {
            service.purgeDeletedResultFiles();
        } catch (Exception error) {
            log.error("删除渲染结果文件失败", error);
        }
    }
}
