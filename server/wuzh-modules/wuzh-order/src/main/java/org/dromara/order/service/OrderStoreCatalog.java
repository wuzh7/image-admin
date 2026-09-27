package org.dromara.order.service;

import org.dromara.image.service.StoreProbeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OrderStoreCatalog {
    private final StoreProbeService storeProbeService;
    private final Map<String, StoreInfo> fallback;

    @Autowired
    public OrderStoreCatalog(StoreProbeService storeProbeService) {
        this.storeProbeService = storeProbeService;
        this.fallback = null;
    }

    OrderStoreCatalog(Map<String, StoreInfo> fallback) {
        this.storeProbeService = null;
        this.fallback = fallback;
    }

    public Map<String, StoreInfo> read() {
        if (storeProbeService == null) return Map.copyOf(fallback);
        return storeProbeService.read().entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                entry -> new StoreInfo(entry.getValue().name(), entry.getValue().remarks()),
                (left, right) -> right, java.util.LinkedHashMap::new));
    }

    public record StoreInfo(String name, String remarks) {
    }

}
