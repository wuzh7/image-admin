package org.dromara.order.store.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.service.InfringementService;
import org.dromara.image.storage.ImageStorage;
import org.dromara.order.store.domain.StoreRenderBatch;
import org.dromara.order.store.domain.StoreRenderItem;
import org.dromara.order.store.mapper.StoreRenderBatchMapper;
import org.dromara.order.store.mapper.StoreRenderItemMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
@ExtendWith(MockitoExtension.class)
class StoreRenderArchiveTest {
    @BeforeAll static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StoreRenderItem.class);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("jsonMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        new org.dromara.common.core.utils.SpringUtils().postProcessBeanFactory(beans);
    }

    @Mock StoreRenderBatchMapper batchMapper;
    @Mock StoreRenderItemMapper itemMapper;
    @Mock ImageAssetMapper imageMapper;
    @Mock InfringementService infringementService;
    @Mock ImageStorage imageStorage;
    @Mock StoreObjectStorage storeStorage;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks StoreWorkflowService service;

    @Test void includesOnlyNumberedRenderedFiles() {
        StoreRenderBatch batch = new StoreRenderBatch(); batch.setOwnerId(42L); batch.setStatus("COMPLETED");
        when(batchMapper.selectById(1L)).thenReturn(batch);
        StoreRenderItem first = new StoreRenderItem(); first.setId(11L); first.setImageId(2L); first.setTemplateId(3L); first.setOutputFormat("png"); first.setResultOssKey("result-1");
        StoreRenderItem second = new StoreRenderItem(); second.setId(12L); second.setImageId(2L); second.setTemplateId(4L); second.setOutputFormat("png"); second.setResultOssKey("result-2");
        when(itemMapper.selectList(any())).thenReturn(List.of(second, first));
        ImageAsset image = new ImageAsset(); image.setId(2L); image.setOriginalName("a.jpg"); image.setStorageKey("original-a"); image.setContentType("image/png"); image.setInfringementScanItemId(5L);
        when(imageMapper.selectByIds(any())).thenReturn(List.of(image));
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            var files = service.archiveFiles(1L, "ORIGINAL_NAME");
            assertEquals(List.of("a(1).png", "a(2).png"), files.stream().map(file -> file.filename()).toList());
            assertEquals(List.of("result-1", "result-2"), files.stream().map(file -> file.ossKey()).toList());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            service.streamArchiveFile(files.get(0), output);
            verify(imageStorage, never()).open(any());
            verify(storeStorage).stream("result-1", output);
            verify(storeStorage, never()).open(any());
            assertThrows(org.dromara.common.core.exception.ServiceException.class, () -> service.archiveFiles(1L, "ENGLISH_KEYWORDS"));
            InfringementScanItem scan = new InfringementScanItem(); scan.setId(5L); scan.setStatus("SUCCESS"); scan.setImageDescription("Blue Cat");
            when(infringementService.currentResults(any())).thenReturn(Map.of(5L, scan));
            assertEquals(List.of("Blue Cat(1).png", "Blue Cat(2).png"),
                service.archiveFiles(1L, "ENGLISH_KEYWORDS").stream().map(file -> file.filename()).toList());
        }
    }

    @Test void stoppingBatchPreservesRunningWorkAndClosesNewClaims() {
        StoreRenderBatch batch = new StoreRenderBatch(); batch.setId(1L); batch.setOwnerId(42L); batch.setStatus("RUNNING");
        when(batchMapper.selectById(1L)).thenReturn(batch);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            assertEquals("STOPPING", service.stopBatch(1L).status());
        }
        verify(batchMapper).stopIfIdle(1L);
        verify(itemMapper, never()).update(any(), any());
    }

    @Test void recordsDownloadAndDeletesOnlyResultReferences() {
        StoreRenderBatch batch = new StoreRenderBatch(); batch.setId(1L); batch.setOwnerId(42L); batch.setStatus("COMPLETED");
        when(batchMapper.selectById(1L)).thenReturn(batch);
        when(itemMapper.selectCount(any())).thenReturn(1L);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            service.confirmDownload(1L);
            assertNotNull(batch.getDownloadedAt());
            service.deleteResults(1L);
            assertEquals("DELETED", batch.getStatus());
        }
        verify(itemMapper).update(isNull(), any());
        verify(storeStorage).deleteRenderArchives(42L, 1L);
        verify(storeStorage, never()).delete(any());
    }

    @Test void sharedResultFileIsNotRemovedWhileAnotherBatchUsesIt() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of("store/results/42/00000000-0000-0000-0000-000000000001.png"));
        when(itemMapper.selectCount(any())).thenReturn(1L);
        service.purgeDeletedResultFiles();
        verify(storeStorage, never()).delete(any());
        when(itemMapper.selectCount(any())).thenReturn(0L);
        service.purgeDeletedResultFiles();
        verify(storeStorage).delete("store/results/42/00000000-0000-0000-0000-000000000001.png");
    }

    @Test void recentlyDownloadedBatchIsNotDeleted() {
        StoreRenderBatch batch = new StoreRenderBatch();
        batch.setDownloadedAt(LocalDateTime.now().minusHours(1));
        batch.setStatus("COMPLETED");
        when(batchMapper.selectById(1L)).thenReturn(batch);
        service.deleteExpiredResults(1L);
        verifyNoInteractions(jdbcTemplate);
        verify(storeStorage, never()).deleteRenderArchives(anyLong(), anyLong());
    }
}
