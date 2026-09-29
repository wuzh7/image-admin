package org.dromara.image.service;

import tools.jackson.databind.json.JsonMapper;

import java.util.List;

final class InfringementRiskRules {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private InfringementRiskRules() { }

    static List<String> cases(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
