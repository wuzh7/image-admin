package org.dromara.image.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageBatch;
import org.dromara.image.domain.InfringementDtos;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.domain.InfringementScanTask;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.mapper.InfringementCallLogMapper;
import org.dromara.image.mapper.InfringementScanTaskMapper;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class InfringementServiceTest {
    @Test void listingKeywordsUseOnlySuccessfulAiDetection() {
        InfringementScanItem result = new InfringementScanItem();
        result.setStatus("SUCCESS");
        result.setImageDescription("  Autumn wall art  ");
        assertEquals("Autumn wall art", InfringementService.aiListingKeywords(result));
        result.setStatus("FAILED");
        assertNull(InfringementService.aiListingKeywords(result));
        result.setStatus("SUCCESS");
        result.setImageDescription("(15).png");
        assertNull(InfringementService.aiListingKeywords(result));
    }

    @Test void libraryTagsPreserveOriginalEnglishArraysWithoutTruncationOrTranslation() {
        var tags = InfringementService.imageVisualTags("{\"subjects\":[\"Lion\"],\"scenes\":[\"Savanna\"],\"themes\":[\"Wildlife\"],\"visualStyles\":[\"Watercolor\"],\"colors\":[\"Red\",\"Blue\"]}");
        assertEquals(List.of("Lion"), tags.subjects());
        assertEquals(List.of("Savanna"), tags.scenes());
        assertEquals(List.of("Wildlife"), tags.themes());
        assertEquals(List.of("Watercolor"), tags.visualStyles());
        assertEquals(List.of("Red", "Blue"), tags.colors());
    }
    private final InfringementScanTaskMapper tasks = mock(InfringementScanTaskMapper.class);
    private final InfringementScanItemMapper items = mock(InfringementScanItemMapper.class);
    private final ImageAssetMapper assets = mock(ImageAssetMapper.class);
    private final InfringementCallLogMapper callLogs = mock(InfringementCallLogMapper.class);
    private final InfringementService service = new InfringementService(tasks, items,
        callLogs, assets, mock(ImageBatchMapper.class), mock(UserService.class));

    @BeforeEach
    void initMybatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ImageAsset.class);
    }

    @Test
    void createsPendingItemsForConfirmedImages() {
        when(tasks.insert(any(InfringementScanTask.class))).thenReturn(1);
        when(items.insert(any(InfringementScanItem.class))).thenReturn(1);
        when(assets.update(isNull(), any())).thenReturn(1);
        ImageBatch batch = new ImageBatch();
        batch.setId(3L);
        batch.setCreateBy(8L);
        ImageAsset first = image(10L, 8L);
        ImageAsset second = image(11L, 8L);

        service.createInitialTask(batch, List.of(first, second));

        verify(tasks).insert(any(InfringementScanTask.class));
        verify(items, times(2)).insert(any(InfringementScanItem.class));
        verify(assets, times(2)).update(isNull(), any());
    }

    @Test
    void officialHybridUsesStandardThrough100AndBatchAbove100() {
        ImageBatch batch = new ImageBatch();
        batch.setInfringementMode("OFFICIAL_HYBRID");
        batch.setProcessedCount(115);
        batch.setDuplicateCount(10);
        batch.setFailedCount(5);
        assertEquals("OFFICIAL_STANDARD", InfringementService.executionMode(batch));

        batch.setProcessedCount(116);
        assertEquals("OFFICIAL_BATCH", InfringementService.executionMode(batch));
    }

    @Test
    void storesTheActualExecutionModeOnNewItems() {
        when(tasks.insert(any(InfringementScanTask.class))).thenReturn(1);
        when(items.insert(any(InfringementScanItem.class))).thenReturn(1);
        when(assets.update(isNull(), any())).thenReturn(1);
        ImageBatch batch = new ImageBatch();
        batch.setId(3L);
        batch.setCreateBy(8L);
        batch.setInfringementMode("OFFICIAL_HYBRID");
        batch.setProcessedCount(106);
        batch.setDuplicateCount(5);
        batch.setFailedCount(0);

        service.createInitialTask(batch, List.of(image(10L, 8L)));

        ArgumentCaptor<InfringementScanItem> created = ArgumentCaptor.forClass(InfringementScanItem.class);
        verify(items).insert(created.capture());
        assertEquals("OFFICIAL_BATCH", created.getValue().getExecutionMode());
    }

    @Test
    void retryCreatesNoEmptyTaskWhenThisTaskHasNoCurrentFailures() {
        InfringementScanTask source = task(3L, 8L, 99L);
        when(tasks.selectById(3L)).thenReturn(source);
        when(items.selectCurrentFailedImagesByTask(3L, 8L)).thenReturn(List.of());
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(8L);
            login.when(LoginHelper::isAdmin).thenReturn(false);
            var result = service.retryFailedTask(3L);
            assertEquals(0, result.imageCount());
        }
        verify(tasks, times(0)).insert(any(InfringementScanTask.class));
    }

    @Test
    void retryCreatesNewCurrentItemAndPreservesFailedHistory() {
        ImageAsset failed = image(10L, 8L);
        failed.setStatus("APPROVED");
        failed.setInfringementScanItemId(20L);
        InfringementScanTask source = task(3L, 8L, 99L);
        when(tasks.selectById(3L)).thenReturn(source);
        when(items.selectCurrentFailedImagesByTask(3L, 8L)).thenReturn(List.of(failed));
        when(tasks.insert(any(InfringementScanTask.class))).thenReturn(1);
        when(items.insert(any(InfringementScanItem.class))).thenReturn(1);
        when(assets.update(isNull(), any())).thenReturn(1);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(8L);

            login.when(LoginHelper::isAdmin).thenReturn(false);
            var result = service.retryFailedTask(3L);

            assertEquals(1, result.imageCount());
        }
        verify(items).insert(any(InfringementScanItem.class));
        verify(items, times(0)).deleteById(any());
        verify(assets).update(isNull(), any());
    }

    @Test
    void retryRemovesItsEmptyTaskWhenAnotherRequestAlreadyReplacedTheItem() {
        ImageAsset failed = image(10L, 8L);
        failed.setStatus("APPROVED");
        failed.setInfringementScanItemId(20L);
        InfringementScanTask source = task(3L, 8L, 99L);
        when(tasks.selectById(3L)).thenReturn(source);
        when(items.selectCurrentFailedImagesByTask(3L, 8L)).thenReturn(List.of(failed));
        when(tasks.insert(any(InfringementScanTask.class))).thenReturn(1);
        when(items.insert(any(InfringementScanItem.class))).thenReturn(1);
        when(assets.update(isNull(), any())).thenReturn(0);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(8L);

            login.when(LoginHelper::isAdmin).thenReturn(false);
            var result = service.retryFailedTask(3L);

            assertEquals(0, result.imageCount());
        }
        verify(items).deleteById(anyLong());
        verify(tasks).deleteById(anyLong());
    }

    @Test
    void administratorKeepsSuccessfulImageResultWithoutCallingGeminiAgain() {
        ImageAsset approved = image(10L, 8L);
        approved.setStatus("APPROVED");
        approved.setBatchId(99L);
        approved.setInfringementScanItemId(20L);
        when(assets.selectById(10L)).thenReturn(approved);
        InfringementScanItem success = new InfringementScanItem();
        success.setStatus("SUCCESS");
        when(items.selectById(20L)).thenReturn(success);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            login.when(LoginHelper::isAdmin).thenReturn(true);

            var result = service.rescanImage(10L);

            assertEquals(0, result.imageCount());
        }
        verify(items, never()).insert(any(InfringementScanItem.class));
        verify(tasks, never()).insert(any(InfringementScanTask.class));
        verify(assets, never()).update(isNull(), any());
    }

    @Test
    void nonAdministratorCannotQueueManualRescan() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isAdmin).thenReturn(false);
            assertThrows(ServiceException.class, () -> service.rescanImage(10L));
        }
    }

    @Test
    void returnsVisualTagsFromStructuredAiResponseAndKeepsLegacyRowsCompatible() {
        InfringementScanTask task = new InfringementScanTask();
        task.setId(3L);
        task.setCreateBy(8L);
        InfringementDtos.ItemRow tagged = new InfringementDtos.ItemRow();
        tagged.setId(4L);
        tagged.setImageId(5L);
        tagged.setImageName("lion.png");
        tagged.setExecutionMode("OFFICIAL_BATCH");
        tagged.setRawAiResponse("{\"subjects\":[\"雌狮\"],\"scenes\":[],\"themes\":[\"复古\"],\"visualStyles\":[\"水彩插画\"],\"colors\":[\"红色\"]}");
        InfringementDtos.ItemRow legacy = new InfringementDtos.ItemRow();
        legacy.setId(6L);
        legacy.setImageId(7L);
        legacy.setImageName("legacy.png");
        when(tasks.selectById(3L)).thenReturn(task);
        when(items.selectItemPage(any(), anyLong(), any(), any())).thenAnswer(call -> {
            Page<InfringementDtos.ItemRow> page = call.getArgument(0);
            page.setRecords(List.of(tagged, legacy));
            page.setTotal(2);
            return page;
        });
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(8L);
            login.when(LoginHelper::isAdmin).thenReturn(false);
            var result = service.listItems(3L, null, null, new org.dromara.common.mybatis.core.page.PageQuery(10, 1));

            var rows = result.getRows().stream().toList();
            assertEquals(List.of("雌狮"), rows.getFirst().subjects());
            assertEquals(List.of("红色"), rows.getFirst().colors());
            assertEquals("OFFICIAL_BATCH", rows.getFirst().executionMode());
            assertEquals(List.of(), rows.get(1).subjects());
        }
    }

    private static ImageAsset image(long id, long ownerId) {
        ImageAsset image = new ImageAsset();
        image.setId(id);
        image.setCreateBy(ownerId);
        return image;
    }

    @Test
    void responseDetailsPreserveRawJsonAndRejectMissingCall() {
        when(tasks.selectById(3L)).thenReturn(task(3L, 8L, 99L));
        String raw = "{\"candidates\":[],\"providerExtra\":true}";
        when(callLogs.selectRawResponse(3L, 4L)).thenReturn(new InfringementDtos.CallResponseView(4L, raw));
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(8L);
            assertEquals(raw, service.callResponse(3L, 4L).rawProviderResponse());
            assertThrows(ServiceException.class, () -> service.callResponse(3L, 6L));
        }
    }

    @Test
    void responseDetailsRequireTaskOwnershipBeforeReadingRawData() {
        when(tasks.selectById(3L)).thenReturn(task(3L, 8L, 99L));
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(9L);
            assertThrows(ServiceException.class, () -> service.callResponse(3L, 4L));
        }
        verify(callLogs, never()).selectRawResponse(anyLong(), anyLong());
    }

    private static InfringementScanTask task(long id, long ownerId, long batchId) {
        InfringementScanTask task = new InfringementScanTask();
        task.setId(id);
        task.setCreateBy(ownerId);
        task.setSourceBatchId(batchId);
        return task;
    }
}
