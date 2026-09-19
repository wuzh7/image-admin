package org.dromara.image.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.dromara.image.config.ImageProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class StoreProbeService {
    private final ImageProperties properties;

    public boolean contains(String storeId) {
        if (storeId == null) return false;
        Path file = Path.of(properties.getInfringement().getStoreFile());
        try {
            List<StoreRow> rows = FesodSheet.read(file.toFile()).head(StoreRow.class).sheet().doReadSync();
            return rows.stream().map(StoreRow::getStoreId).filter(Objects::nonNull).anyMatch(storeId::equals);
        } catch (Exception exception) {
            throw new IllegalStateException("读取店铺数据文件失败", exception);
        }
    }

    @Data
    public static class StoreRow {
        @ExcelProperty("store_id")
        private String storeId;
    }
}
