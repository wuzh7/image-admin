package org.dromara.gateway;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

@Component
public class ImageRiskParser {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FIELDS = Set.of("descriptionKeywords", "subjects", "scenes", "themes", "visualStyles", "colors");

    public ParsedResult parseVisual(String raw) {
        JsonNode result;
        try {
            result = JSON.readTree(raw == null ? "" : raw.trim());
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "UNPARSABLE_AI_RESPONSE", "AI 返回格式不可解析");
        }
        if (!result.isObject() || result.size() != FIELDS.size() || !FIELDS.stream().allMatch(result::has)) {
            throw new GatewayException(502, "UNPARSABLE_AI_RESPONSE", "AI 返回字段不符合图片分析格式");
        }
        JsonNode description = result.path("descriptionKeywords");
        if (!description.isTextual() || description.asText().isBlank() || description.asText().length() > 100
            || !description.asText().chars().allMatch(character -> character <= 127)
            || !description.asText().matches(".*[A-Za-z].*")) {
            throw new GatewayException(502, "INVALID_IMAGE_DESCRIPTION", "图片描述必须为不超过100字符的英文句子");
        }
        for (String field : FIELDS) {
            if ("descriptionKeywords".equals(field)) continue;
            JsonNode tags = result.path(field);
            int max = "colors".equals(field) ? 1 : 5;
            if (!tags.isArray() || tags.size() > max) {
                throw new GatewayException(502, "INVALID_VISUAL_TAG", "图片分类标签数量不正确");
            }
            for (JsonNode tag : tags) {
                if (!tag.isTextual() || !tag.asText().matches("[\\p{IsHan}]+|3D渲染")) {
                    throw new GatewayException(502, "INVALID_VISUAL_TAG", "图片分类标签必须为中文");
                }
            }
        }
        return new ParsedResult(description.asText().trim(), raw);
    }

    public record ParsedResult(String imageDescription, String rawAiResponse) { }
}
