package org.dromara.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

@Tag("local")
class ImageRiskControllerTest {
    @Test
    void acceptsImagesLargerThanTheFormerTwentyMegabyteLimit() throws Exception {
        GeminiClient gemini = mock(GeminiClient.class);
        when(gemini.modes()).thenReturn(Set.of("RELAY"));
        when(gemini.model()).thenReturn("gemini-test");
        when(gemini.analyze(any(byte[].class), any(), any())).thenReturn(new GeminiClient.Analysis(
            "{\"riskLevel\":\"LOW\",\"riskReason\":null,\"descriptionKeywords\":\"test\",\"subjects\":[],\"scenes\":[],\"themes\":[],\"visualStyles\":[],\"colors\":[]}",
            false, 0));
        ImageRiskController controller = new ImageRiskController(List.of(gemini), new ImageRiskParser(), "token");
        byte[] image = new byte[21 * 1024 * 1024];

        assertEquals("LOW", controller.analyze("token", "large", "RELAY", "image/png",
            new MockMultipartFile("image", "large.png", "image/png", image)).riskLevel());
        verify(gemini).analyze(argThat(bytes -> bytes.length == 21 * 1024 * 1024), eq("image/png"), eq("RELAY"));
    }

    @Test
    void relayAcceptsHighCaseResultWithoutInventingRiskReason() throws Exception {
        GeminiClient relay = mock(GeminiClient.class);
        when(relay.modes()).thenReturn(Set.of("RELAY"));
        when(relay.model()).thenReturn("gemini -> doubao");
        when(relay.analyze(new byte[]{1}, "image/png", "RELAY")).thenReturn(new GeminiClient.Analysis("""
            {"riskLevel":"HIGH","riskReason":null,"descriptionKeywords":"flower art",
             "subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],
             "CaseNumber":["1:26-cv-12345"]}
            """, true, 1));
        ImageRiskController controller = new ImageRiskController(List.of(relay), new ImageRiskParser(), "token");

        var result = controller.analyze("token", "request", "RELAY", "image/png",
            new MockMultipartFile("image", "a.png", "image/png", new byte[]{1}));

        assertEquals("HIGH", result.riskLevel());
        assertEquals(null, result.riskReason());
        assertEquals(List.of("1:26-cv-12345"), result.caseNumbers());
    }

    @Test
    void callsGeminiExactlyOnceAndReturnsParsedResult() throws Exception {
        GeminiClient gemini = mock(GeminiClient.class);
        when(gemini.mode()).thenReturn("RELAY");
        when(gemini.modes()).thenReturn(Set.of("RELAY"));
        when(gemini.model()).thenReturn("gemini-test");
        when(gemini.analyze(new byte[]{1, 2}, "image/png", "RELAY"))
            .thenReturn(new GeminiClient.Analysis("{\"riskLevel\":\"LOW\",\"riskReason\":null,\"descriptionKeywords\":\"simple abstract pattern\",\"subjects\":[],\"scenes\":[],\"themes\":[],\"visualStyles\":[],\"colors\":[]}", true, 2)
                .withRawProviderResponse("{\"candidates\":[],\"providerExtra\":true}"));
        ImageRiskController controller = new ImageRiskController(List.of(gemini), new ImageRiskParser(), "token");

        var result = controller.analyze("token", "request-1", "RELAY", "image/png",
            new MockMultipartFile("image", "a.png", "image/png", new byte[]{1, 2}));

        assertEquals("LOW", result.riskLevel());
        assertEquals("{\"candidates\":[],\"providerExtra\":true}", result.rawProviderResponse());
        assertEquals("request-1", result.requestId());
        assertEquals("relay_pipeline_v1", result.promptVersion());
        assertEquals("gemini-test", result.model());
        assertEquals(true, result.searchUsed());
        assertEquals(2, result.searchQueryCount());
        verify(gemini, times(1)).analyze(new byte[]{1, 2}, "image/png", "RELAY");
    }

    @Test
    void rejectsWrongTokenBeforeCallingGemini() {
        GeminiClient gemini = mock(GeminiClient.class);
        when(gemini.mode()).thenReturn("RELAY");
        when(gemini.modes()).thenReturn(Set.of("RELAY"));
        ImageRiskController controller = new ImageRiskController(List.of(gemini), new ImageRiskParser(), "token");
        MockMultipartFile image = new MockMultipartFile("image", "a.png", "image/png", new byte[]{1});
        GatewayException error = assertThrows(GatewayException.class,
            () -> controller.analyze("wrong", "request-1", "RELAY", "image/png", image));
        assertEquals(401, error.status());
        verify(gemini, times(0)).analyze(new byte[]{1}, "image/png");
    }

    @Test
    void routesTheBatchModeToExactlyOneProvider() throws Exception {
        GeminiClient relay = mock(GeminiClient.class);
        GeminiClient official = mock(GeminiClient.class);
        when(relay.mode()).thenReturn("RELAY");
        when(official.mode()).thenReturn("OFFICIAL_FLEX");
        when(relay.modes()).thenReturn(Set.of("RELAY"));
        when(official.modes()).thenReturn(Set.of("OFFICIAL_FLEX"));
        when(official.model()).thenReturn("gemini-3.8-flash");
        when(official.analyze(new byte[]{1}, "image/png", "OFFICIAL_FLEX")).thenReturn(new GeminiClient.Analysis(
            "{\"riskLevel\":\"HIGH\",\"riskReason\":\"可见明确品牌Logo\",\"descriptionKeywords\":\"branded logo graphic\",\"subjects\":[],\"scenes\":[],\"themes\":[],\"visualStyles\":[],\"colors\":[]}",
            false, 0));
        ImageRiskController controller = new ImageRiskController(List.of(relay, official), new ImageRiskParser(), "token");

        controller.analyze("token", "request-2", "OFFICIAL_FLEX", "image/png",
            new MockMultipartFile("image", "a.png", "image/png", new byte[]{1}));

        verify(official, times(1)).analyze(new byte[]{1}, "image/png", "OFFICIAL_FLEX");
        verify(relay, times(0)).analyze(new byte[]{1}, "image/png", "OFFICIAL_FLEX");
    }

    @Test
    void routesOfficialStandardWithoutUsingFlexOrRelay() throws Exception {
        GeminiClient relay = mock(GeminiClient.class);
        GeminiClient official = mock(GeminiClient.class);
        when(relay.modes()).thenReturn(Set.of("RELAY"));
        when(official.modes()).thenReturn(Set.of("OFFICIAL_FLEX", "OFFICIAL_STANDARD"));
        when(official.model()).thenReturn("gemini-3.8-flash");
        when(official.analyze(new byte[]{1}, "image/png", "OFFICIAL_STANDARD")).thenReturn(new GeminiClient.Analysis(
            "{\"riskLevel\":\"LOW\",\"riskReason\":\"\",\"descriptionKeywords\":\"flower art\",\"subjects\":[],\"scenes\":[],\"themes\":[],\"visualStyles\":[],\"colors\":[]}",
            true, 1));
        ImageRiskController controller = new ImageRiskController(List.of(relay, official), new ImageRiskParser(), "token");

        var result = controller.analyze("token", "request-3", "OFFICIAL_STANDARD", "image/png",
            new MockMultipartFile("image", "a.png", "image/png", new byte[]{1}));

        assertEquals("gemini_prompt", result.promptVersion());
        verify(official, times(1)).analyze(new byte[]{1}, "image/png", "OFFICIAL_STANDARD");
        verify(official, times(0)).analyze(new byte[]{1}, "image/png", "OFFICIAL_FLEX");
        verify(relay, times(0)).analyze(new byte[]{1}, "image/png", "OFFICIAL_STANDARD");
    }

    @Test
    void acceptsUnsearchedLowAndPreservesNeedsReview() throws Exception {
        GeminiClient official = mock(GeminiClient.class);
        when(official.modes()).thenReturn(Set.of("OFFICIAL_STANDARD"));
        when(official.model()).thenReturn("gemini-3.8-flash");
        when(official.analyze(new byte[]{1}, "image/png", "OFFICIAL_STANDARD"))
            .thenReturn(new GeminiClient.Analysis(
                "{\"riskLevel\":\"LOW\",\"riskReason\":\"\",\"descriptionKeywords\":\"flower art\",\"subjects\":[],\"scenes\":[],\"themes\":[],\"visualStyles\":[],\"colors\":[]}",
                false, 0))
            .thenReturn(new GeminiClient.Analysis(
                "{\"riskLevel\":\"NEEDS_REVIEW\",\"riskReason\":\"存在可见角色化元素\",\"descriptionKeywords\":\"character illustration\",\"subjects\":[],\"scenes\":[],\"themes\":[],\"visualStyles\":[],\"colors\":[]}",
                false, 0));
        ImageRiskController controller = new ImageRiskController(List.of(official), new ImageRiskParser(), "token");
        MockMultipartFile image = new MockMultipartFile("image", "a.png", "image/png", new byte[]{1});

        var low = controller.analyze("token", "request-low", "OFFICIAL_STANDARD", "image/png", image);
        var review = controller.analyze("token", "request-review", "OFFICIAL_STANDARD", "image/png", image);

        assertEquals("LOW", low.riskLevel());
        assertEquals("NEEDS_REVIEW", review.riskLevel());
        assertEquals("存在可见角色化元素", review.riskReason());
        assertEquals(false, low.searchUsed());
        verify(official, times(2)).analyze(new byte[]{1}, "image/png", "OFFICIAL_STANDARD");
    }

    @Test
    void returnsCaseLookupArrayAndUsageWithoutRepeatingRiskScan() throws Exception {
        GeminiClient relay = mock(GeminiClient.class);
        when(relay.modes()).thenReturn(Set.of("RELAY"));
        when(relay.model()).thenReturn("model");
        when(relay.lookupCases(new byte[]{1}, "image/png", "previous"))
            .thenReturn(new GeminiClient.Analysis("[\"1:26-cv-12345\"]", true, 1)
                .withRawProviderResponse("{\"candidates\":[],\"lookupExtra\":true}"));
        var controller = new ImageRiskController(List.of(relay), new ImageRiskParser(), "token");
        var result = controller.lookupCases("token", "lookup-1", "RELAY", "image/png",
            new MockMultipartFile("image", "a.png", "image/png", new byte[]{1}), "previous");
        assertEquals(List.of("1:26-cv-12345"), result.caseNumbers());
        assertEquals(true, result.searchUsed());
        assertEquals("{\"candidates\":[],\"lookupExtra\":true}", result.rawProviderResponse());
        verify(relay, times(1)).lookupCases(new byte[]{1}, "image/png", "previous");
        verify(relay, times(0)).analyze(any(), any(), any());
    }

    @Test
    void createsOneOfficialBatchForTheSubmittedSignedUrls() {
        GoogleGeminiClient official = mock(GoogleGeminiClient.class);
        when(official.modes()).thenReturn(Set.of("OFFICIAL_FLEX", "OFFICIAL_STANDARD"));
        var images = List.of(
            new ImageRiskController.BatchImageRequest("https://oss.example/a.png?signature=1", "image/png"),
            new ImageRiskController.BatchImageRequest("https://oss.example/b.jpg?signature=2", "image/jpeg"));
        when(official.createBatch(anyList(), any())).thenReturn("batches/123");
        ImageRiskController controller = new ImageRiskController(List.of(official), new ImageRiskParser(), "token");

        var response = controller.createBatch("token", "request-4",
            new ImageRiskController.BatchCreateRequest(images));

        assertEquals("batches/123", response.providerJobId());
        assertEquals(2, response.requestCount());
        verify(official, times(1)).createBatch(anyList(), any());
    }

    @Test
    void rejectsBatchImageWithoutContentTypeAsBadRequest() {
        GoogleGeminiClient official = mock(GoogleGeminiClient.class);
        when(official.modes()).thenReturn(Set.of("OFFICIAL_STANDARD"));
        ImageRiskController controller = new ImageRiskController(List.of(official), new ImageRiskParser(), "token");

        GatewayException error = assertThrows(GatewayException.class, () -> controller.createBatch("token", "request-5",
            new ImageRiskController.BatchCreateRequest(List.of(
                new ImageRiskController.BatchImageRequest("https://oss.example/a.png?signature=1", null)))));

        assertEquals(400, error.status());
        verify(official, times(0)).createBatch(anyList(), any());
    }

    @Test
    void returnsUnsearchedLowBatchItemAsSuccessfulResult() {
        GoogleGeminiClient official = mock(GoogleGeminiClient.class);
        when(official.modes()).thenReturn(Set.of("OFFICIAL_STANDARD"));
        when(official.model()).thenReturn("gemini-3.8-flash");
        when(official.getBatch("batches/123")).thenReturn(new GoogleGeminiClient.BatchStatus(
            "SUCCEEDED", null, List.of(new GoogleGeminiClient.BatchItem(0, new GeminiClient.Analysis(
                "{\"riskLevel\":\"LOW\",\"riskReason\":\"\",\"descriptionKeywords\":\"flower art\",\"subjects\":[],\"scenes\":[],\"themes\":[],\"visualStyles\":[],\"colors\":[]}",
                false, 0), null, null))));
        ImageRiskController controller = new ImageRiskController(List.of(official), new ImageRiskParser(), "token");

        var item = controller.getBatch("token", "request-6", "batches/123").items().getFirst();

        assertEquals(null, item.errorCode());
        assertEquals("LOW", item.result().riskLevel());
        assertEquals(false, item.result().searchUsed());
    }
}
