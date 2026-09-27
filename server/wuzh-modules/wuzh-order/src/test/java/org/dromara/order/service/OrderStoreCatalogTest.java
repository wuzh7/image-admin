package org.dromara.order.service;

import org.dromara.image.service.StoreProbeService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.Tags({@Tag("dev"), @Tag("local")})
class OrderStoreCatalogTest {
    @Test
    void readsStoreDataAndLeavesUnknownStoresBlank() {
        StoreProbeService service = mock(StoreProbeService.class);
        when(service.read()).thenReturn(Map.of("store-1", new StoreProbeService.StoreInfo("测试店铺", "主站备注")));
        OrderStoreCatalog catalog = new OrderStoreCatalog(service);

        assertThat(catalog.read().get("store-1"))
            .isEqualTo(new OrderStoreCatalog.StoreInfo("测试店铺", "主站备注"));
        assertThat(catalog.read()).doesNotContainKey("missing");
    }
}
