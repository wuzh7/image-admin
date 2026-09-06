package org.dromara.image.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageStartup implements HealthIndicator {

    private final SscdFeatureExtractor extractor;
    private final QdrantVectorStore vectorStore;
    private final ImageTaskRunner taskRunner;
    private final ImageService imageService;
    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() throws Exception {
        String sha256 = extractor.modelSha256();
        if (!SscdFeatureExtractor.EXPECTED_MODEL_SHA256.equalsIgnoreCase(sha256)) {
            throw new IllegalStateException("SSCD模型SHA-256不匹配，拒绝启动: " + sha256);
        }
        extractor.warmup();
        vectorStore.initialize();
        int backfilled = imageService.backfillMissingAspectRatios();
        taskRunner.recover();
        ready = true;
        log.info("图片去重模块已就绪 model={} qdrant={} aspectRatiosBackfilled={}", sha256, vectorStore.collectionName(), backfilled);
    }

    @Override
    public Health health() {
        return ready ? Health.up().build() : Health.down().withDetail("reason", "SSCD模型尚未预热").build();
    }
}
