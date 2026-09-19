package org.dromara.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Objects;

@Component
public class ImageRiskParser {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ParsedResult parse(String raw) {
        return parse(raw, false);
    }

    public ParsedResult parseRelayFinal(String raw) {
        return parse(raw, true);
    }

    private ParsedResult parse(String raw, boolean allowCaseConfirmedHighWithoutReason) {
        String value = raw == null ? "" : raw.trim()
            .replaceAll("^```(?:json|text)?\\s*|\\s*```$", "").trim();
        StructuredResponse response;
        try {
            response = JSON.readValue(value, StructuredResponse.class);
        } catch (RuntimeException ignored) {
            throw new GatewayException(502, "UNPARSABLE_AI_RESPONSE", "AI 返回格式不可解析");
        }
        if (response == null) {
            throw new GatewayException(502, "UNPARSABLE_AI_RESPONSE", "AI 返回格式不可解析");
        }
        String description = normalizeDescription(response.descriptionKeywords(), response.imageDescription());
        VisualTags tags = new VisualTags(
            normalizeTags(response.subjects(), 5), normalizeTags(response.scenes(), 5),
            normalizeTags(response.themes(), 5), normalizeTags(response.visualStyles(), 5),
            normalizeTags(response.colors(), 1));
        List<String> cases = normalizeTags(response.caseNumbers(), Integer.MAX_VALUE);
        if ("HIGH".equals(response.riskLevel())) {
            String reason = allowCaseConfirmedHighWithoutReason && !cases.isEmpty()
                && (response.riskReason() == null || response.riskReason().isBlank())
                ? null : normalizeReason(response.riskReason(), 100);
            return new ParsedResult("HIGH", reason, description, tags, raw, cases);
        }
        if ("LOW".equals(response.riskLevel())) {
            return new ParsedResult("LOW", response.riskReason() == null || response.riskReason().isBlank()
                ? null : normalizeReason(response.riskReason(), 100), description, tags, raw, cases);
        }
        if ("NEEDS_REVIEW".equalsIgnoreCase(response.riskLevel())) {
            return new ParsedResult("NEEDS_REVIEW", normalizeReason(response.riskReason(), 100), description, tags, raw, cases);
        }
        throw new GatewayException(502, "INVALID_RISK_LEVEL", "AI 风险等级必须是 HIGH、LOW 或 needs_review");
    }

    public List<String> parseCases(String raw) {
        try {
            var node = JSON.readTree(raw == null ? "" : raw.trim().replaceAll("^```(?:json|text)?\\s*|\\s*```$", "").trim());
            if (!node.isArray()) throw new IllegalArgumentException();
            List<String> values = new java.util.ArrayList<>();
            for (var value : node) {
                if (!value.isTextual()) throw new IllegalArgumentException();
                values.add(value.asText());
            }
            return normalizeTags(values, Integer.MAX_VALUE);
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "UNPARSABLE_CASE_RESPONSE", "AI 案号返回必须是字符串数组");
        }
    }

    private String normalizeReason(String reason, int maxLength) {
        if (reason == null || reason.isBlank()) {
            throw new GatewayException(502, "INVALID_RISK_REASON", "高风险原因为空");
        }
        String value = reason.trim();
        if (value.codePointCount(0, value.length()) <= maxLength) return value;
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }

    private String normalizeDescription(String keywords, String legacyDescription) {
        String description = keywords == null || keywords.isBlank() ? legacyDescription : keywords;
        if (description == null || description.isBlank()) {
            throw new GatewayException(502, "INVALID_IMAGE_DESCRIPTION", "图片解析必须是英文");
        }
        String value = description.trim().replaceAll("\\s+", " ");
        if (!value.chars().allMatch(character -> character <= 127)) {
            throw new GatewayException(502, "INVALID_IMAGE_DESCRIPTION", "图片解析必须是英文");
        }
        if (value.length() <= 50) return value;
        String prefix = value.substring(0, 50);
        int wordBoundary = prefix.lastIndexOf(' ');
        return wordBoundary > 0 ? prefix.substring(0, wordBoundary) : prefix;
    }

    private List<String> normalizeTags(List<String> tags, int max) {
        if (tags == null) return List.of();
        return tags.stream().filter(Objects::nonNull).map(String::trim).filter(tag -> !tag.isBlank())
            .distinct().limit(max).toList();
    }

    public record ParsedResult(String riskLevel, String riskReason, String imageDescription,
                               VisualTags visualTags, String rawAiResponse, List<String> caseNumbers) {
    }

    public record VisualTags(List<String> subjects, List<String> scenes, List<String> themes,
                             List<String> visualStyles, List<String> colors) {
    }

    private record StructuredResponse(String riskLevel, String riskReason, String descriptionKeywords,
                                      String imageDescription, List<String> subjects, List<String> scenes,
                                      List<String> themes, List<String> visualStyles, List<String> colors,
                                      Integer confidence, List<String> detectedElements,
                                      @JsonProperty("CaseNumber") List<String> caseNumbers) {
    }
}
