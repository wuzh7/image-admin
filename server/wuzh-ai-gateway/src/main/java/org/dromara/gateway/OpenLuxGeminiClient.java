package org.dromara.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

@Component
public class OpenLuxGeminiClient implements GeminiClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient client;
    private final String apiKey;
    private final RelayStageProvider stages;
    private final RelayPipeline pipeline;

    public OpenLuxGeminiClient(
        @Value("${openlux.base-url:https://api.openlux.ai}") String baseUrl,
        @Value("${openlux.api-key:missing-local-key}") String apiKey,
        @Value("${openlux.timeout-seconds:900}") int timeoutSeconds,
        RelayStageProvider stageProvider,
        ImageRiskParser parser) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", ""))
            .requestFactory(requestFactory).build();
        this.apiKey = apiKey;
        this.stages = stageProvider;
        this.pipeline = new RelayPipeline(parser, new RelayResultMerger());
    }

    @Override
    public String mode() {
        return "RELAY";
    }

    @Override
    public Analysis analyze(byte[] image, String contentType) {
        List<RelayPipeline.Stage> configured = stages.get();
        return pipeline.execute(configured, (stage, prompt, previous, callType) -> callType == RelayPipeline.CallType.PROMPT1
            ? generatePrompt1(image, contentType, stage, prompt, previous)
            : generateFollowup(image, contentType, stage, previous, prompt, false));
    }

    @Override
    public Analysis lookupCases(byte[] image, String contentType, String previousResponse) {
        RelayPipeline.Stage stage = stages.get().getFirst();
        if (stage.prompt2() == null || stage.prompt2().isBlank()) {
            return new Analysis("[]", false, 0, null, null);
        }
        return generateFollowup(image, contentType, stage, previousResponse,
            stage.prompt2(), true);
    }

    private Analysis generatePrompt1(byte[] image, String contentType, RelayPipeline.Stage stage, String prompt,
                                     String previousResponse) {
        var parts = new java.util.ArrayList<Map<String, Object>>();
        parts.add(Map.of("inlineData", Map.of("mimeType", contentType,
            "data", Base64.getEncoder().encodeToString(image))));
        parts.add(Map.of("text", prompt));
        if (previousResponse != null) parts.add(Map.of("text", "Previous stage result:\n" + previousResponse));
        Map<String, Object> schema = previousResponse == null ? responseSchema() : riskSchema();
        return generate(stage, List.of(Map.of("role", "user", "parts", parts)), schema,
            previousResponse == null ? "PROMPT1" : "REVIEW");
    }

    private Analysis generateFollowup(byte[] image, String contentType, RelayPipeline.Stage stage,
                                      String previousResponse, String prompt2, boolean casesOnly) {
        var history = new java.util.ArrayList<Map<String, Object>>();
        history.add(Map.of("role", "user", "parts", List.of(
            Map.of("inlineData", Map.of("mimeType", contentType, "data", Base64.getEncoder().encodeToString(image))),
            Map.of("text", stage.prompt1()))));
        history.add(Map.of("role", "model", "parts", List.of(Map.of("text", previousResponse))));
        history.add(Map.of("role", "user", "parts", List.of(Map.of("text", prompt2))));
        Map<String, Object> schema = casesOnly ? Map.of("type", "ARRAY", "items", Map.of("type", "STRING")) : null;
        return generate(stage, history, schema, casesOnly ? "CASE_LOOKUP" : "PROMPT2");
    }

    private Analysis generate(RelayPipeline.Stage stage, List<Map<String, Object>> history,
                              Map<String, Object> schema, String operation) {
        String model = stage.model();
        if (apiKey == null || apiKey.isBlank() || "missing-local-key".equals(apiKey)) {
            throw new GatewayException(503, "OPENLUX_NOT_CONFIGURED", "OpenLux API Key 未配置");
        }
        Map<String, Object> generationConfig = new java.util.LinkedHashMap<>();
        generationConfig.put("temperature", 0);
        generationConfig.put("responseMimeType", "application/json");
        if (schema != null) generationConfig.put("responseSchema", schema);
        Map<String, Object> payload = Map.of(
            "contents", history,
            "tools", List.of(Map.of("googleSearch", Map.of())),
            "generationConfig", generationConfig);
        String rawResponse = null;
        try {
            boolean chat = "CHAT_COMPLETIONS".equals(stage.protocol());
            RestClient.RequestBodySpec request = chat
                ? client.post().uri("/v1/chat/completions").header("Authorization", "Bearer " + apiKey)
                : client.post().uri(builder -> builder.path("/v1beta/models/{model}:generateContent")
                    .queryParam("key", apiKey).build(model));
            rawResponse = request
                .contentType(MediaType.APPLICATION_JSON)
                .body(chat ? chatPayload(model, history) : payload)
                .retrieve()
                .body(String.class);
            AiProviderResponseLogger.log("RELAY " + model + " " + operation, 200, rawResponse);
            if (chat) return parseChat(rawResponse);
            GenerateContentResponse response = rawResponse == null ? null : JSON.readValue(rawResponse, GenerateContentResponse.class);
            String content = firstText(response);
            if (content == null || content.isBlank()) {
                throw new GatewayException(502, "EMPTY_AI_RESPONSE", "OpenLux 返回为空")
                    .withRawProviderResponse(rawResponse);
            }
            int queryCount = searchQueryCount(response);
            return new Analysis(content, queryCount > 0, queryCount, usage(response), rawResponse);
        } catch (RestClientResponseException exception) {
            AiProviderResponseLogger.log("RELAY " + model, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw mapHttpError(exception.getStatusCode()).withRawProviderResponse(exception.getResponseBodyAsString());
        } catch (ResourceAccessException exception) {
            throw new GatewayException(504, "OPENLUX_TIMEOUT", "OpenLux 网络异常或请求超时");
        } catch (RestClientException exception) {
            throw new GatewayException(502, "OPENLUX_RESPONSE_ERROR", "OpenLux 响应异常").withRawProviderResponse(rawResponse);
        } catch (GatewayException exception) {
            throw exception.rawProviderResponse() == null ? exception.withRawProviderResponse(rawResponse) : exception;
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "OPENLUX_RESPONSE_ERROR", "OpenLux 响应异常").withRawProviderResponse(rawResponse);
        }
    }

    @Override
    public String model() {
        return stages.model();
    }

    private static Map<String, Object> chatPayload(String model, List<Map<String, Object>> history) {
        var messages = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> entry : history) {
            var content = new java.util.ArrayList<Map<String, Object>>();
            var parts = JSON.valueToTree(entry.get("parts"));
            for (var part : parts) {
                if (part.has("inlineData")) {
                    var data = part.path("inlineData");
                    content.add(Map.of("type", "image_url", "image_url", Map.of("url",
                        "data:" + data.path("mimeType").asText() + ";base64," + data.path("data").asText())));
                } else {
                    content.add(Map.of("type", "text", "text", part.path("text").asText()));
                }
            }
            messages.add(Map.of("role", "model".equals(entry.get("role")) ? "assistant" : "user", "content", content));
        }
        return Map.of("model", model, "messages", messages, "stream", false);
    }

    private static Analysis parseChat(String raw) {
        var response = JSON.readTree(raw == null ? "" : raw);
        var choice = response.path("choices").path(0);
        String finish = choice.path("finish_reason").asText("");
        String content = choice.path("message").path("content").asText("");
        if (content.isBlank() || (!finish.isBlank() && !"stop".equals(finish))) {
            throw new GatewayException(502, "EMPTY_AI_RESPONSE", "中转站 Chat 返回为空或未完整结束");
        }
        var usage = response.path("usage");
        GeminiClient.Usage tokens = usage.isObject() ? new GeminiClient.Usage(
            chatInteger(usage.path("prompt_tokens")),
            chatInteger(usage.path("prompt_tokens_details").path("cached_tokens")),
            chatInteger(usage.path("completion_tokens")),
            chatInteger(usage.path("completion_tokens_details").path("reasoning_tokens")), null,
            chatInteger(usage.path("total_tokens")), JSON.writeValueAsString(usage)) : null;
        // No invented search evidence: chat relay search capabilities must be confirmed separately.
        return new Analysis(content, false, 0, tokens, raw);
    }

    private static Integer chatInteger(tools.jackson.databind.JsonNode value) {
        return value.isNumber() ? value.intValue() : null;
    }

    private static Map<String, Object> responseSchema() {
        Map<String, Object> text = Map.of("type", "STRING");
        Map<String, Object> tags = Map.of("type", "ARRAY", "items", text, "maxItems", 5);
        return Map.of(
            "type", "OBJECT",
            "properties", Map.ofEntries(
                Map.entry("riskLevel", Map.of("type", "STRING", "enum", List.of("HIGH", "LOW", "needs_review"))),
                Map.entry("riskReason", Map.of("type", "STRING", "maxLength", 100)),
                Map.entry("confidence", Map.of("type", "INTEGER", "minimum", 0, "maximum", 100)),
                Map.entry("detectedElements", tags),
                Map.entry("descriptionKeywords", Map.of("type", "STRING", "maxLength", 50)),
                Map.entry("subjects", tags), Map.entry("scenes", tags), Map.entry("themes", tags),
                Map.entry("visualStyles", tags),
                Map.entry("colors", Map.of("type", "ARRAY", "items", text, "maxItems", 1)),
                Map.entry("CaseNumber", Map.of("type", "ARRAY", "items", text))),
            "required", List.of("riskLevel", "riskReason", "descriptionKeywords", "subjects", "scenes",
                "themes", "visualStyles", "colors", "CaseNumber"));
    }

    private static Map<String, Object> riskSchema() {
        return Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                "riskLevel", Map.of("type", "STRING", "enum", List.of("HIGH", "LOW", "needs_review")),
                "riskReason", Map.of("type", "STRING", "maxLength", 100),
                "CaseNumber", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
            "required", List.of("riskLevel", "riskReason", "CaseNumber"));
    }

    private static String firstText(GenerateContentResponse response) {
        if (response == null || response.candidates() == null) return null;
        return response.candidates().stream()
            .filter(candidate -> candidate != null && candidate.content() != null && candidate.content().parts() != null)
            .flatMap(candidate -> candidate.content().parts().stream())
            .filter(part -> !Boolean.TRUE.equals(part.thought()))
            .map(Part::text).filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
    }

    private static int searchQueryCount(GenerateContentResponse response) {
        if (response == null || response.candidates() == null) return 0;
        return response.candidates().stream().filter(candidate -> candidate != null && candidate.groundingMetadata() != null)
            .flatMap(candidate -> candidate.groundingMetadata().webSearchQueries() == null
                ? java.util.stream.Stream.empty() : candidate.groundingMetadata().webSearchQueries().stream())
            .filter(query -> query != null && !query.isBlank()).toList().size();
    }

    private static GeminiClient.Usage usage(GenerateContentResponse response) {
        if (response == null || response.usageMetadata() == null || response.usageMetadata().isEmpty()) return null;
        Map<String, Object> value = response.usageMetadata();
        return new GeminiClient.Usage(
            integer(value.get("promptTokenCount")),
            integer(value.get("cachedContentTokenCount")),
            integer(value.get("candidatesTokenCount")),
            integer(value.get("thoughtsTokenCount")),
            integer(value.get("toolUsePromptTokenCount")),
            integer(value.get("totalTokenCount")),
            JSON.writeValueAsString(value));
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static GatewayException mapHttpError(HttpStatusCode status) {
        return switch (status.value()) {
            case 401, 403 -> new GatewayException(401, "OPENLUX_INVALID_API_KEY", "OpenLux API Key 无效");
            case 404 -> new GatewayException(400, "OPENLUX_MODEL_NOT_FOUND", "OpenLux 不支持所选模型");
            case 429 -> new GatewayException(429, "OPENLUX_RATE_LIMITED", "OpenLux 请求限流或余额不足");
            default -> status.is5xxServerError()
                ? new GatewayException(502, "OPENLUX_UPSTREAM_ERROR", "OpenLux 上游异常")
                : new GatewayException(400, "OPENLUX_CLIENT_ERROR", "OpenLux 请求被拒绝");
        };
    }

    private record GenerateContentResponse(List<Candidate> candidates, Map<String, Object> usageMetadata) {
    }

    private record Candidate(Content content, GroundingMetadata groundingMetadata) {
    }

    private record Content(List<Part> parts) {
    }

    private record Part(String text, Boolean thought) {
    }

    private record GroundingMetadata(List<String> webSearchQueries) {
    }
}
