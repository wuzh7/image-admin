package org.dromara.image.service;

import org.dromara.image.domain.StoreProbe;
import org.dromara.image.domain.StoreProbeDtos;
import org.dromara.image.mapper.StoreProbeMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@org.junit.jupiter.api.Tags({@Tag("dev"), @Tag("local")})
class StoreProbeServiceTest {
    private final StoreProbeMapper mapper = mock(StoreProbeMapper.class);
    private final StoreProbeService service = new StoreProbeService(mapper);

    @Test
    void checksStoreIdAndReadsNamesFromTheDatabaseMapper() {
        StoreProbe row = new StoreProbe();
        row.setStoreId("store-1");
        row.setStoreName("测试店铺");
        row.setRemarks("主站备注");
        when(mapper.selectCount(any())).thenReturn(1L, 0L);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertTrue(service.contains(" store-1 "));
        assertFalse(service.contains("missing"));
        assertEquals(new StoreProbeService.StoreInfo("测试店铺", "主站备注"), service.read().get("store-1"));
    }

    @Test
    void editingCanClearOptionalFields() {
        StoreProbe row = new StoreProbe();
        row.setId(1L);
        row.setStoreId("store-1");
        row.setStoreName("旧名称");
        row.setRemarks("旧备注");
        when(mapper.selectById(1L)).thenReturn(row);
        when(mapper.updateById(row)).thenReturn(1);

        service.save(new StoreProbeDtos.SaveRequest(1L, "store-1", null, null), true);

        assertEquals("", row.getStoreName());
        assertEquals("", row.getRemarks());
        verify(mapper).updateById(row);
    }

    @Test
    void removesStoreFromDatabase() {
        when(mapper.deleteById(1L)).thenReturn(1);

        service.remove(1L);

        verify(mapper).deleteById(1L);
    }
}
