package org.dromara.order.store.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.order.store.domain.StoreDtos;
import org.dromara.order.store.service.StoreReferenceService;
import java.util.List;
import org.dromara.order.store.service.StoreWorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("local")
class StoreWorkflowControllerTest {
    @Test
    void streamsOwnedFileWithoutProxyBuffering() throws Exception {
        StoreWorkflowService service = mock(StoreWorkflowService.class);
        when(service.ownedFile("store/templates/42/background.png")).thenReturn(output -> { });
        MockMvc mvc = standaloneSetup(new StoreWorkflowController(service, mock(StoreReferenceService.class))).build();

        mvc.perform(get("/store/files").param("key", "store/templates/42/background.png"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Accel-Buffering", "no"));
    }

    @Test
    void combinedTaskRequiresListingPermissionBeforeCreatingBatch() {
        StoreWorkflowService service = mock(StoreWorkflowService.class);
        var controller = new StoreWorkflowController(service, mock(StoreReferenceService.class));
        var request = new StoreDtos.RenderBatchRequest(1L, 2L, List.of(3L), null, null,
            List.of(new StoreDtos.RenderDestinationRequest(4L, 5L, null)), "jpg", 90, "combined");
        try (var permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.checkPermission("store:listing-export:add"))
                .thenThrow(new ServiceException("无表格权限"));
            assertThrows(ServiceException.class, () -> controller.createBatch(request));
        }
        verifyNoInteractions(service);
    }

    @Test
    void returnsWorkerAssetUrlsAsData() throws Exception {
        StoreWorkflowService service = mock(StoreWorkflowService.class);
        when(service.imageAccess(1)).thenReturn("/store/internal/images/1/file");
        when(service.templateAccess(2)).thenReturn("/store/internal/templates/2/file");
        when(service.maskAccess(3)).thenReturn("/store/internal/masks/3/file");
        StoreWorkflowController controller = new StoreWorkflowController(service, mock(StoreReferenceService.class));
        ReflectionTestUtils.setField(controller, "workerToken", "test-token");
        MockMvc mvc = standaloneSetup(controller).build();

        for (String path : new String[] {"images/1", "templates/2", "masks/3"}) {
            mvc.perform(get("/store/internal/" + path + "/access").header("X-Render-Worker-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("/store/internal/" + path + "/file"));
        }
    }
}
