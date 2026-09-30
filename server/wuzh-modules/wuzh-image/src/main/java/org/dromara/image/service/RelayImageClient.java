package org.dromara.image.service;

import org.dromara.image.config.ImageProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class RelayImageClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MODEL = "doubao-seed-2-0-lite-260428";
    private static final String PROMPT_VERSION = "relay_image_analysis_v1";
    private static final Set<String> FIELDS = Set.of("descriptionKeywords", "subjects", "scenes", "themes", "visualStyles", "colors");
    private final RestClient client;
    private final String apiKey;
    private final Path promptFile;

    public RelayImageClient(ImageProperties properties) {
        ImageProperties.Infringement config = properties.getInfringement();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, config.getRequestTimeoutSeconds()));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(config.getRelayBaseUrl().replaceAll("/+$", ""))
            .requestFactory(factory).build();
        this.apiKey = config.getRelayApiKey();
        this.promptFile = Path.of(config.getPromptFile());
    }

    public AnalysisResponse analyze(byte[] image, String contentType) {
        if (apiKey == null || apiKey.isBlank() || "replace-with-openlux-key".equals(apiKey)) {
            throw new RelayException("OPENLUX_NOT_CONFIGURED", "中转站 API Key 未配置", false);
        }
        if (image == null || image.length == 0 || contentType == null
            || !Set.of("image/jpeg", "image/png", "image/webp").contains(contentType)) {
            throw new RelayException("INVALID_IMAGE", "只接受 JPEG、PNG 或 WebP 图片", false);
        }
        String prompt;
        try {
            prompt = Files.readString(promptFile, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new RelayException("RELAY_PROMPT_UNAVAILABLE", "图片分析提示词不可读取", false);
        }
        if (prompt.isBlank()) throw new RelayException("EMPTY_RELAY_PROMPT", "图片分析提示词为空", false);
        Map<String, Object> payload = Map.of("model", MODEL, "stream", false,
            "messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", prompt),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:" + contentType + ";base64,"
                    + Base64.getEncoder().encodeToString(image)))))));
        String raw = null;
        try {
            raw = client.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(String.class);
            JsonNode response = JSON.readTree(raw == null ? "" : raw);
            JsonNode choice = response.path("choices").path(0);
            String finish = choice.path("finish_reason").asText("");
            String content = choice.path("message").path("content").asText("");
            if (content.isBlank() || (!finish.isBlank() && !"stop".equals(finish))) {
                throw new RelayException("EMPTY_AI_RESPONSE", "中转站返回为空或未完整结束", true);
            }
            JsonNode usageNode = response.path("usage");
            UsageMetadata usage = usageNode.isObject() ? new UsageMetadata(
                integer(usageNode.path("prompt_tokens")), integer(usageNode.path("prompt_tokens_details").path("cached_tokens")),
                integer(usageNode.path("completion_tokens")), integer(usageNode.path("completion_tokens_details").path("reasoning_tokens")),
                null, integer(usageNode.path("total_tokens")), JSON.writeValueAsString(usageNode)) : null;
            String description;
            try {
                description = parseDescription(content);
            } catch (RelayException exception) {
                throw exception.withAiResponse(content, usage);
            }
            return new AnalysisResponse(description, content, MODEL, PROMPT_VERSION, usage, raw);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new RelayException(status == 429 ? "OPENLUX_RATE_LIMITED" : "OPENLUX_UPSTREAM_ERROR",
                "中转站请求失败", status == 429 || status >= 500)
                .withRawProviderResponse(exception.getResponseBodyAsString());
        } catch (ResourceAccessException exception) {
            throw new RelayException("OPENLUX_TIMEOUT", "中转站网络异常或请求超时", true);
        } catch (RelayException exception) {
            throw exception.withRawProviderResponse(raw);
        } catch (RuntimeException exception) {
            throw new RelayException("OPENLUX_RESPONSE_ERROR", "中转站响应异常", true).withRawProviderResponse(raw);
        }
    }

    static String parseDescription(String raw) {
        JsonNode result;
        try {
            result = JSON.readTree(raw == null ? "" : raw.trim());
        } catch (RuntimeException exception) {
            throw new RelayException("UNPARSABLE_AI_RESPONSE", "AI 返回格式不可解析", true);
        }
        if (!result.isObject() || result.size() != FIELDS.size() || !FIELDS.stream().allMatch(result::has)) {
            throw new RelayException("UNPARSABLE_AI_RESPONSE", "AI 返回字段不符合图片分析格式", true);
        }
        JsonNode description = result.path("descriptionKeywords");
        if (!description.isTextual() || description.asText().isBlank()
            || !description.asText().chars().allMatch(character -> character <= 127)
            || !description.asText().matches(".*[A-Za-z].*")) {
            throw new RelayException("INVALID_IMAGE_DESCRIPTION", "图片描述必须为英文句子", true);
        }
        for (String field : FIELDS) {
            if ("descriptionKeywords".equals(field)) continue;
            JsonNode tags = result.path(field);
            int max = "colors".equals(field) ? 1 : 5;
            if (!tags.isArray() || tags.size() > max) {
                throw new RelayException("INVALID_VISUAL_TAG", "图片分类标签数量不正确", true);
            }
            for (JsonNode tag : tags) {
                if (!tag.isTextual() || !tag.asText().matches("[\\p{IsHan}]+|3D渲染")) {
                    throw new RelayException("INVALID_VISUAL_TAG", "图片分类标签必须为中文", true);
                }
            }
        }
        return description.asText().trim();
    }

    private static Integer integer(JsonNode node) {
        return node.isNumber() ? node.intValue() : null;
    }

    public record AnalysisResponse(String imageDescription, String rawAiResponse, String model,
                                   String promptVersion, UsageMetadata usage, String rawProviderResponse) { }
    public record UsageMetadata(Integer promptTokenCount, Integer cachedContentTokenCount,
                                Integer candidatesTokenCount, Integer thoughtsTokenCount,
                                Integer toolUsePromptTokenCount, Integer totalTokenCount, String rawJson) { }

    public static class RelayException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final String rawAiResponse;
        private final UsageMetadata usage;
        private final String rawProviderResponse;

        public RelayException(String code, String message, boolean retryable) {
            this(code, message, retryable, null, null, null);
        }

        private RelayException(String code, String message, boolean retryable, String rawAiResponse,
                               UsageMetadata usage, String rawProviderResponse) {
            super(message);
            this.code = code;
            this.retryable = retryable;
            this.rawAiResponse = rawAiResponse;
            this.usage = usage;
            this.rawProviderResponse = rawProviderResponse;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public String rawAiResponse() { return rawAiResponse; }
        public UsageMetadata usage() { return usage; }
        public String rawProviderResponse() { return rawProviderResponse; }
        private RelayException withAiResponse(String response, UsageMetadata tokens) {
            return new RelayException(code, getMessage(), retryable, response, tokens, rawProviderResponse);
        }
        private RelayException withRawProviderResponse(String response) {
            return new RelayException(code, getMessage(), retryable, rawAiResponse, usage, response);
        }
    }
}
