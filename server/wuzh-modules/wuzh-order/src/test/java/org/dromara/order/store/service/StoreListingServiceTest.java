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
import org.dromara.order.store.domain.StoreDtos;
import org.dromara.order.store.domain.StoreRenderItem;
import org.dromara.image.storage.ImageStorage;
import org.dromara.common.json.utils.JsonUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
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
        var builder = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> type : List.of(StoreListingExport.class, ImageAsset.class, StoreRenderItem.class)) TableInfoHelper.initTableInfo(builder, type);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("jsonMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        var ids = mock(com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator.class);
        when(ids.nextId(isNull())).thenReturn(100L);
        beans.registerSingleton("identifierGenerator", ids);
        new org.dromara.common.core.utils.SpringUtils().postProcessBeanFactory(beans);
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
    @Test void completedRenderOnlyBatchCanGenerateItsFirstTableLater() {
        StoreRenderBatch batch = new StoreRenderBatch(); batch.setId(1L); batch.setOwnerId(42L); batch.setCategoryId(4L); batch.setStatus("COMPLETED");
        when(batchMapper.selectById(1L)).thenReturn(batch);
        StoreShop shop = new StoreShop(); shop.setId(3L); shop.setOwnerId(42L); shop.setStatus("ACTIVE");
        when(shopMapper.selectById(3L)).thenReturn(shop);
        when(templateMapper.selectById(5L)).thenReturn(template());
        StoreRenderItem item = new StoreRenderItem(); item.setImageId(11L);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
        UserDTO policy = new UserDTO(); policy.setListingAllRisksEnabled(true);
        when(users.selectById(42L)).thenReturn(policy);
        when(imageMapper.selectByIds(List.of(11L))).thenReturn(List.of(image()));
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            assertEquals(1L, service.create(new StoreListingService.GenerateRequest(1L, 3L, 5L)).batchId());
        }
        var saved = ArgumentCaptor.forClass(StoreListingExport.class);
        verify(exportMapper).insert(saved.capture());
        assertEquals(profile(), saved.getValue().getListingProfileJson());
        assertEquals(null, saved.getValue().getImageIdsJson());
    }

    @Test void createsOriginalTablesForTwoShopsWithoutCheckingRenderPolicy() {
        ImageAsset image = image();
        UserDTO policy = new UserDTO(); policy.setRenderAllRisksEnabled(false); policy.setListingAllRisksEnabled(true);
        when(users.selectById(42L)).thenReturn(policy);
        when(imageMapper.selectByIds(List.of(11L))).thenReturn(List.of(image));
        when(imageMapper.selectCount(any())).thenReturn(1L);
        for (long shopId : List.of(3L, 7L)) {
            StoreShop shop = new StoreShop(); shop.setId(shopId); shop.setOwnerId(42L); shop.setStatus("ACTIVE");
            StoreListingTemplate template = template(); template.setId(shopId + 2); template.setShopId(shopId);
            when(shopMapper.selectById(shopId)).thenReturn(shop);
            when(templateMapper.selectById(shopId + 2)).thenReturn(template);
        }
        var request = new StoreListingService.ImageGenerateRequest(4L, List.of(11L, 11L), List.of(
            new StoreDtos.RenderDestinationRequest(3L, 5L, null), new StoreDtos.RenderDestinationRequest(7L, 9L, null)), "original-tables");
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            assertEquals(2, service.createFromImages(request).size());
        }
        var saved = ArgumentCaptor.forClass(StoreListingExport.class);
        verify(exportMapper, times(2)).insert(saved.capture());
        for (StoreListingExport export : saved.getAllValues()) {
            assertEquals(null, export.getBatchId());
            assertEquals("PENDING", export.getStatus());
            assertEquals(List.of(11L), JsonUtils.parseArray(export.getImageIdsJson(), Long.class));
            assertEquals("original-tables", export.getIdempotencyKey());
        }
        verifyNoInteractions(batchMapper, itemMapper);
    }

    @Test void rejectsOriginalsOutsideSelectedCategory() {
        UserDTO policy = new UserDTO(); policy.setListingAllRisksEnabled(true);
        when(users.selectById(42L)).thenReturn(policy);
        when(imageMapper.selectByIds(List.of(11L))).thenReturn(List.of(image()));
        when(imageMapper.selectCount(any())).thenReturn(0L);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            assertThrows(ServiceException.class, () -> service.createFromImages(new StoreListingService.ImageGenerateRequest(
                99L, List.of(11L), List.of(new StoreDtos.RenderDestinationRequest(3L, 5L, null)), "wrong-category")));
        }
        verifyNoInteractions(batchMapper, itemMapper, exportMapper, templateMapper);
    }

    @Test void originalRetryUsesItsSelectedImagesWithoutRenderItems() {
        StoreListingExport export = new StoreListingExport(); export.setId(1L); export.setOwnerId(42L);
        export.setStatus("FAILED"); export.setImageIdsJson("[\"11\"]");
        when(exportMapper.selectById(1L)).thenReturn(export);
        UserDTO policy = new UserDTO(); policy.setListingAllRisksEnabled(true);
        when(users.selectById(42L)).thenReturn(policy);
        when(imageMapper.selectByIds(List.of(11L))).thenReturn(List.of(image()));
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            assertEquals("PENDING", service.retry(1L).status());
        }
        verifyNoInteractions(batchMapper, itemMapper);
        assertEquals("Flower", JsonUtils.parseObject(export.getListingKeywordsJson(), Map.class).get("11"));
    }

    @Test void workbookUsesOriginalOrCompletedRenderImages(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path source = directory.resolve("template.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("商品");
            String[] headers = {"商品层级", "SPU货号", "商品名称", "英文名称", "SKU货号", "SKU预览图-英语", "详情图文-英语", "轮播图1", "轮播图2"};
            var row = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) row.createCell(i).setCellValue(headers[i]);
            sheet.createRow(1).createCell(0).setCellValue("SPU");
            sheet.createRow(2).createCell(0).setCellValue("SKU");
            try (var output = Files.newOutputStream(source)) { workbook.write(output); }
        }
        var template = template(); template.setOssKey("template.xlsx");
        when(templateMapper.selectById(5L)).thenReturn(template);
        when(storage.open("template.xlsx")).thenReturn(new ImageStorage.LocalFile(source, false));
        when(imageMapper.selectByIds(List.of(11L))).thenReturn(List.of(image()));
        Path generated = directory.resolve("result.xlsx");
        when(storage.saveGenerated(eq(42L), any())).thenAnswer(call -> { Files.copy(call.getArgument(1, Path.class), generated, java.nio.file.StandardCopyOption.REPLACE_EXISTING); return "result.xlsx"; });
        when(storage.renderInputUrl(image().getStorageKey())).thenReturn(Optional.of("https://cdn.example/original.jpg"));
        StoreListingExport export = new StoreListingExport(); export.setOwnerId(42L); export.setShopId(3L);
        export.setListingTemplateId(5L); export.setListingProfileJson(profile()); export.setImageIdsJson("[\"11\"]");
        export.setListingKeywordsJson("{\"11\":\"Flower\"}");
        service.generate(export);
        try (var result = new XSSFWorkbook(generated.toFile())) {
            var sheet = result.getSheetAt(0);
            assertEquals("https://cdn.example/original.jpg", sheet.getRow(1).getCell(7).getStringCellValue());
            assertEquals("", sheet.getRow(1).getCell(8).getStringCellValue());
            assertEquals("https://cdn.example/original.jpg", sheet.getRow(2).getCell(5).getStringCellValue());
        }
        verifyNoInteractions(batchMapper, itemMapper);
        StoreRenderBatch batch = new StoreRenderBatch(); batch.setId(1L); batch.setOwnerId(42L); batch.setCategoryId(4L);
        batch.setStatus("COMPLETED"); batch.setSelectedCount(2);
        when(batchMapper.selectById(1L)).thenReturn(batch);
        StoreRenderItem first = new StoreRenderItem(); first.setImageId(11L); first.setStatus("SUCCEEDED");
        first.setResultOssKey("render-public/results/42/00000000-0000-0000-0000-000000000001.jpg");
        StoreRenderItem second = new StoreRenderItem(); second.setImageId(11L); second.setStatus("SKIPPED");
        second.setResultOssKey("render-public/results/42/00000000-0000-0000-0000-000000000002.jpg");
        when(itemMapper.selectList(any())).thenReturn(List.of(first, second));
        when(storage.url(first.getResultOssKey(), "image/*")).thenReturn("https://cdn.example/render-1.jpg");
        when(storage.url(second.getResultOssKey(), "image/*")).thenReturn("https://cdn.example/render-2.jpg");
        export.setBatchId(1L); export.setImageIdsJson(null);
        service.generate(export);
        try (var result = new XSSFWorkbook(generated.toFile())) {
            var sheet = result.getSheetAt(0);
            assertEquals("https://cdn.example/render-1.jpg", sheet.getRow(1).getCell(7).getStringCellValue());
            assertEquals("https://cdn.example/render-2.jpg", sheet.getRow(1).getCell(8).getStringCellValue());
            assertEquals("https://cdn.example/render-2.jpg", sheet.getRow(2).getCell(5).getStringCellValue());
        }
        verify(storage, times(1)).renderInputUrl(anyString());
        batch.setStatus("PARTIAL_FAILURE");
        assertThrows(ServiceException.class, () -> service.generate(export));
    }

    @Test void onlyExportedOriginalsCanBePubliclyRead() {
        String key = image().getStorageKey();
        when(imageMapper.selectCount(any())).thenReturn(0L, 1L);
        assertThrows(ServiceException.class, () -> service.publicResult(key));
        verify(storage, never()).open(anyString());
        service.publicResult(key);
        verify(storage).open(key);
        assertThrows(ServiceException.class, () -> service.publicResult("library/2026/10/../../private.jpg"));
    }

    private static ImageAsset image() {
        ImageAsset image = new ImageAsset(); image.setId(11L); image.setCreateBy(42L); image.setCategoryId(4L);
        image.setStatus("APPROVED"); image.setRiskLevel("HIGH"); image.setOriginalName("Flower.jpg");
        image.setStorageKey("library/2026/10/11-abcdef0123456789.jpg");
        return image;
    }
    private static StoreListingTemplate template() {
        StoreListingTemplate template = new StoreListingTemplate(); template.setId(5L); template.setShopId(3L);
        template.setOwnerId(42L); template.setCategoryId(4L); template.setStatus("ACTIVE"); template.setConfigJson(profile());
        return template;
    }
    private static String profile() {
        return """
            {"titleLanguage":"en","keywordMode":"image","words":{
            "required":[{"en":"Art"}],"material":[{"en":"Cotton"}],"hot":[{"en":"Popular"}],
            "holiday":[{"en":"Christmas"}],"scene":[{"en":"Home"}]},
            "selected":{"required":0,"material":0,"hot":0,"holiday":-1,"scene":0},
            "previewIndex":2,"skuStart":1,"skuDigits":2,"skuSeparator":"-","detailSeparator":"|","carouselScope":"both"}
            """;
    }

}
