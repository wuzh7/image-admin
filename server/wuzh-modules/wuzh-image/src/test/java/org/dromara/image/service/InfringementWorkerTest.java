package org.dromara.image.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class InfringementWorkerTest {
    private final InfringementScanItemMapper items = mock(InfringementScanItemMapper.class);
    private final ImageAssetMapper assets = mock(ImageAssetMapper.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final RelayImageClient relay = mock(RelayImageClient.class);
    private final InfringementCallLogger callLogger = mock(InfringementCallLogger.class);
    private final InfringementWorker worker = new InfringementWorker(items, assets, storage, relay,
        callLogger, mock(ThreadPoolTaskExecutor.class));

    @BeforeEach
    void initMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InfringementScanItem.class);
    }

    @Test
    void visualAnalysisCompletesWithoutRisk(@TempDir Path directory) throws Exception {
        Path file = writePng(directory.resolve("image.png"), 20, 20);
        InfringementScanItem item = item();
        when(assets.selectById(7L)).thenReturn(image());
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(relay.analyze(any(), any())).thenReturn(
            new RelayImageClient.AnalysisResponse("A red flower.", "raw",
                "doubao-seed-2-0-lite-260428", "relay_image_analysis_v1", null, null));
        final Collection<?>[] values = new Collection<?>[1];
        final String[] columns = new String[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            columns[0] = update.getSqlSet();
            return 1;
        });

        worker.process(item);

        assertTrue(values[0].contains("SUCCESS"));
        assertTrue(values[0].contains("A red flower."));
        assertFalse(values[0].contains("HIGH"));
        assertFalse(values[0].contains("LOW"));
        assertFalse(columns[0].contains("risk_level"));
        assertFalse(columns[0].contains("risk_reason"));
        assertFalse(columns[0].contains("case_numbers_json"));
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(relay).analyze(sent.capture(), eq("image/jpeg"));
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(sent.getValue()));
        assertEquals(20, normalized.getWidth());
        assertEquals(20, normalized.getHeight());
    }

    @Test
    void rejectsUnexpectedAnalysisResult(@TempDir Path directory) throws Exception {
        Path file = writePng(directory.resolve("image.png"), 20, 20);
        when(assets.selectById(7L)).thenReturn(image());
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(relay.analyze(any(), any())).thenReturn(
            new RelayImageClient.AnalysisResponse("A red flower.", "raw", "model", "unexpected", null, null));
        final Collection<?>[] values = new Collection<?>[1];
        final String[] columns = new String[1];
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            values[0] = update.getParamNameValuePairs().values();
            columns[0] = update.getSqlSet();
            return 1;
        });

        worker.process(item());

        assertTrue(values[0].contains("FAILED"));
        assertFalse(values[0].contains("A red flower."));
        assertFalse(columns[0].contains("risk_level"));
        assertFalse(columns[0].contains("risk_reason"));
    }

    @Test
    void recoversInterruptedAnalysisWithoutChangingRisk() {
        when(items.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<InfringementScanItem> update = call.getArgument(1);
            String conditions = update.getSqlSegment();
            String columns = update.getSqlSet();
            assertTrue(conditions.contains("execution_mode"));
            assertTrue(conditions.contains("call_type"));
            assertTrue(conditions.contains("provider_job_id"));
            assertTrue(conditions.contains("started_at"));
            assertTrue(update.getParamNameValuePairs().containsValue("IMAGE_ANALYSIS"));
            assertTrue(update.getParamNameValuePairs().containsValue("PENDING"));
            assertFalse(columns.contains("risk_level"));
            assertFalse(columns.contains("risk_reason"));
            return 5;
        });

        worker.recoverInterrupted();

        verify(items).update(isNull(), any());
    }

    @Test
    void normalizesEveryLargeImage(@TempDir Path directory) throws Exception {
        Path file = writePng(directory.resolve("image.png"), 2000, 100);
        when(assets.selectById(7L)).thenReturn(image());
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(relay.analyze(any(), any())).thenReturn(
            new RelayImageClient.AnalysisResponse("A red flower.", "raw",
                "doubao-seed-2-0-lite-260428", "relay_image_analysis_v1", null, null));

        worker.process(item());

        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(relay).analyze(sent.capture(), eq("image/jpeg"));
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(sent.getValue()));
        assertTrue(normalized.getWidth() <= 1536);
        assertTrue(normalized.getHeight() <= 1536);
    }

    @Test
    void downsizesImageAboveArkByteLimit(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("image.jpg");
        try (var source = getClass().getResourceAsStream("/sscd-baseline/test_img1.jpg")) {
            Files.copy(source, file);
        }
        try (RandomAccessFile oversized = new RandomAccessFile(file.toFile(), "rw")) {
            oversized.setLength(10L * 1024 * 1024 + 1);
        }
        ImageAsset image = image();
        image.setContentType("image/jpeg");
        image.setFileSize(Files.size(file));
        when(assets.selectById(7L)).thenReturn(image);
        when(storage.open("review/a.png")).thenReturn(new ImageStorage.LocalFile(file, false));
        when(relay.analyze(any(), any())).thenReturn(
            new RelayImageClient.AnalysisResponse("A red flower.", "raw",
                "doubao-seed-2-0-lite-260428", "relay_image_analysis_v1", null, null));

        worker.process(item());

        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(relay).analyze(sent.capture(), eq("image/jpeg"));
        assertTrue(sent.getValue().length < 10L * 1024 * 1024);
    }

    private static InfringementScanItem item() {
        InfringementScanItem item = new InfringementScanItem();
        item.setId(10L);
        item.setImageId(7L);
        item.setStatus("PROCESSING");
        item.setAttemptCount(1);
        item.setInfringementMode("RELAY");
        item.setExecutionMode("RELAY");
        item.setCallType("IMAGE_ANALYSIS");
        return item;
    }

    private static Path writePng(Path path, int width, int height) throws Exception {
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        return path;
    }

    private static ImageAsset image() {
        ImageAsset image = new ImageAsset();
        image.setId(7L);
        image.setInfringementScanItemId(10L);
        image.setStorageKey("review/a.png");
        image.setContentType("image/png");
        image.setOriginalName("a.png");
        return image;
    }
}
