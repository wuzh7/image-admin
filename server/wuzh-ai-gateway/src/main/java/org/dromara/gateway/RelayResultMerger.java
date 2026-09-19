package org.dromara.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

final class RelayResultMerger {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    boolean needsPrompt2(String currentRaw) {
        ObjectNode current = object(currentRaw);
        String riskLevel = current.path("riskLevel").asText();
        if ("LOW".equalsIgnoreCase(riskLevel)) return true;
        return !"HIGH".equalsIgnoreCase(riskLevel)
            && cases(current.path("CaseNumber")).isEmpty()
            && current.path("riskReason").isTextual()
            && !current.path("riskReason").asText().isBlank();
    }

    String mergeStage(String currentRaw, String reviewRaw) {
        ObjectNode current = object(currentRaw);
        ObjectNode review = object(reviewRaw);
        if (!review.has("riskLevel") || !review.has("CaseNumber")) throw invalid();
        if (review.has("riskLevel")) {
            String riskLevel = normalizeRisk(review.path("riskLevel").asText());
            current.put("riskLevel", riskLevel);
        }
        if (review.has("riskReason")) {
            String reason = reason(review.get("riskReason"));
            if (reason == null) current.putNull("riskReason");
            else current.put("riskReason", reason);
        }
        if (review.has("CaseNumber")) replaceCases(current, cases(review.path("CaseNumber")));
        return JSON.writeValueAsString(current);
    }

    String mergePrompt2(String currentRaw, String prompt2Raw) {
        ObjectNode current = object(currentRaw);
        Adjustment prompt2 = adjustment(prompt2Raw);
        if ("LOW".equalsIgnoreCase(current.path("riskLevel").asText())) {
            if (!prompt2.caseNumbers().isEmpty()) {
                current.put("riskLevel", "HIGH");
                replaceCases(current, prompt2.caseNumbers());
                if (prompt2.riskReason() != null) current.put("riskReason", prompt2.riskReason());
            } else if (prompt2.riskReason() != null) {
                current.put("riskLevel", "NEEDS_REVIEW");
                current.put("riskReason", prompt2.riskReason());
            }
        } else if (!"HIGH".equalsIgnoreCase(current.path("riskLevel").asText())
            && cases(current.path("CaseNumber")).isEmpty()
            && current.path("riskReason").isTextual() && !current.path("riskReason").asText().isBlank()
            && !prompt2.caseNumbers().isEmpty()) {
            current.put("riskLevel", "HIGH");
            replaceCases(current, prompt2.caseNumbers());
        }
        return JSON.writeValueAsString(current);
    }

    private static ObjectNode object(String raw) {
        JsonNode value = read(raw);
        if (value instanceof ObjectNode object) return object.deepCopy();
        throw new GatewayException(502, "UNPARSABLE_AI_RESPONSE", "AI 返回格式不可解析");
    }

    private static Adjustment adjustment(String raw) {
        JsonNode value = read(raw);
        if (value.isArray()) return new Adjustment(null, cases(value));
        if (value instanceof ObjectNode object) {
            if (!object.has("CaseNumber")) throw invalid();
            String reason = reason(object.get("riskReason"));
            return new Adjustment(reason, cases(object.path("CaseNumber")));
        }
        throw new GatewayException(502, "UNPARSABLE_AI_RESPONSE", "AI 返回格式不可解析");
    }

    private static List<String> cases(JsonNode value) {
        if (!value.isArray()) throw invalid();
        List<String> cases = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual()) throw invalid();
            if (!item.asText().isBlank()) cases.add(item.asText().trim());
        });
        return cases.stream().distinct().toList();
    }

    private static void replaceCases(ObjectNode result, List<String> cases) {
        var values = result.putArray("CaseNumber");
        cases.forEach(values::add);
    }

    private static String reason(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid();
        return value.asText().isBlank() ? null : value.asText().trim();
    }

    private static JsonNode read(String raw) {
        try {
            return JSON.readTree(raw == null ? "" : raw.trim()
                .replaceAll("^```(?:json|text)?\\s*|\\s*```$", "").trim());
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static GatewayException invalid() {
        return new GatewayException(502, "UNPARSABLE_AI_RESPONSE", "AI 风险结果必须为有效 JSON，CaseNumber 必须为字符串数组");
    }

    private static String normalizeRisk(String value) {
        if ("HIGH".equalsIgnoreCase(value)) return "HIGH";
        if ("LOW".equalsIgnoreCase(value)) return "LOW";
        if ("NEEDS_REVIEW".equalsIgnoreCase(value)) return "NEEDS_REVIEW";
        throw new GatewayException(502, "INVALID_RISK_LEVEL", "AI 风险等级必须是 HIGH、LOW 或 needs_review");
    }

    private record Adjustment(String riskReason, List<String> caseNumbers) {
    }
}
