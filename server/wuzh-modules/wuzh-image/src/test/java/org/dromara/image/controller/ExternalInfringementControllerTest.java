package org.dromara.image.controller;

import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ExternalInfringementDtos;
import org.dromara.image.service.ExternalInfringementService;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("local")
class ExternalInfringementControllerTest {
    @Test
    void resultRequiresApiKeyAndReadsCapitalizedCaseNumber() throws Exception {
        var service = mock(ExternalInfringementService.class);
        var properties = new ImageProperties();
        properties.getInfringement().setExternalApiKey("secret");
        var mvc = standaloneSetup(new ExternalInfringementController(service, properties, mock(ImageStorage.class))).build();
        String body = """
            {"imageId":"11","claimToken":"token","riskLevel":"needs_review",
             "riskReason":"疑似侵权","CaseNumber":["26-cv-1"],"verificationUrls":[]}
            """;
        mvc.perform(post("/open-api/infringement/images/result")
            .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);

        mvc.perform(post("/open-api/infringement/images/result")
            .header("X-API-Key", "secret").contentType("application/json").content(body))
            .andExpect(status().isOk());
        var result = org.mockito.ArgumentCaptor.forClass(ExternalInfringementDtos.Result.class);
        verify(service).complete(result.capture());
        assertEquals("26-cv-1", result.getValue().caseNumbers().getFirst());
    }
}
