package org.dromara.order.store.service;

import org.dromara.common.oss.client.OssClient;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.storage.ImageStorage;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
class StoreObjectStorageTest {
    @TempDir Path root;

    @Test
    void savesImageAndListingFileUnderOwnerDirectory() throws Exception {
        ImageProperties properties = new ImageProperties();
        properties.getStorage().setRoot(root.toString());
        ImageStorage imageStorage = mock(ImageStorage.class);
        when(imageStorage.accessUrl(anyString(), anyString(), eq(false))).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked") ObjectProvider<OssClient> oss = mock(ObjectProvider.class);
        StoreObjectStorage storage = new StoreObjectStorage(properties, imageStorage, oss);

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", png);
        var image = storage.save(42, "templates", new MockMultipartFile("file", "scene.png", "image/png", png.toByteArray()));
        assertEquals(2, image.width());
        assertEquals(3, image.height());
        assertTrue(image.ossKey().startsWith("store/templates/42/"));
        try (var saved = storage.open(image.ossKey())) { assertArrayEquals(png.toByteArray(), Files.readAllBytes(saved.path())); }

        ByteArrayOutputStream xlsx = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("商品");
            String[] headers = {"商品层级", "SPU货号", "商品名称", "英文名称", "SKU货号", "SKU预览图-英语", "详情图文-英语", "轮播图1"};
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            sheet.createRow(1).createCell(0).setCellValue("SPU");
            sheet.createRow(2).createCell(0).setCellValue("SKU");
            workbook.write(xlsx);
        }
        var listing = storage.save(42, "listing", new MockMultipartFile("file", "catalog.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx.toByteArray()));
        assertTrue(listing.ossKey().startsWith("store/listing/42/"));
        assertNull(listing.width());
        assertThrows(Exception.class, () -> storage.save(42, "listing", new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", new byte[]{'P', 'K', 3, 4})));
        assertThrows(Exception.class, () -> storage.open("../outside.xlsx"));
    }
}
