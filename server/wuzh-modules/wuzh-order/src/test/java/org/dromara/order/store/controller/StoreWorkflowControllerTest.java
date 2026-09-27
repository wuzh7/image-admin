package org.dromara.order.store.controller;

import org.dromara.order.store.service.StoreReferenceService;
import org.dromara.order.store.service.StoreWorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("local")
class StoreWorkflowControllerTest {
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
