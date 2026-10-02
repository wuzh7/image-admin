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
    private final RestClient client;
    private final String apiKey;
    private final Path promptFile;

    public RelayImageClient(ImageProperties properties) {
        ImageProperties.Infringement config = properties.getInfringement();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, config.getRequestTimeoutSeconds()));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(config.getArkBaseUrl().replaceAll("/+$", ""))
            .requestFactory(factory).build();
        this.apiKey = config.getArkApiKey();
        this.promptFile = Path.of(config.getPromptFile());
    }

    public AnalysisResponse analyze(byte[] image, String contentType) {
        if (apiKey == null || apiKey.isBlank() || "replace-with-ark-key".equals(apiKey)) {
            throw new RelayException("ARK_NOT_CONFIGURED", "火山方舟 API Key 未配置", false);
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
        Map<String, Object> payload = Map.of("model", MODEL, "stream", false, "store", false,
            "thinking", Map.of("type", "disabled"),
            "input", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "input_image", "image_url", "data:" + contentType + ";base64,"
                    + Base64.getEncoder().encodeToString(image)),
                Map.of("type", "input_text", "text", prompt)))));
        String raw = null;
        try {
            raw = client.post().uri("/api/v3/responses")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(String.class);
            JsonNode response = JSON.readTree(raw == null ? "" : raw);
            String content = "";
            for (JsonNode item : response.path("output")) {
                if (!"message".equals(item.path("type").asText())
                    || !"completed".equals(item.path("status").asText())) continue;
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asText())) {
                        content = part.path("text").asText("");
                        break;
                    }
                }
                if (!content.isBlank()) break;
            }
            if (!"completed".equals(response.path("status").asText()) || content.isBlank()) {
                throw new RelayException("EMPTY_AI_RESPONSE", "火山方舟返回为空或未完整结束", true);
            }
            JsonNode usageNode = response.path("usage");
            UsageMetadata usage = usageNode.isObject() ? new UsageMetadata(
                integer(usageNode.path("input_tokens")), integer(usageNode.path("input_tokens_details").path("cached_tokens")),
                integer(usageNode.path("output_tokens")), integer(usageNode.path("output_tokens_details").path("reasoning_tokens")),
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
            throw new RelayException(status == 429 ? "ARK_RATE_LIMITED" : "ARK_UPSTREAM_ERROR",
                "火山方舟请求失败", status == 429 || status >= 500)
                .withRawProviderResponse(exception.getResponseBodyAsString());
        } catch (ResourceAccessException exception) {
            throw new RelayException("ARK_TIMEOUT", "火山方舟网络异常或请求超时", true);
        } catch (RelayException exception) {
            throw exception.withRawProviderResponse(raw);
        } catch (RuntimeException exception) {
            throw new RelayException("ARK_RESPONSE_ERROR", "火山方舟响应异常", true).withRawProviderResponse(raw);
        }
    }

    static String parseDescription(String raw) {
        JsonNode result;
        try {
            result = JSON.readTree(raw == null ? "" : raw.trim());
        } catch (RuntimeException exception) {
            throw new RelayException("UNPARSABLE_AI_RESPONSE", "AI 返回格式不可解析", true);
        }
        JsonNode description = result.path("descriptionKeywords");
        if (!description.isTextual() || description.asText().isBlank()) {
            throw new RelayException("INVALID_IMAGE_DESCRIPTION", "AI 未返回图片描述", true);
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
