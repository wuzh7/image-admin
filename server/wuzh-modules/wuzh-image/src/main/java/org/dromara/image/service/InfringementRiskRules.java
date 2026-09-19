package org.dromara.image.service;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;

final class InfringementRiskRules {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private InfringementRiskRules() { }

    static boolean needsCaseLookup(GeminiGatewayClient.GatewayResponse result) {
        return (result.caseNumbers().isEmpty() && result.riskReason() != null && !result.riskReason().isBlank())
            || "LOW".equals(result.riskLevel());
    }

    static String riskLevel(GeminiGatewayClient.GatewayResponse result) {
        return result.caseNumbers().isEmpty() ? InfringementCallLogger.normalizeRisk(result.riskLevel()) : "HIGH";
    }

    static String casesJson(List<String> values) {
        return JSON.writeValueAsString(values);
    }

    static List<String> cases(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
