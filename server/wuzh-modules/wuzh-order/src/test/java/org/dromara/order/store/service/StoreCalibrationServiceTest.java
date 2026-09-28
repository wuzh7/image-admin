package org.dromara.order.store.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.domain.ImageCategory;
import org.dromara.image.mapper.ImageCategoryMapper;
import org.dromara.order.store.domain.StoreCalibration;
import org.dromara.order.store.domain.StoreDtos;
import org.dromara.order.store.domain.StoreTemplate;
import org.dromara.order.store.domain.StoreTemplateGroup;
import org.dromara.order.store.mapper.StoreTemplateGroupMapper;
import org.dromara.order.store.mapper.StoreTemplateMapper;
import org.dromara.order.store.mapper.StoreCalibrationMapper;
import org.dromara.order.store.mapper.StoreRenderBatchMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
@ExtendWith(MockitoExtension.class)
class StoreCalibrationServiceTest {
    @BeforeAll static void initializeJsonMapper() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("jsonMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        var ids = mock(com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator.class);
        when(ids.nextId(isNull())).thenReturn(1L);
        beans.registerSingleton("identifierGenerator", ids);
        new org.dromara.common.core.utils.SpringUtils().postProcessBeanFactory(beans);
    }

    @Mock StoreCalibrationMapper calibrationMapper;
    @Mock StoreTemplateGroupMapper groupMapper;
    @Mock StoreTemplateMapper templateMapper;
    @Mock StoreRenderBatchMapper batchMapper;
    @Mock ImageCategoryMapper categoryMapper;
    @Mock StoreObjectStorage storage;
    @InjectMocks StoreWorkflowService service;

    private static final String CONFIG = "{\"schemaVersion\":2,\"engine\":\"declarative-canvas-v1\",\"regions\":[{}]}";
    private static final String BACKGROUND = "store/templates/42/00000000-0000-0000-0000-000000000001.png";
    private static final String MASK = "store/templates/42/00000000-0000-0000-0000-000000000002.png";

    @Test
    void deletingPublishedBackgroundRemovesItFromItsGroup() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            var background = new StoreCalibration(); background.setId(7L); background.setOwnerId(42L);
            background.setBackgroundOssKey(BACKGROUND); background.setStatus("PUBLISHED");
            var removed = new StoreTemplate(); removed.setId(8L); removed.setGroupId(9L);
            removed.setOwnerId(42L); removed.setOssKey(BACKGROUND); removed.setStatus("ACTIVE");
            var remaining = new StoreTemplate(); remaining.setId(10L); remaining.setGroupId(9L);
            remaining.setStatus("ACTIVE"); remaining.setTemplateConfigJson("{\"sortOrder\":1,\"declaration\":{\"schemaVersion\":2},\"maskOssKey\":\"" + MASK + "\"}");
            var group = new StoreTemplateGroup(); group.setId(9L); group.setStatus("ACTIVE");
            when(calibrationMapper.selectById(7L)).thenReturn(background);
            when(templateMapper.selectList(any())).thenReturn(java.util.List.of(removed), java.util.List.of(remaining));
            when(groupMapper.selectById(9L)).thenReturn(group);
            service.deleteCalibration(7L);
            assertEquals("DELETED", background.getStatus());
            assertEquals("DELETED", removed.getStatus());
            assertEquals("ACTIVE", group.getStatus());
            assertEquals(MASK, group.getMaskOssKey());
            assertTrue(group.getCalibrationConfigJson().contains("schemaVersion"));
        }
    }

    @Test
    void publishesOwnedBackgroundAndMask() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            when(categoryMapper.selectById(3L)).thenReturn(new ImageCategory());
            when(storage.url(anyString(), anyString())).thenAnswer(invocation -> "/files/" + invocation.getArgument(0));
            var saved = service.saveCalibration(new StoreDtos.CalibrationRequest("地毯", 3L, CONFIG, BACKGROUND, MASK), null);
            assertEquals("PUBLISHED", saved.status());
            assertEquals(BACKGROUND, saved.backgroundOssKey());
            assertEquals(MASK, saved.maskOssKey());
            assertEquals(1, saved.version());
            verify(calibrationMapper).insert(any(StoreCalibration.class));
        }
    }

    @Test
    void refusesToEditAnotherUsersCalibration() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            StoreCalibration another = new StoreCalibration();
            another.setOwnerId(99L);
            when(calibrationMapper.selectById(7L)).thenReturn(another);
            assertThrows(ServiceException.class, () -> service.saveCalibration(
                new StoreDtos.CalibrationRequest("地毯", 3L, CONFIG, BACKGROUND, MASK), 7L));
            verify(calibrationMapper, never()).updateById(any(StoreCalibration.class));
        }
    }

    @Test
    void savesEachPublishedBackgroundWithItsOwnCalibrationAndOrder() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            when(categoryMapper.selectById(3L)).thenReturn(new ImageCategory());
            var first = new StoreCalibration(); first.setId(1L); first.setOwnerId(42L); first.setCategoryId(3L);
            first.setStatus("PUBLISHED"); first.setBackgroundOssKey(BACKGROUND); first.setCalibrationConfigJson(CONFIG); first.setMaskOssKey(MASK);
            var second = new StoreCalibration(); second.setId(2L); second.setOwnerId(42L); second.setCategoryId(3L);
            second.setStatus("PUBLISHED"); second.setBackgroundOssKey("store/templates/42/00000000-0000-0000-0000-000000000003.png");
            second.setCalibrationConfigJson("{\"schemaVersion\":2,\"engine\":\"declarative-canvas-v1\",\"regions\":[{\"name\":\"第二张\"}]}");
            when(calibrationMapper.selectById(1L)).thenReturn(first);
            when(calibrationMapper.selectById(2L)).thenReturn(second);
            when(templateMapper.selectList(any())).thenReturn(java.util.List.of());
            service.saveGroup(new StoreDtos.TemplateGroupRequest("地毯组", 3L, CONFIG, MASK, java.util.List.of(
                new StoreDtos.TemplateInput("第一张", BACKGROUND, null, "{\"calibrationId\":1}", 100, 100),
                new StoreDtos.TemplateInput("第二张", second.getBackgroundOssKey(), null, "{\"calibrationId\":2}", 100, 100))), null);
            var saved = org.mockito.ArgumentCaptor.forClass(StoreTemplate.class);
            verify(templateMapper, times(2)).insert(saved.capture());
            var firstConfig = org.dromara.common.json.utils.JsonUtils.parseObject(saved.getAllValues().get(0).getTemplateConfigJson(), java.util.Map.class);
            var secondConfig = org.dromara.common.json.utils.JsonUtils.parseObject(saved.getAllValues().get(1).getTemplateConfigJson(), java.util.Map.class);
            assertEquals(0, firstConfig.get("sortOrder"));
            assertEquals(1, secondConfig.get("sortOrder"));
            assertEquals(MASK, firstConfig.get("maskOssKey"));
            assertNull(secondConfig.get("maskOssKey"));
            assertNotEquals(firstConfig.get("declaration"), secondConfig.get("declaration"));
        }
    }

    @Test
    void changingLinkedBackgroundCategoryUpdatesItsGroupAndOtherBackgrounds() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            when(categoryMapper.selectById(4L)).thenReturn(new ImageCategory());
            when(storage.url(anyString(), anyString())).thenReturn("/background");
            var first = new StoreCalibration(); first.setId(1L); first.setOwnerId(42L);
            first.setCategoryId(3L); first.setStatus("PUBLISHED"); first.setVersion(1); first.setBackgroundOssKey(BACKGROUND);
            var second = new StoreCalibration(); second.setId(2L); second.setOwnerId(42L);
            second.setCategoryId(3L); second.setStatus("PUBLISHED");
            second.setBackgroundOssKey("store/templates/42/00000000-0000-0000-0000-000000000003.png");
            var a = new StoreTemplate(); a.setId(11L); a.setGroupId(9L); a.setOwnerId(42L);
            a.setCategoryId(3L); a.setStatus("ACTIVE"); a.setOssKey(BACKGROUND); a.setTemplateConfigJson("{}");
            var b = new StoreTemplate(); b.setId(12L); b.setGroupId(9L); b.setOwnerId(42L);
            b.setCategoryId(3L); b.setStatus("ACTIVE"); b.setOssKey(second.getBackgroundOssKey()); b.setTemplateConfigJson("{}");
            var group = new StoreTemplateGroup(); group.setId(9L); group.setCategoryId(3L);
            when(calibrationMapper.selectById(1L)).thenReturn(first);
            when(calibrationMapper.selectList(any())).thenReturn(java.util.List.of(first, second));
            when(templateMapper.selectList(any())).thenReturn(java.util.List.of(a), java.util.List.of(a, b));
            when(groupMapper.selectById(9L)).thenReturn(group);
            service.saveCalibration(new StoreDtos.CalibrationRequest("新类目背景", 4L,
                "{\"schemaVersion\":2,\"engine\":\"declarative-canvas-v1\",\"regions\":[{}],\"width\":100,\"height\":100}", BACKGROUND, null), 1L);
            assertEquals(4L, first.getCategoryId());
            assertEquals(4L, second.getCategoryId());
            assertEquals(4L, a.getCategoryId());
            assertEquals(4L, b.getCategoryId());
            assertEquals(4L, group.getCategoryId());
        }
    }

    @Test
    void restoresDeletedGroupAsInactiveOnly() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserIdStr).thenReturn("42");
            var group = new StoreTemplateGroup(); group.setOwnerId(42L); group.setStatus("DELETED");
            when(groupMapper.selectById(7L)).thenReturn(group);
            assertThrows(ServiceException.class, () -> service.setGroupStatus(7L, "ACTIVE"));
            service.setGroupStatus(7L, "INACTIVE");
            assertEquals("INACTIVE", group.getStatus());
            verify(groupMapper).updateById(group);
        }
    }
}
