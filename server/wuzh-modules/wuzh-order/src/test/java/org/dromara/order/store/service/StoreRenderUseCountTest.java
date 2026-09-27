package org.dromara.order.store.service;

import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.order.store.domain.StoreDtos;
import org.dromara.order.store.domain.StoreRenderItem;
import org.dromara.order.store.mapper.StoreRenderBatchMapper;
import org.dromara.order.store.mapper.StoreRenderItemMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
@ExtendWith(MockitoExtension.class)
class StoreRenderUseCountTest {
    @Mock StoreRenderItemMapper itemMapper;
    @Mock StoreRenderBatchMapper batchMapper;
    @Mock ImageAssetMapper imageMapper;
    @Mock StoreObjectStorage storage;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks StoreWorkflowService service;

    @Test
    void countsOnlyFirstSuccessfulTemplateInAGroup() {
        StoreRenderItem item = new StoreRenderItem();
        item.setId(1L); item.setImageId(2L); item.setTemplateGroupId(3L); item.setBatchId(4L);
        when(itemMapper.selectById(1L)).thenReturn(item);
        when(itemMapper.complete(eq(1L), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        doReturn(2L).when(jdbcTemplate).queryForObject(startsWith("SELECT id"), eq(Long.class), eq(2L));
        doReturn(0L, 1L).when(jdbcTemplate).queryForObject(startsWith("SELECT COUNT"), eq(Long.class), eq(2L), eq(3L));
        var request = new StoreDtos.CompleteRequest("worker", "lease", new StoreDtos.Result("result.png", "hash", 100, 100));
        service.complete(1L, request);
        service.complete(1L, request);
        verify(imageMapper, times(1)).update(isNull(), any());
        verify(itemMapper, never()).updateById(any(StoreRenderItem.class));
    }
}
