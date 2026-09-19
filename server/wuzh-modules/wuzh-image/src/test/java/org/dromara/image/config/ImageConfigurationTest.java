package org.dromara.image.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("local")
class ImageConfigurationTest {

    @Test
    void relayExecutorUsesConfiguredWorkers() {
        ImageProperties properties = new ImageProperties();
        properties.getInfringement().setRelayWorkers(10);
        ThreadPoolTaskExecutor executor = new ImageConfiguration().imageRelayInfringementExecutor(properties);
        try {
            assertEquals(10, executor.getCorePoolSize());
            assertEquals(10, executor.getMaxPoolSize());
        } finally {
            executor.shutdown();
        }
    }
}
