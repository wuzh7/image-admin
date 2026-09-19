package org.dromara.gateway;

import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class RelayPipeline {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ImageRiskParser parser;
    private final RelayResultMerger merger;

    RelayPipeline(ImageRiskParser parser, RelayResultMerger merger) {
        this.parser = parser;
        this.merger = merger;
    }

    GeminiClient.Analysis execute(List<Stage> stages, StageInvoker invoker) {
        if (stages == null || stages.isEmpty()) {
            throw new GatewayException(503, "RELAY_STAGES_NOT_CONFIGURED", "中转站模型链未配置");
        }
        String merged = null;
        String previousStage = null;
        boolean searchUsed = false;
        int searchQueryCount = 0;
        List<GeminiClient.Usage> usages = new ArrayList<>();
        List<Map<String, Object>> providerResponses = new ArrayList<>();
        String lastRaw = null;
        Stage activeStage = null;
        CallType activeCall = CallType.PROMPT1;
        try {
            for (Stage stage : stages) {
                activeStage = stage;
                activeCall = CallType.PROMPT1;
                lastRaw = null;
                if (stage.model() == null || stage.model().isBlank() || stage.prompt1() == null || stage.prompt1().isBlank()) {
                    throw new GatewayException(503, "RELAY_STAGE_INVALID", "中转站模型或 Prompt1 配置为空");
                }
                GeminiClient.Analysis prompt1 = invoker.call(stage, stage.prompt1(), previousStage, CallType.PROMPT1);
                lastRaw = prompt1.rawAiResponse();
                searchUsed |= prompt1.searchUsed();
                searchQueryCount += prompt1.searchQueryCount();
                capture(stage, CallType.PROMPT1, prompt1, usages, providerResponses);
                if (merged == null) {
                    merged = merger.mergeStage(prompt1.rawAiResponse(), prompt1.rawAiResponse());
                    parser.parseRelayFinal(merged);
                } else {
                    merged = merger.mergeStage(merged, prompt1.rawAiResponse());
                }
                String prompt2Result = null;
                if (stage.prompt2() != null && !stage.prompt2().isBlank() && merger.needsPrompt2(merged)) {
                    activeCall = CallType.PROMPT2;
                    lastRaw = null;
                    GeminiClient.Analysis prompt2 = invoker.call(stage, stage.prompt2(), merged, CallType.PROMPT2);
                    lastRaw = prompt2.rawAiResponse();
                    searchUsed |= prompt2.searchUsed();
                    searchQueryCount += prompt2.searchQueryCount();
                    capture(stage, CallType.PROMPT2, prompt2, usages, providerResponses);
                    merged = merger.mergePrompt2(merged, prompt2.rawAiResponse());
                    prompt2Result = prompt2.rawAiResponse();
                }
                parser.parseRelayFinal(merged);
                var context = JSON.createObjectNode();
                context.put("model", stage.model());
                context.set("prompt1Result", JSON.readTree(prompt1.rawAiResponse().trim()
                    .replaceAll("^```(?:json|text)?\\s*|\\s*```$", "").trim()));
                if (prompt2Result == null) context.putNull("prompt2Result");
                else context.set("prompt2Result", JSON.readTree(prompt2Result.trim()
                    .replaceAll("^```(?:json|text)?\\s*|\\s*```$", "").trim()));
                context.set("mergedResult", JSON.readTree(merged));
                previousStage = JSON.writeValueAsString(context);
            }
        } catch (GatewayException exception) {
            if (exception.usage() != null) usages.add(exception.usage());
            if (exception.rawProviderResponse() != null) {
                Map<String, Object> entry = auditEntry(activeStage, activeCall);
                entry.put("errorCode", exception.code());
                entry.put("response", exception.rawProviderResponse());
                providerResponses.add(entry);
            }
            throw exception.withAiResponse(exception.rawAiResponse() == null ? lastRaw : exception.rawAiResponse(), aggregate(usages))
                .withRawProviderResponse(JSON.writeValueAsString(providerResponses));
        }
        return new GeminiClient.Analysis(merged, searchUsed, searchQueryCount, aggregate(usages),
            JSON.writeValueAsString(providerResponses));
    }

    private static void capture(Stage stage, CallType callType, GeminiClient.Analysis analysis,
                                List<GeminiClient.Usage> usages, List<Map<String, Object>> providerResponses) {
        if (analysis.usage() != null) usages.add(analysis.usage());
        Map<String, Object> entry = auditEntry(stage, callType);
        entry.put("response", analysis.rawProviderResponse() == null ? "" : analysis.rawProviderResponse());
        providerResponses.add(entry);
    }

    private static Map<String, Object> auditEntry(Stage stage, CallType callType) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("model", stage.model());
        entry.put("callType", callType.name());
        String promptName = callType == CallType.PROMPT1 ? stage.prompt1Name() : stage.prompt2Name();
        if (promptName != null) entry.put("promptName", promptName);
        return entry;
    }

    private static GeminiClient.Usage aggregate(List<GeminiClient.Usage> usages) {
        if (usages.isEmpty()) return null;
        if (usages.size() == 1) return usages.getFirst();
        return new GeminiClient.Usage(
            sum(usages.stream().map(GeminiClient.Usage::promptTokenCount).toList()),
            sum(usages.stream().map(GeminiClient.Usage::cachedContentTokenCount).toList()),
            sum(usages.stream().map(GeminiClient.Usage::candidatesTokenCount).toList()),
            sum(usages.stream().map(GeminiClient.Usage::thoughtsTokenCount).toList()),
            sum(usages.stream().map(GeminiClient.Usage::toolUsePromptTokenCount).toList()),
            sum(usages.stream().map(GeminiClient.Usage::totalTokenCount).toList()),
            JSON.writeValueAsString(usages.stream().map(GeminiClient.Usage::rawJson).toList()));
    }

    private static Integer sum(List<Integer> values) {
        List<Integer> present = values.stream().filter(value -> value != null).toList();
        return present.isEmpty() ? null : present.stream().mapToInt(Integer::intValue).sum();
    }

    record Stage(String model, String prompt1, String prompt2, String protocol, String prompt1Name, String prompt2Name) {
        Stage(String model, String prompt1, String prompt2, String protocol) {
            this(model, prompt1, prompt2, protocol, null, null);
        }

        Stage(String model, String prompt1, String prompt2) {
            this(model, prompt1, prompt2, "GEMINI");
        }
    }

    enum CallType {
        PROMPT1,
        PROMPT2
    }

    @FunctionalInterface
    interface StageInvoker {
        GeminiClient.Analysis call(Stage stage, String prompt, String previousResult, CallType callType);
    }
}
