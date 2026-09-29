package org.dromara.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class RelayImageClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final Path promptFile;

    public RelayImageClient(@Value("${openlux.base-url:https://api.openlux.ai}") String baseUrl,
                            @Value("${openlux.api-key:missing-local-key}") String apiKey,
                            @Value("${openlux.timeout-seconds:900}") int timeoutSeconds,
                            @Value("${relay.model:doubao-seed-2-0-lite-260428}") String model,
                            @Value("${relay.prompt-file:/opt/image-admin-ai-gateway/prompts/image_analysis_prompt.txt}") String promptFile) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.model = model;
        this.promptFile = Path.of(promptFile);
    }

    public String model() {
        return model;
    }

    public Analysis analyze(byte[] image, String contentType) {
        if (apiKey == null || apiKey.isBlank() || "missing-local-key".equals(apiKey)) {
            throw new GatewayException(503, "OPENLUX_NOT_CONFIGURED", "中转站 API Key 未配置");
        }
        String prompt;
        try {
            prompt = Files.readString(promptFile, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new GatewayException(503, "RELAY_PROMPT_UNAVAILABLE", "图片分析提示词不可读取");
        }
        if (prompt.isBlank()) throw new GatewayException(503, "EMPTY_RELAY_PROMPT", "图片分析提示词为空");
        Map<String, Object> payload = Map.of("model", model, "stream", false,
            "messages", List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", prompt),
                Map.of("type", "image_url", "image_url", Map.of("url", "data:" + contentType + ";base64,"
                    + Base64.getEncoder().encodeToString(image)))))));
        String raw = null;
        try {
            raw = client.post().uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(String.class);
            AiProviderResponseLogger.log("RELAY " + model + " IMAGE_ANALYSIS", 200, raw);
            var response = JSON.readTree(raw == null ? "" : raw);
            var choice = response.path("choices").path(0);
            String finish = choice.path("finish_reason").asText("");
            String content = choice.path("message").path("content").asText("");
            if (content.isBlank() || (!finish.isBlank() && !"stop".equals(finish))) {
                throw new GatewayException(502, "EMPTY_AI_RESPONSE", "中转站返回为空或未完整结束");
            }
            var usage = response.path("usage");
            Usage tokens = usage.isObject() ? new Usage(
                integer(usage.path("prompt_tokens")), integer(usage.path("prompt_tokens_details").path("cached_tokens")),
                integer(usage.path("completion_tokens")), integer(usage.path("completion_tokens_details").path("reasoning_tokens")),
                null, integer(usage.path("total_tokens")), JSON.writeValueAsString(usage)) : null;
            return new Analysis(content, tokens, raw);
        } catch (RestClientResponseException exception) {
            AiProviderResponseLogger.log("RELAY " + model, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            int status = exception.getStatusCode().value();
            throw new GatewayException(status == 429 ? 429 : status >= 500 ? 502 : 400,
                status == 429 ? "OPENLUX_RATE_LIMITED" : "OPENLUX_UPSTREAM_ERROR", "中转站请求失败")
                .withRawProviderResponse(exception.getResponseBodyAsString());
        } catch (ResourceAccessException exception) {
            throw new GatewayException(504, "OPENLUX_TIMEOUT", "中转站网络异常或请求超时");
        } catch (GatewayException exception) {
            throw exception.withRawProviderResponse(raw);
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "OPENLUX_RESPONSE_ERROR", "中转站响应异常").withRawProviderResponse(raw);
        }
    }

    private static Integer integer(tools.jackson.databind.JsonNode node) {
        return node.isNumber() ? node.intValue() : null;
    }

    public record Analysis(String rawAiResponse, Usage usage, String rawProviderResponse) { }
    public record Usage(Integer promptTokenCount, Integer cachedContentTokenCount, Integer candidatesTokenCount,
                        Integer thoughtsTokenCount, Integer toolUsePromptTokenCount, Integer totalTokenCount,
                        String rawJson) { }
}
