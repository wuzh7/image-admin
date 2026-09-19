package org.dromara.order.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.Tags({@Tag("dev"), @Tag("local")})
class OrderStoreCatalogTest {
    @TempDir
    Path directory;

    @Test
    void readsProductionHeadersAndLeavesUnknownStoresBlank() {
        Path file = directory.resolve("store-probe.xlsx");
        FesodSheet.write(file.toFile(), StoreRow.class).sheet().doWrite(List.of(
            new StoreRow("store-1", "测试店铺", "主站备注")
        ));

        OrderStoreCatalog catalog = new OrderStoreCatalog(file.toString());

        assertThat(catalog.read().get("store-1"))
            .isEqualTo(new OrderStoreCatalog.StoreInfo("测试店铺", "主站备注"));
        assertThat(catalog.read()).doesNotContainKey("missing");
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StoreRow {
        @ExcelProperty("store_id")
        private String storeId;
        @ExcelProperty("store_name")
        private String storeName;
        @ExcelProperty("remarks")
        private String remarks;
    }
}
