package org.dromara.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class ImageRiskControllerTest {
    @Test
    void returnsVisualAnalysisWithoutInventingRisk() throws Exception {
        RelayImageClient client = mock(RelayImageClient.class);
        when(client.model()).thenReturn("doubao-seed-2-0-lite-260428");
        when(client.analyze(new byte[]{1}, "image/png")).thenReturn(new RelayImageClient.Analysis("""
            {"descriptionKeywords":"A red flower on white.","subjects":["花卉"],"scenes":[],
             "themes":[],"visualStyles":[],"colors":["红色"]}
            """, null, "provider response"));
        var controller = new ImageRiskController(client, new ImageRiskParser(), "token");

        var result = controller.analyze("token", "request", "RELAY", "image/png",
            new MockMultipartFile("image", "a.png", "image/png", new byte[]{1}));

        assertEquals(null, result.riskLevel());
        assertEquals(null, result.riskReason());
        assertEquals("A red flower on white.", result.imageDescription());
        assertEquals("relay_image_analysis_v1", result.promptVersion());
        assertEquals("doubao-seed-2-0-lite-260428", result.model());
        assertEquals("provider response", result.rawProviderResponse());
        verify(client, times(1)).analyze(new byte[]{1}, "image/png");
    }

    @Test
    void rejectsOldModeAndWrongTokenBeforeCallingProvider() throws Exception {
        RelayImageClient client = mock(RelayImageClient.class);
        var controller = new ImageRiskController(client, new ImageRiskParser(), "token");
        var image = new MockMultipartFile("image", "a.png", "image/png", new byte[]{1});
        assertEquals("INVALID_ANALYSIS_MODE", assertThrows(GatewayException.class,
            () -> controller.analyze("token", "request", "OFFICIAL_HYBRID", "image/png", image)).code());
        assertEquals("UNAUTHORIZED", assertThrows(GatewayException.class,
            () -> controller.analyze("wrong", "request", "RELAY", "image/png", image)).code());
        verify(client, times(0)).analyze(new byte[]{1}, "image/png");
    }
}
