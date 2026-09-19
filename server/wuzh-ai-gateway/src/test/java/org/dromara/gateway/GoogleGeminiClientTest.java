package org.dromara.gateway;

import com.google.genai.types.Candidate;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.ServiceTier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class GoogleGeminiClientTest {
    @Test
    void jsonlKeepsFullOriginalItemIncludingUnknownMetadata() throws Exception {
        var client = new GoogleGeminiClient(null, "model", null);
        var method = GoogleGeminiClient.class.getDeclaredMethod("parseFileResult", String.class);
        method.setAccessible(true);
        String line = "{\"key\":\"request-7\",\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"[]\"}]}}],\"unknownMetadata\":\"keep\"}}";
        var result = (GoogleGeminiClient.BatchItem) method.invoke(client, line);
        assertEquals(7, result.index());
        assertEquals(line, result.rawProviderResponse());
        assertEquals(line, result.analysis().rawProviderResponse());
        assertEquals("[]", result.analysis().rawAiResponse());
    }
    @Test
    void requestsGroundedStructuredJsonWithRiskAndVisualLabelFields() {
        var config = GoogleGeminiClient.structuredOutputConfig();
        assertEquals("application/json", config.responseMimeType().orElseThrow());

        var schema = config.responseSchema().orElseThrow();
        assertEquals(List.of("riskLevel", "riskReason", "descriptionKeywords", "subjects", "scenes", "themes", "visualStyles", "colors", "CaseNumber"),
            schema.required().orElseThrow());
        var properties = schema.properties().orElseThrow();
        assertEquals(List.of("HIGH", "LOW", "needs_review"), properties.get("riskLevel").enum_().orElseThrow());
        assertEquals(100L, properties.get("riskReason").maxLength().orElseThrow());
        assertEquals(5L, properties.get("detectedElements").maxItems().orElseThrow());
        assertEquals(0D, properties.get("confidence").minimum().orElseThrow());
        assertEquals(100D, properties.get("confidence").maximum().orElseThrow());
        assertEquals(50L, properties.get("descriptionKeywords").maxLength().orElseThrow());
        assertEquals(5L, properties.get("subjects").maxItems().orElseThrow());
        assertEquals(1L, properties.get("colors").maxItems().orElseThrow());
        assertEquals(1, config.tools().orElseThrow().size());
        assertEquals(true, config.tools().orElseThrow().getFirst().googleSearch().isPresent());
        assertTrue(config.temperature().isEmpty());
        assertEquals(ServiceTier.Known.FLEX, config.serviceTier().orElseThrow().knownEnum());
        assertTrue(GoogleGeminiClient.structuredOutputConfig(false).serviceTier().isEmpty());
    }

    @Test
    void recordsOnlyExecutedSearchQueries() {
        var response = GenerateContentResponse.builder()
            .candidates(Candidate.builder()
                .groundingMetadata(GroundingMetadata.builder().webSearchQueries("Scenics trademark", "Scenics copyright"))
                .build())
            .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(120)
                .cachedContentTokenCount(10)
                .candidatesTokenCount(30)
                .thoughtsTokenCount(40)
                .toolUsePromptTokenCount(20)
                .totalTokenCount(210)
                .build())
            .build();

        GeminiClient.Analysis analysis = GoogleGeminiClient.withSearchUsage("raw", response);
        assertEquals(true, analysis.searchUsed());
        assertEquals(2, analysis.searchQueryCount());
        assertEquals(120, analysis.usage().promptTokenCount());
        assertEquals(10, analysis.usage().cachedContentTokenCount());
        assertEquals(30, analysis.usage().candidatesTokenCount());
        assertEquals(40, analysis.usage().thoughtsTokenCount());
        assertEquals(20, analysis.usage().toolUsePromptTokenCount());
        assertEquals(210, analysis.usage().totalTokenCount());
        assertTrue(analysis.usage().rawJson().contains("\"promptTokenCount\":120"));
    }

    @Test
    void distinguishesFlexCapacityFailureFromOtherGeminiServerErrors() {
        GatewayException unavailable = GoogleGeminiClient.mapApiStatus(503);
        GatewayException internal = GoogleGeminiClient.mapApiStatus(500);

        assertEquals(503, unavailable.status());
        assertEquals("GEMINI_SERVICE_UNAVAILABLE", unavailable.code());
        assertEquals("Gemini Flex 容量不足（HTTP 503）", unavailable.getMessage());
        assertEquals(502, internal.status());
        assertEquals("GEMINI_INTERNAL_ERROR", internal.code());
        assertEquals("Gemini 服务内部错误（HTTP 500）", internal.getMessage());
    }

    @Test
    void distinguishesMissingBatchJobFromMissingModel() {
        GatewayException missing = GoogleGeminiClient.mapBatchLookupStatus(404);
        GatewayException rejected = GoogleGeminiClient.mapBatchLookupStatus(400);

        assertEquals(404, missing.status());
        assertEquals("GEMINI_BATCH_NOT_FOUND", missing.code());
        assertEquals(400, rejected.status());
        assertEquals("GEMINI_BATCH_INVALID_JOB", rejected.code());
    }

    @Test
    void batchSourceReferencesSignedUrlsWithoutEmbeddingImageBytes() {
        var source = GoogleGeminiClient.batchSource(List.of(
            new GoogleGeminiClient.BatchImage("https://oss.example/a.png?signature=hidden", "image/png")
        ), "test prompt");

        var request = source.inlinedRequests().orElseThrow().getFirst();
        var parts = request.contents().orElseThrow().getFirst().parts().orElseThrow();
        assertEquals("https://oss.example/a.png?signature=hidden",
            parts.getFirst().fileData().orElseThrow().fileUri().orElseThrow());
        assertEquals("image/png", parts.getFirst().fileData().orElseThrow().mimeType().orElseThrow());
        assertEquals("test prompt", parts.get(1).text().orElseThrow());
        assertTrue(request.config().orElseThrow().tools().orElseThrow().getFirst().googleSearch().isPresent());
        assertTrue(source.fileName().isEmpty());
    }

}
