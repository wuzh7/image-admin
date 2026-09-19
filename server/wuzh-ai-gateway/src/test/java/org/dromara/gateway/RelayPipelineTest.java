package org.dromara.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("local")
class RelayPipelineTest {
    @Test
    void recordsActualPromptNamesForFailedFollowupAsWellAsSuccessfulCalls() {
        var pipeline = new RelayPipeline(new ImageRiskParser(), new RelayResultMerger());
        GatewayException error = assertThrows(GatewayException.class, () -> pipeline.execute(List.of(
            new RelayPipeline.Stage("gemini", "first", "second", "GEMINI", "assessment.txt", "lookup.txt")),
            (stage, prompt, previous, type) -> {
                if (type == RelayPipeline.CallType.PROMPT1) return analysis("""
                    {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"flower art",
                     "subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"CaseNumber":[]}
                    """);
                throw new GatewayException(502, "UPSTREAM_ERROR", "upstream error")
                    .withRawProviderResponse("provider error");
            }));
        var audit = tools.jackson.databind.json.JsonMapper.builder().build().readTree(error.rawProviderResponse());
        assertEquals("assessment.txt", audit.get(0).path("promptName").asText());
        assertEquals("lookup.txt", audit.get(1).path("promptName").asText());
        assertEquals("PROMPT2", audit.get(1).path("callType").asText());
        assertEquals("UPSTREAM_ERROR", audit.get(1).path("errorCode").asText());
    }

    @Test
    void invalidReviewFailsWithFullAuditInsteadOfReturningPreviousLow() {
        RelayPipeline pipeline = new RelayPipeline(new ImageRiskParser(), new RelayResultMerger());
        GatewayException error = assertThrows(GatewayException.class, () -> pipeline.execute(List.of(
            new RelayPipeline.Stage("gemini", "first", null),
            new RelayPipeline.Stage("doubao", "review", null)), (stage, prompt, previous, type) -> {
                if ("gemini".equals(stage.model())) return analysisWithUsage("""
                    {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"flower art",
                     "subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"CaseNumber":[]}
                    """, 3, "provider-one");
                return analysisWithUsage("{}", 5, "invalid-provider-two");
            }));
        assertEquals("UNPARSABLE_AI_RESPONSE", error.code());
        assertEquals("{}", error.rawAiResponse());
        assertEquals(8, error.usage().totalTokenCount());
        assertTrue(error.rawProviderResponse().contains("provider-one"));
        assertTrue(error.rawProviderResponse().contains("invalid-provider-two"));
    }

    @Test
    void passesBothPreviousCallsAndMergedResultToNextStageAndAppliesItsOwnLookup() {
        RelayPipeline pipeline = new RelayPipeline(new ImageRiskParser(), new RelayResultMerger());
        List<String> calls = new ArrayList<>();
        GeminiClient.Analysis result = pipeline.execute(List.of(
            new RelayPipeline.Stage("gemini", "first", "lookup"),
            new RelayPipeline.Stage("doubao", "review", "review lookup")), (stage, prompt, previous, type) -> {
            calls.add(stage.model() + ":" + type);
            if ("gemini".equals(stage.model())) {
                return analysis(type == RelayPipeline.CallType.PROMPT1 ? """
                    {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"flower art",
                     "subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"CaseNumber":[]}
                    """ : "{\"riskReason\":\"first lookup reason\",\"CaseNumber\":[]}");
            }
            if (type == RelayPipeline.CallType.PROMPT1) {
                var context = tools.jackson.databind.json.JsonMapper.builder().build().readTree(previous);
                assertEquals("LOW", context.path("prompt1Result").path("riskLevel").asText());
                assertEquals("first lookup reason", context.path("prompt2Result").path("riskReason").asText());
                assertEquals("NEEDS_REVIEW", context.path("mergedResult").path("riskLevel").asText());
                return analysis("{\"riskLevel\":\"LOW\",\"riskReason\":null,\"CaseNumber\":[]}");
            }
            return analysis("[\"1:26-cv-12345\"]");
        });
        assertEquals(List.of("gemini:PROMPT1", "gemini:PROMPT2", "doubao:PROMPT1", "doubao:PROMPT2"), calls);
        assertEquals("HIGH", new ImageRiskParser().parseRelayFinal(result.rawAiResponse()).riskLevel());
    }

    @Test
    void skipsBlankPrompt2AndRunsExtensibleStagesInOrder() {
        RelayPipeline pipeline = new RelayPipeline(new ImageRiskParser(), new RelayResultMerger());
        List<String> calls = new ArrayList<>();
        List<String> previousResults = new ArrayList<>();
        List<RelayPipeline.Stage> stages = List.of(
            new RelayPipeline.Stage("gemini-3.8-flash", "stage1 prompt1", ""),
            new RelayPipeline.Stage("doubao-seed-2-0-lite-260215", "stage2 prompt1", null),
            new RelayPipeline.Stage("future-stage3", "stage3 prompt1", ""));

        GeminiClient.Analysis result = pipeline.execute(stages, (stage, prompt, previous, callType) -> {
            calls.add(stage.model() + ":" + callType);
            previousResults.add(previous);
            return switch (stage.model()) {
                case "gemini-3.8-flash" -> analysis("""
                    {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"stage one description",
                     "subjects":["original"],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"CaseNumber":[]}
                    """);
                case "doubao-seed-2-0-lite-260215" -> analysis("""
                    {"riskLevel":"NEEDS_REVIEW","riskReason":"复核发现疑点","descriptionKeywords":"must not replace",
                     "subjects":["must not replace"],"CaseNumber":[]}
                    """);
                default -> analysis("""
                    {"riskLevel":"HIGH","riskReason":"找到案号","CaseNumber":["1:26-cv-12345"]}
                    """);
            };
        });

        assertEquals(List.of(
            "gemini-3.8-flash:PROMPT1",
            "doubao-seed-2-0-lite-260215:PROMPT1",
            "future-stage3:PROMPT1"), calls);
        assertEquals(null, previousResults.get(0));
        assertTrue(previousResults.get(1).contains("stage one description"));
        assertTrue(previousResults.get(2).contains("复核发现疑点"));
        assertTrue(result.rawAiResponse().contains("stage one description"));
        assertTrue(result.rawAiResponse().contains("1:26-cv-12345"));
        assertTrue(!result.rawAiResponse().contains("must not replace"));
    }

    @Test
    void runsConfiguredPrompt2OnlyWhenCurrentStateNeedsIt() {
        RelayPipeline pipeline = new RelayPipeline(new ImageRiskParser(), new RelayResultMerger());
        List<RelayPipeline.CallType> calls = new ArrayList<>();
        RelayPipeline.Stage stage = new RelayPipeline.Stage("gemini-3.8-flash", "prompt1", "prompt2");

        GeminiClient.Analysis result = pipeline.execute(List.of(stage), (current, prompt, previous, callType) -> {
            calls.add(callType);
            if (callType == RelayPipeline.CallType.PROMPT1) {
                return analysis("""
                    {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"flower art",
                     "subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"CaseNumber":[]}
                    """);
            }
            assertEquals("prompt2", prompt);
            assertTrue(previous.contains("flower art"));
            return analysis("{\"riskReason\":\"疑似版权作品\",\"CaseNumber\":[]}");
        });

        assertEquals(List.of(RelayPipeline.CallType.PROMPT1, RelayPipeline.CallType.PROMPT2), calls);
        assertTrue(result.rawAiResponse().contains("NEEDS_REVIEW"));
        assertTrue(result.rawAiResponse().contains("疑似版权作品"));
    }

    @Test
    void aggregatesUsageAndKeepsEveryStageProviderResponse() {
        RelayPipeline pipeline = new RelayPipeline(new ImageRiskParser(), new RelayResultMerger());
        List<RelayPipeline.Stage> stages = List.of(
            new RelayPipeline.Stage("model-one", "prompt one", null),
            new RelayPipeline.Stage("model-two", "prompt two", null));

        GeminiClient.Analysis result = pipeline.execute(stages, (stage, prompt, previous, callType) -> {
            if ("model-one".equals(stage.model())) {
                return analysisWithUsage("""
                    {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"flower art",
                     "subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"CaseNumber":[]}
                    """, 3, "provider-one");
            }
            return analysisWithUsage("{\"riskLevel\":\"LOW\",\"riskReason\":null,\"CaseNumber\":[]}",
                5, "provider-two");
        });

        assertEquals(8, result.usage().totalTokenCount());
        assertTrue(result.rawProviderResponse().contains("model-one"));
        assertTrue(result.rawProviderResponse().contains("provider-one"));
        assertTrue(result.rawProviderResponse().contains("model-two"));
        assertTrue(result.rawProviderResponse().contains("provider-two"));
    }

    private static GeminiClient.Analysis analysis(String raw) {
        return new GeminiClient.Analysis(raw, false, 0);
    }

    private static GeminiClient.Analysis analysisWithUsage(String raw, int totalTokens, String providerResponse) {
        return new GeminiClient.Analysis(raw, false, 0,
            new GeminiClient.Usage(null, null, null, null, null, totalTokens, "{}"), providerResponse);
    }
}
