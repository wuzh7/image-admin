package org.dromara.order.store.controller;

import org.dromara.image.storage.ImageStorage;
import org.dromara.order.store.service.StoreListingService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("local")
class StoreListingControllerTest {
    @Test void imageLinksReturnInlineContent(@TempDir Path directory) throws Exception {
        var service = mock(StoreListingService.class);
        String key = "render-public/results/42/00000000-0000-0000-0000-000000000001.png";
        byte[] bytes = {1, 2, 3};
        Path image = Files.write(directory.resolve("result.png"), bytes);
        when(service.publicResult(key)).thenReturn(new ImageStorage.LocalFile(image, false));
        var mvc = standaloneSetup(new StoreListingController(service)).build();
        var response = mvc.perform(get("/store/listing-exports/public-results").param("key", key))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(response))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "inline"))
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(content().bytes(bytes));
    }
}
