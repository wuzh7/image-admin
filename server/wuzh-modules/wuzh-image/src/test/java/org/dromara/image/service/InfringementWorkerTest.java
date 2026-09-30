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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1, 2});
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
        verify(relay).analyze(any(), any());
    }

    @Test
    void rejectsUnexpectedAnalysisResult(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("image.png"), new byte[]{1});
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
