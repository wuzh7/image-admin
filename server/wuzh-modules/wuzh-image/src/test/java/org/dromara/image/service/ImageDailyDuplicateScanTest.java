package org.dromara.image.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageSimilarityMatch;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Tags({@Tag("dev"), @Tag("local")})
class ImageDailyDuplicateScanTest {
    private final ImageAssetMapper assets = mock(ImageAssetMapper.class);
    private final ImageRecordMapper records = mock(ImageRecordMapper.class);
    private final ImageDuplicateChecker checker = mock(ImageDuplicateChecker.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ImageDailyDuplicateScan scan = new ImageDailyDuplicateScan(assets, records, checker,
        new ImageProperties(), transactions, new ImageDataLock());
    private final LocalDate day = LocalDate.of(2026, 9, 18);

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ImageAsset.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(records.lockDailyScan()).thenReturn(2L);
    }

    @Test void scansFullUploadDayAndCreatesCanonicalPairsAboveThreshold() {
        ImageAsset image = new ImageAsset(); image.setId(20L);
        when(assets.selectList(any())).thenReturn(List.of(image), List.of());
        when(checker.check(image, null)).thenReturn(List.of(
            new ImageSimilarityMatch(10L, new BigDecimal("0.8"), "LIBRARY"),
            new ImageSimilarityMatch(30L, new BigDecimal("0.1"), "LIBRARY")));
        var before = java.time.LocalDateTime.now().withNano(0);
        scan.scan(day);
        verify(records).addTodo(anyLong(), eq(10L), eq(20L), eq(new BigDecimal("0.8")), eq(day));
        verify(records, times(1)).addTodo(anyLong(), anyLong(), anyLong(), any(), any());
        ArgumentCaptor<LambdaQueryWrapper<ImageAsset>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(assets, times(2)).selectList(query.capture());
        var first = query.getAllValues().getFirst(); first.getSqlSegment();
        assertTrue(first.getParamNameValuePairs().containsValue(day.atStartOfDay()));
        assertTrue(first.getParamNameValuePairs().containsValue(day.plusDays(1).atStartOfDay()));
        var marker = ArgumentCaptor.forClass(java.time.LocalDateTime.class);
        var order = inOrder(records, transactions);
        order.verify(records).completeScan(eq(day), marker.capture()); order.verify(transactions).commit(any());
        assertFalse(marker.getValue().isBefore(before));
        assertEquals(0, marker.getValue().getNano());
    }

    @Test void failedScanRollsBackAndDoesNotMarkDateCompleted() {
        ImageAsset image = new ImageAsset(); image.setId(20L);
        when(assets.selectList(any())).thenReturn(List.of(image));
        when(checker.check(image, null)).thenThrow(new IllegalStateException("qdrant offline"));
        assertThrows(IllegalStateException.class, () -> scan.scan(day));
        verify(records, never()).completeScan(any(), any()); verify(transactions).rollback(any());
    }

    @Test void completedDateIsSkippedUnderDatabaseLock() {
        when(records.scanCompleted(day)).thenReturn(1);
        scan.scan(day); verify(records).lockDailyScan(); verifyNoInteractions(assets, checker);
    }
}
