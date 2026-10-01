package org.dromara.order.store.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.order.store.domain.StoreListingTemplate;
import org.dromara.order.store.domain.StoreListingExport;
import org.dromara.order.store.domain.StoreRenderBatch;
import org.dromara.order.store.domain.StoreShop;
import org.dromara.order.store.mapper.StoreListingExportMapper;
import org.dromara.order.store.mapper.StoreListingTemplateMapper;
import org.dromara.order.store.mapper.StoreRenderBatchMapper;
import org.dromara.order.store.mapper.StoreShopMapper;
import org.dromara.order.store.mapper.StoreRenderItemMapper;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.service.InfringementService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@Tag("local")
@ExtendWith(MockitoExtension.class)
class StoreListingServiceTest {
    @BeforeAll static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StoreListingExport.class);
    }
    @Mock StoreRenderBatchMapper batchMapper;
    @Mock StoreShopMapper shopMapper;
    @Mock StoreListingTemplateMapper templateMapper;
    @Mock StoreListingExportMapper exportMapper;
    @Mock StoreRenderItemMapper itemMapper;
    @Mock StoreObjectStorage storage;
    @Mock ImageAssetMapper imageMapper;
    @Mock InfringementService infringementService;
    @Mock UserService users;
    @InjectMocks StoreListingService service;

    @Test void completedRenderActivatesItsBoundListingExport() {
        service.processPending();
        verify(exportMapper).activateReady();
    }

    @Test void filenameFallbackStillRespectsListingRiskPolicy() {
        ImageAsset image = new ImageAsset();
        image.setId(1L); image.setCreateBy(42L); image.setStatus("APPROVED");
        image.setRiskLevel("HIGH"); image.setOriginalName("花卉.jpg");
        when(imageMapper.selectByIds(java.util.List.of(1L))).thenReturn(java.util.List.of(image));
        UserDTO policy = new UserDTO();
        policy.setListingAllRisksEnabled(false);
        when(users.selectById(42L)).thenReturn(policy);
        assertThrows(ServiceException.class, () -> service.snapshotKeywords(java.util.List.of(1L), 42L));
        policy.setListingAllRisksEnabled(true);
        assertEquals("花卉", service.snapshotKeywords(java.util.List.of(1L), 42L).get("1"));
    }

    @Test void generatedImageLinksHaveAnAbsolutePermanentAddress() {
        String key = "store/results/42/00000000-0000-0000-0000-000000000001.jpg";
        assertEquals("https://hytpod.com/prod-api/store/listing-exports/public-results?key=store%2Fresults%2F42%2F00000000-0000-0000-0000-000000000001.jpg",
            StoreListingService.publicResultUrl("https://hytpod.com/prod-api/store/listing-exports/public-results?key=", key));
        assertThrows(ServiceException.class, () -> StoreListingService.publicResultUrl("", key));
        assertThrows(ServiceException.class, () -> StoreListingService.publicResultUrl("/prod-api/store/files?key=", key));
        assertThrows(ServiceException.class, () -> StoreListingService.publicResultUrl("http://example.com/prod-api/store/listing-exports/public-results?key=", key));
        assertThrows(ServiceException.class, () -> service.publicResult(key));
        verify(storage, never()).open(anyString());
    }

    @Test void cannotExportWithAnotherExcelsConfiguration() {
        StoreRenderBatch batch = new StoreRenderBatch();
        batch.setId(1L); batch.setOwnerId(42L); batch.setStatus("COMPLETED");
        batch.setShopId(3L); batch.setCategoryId(4L); batch.setListingTemplateId(5L);
        StoreShop shop = new StoreShop();
        shop.setId(3L); shop.setOwnerId(42L); shop.setStatus("ACTIVE");
        StoreListingTemplate otherExcel = new StoreListingTemplate();
        otherExcel.setId(6L); otherExcel.setOwnerId(42L); otherExcel.setStatus("ACTIVE");
        otherExcel.setShopId(3L); otherExcel.setCategoryId(4L);
        when(batchMapper.selectById(1L)).thenReturn(batch);
        when(shopMapper.selectById(3L)).thenReturn(shop);
        when(templateMapper.selectById(6L)).thenReturn(otherExcel);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            assertThrows(ServiceException.class, () -> service.create(new StoreListingService.GenerateRequest(1L, 3L, 6L)));
        }
        verify(exportMapper, never()).insert(any(StoreListingExport.class));
    }

    @Test void reusesTheSecondShopsBoundExcelFromOneRenderBatch() {
        StoreRenderBatch batch = new StoreRenderBatch();
        batch.setId(1L); batch.setOwnerId(42L); batch.setStatus("COMPLETED"); batch.setCategoryId(4L);
        batch.setShopId(3L); batch.setListingTemplateId(5L);
        StoreShop shop = new StoreShop(); shop.setId(7L); shop.setOwnerId(42L); shop.setStatus("ACTIVE");
        StoreListingTemplate excel = new StoreListingTemplate();
        excel.setId(8L); excel.setOwnerId(42L); excel.setStatus("ACTIVE"); excel.setShopId(7L); excel.setCategoryId(4L);
        StoreListingExport export = new StoreListingExport();
        export.setId(9L); export.setBatchId(1L); export.setShopId(7L); export.setListingTemplateId(8L); export.setStatus("COMPLETED");
        when(batchMapper.selectById(1L)).thenReturn(batch);
        when(shopMapper.selectById(7L)).thenReturn(shop);
        when(templateMapper.selectById(8L)).thenReturn(excel);
        when(exportMapper.selectOne(any())).thenReturn(export);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            assertEquals(9L, service.create(new StoreListingService.GenerateRequest(1L, 7L, 8L)).id());
        }
        verify(exportMapper, never()).insert(any(StoreListingExport.class));
    }
}
