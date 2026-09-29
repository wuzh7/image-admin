package org.dromara.order.store.controller;

import org.dromara.order.store.service.StoreReferenceService;
import org.dromara.order.store.service.StoreWorkflowService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("local")
class StoreWorkflowControllerDownloadTest {
    @Test void downloadUrlIsResponseData() {
        StoreWorkflowService service = mock(StoreWorkflowService.class);
        when(service.archiveUrl(1L, "ORIGINAL_NAME")).thenReturn(Optional.of("https://example.com/render.zip"));

        var controller = new StoreWorkflowController(service, mock(StoreReferenceService.class));

        assertEquals("https://example.com/render.zip", controller.downloadUrl(1L, "ORIGINAL_NAME").getData());
    }
}
