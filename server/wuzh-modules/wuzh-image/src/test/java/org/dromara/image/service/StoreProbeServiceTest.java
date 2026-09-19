package org.dromara.image.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.dromara.image.config.ImageProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class StoreProbeServiceTest {

    @TempDir
    Path directory;

    @Test
    void matchesStoreIdAndReloadsAfterTheFileChanges() {
        Path file = directory.resolve("stores.xlsx");
        write(file, "634418229831793");
        ImageProperties properties = new ImageProperties();
        properties.getInfringement().setStoreFile(file.toString());
        StoreProbeService service = new StoreProbeService(properties);

        assertTrue(service.contains("634418229831793"));
        assertFalse(service.contains("missing"));

        write(file, "new-store-id");
        assertFalse(service.contains("634418229831793"));
        assertTrue(service.contains("new-store-id"));
    }

    @Test
    void readsAnAtomicallyReplacedFileEvenWhenItsMetadataIsUnchanged() throws Exception {
        Path file = directory.resolve("stores.xlsx");
        write(file, "634418229831793");
        Path replacement = directory.resolve("replacement.xlsx");
        write(replacement, "634418229831794");
        long size = Math.max(Files.size(file), Files.size(replacement));
        padToSize(file, size);
        padToSize(replacement, size);
        var lastModified = Files.getLastModifiedTime(file);
        ImageProperties properties = new ImageProperties();
        properties.getInfringement().setStoreFile(file.toString());
        StoreProbeService service = new StoreProbeService(properties);

        assertTrue(service.contains("634418229831793"));

        assertEquals(size, Files.size(replacement));
        Files.setLastModifiedTime(replacement, lastModified);
        Files.move(replacement, file, ATOMIC_MOVE, REPLACE_EXISTING);

        assertFalse(service.contains("634418229831793"));
        assertTrue(service.contains("634418229831794"));
    }

    private static void write(Path file, String storeId) {
        FesodSheet.write(file.toFile(), StoreRow.class).sheet().doWrite(List.of(new StoreRow(storeId)));
    }

    private static void padToSize(Path file, long size) throws Exception {
        long padding = size - Files.size(file);
        if (padding > 0) Files.write(file, new byte[Math.toIntExact(padding)], APPEND);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StoreRow {
        @ExcelProperty("store_id")
        private String storeId;
    }
}
