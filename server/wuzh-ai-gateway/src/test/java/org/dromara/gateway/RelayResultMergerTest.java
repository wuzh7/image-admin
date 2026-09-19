package org.dromara.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("local")
class RelayResultMergerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void malformedFollowupOrReviewIsTechnicalFailureNotUnchangedLow() {
        RelayResultMerger merger = new RelayResultMerger();
        String current = "{\"riskLevel\":\"LOW\",\"riskReason\":null,\"CaseNumber\":[]}";
        for (String invalid : java.util.List.of("not json", "{}", "{\"CaseNumber\":\"case\"}", "[123]", "null")) {
            assertThrows(GatewayException.class, () -> merger.mergePrompt2(current, invalid));
        }
        assertThrows(GatewayException.class, () -> merger.mergeStage(current, "{}"));
        assertThrows(GatewayException.class, () -> merger.mergeStage(current,
            "{\"riskLevel\":\"LOW\",\"riskReason\":123,\"CaseNumber\":[]}"));
    }

    @Test
    void lowPrompt2WithCasesBecomesHighAndPreservesOtherFields() {
        RelayResultMerger merger = new RelayResultMerger();

        var result = JSON.readTree(merger.mergePrompt2("""
            {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"flower art",
             "subjects":["flower"],"CaseNumber":[]}
            """, "[\"1:26-cv-12345\"]"));

        assertEquals("HIGH", result.path("riskLevel").asText());
        assertEquals("1:26-cv-12345", result.path("CaseNumber").get(0).asText());
        assertEquals("flower art", result.path("descriptionKeywords").asText());
        assertEquals("flower", result.path("subjects").get(0).asText());
    }

    @Test
    void lowPrompt2UsesReasonForReviewAndKeepsOriginalWhenNothingFound() {
        RelayResultMerger merger = new RelayResultMerger();
        String low = """
            {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"flower art","CaseNumber":[]}
            """;

        var review = JSON.readTree(merger.mergePrompt2(low,
            "{\"riskReason\":\"疑似版权作品\",\"CaseNumber\":[]}"));
        var unchanged = JSON.readTree(merger.mergePrompt2(low,
            "{\"riskReason\":null,\"CaseNumber\":[]}"));

        assertEquals("NEEDS_REVIEW", review.path("riskLevel").asText());
        assertEquals("疑似版权作品", review.path("riskReason").asText());
        assertEquals("LOW", unchanged.path("riskLevel").asText());
        assertEquals("flower art", unchanged.path("descriptionKeywords").asText());
    }

    @Test
    void suspiciousNonHighOnlyUpgradesWhenPrompt2FindsCases() {
        RelayResultMerger merger = new RelayResultMerger();
        String suspicious = """
            {"riskLevel":"NEEDS_REVIEW","riskReason":"疑似版权作品",
             "descriptionKeywords":"character art","CaseNumber":[]}
            """;

        var found = JSON.readTree(merger.mergePrompt2(suspicious, "[\"1:26-cv-99999\"]"));
        var missing = JSON.readTree(merger.mergePrompt2(suspicious, "[]"));

        assertEquals("HIGH", found.path("riskLevel").asText());
        assertEquals("1:26-cv-99999", found.path("CaseNumber").get(0).asText());
        assertEquals("疑似版权作品", found.path("riskReason").asText());
        assertEquals("NEEDS_REVIEW", missing.path("riskLevel").asText());
        assertEquals("疑似版权作品", missing.path("riskReason").asText());
    }

    @Test
    void prompt2DecisionMatchesLowAndSuspiciousRules() {
        RelayResultMerger merger = new RelayResultMerger();

        assertTrue(merger.needsPrompt2("{\"riskLevel\":\"LOW\",\"riskReason\":null,\"CaseNumber\":[]}"));
        assertTrue(merger.needsPrompt2("{\"riskLevel\":\"NEEDS_REVIEW\",\"riskReason\":\"疑似\",\"CaseNumber\":[]}"));
        assertFalse(merger.needsPrompt2("{\"riskLevel\":\"HIGH\",\"riskReason\":\"已确认\",\"CaseNumber\":[]}"));
        assertFalse(merger.needsPrompt2("{\"riskLevel\":\"NEEDS_REVIEW\",\"riskReason\":null,\"CaseNumber\":[]}"));
        assertTrue(merger.needsPrompt2("{\"riskLevel\":\"LOW\",\"riskReason\":null,\"CaseNumber\":[\"case\"]}"));
    }

    @Test
    void laterStageOnlyOverwritesRiskWhitelist() {
        RelayResultMerger merger = new RelayResultMerger();
        var result = JSON.readTree(merger.mergeStage("""
            {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"stage one description",
             "subjects":["original"],"confidence":81,"CaseNumber":[]}
            """, """
            {"riskLevel":"NEEDS_REVIEW","riskReason":"复核发现疑点","descriptionKeywords":"must not replace",
             "subjects":["must not replace"],"confidence":5,"CaseNumber":[]}
            """));

        assertEquals("NEEDS_REVIEW", result.path("riskLevel").asText());
        assertEquals("复核发现疑点", result.path("riskReason").asText());
        assertEquals("stage one description", result.path("descriptionKeywords").asText());
        assertEquals("original", result.path("subjects").get(0).asText());
        assertEquals(81, result.path("confidence").asInt());
    }
}
