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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipInputStream;

import org.dromara.order.store.domain.StoreDtos;
import software.amazon.awssdk.services.s3.model.S3Exception;

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
        assertTrue(storage.renderInputUrl("library/asset.jpg").isEmpty());
        verify(imageStorage, never()).copy(anyString(), anyString());

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", png);
        var image = storage.save(42, "templates", new MockMultipartFile("file", "scene.png", "image/png", png.toByteArray()));
        assertEquals(2, image.width());
        assertEquals(3, image.height());
        assertTrue(image.ossKey().startsWith("store/templates/42/"));
        try (var saved = storage.open(image.ossKey())) { assertArrayEquals(png.toByteArray(), Files.readAllBytes(saved.path())); }
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        storage.stream(image.ossKey(), streamed);
        assertArrayEquals(png.toByteArray(), streamed.toByteArray());

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

    @Test
    void renderCopiesOnlyInputsAndReturnsPermanentOssUrls() throws Exception {
        ImageProperties properties = new ImageProperties();
        properties.getStorage().setType("oss");
        properties.getStorage().getOss().setBucket("render-bucket");
        properties.getStorage().getOss().setPublicEndpoint("https://s3.oss-cn-hangzhou.aliyuncs.com");
        ImageStorage imageStorage = mock(ImageStorage.class);
        @SuppressWarnings("unchecked") ObjectProvider<OssClient> provider = mock(ObjectProvider.class);
        OssClient client = mock(OssClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        StoreObjectStorage storage = new StoreObjectStorage(properties, imageStorage, provider);

        assertEquals("https://render-bucket.oss-cn-hangzhou.aliyuncs.com/render-public/inputs/library/asset.jpg",
            storage.renderInputUrl("library/asset.jpg").orElseThrow());
        verify(imageStorage).copy("library/asset.jpg", "render-public/inputs/library/asset.jpg");

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", png);
        var result = storage.save(42, "results", new MockMultipartFile("file", "result.png", "image/png", png.toByteArray()));
        assertTrue(result.ossKey().startsWith("render-public/results/42/"));
        assertEquals("https://render-bucket.oss-cn-hangzhou.aliyuncs.com/" + result.ossKey(), result.imageUrl());
        verify(client).bucketUpload(eq("render-bucket"), eq(result.ossKey()), any(Path.class));
        verify(imageStorage, never()).accessUrl(eq(result.ossKey()), anyString(), anyBoolean());
    }

    @Test
    void streamsOssFileWithoutOpeningTemporaryFile() throws Exception {
        ImageProperties properties = new ImageProperties();
        properties.getStorage().setType("oss");
        ImageStorage imageStorage = mock(ImageStorage.class);
        @SuppressWarnings("unchecked") ObjectProvider<OssClient> provider = mock(ObjectProvider.class);
        OssClient client = mock(OssClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        doAnswer(invocation -> { ((java.io.OutputStream) invocation.getArgument(1)).write(new byte[] { 1, 2, 3 }); return null; })
            .when(client).download(eq("store/templates/42/background.png"), any(java.io.OutputStream.class));

        new StoreObjectStorage(properties, imageStorage, provider).stream("store/templates/42/background.png", output);

        assertArrayEquals(new byte[] { 1, 2, 3 }, output.toByteArray());
        verify(imageStorage, never()).open(anyString());
    }

    @Test
    void cachesRenderedArchiveAndStreamsImagesDirectlyFromOss() throws Exception {
        ImageProperties properties = new ImageProperties();
        properties.getStorage().setType("oss");
        properties.getStorage().getOss().setBucket("render-bucket");
        ImageStorage imageStorage = mock(ImageStorage.class);
        @SuppressWarnings("unchecked") ObjectProvider<OssClient> provider = mock(ObjectProvider.class);
        OssClient client = mock(OssClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        String key = "store/archives/42/1/ORIGINAL_NAME/render-1.zip";
        when(client.head(key)).thenThrow(new CompletionException(S3Exception.builder().statusCode(404).build()))
            .thenReturn(new org.dromara.common.oss.model.ObjectInfo(100, "application/zip", "etag"));
        when(imageStorage.signGetUrl(key, Duration.ofHours(24))).thenReturn(Optional.of("https://oss.example/render-1.zip"));
        doAnswer(invocation -> { ((java.io.OutputStream) invocation.getArgument(1)).write(new byte[]{1, 2, 3}); return null; })
            .when(client).download(eq("result-1"), any(java.io.OutputStream.class));
        doAnswer(invocation -> {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(invocation.getArgument(2)))) {
                assertEquals("a(1).png", zip.getNextEntry().getName());
                assertArrayEquals(new byte[]{1, 2, 3}, zip.readAllBytes());
                assertNull(zip.getNextEntry());
            }
            return null;
        }).when(client).bucketUpload(eq("render-bucket"), eq(key), any(Path.class), any());
        StoreObjectStorage storage = new StoreObjectStorage(properties, imageStorage, provider);
        List<StoreDtos.ArchiveFile> files = List.of(new StoreDtos.ArchiveFile("result-1", "a(1).png"));

        assertEquals("https://oss.example/render-1.zip", storage.renderArchiveUrl(42, 1, "ORIGINAL_NAME", files).orElseThrow());
        assertEquals("https://oss.example/render-1.zip", storage.renderArchiveUrl(42, 1, "ORIGINAL_NAME", files).orElseThrow());
        verify(client, times(1)).bucketUpload(eq("render-bucket"), eq(key), any(Path.class), any());
        verify(imageStorage, never()).open(anyString());
    }
}
