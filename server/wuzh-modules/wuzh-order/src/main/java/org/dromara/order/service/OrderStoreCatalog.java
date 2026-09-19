package org.dromara.order.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OrderStoreCatalog {
    private final String file;

    public OrderStoreCatalog(@Value("${image.infringement.store-file:/app/config/store-probe.xlsx}") String file) {
        this.file = file;
    }

    public Map<String, StoreInfo> read() {
        try {
            Map<String, StoreInfo> stores = new LinkedHashMap<>();
            List<StoreRow> rows = FesodSheet.read(Path.of(file).toFile()).head(StoreRow.class).sheet().doReadSync();
            for (StoreRow row : rows) {
                if (row.storeId != null && !row.storeId.isBlank()) {
                    stores.put(row.storeId, new StoreInfo(row.storeName, row.remarks));
                }
            }
            return stores;
        } catch (Exception exception) {
            log.warn("读取订单店铺数据失败，店铺名称和备注将留空: {} ({})", file, exception.getMessage());
            return Map.of();
        }
    }

    public record StoreInfo(String name, String remarks) {
    }

    @Data
    public static class StoreRow {
        @ExcelProperty("store_id")
        private String storeId;
        @ExcelProperty("store_name")
        private String storeName;
        @ExcelProperty("remarks")
        private String remarks;
    }
}
