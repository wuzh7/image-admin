package org.dromara.order.store.service;

import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.order.store.domain.StoreDtos;
import org.dromara.order.store.domain.StoreListingTemplate;
import org.dromara.order.store.domain.StoreShop;
import org.dromara.order.store.domain.StoreTemplate;
import org.dromara.order.store.domain.StoreTemplateGroup;
import org.dromara.order.store.domain.StoreListingExport;
import org.dromara.order.store.domain.StoreRenderBatch;
import org.dromara.order.store.domain.StoreRenderItem;
import org.dromara.order.store.mapper.StoreListingExportMapper;
import org.dromara.order.store.mapper.StoreListingTemplateMapper;
import org.dromara.order.store.mapper.StoreTemplateGroupMapper;
import org.dromara.order.store.mapper.StoreTemplateMapper;
import org.dromara.order.store.mapper.StoreRenderBatchMapper;
import org.dromara.order.store.mapper.StoreRenderItemMapper;
import org.dromara.order.store.mapper.StoreShopMapper;
import org.dromara.image.domain.ImageCategory;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageCategoryMapper;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.service.InfringementService;
import org.dromara.system.api.UserService;
import org.dromara.system.api.domain.UserDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;

@Tag("local")
@ExtendWith(MockitoExtension.class)
class StoreListingProfileServiceTest {
    @BeforeAll static void initializeJsonMapper() {
        var builder = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(builder, ImageAsset.class);
        TableInfoHelper.initTableInfo(builder, StoreRenderItem.class);
        TableInfoHelper.initTableInfo(builder, StoreRenderBatch.class);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("jsonMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        var ids = mock(com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator.class);
        when(ids.nextId(isNull())).thenReturn(100L);
        beans.registerSingleton("identifierGenerator", ids);
        new org.dromara.common.core.utils.SpringUtils().postProcessBeanFactory(beans);
    }

    @Mock StoreListingTemplateMapper templateMapper;
    @Mock StoreShopMapper shopMapper;
    @Mock ImageCategoryMapper categoryMapper;
    @Mock StoreTemplateGroupMapper groupMapper;
    @Mock StoreTemplateMapper storeTemplateMapper;
    @Mock StoreRenderBatchMapper batchMapper;
    @Mock StoreRenderItemMapper itemMapper;
    @Mock StoreListingExportMapper exportMapper;
    @Mock ImageAssetMapper imageMapper;
    @Mock InfringementService infringementService;
    @Mock StoreListingService listingService;
    @Mock UserService users;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks StoreWorkflowService service;

    @Test void savesConfigurationOnSelectedExcelOnly() {
        StoreListingTemplate first = template(1L);
        StoreListingTemplate second = template(2L);
        when(templateMapper.selectById(1L)).thenReturn(first);
        when(templateMapper.selectById(2L)).thenReturn(second);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            service.saveListingProfile(1L, new StoreDtos.ListingProfileRequest("{\"skuStart\":1}"));
            service.saveListingProfile(2L, new StoreDtos.ListingProfileRequest("{\"skuStart\":9}"));
        }
        assertEquals("{\"skuStart\":1}", first.getConfigJson());
        assertEquals("{\"skuStart\":9}", second.getConfigJson());
        verify(templateMapper).updateById(first);
        verify(templateMapper).updateById(second);
    }

    @Test void numbersExcelsWithinEachShopAndCategory() {
        StoreShop shop = new StoreShop();
        shop.setId(3L); shop.setOwnerId(42L); shop.setStatus("ACTIVE");
        shop.setName("测试店铺"); shop.setShopCode("shop-a");
        ImageCategory home = new ImageCategory(); home.setId(4L); home.setName("家居收纳");
        ImageCategory outdoor = new ImageCategory(); outdoor.setId(5L); outdoor.setName("户外用品");
        when(shopMapper.selectById(3L)).thenReturn(shop);
        when(categoryMapper.selectById(4L)).thenReturn(home);
        when(categoryMapper.selectById(5L)).thenReturn(outdoor);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(3L))).thenReturn(3L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(42L), eq(3L), eq(4L))).thenReturn(3);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(42L), eq(3L), eq(5L))).thenReturn(1);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            var third = service.saveListingTemplate(new StoreDtos.ListingTemplateRequest(3L, 4L, "a.xlsx", "store/listing/42/1234abcd.xlsx", "a.xlsx", "{\"skuStart\":1}"), null);
            var first = service.saveListingTemplate(new StoreDtos.ListingTemplateRequest(3L, 5L, "b.xlsx", "store/listing/42/5678abcd.xlsx", "b.xlsx", "{\"skuStart\":9}"), null);
            assertEquals("测试店铺(shop-a)_家居收纳_003", third.templateCode());
            assertEquals("测试店铺(shop-a)_户外用品_001", first.templateCode());
            assertEquals("{\"skuStart\":1}", third.configJson());
            assertEquals("{\"skuStart\":9}", first.configJson());
        }
    }

    @Test void doesNotCreateExcelWithoutConfiguration() {
        assertThrows(org.dromara.common.core.exception.ServiceException.class, () ->
            service.saveListingTemplate(new StoreDtos.ListingTemplateRequest(3L, 4L, "a.xlsx", "store/listing/42/1234abcd.xlsx", "a.xlsx", null), null));
        verifyNoInteractions(templateMapper);
    }

    @Test void oneRenderBatchKeepsSeparateProfilesForTwoShops() {
        StoreTemplateGroup group = new StoreTemplateGroup();
        group.setId(9L); group.setOwnerId(42L); group.setCategoryId(4L); group.setStatus("ACTIVE");
        StoreTemplate background = new StoreTemplate();
        background.setId(10L); background.setGroupId(9L); background.setStatus("ACTIVE"); background.setOssKey("store/templates/42/a.png");
        when(groupMapper.selectById(9L)).thenReturn(group);
        when(storeTemplateMapper.selectList(any())).thenReturn(List.of(background));
        when(imageMapper.selectCount(any())).thenReturn(2L);
        when(users.selectById(42L)).thenReturn(new UserDTO());
        when(listingService.snapshotKeywords(List.of(11L, 13L), 42L))
            .thenReturn(Map.of("11", "Autumn wall art", "13", "AI canvas print"));
        when(itemMapper.selectList(any())).thenReturn(List.of());
        String profile = "{\"words\":{\"hot\":[{\"en\":\"A\"},{\"en\":\"B\"}]},\"selected\":{\"hot\":0}}";
        for (long shopId : List.of(3L, 7L)) {
            StoreShop shop = new StoreShop(); shop.setId(shopId); shop.setOwnerId(42L); shop.setStatus("ACTIVE");
            StoreListingTemplate excel = template(shopId + 2); excel.setShopId(shopId); excel.setCategoryId(4L); excel.setConfigJson(profile);
            when(shopMapper.selectById(shopId)).thenReturn(shop);
            when(templateMapper.selectById(shopId + 2)).thenReturn(excel);
        }
        var request = new StoreDtos.RenderBatchRequest(4L, 9L, List.of(11L, 13L), null, null,
            List.of(new StoreDtos.RenderDestinationRequest(3L, 5L, Map.of("hot", 1)),
                new StoreDtos.RenderDestinationRequest(7L, 9L, null)), "JPEG", 90, "two-shops");
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            service.createBatch(request);
        }
        ArgumentCaptor<StoreListingExport> exports = ArgumentCaptor.forClass(StoreListingExport.class);
        verify(exportMapper, times(2)).insert((StoreListingExport) exports.capture());
        assertEquals(3L, exports.getAllValues().get(0).getShopId());
        assertEquals(7L, exports.getAllValues().get(1).getShopId());
        assertTrue(exports.getAllValues().get(0).getListingProfileJson().contains("\"hot\":1"));
        assertEquals(profile, exports.getAllValues().get(1).getListingProfileJson());
        assertTrue(exports.getAllValues().get(0).getListingKeywordsJson().contains("Autumn wall art"));
        assertTrue(exports.getAllValues().get(0).getListingKeywordsJson().contains("AI canvas print"));
        assertTrue(!exports.getAllValues().get(0).getListingKeywordsJson().contains("Display name must be ignored"));
        verify(batchMapper, times(1)).insert(any(StoreRenderBatch.class));
    }

    @Test void renderRestrictionRejectsImageWithoutCompletedLowRiskAnalysis() {
        UserDTO policy = new UserDTO(); policy.setRenderAllRisksEnabled(false);
        when(users.selectById(42L)).thenReturn(policy);
        StoreTemplateGroup group = new StoreTemplateGroup();
        group.setId(9L); group.setOwnerId(42L); group.setCategoryId(4L); group.setStatus("ACTIVE");
        when(groupMapper.selectById(9L)).thenReturn(group);
        StoreTemplate template = new StoreTemplate(); template.setId(10L); template.setGroupId(9L); template.setStatus("ACTIVE");
        when(storeTemplateMapper.selectList(any())).thenReturn(List.of(template));
        when(imageMapper.selectCount(any())).thenReturn(1L, 0L);
        var request = new StoreDtos.RenderBatchRequest(4L, 9L, List.of(11L), null, null, null, "jpg", 90, "restricted");
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            var error = assertThrows(org.dromara.common.core.exception.ServiceException.class, () -> service.createBatch(request));
            assertEquals("仅低风险且分析完成的图片可以渲染", error.getMessage());
        }
        verify(batchMapper, never()).insert(any(StoreRenderBatch.class));
    }

    private static StoreListingTemplate template(long id) {
        StoreListingTemplate template = new StoreListingTemplate();
        template.setId(id);
        template.setOwnerId(42L);
        template.setStatus("ACTIVE");
        return template;
    }
}
