package org.dromara.order.store.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.mapper.ImageCategoryMapper;
import org.dromara.order.store.domain.StoreDtos;
import org.dromara.order.store.domain.StoreShop;
import org.dromara.order.store.mapper.StoreShopMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
@ExtendWith(MockitoExtension.class)
class StoreShopServiceTest {
    @BeforeAll static void initializeJsonMapper() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("jsonMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        var ids = mock(com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator.class);
        when(ids.nextId(isNull())).thenReturn(1L);
        beans.registerSingleton("identifierGenerator", ids);
        new org.dromara.common.core.utils.SpringUtils().postProcessBeanFactory(beans);
    }

    @Mock StoreShopMapper shopMapper;
    @Mock ImageCategoryMapper categoryMapper;
    @Mock StoreReferenceService references;
    @InjectMocks StoreWorkflowService service;

    private static StoreDtos.ShopRequest request(String code, List<Long> categories) {
        return new StoreDtos.ShopRequest(" 栖木家居 ", null, null, code, null, null, categories, "备注", 7L, 8L, List.of(9L, 10L));
    }

    @Test void createsShopWithDemoFieldsAndCategoryScope() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            when(references.operatorName(7L)).thenReturn("林知夏");
            when(references.optionName("PLATFORM", 8L)).thenReturn("Temu");
            when(references.optionName("SITE", 9L)).thenReturn("US / USD");
            when(references.optionName("SITE", 10L)).thenReturn("EU / EUR");
            when(categoryMapper.selectCount(any())).thenReturn(2L);
            var result = service.saveShop(request("shop-a", List.of(3L, 4L)), null);
            var saved = ArgumentCaptor.forClass(StoreShop.class);
            verify(shopMapper).insert(saved.capture());
            assertEquals("shop-a", result.shopCode());
            assertEquals("栖木家居", result.name());
            assertEquals(42L, saved.getValue().getOwnerId());
            Map<?, ?> config = org.dromara.common.json.utils.JsonUtils.parseObject(saved.getValue().getConfigJson(), Map.class);
            assertEquals("林知夏", config.get("operator"));
            assertEquals("Temu", saved.getValue().getPlatform());
            assertEquals(List.of(9, 10), config.get("siteIds"));
            assertEquals(List.of(3, 4), config.get("categoryIds"));
        }
    }

    @Test void rejectsInvalidCategoriesAndDuplicateShopCode() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            when(references.operatorName(7L)).thenReturn("林知夏");
            when(references.optionName("PLATFORM", 8L)).thenReturn("Temu");
            when(references.optionName("SITE", 9L)).thenReturn("US / USD");
            when(references.optionName("SITE", 10L)).thenReturn("EU / EUR");
            when(categoryMapper.selectCount(any())).thenReturn(1L);
            assertThrows(ServiceException.class, () -> service.saveShop(request("shop-a", List.of(3L, 4L)), null));
            verify(shopMapper, never()).insert(any(StoreShop.class));
            when(categoryMapper.selectCount(any())).thenReturn(2L);
            when(shopMapper.insert(any(StoreShop.class))).thenThrow(new DuplicateKeyException("duplicate"));
            ServiceException error = assertThrows(ServiceException.class, () -> service.saveShop(request("shop-a", List.of(3L, 4L)), null));
            assertEquals("店铺 ID 已存在", error.getMessage());
        }
    }
}
