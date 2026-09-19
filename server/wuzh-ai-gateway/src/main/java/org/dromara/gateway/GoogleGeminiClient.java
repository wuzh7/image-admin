package org.dromara.gateway;

import com.google.genai.Client;
import com.google.genai.types.BatchJob;
import com.google.genai.types.BatchJobSource;
import com.google.genai.types.Candidate;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.CreateBatchJobConfig;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.InlinedRequest;
import com.google.genai.types.InlinedResponse;
import com.google.genai.types.JobState;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.ServiceTier;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Set;

@Component
public class GoogleGeminiClient implements GeminiClient {
    private static final GenerateContentConfig STANDARD_CONFIG = structuredOutputConfig(false);
    private static final GenerateContentConfig FLEX_CONFIG = structuredOutputConfig(true);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Client client;
    private final String model;
    private final GeminiPromptProvider promptProvider;

    public GoogleGeminiClient(Client client, @Value("${gemini.model:gemini-3.8-flash}") String model,
        GeminiPromptProvider promptProvider) {
        this.client = client;
        this.model = model;
        this.promptProvider = promptProvider;
    }

    @Override
    public String mode() {
        return "OFFICIAL_FLEX";
    }

    @Override
    public Set<String> modes() {
        return Set.of("OFFICIAL_FLEX", "OFFICIAL_STANDARD");
    }

    @Override
    public GeminiClient.Analysis analyze(byte[] image, String contentType) {
        return analyze(image, contentType, "OFFICIAL_FLEX");
    }

    @Override
    public GeminiClient.Analysis analyze(byte[] image, String contentType, String mode) {
        Content content = Content.fromParts(Part.fromBytes(image, contentType), Part.fromText(promptProvider.get()));
        var capture = AiProviderResponseLogger.capture();
        try (capture) {
            GenerateContentConfig config = "OFFICIAL_FLEX".equals(mode) ? FLEX_CONFIG : STANDARD_CONFIG;
            GenerateContentResponse response = client.models.generateContent(model, content, config);
            String text = response.text();
            if (text == null || text.isBlank()) throw new GatewayException(502, "EMPTY_AI_RESPONSE", "Gemini 返回为空")
                .withRawProviderResponse(capture.body());
            return withSearchUsage(text, response).withRawProviderResponse(capture.body());
        } catch (ApiException exception) {
            throw mapApiStatus(exception.code(), "OFFICIAL_FLEX".equals(mode)).withRawProviderResponse(capture.body());
        } catch (GenAiIOException exception) {
            throw new GatewayException(502, "GEMINI_NETWORK_ERROR", "Gemini 网络异常").withRawProviderResponse(capture.body());
        } catch (GatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "AI_UPSTREAM_ERROR", "AI 调用失败").withRawProviderResponse(capture.body());
        }
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public Analysis lookupCases(byte[] image, String contentType, String previousResponse) {
        List<Content> history = List.of(
            Content.builder().role("user").parts(Part.fromBytes(image, contentType), Part.fromText(promptProvider.get())).build(),
            Content.builder().role("model").parts(Part.fromText(previousResponse)).build(),
            Content.builder().role("user").parts(Part.fromText(CASE_LOOKUP_PROMPT)).build());
        var capture = AiProviderResponseLogger.capture();
        try (capture) {
            GenerateContentResponse response = client.models.generateContent(model, history,
                GenerateContentConfig.builder()
                    .tools(Tool.builder().googleSearch(GoogleSearch.builder()).build())
                    .responseMimeType("application/json")
                    .responseSchema(Schema.builder().type(Type.Known.ARRAY)
                        .items(Schema.builder().type(Type.Known.STRING).build()).build()).build());
            String text = response.text();
            if (text == null || text.isBlank()) throw new GatewayException(502, "EMPTY_AI_RESPONSE", "Gemini 返回为空")
                .withRawProviderResponse(capture.body());
            return withSearchUsage(text, response).withRawProviderResponse(capture.body());
        } catch (ApiException exception) {
            throw mapApiStatus(exception.code(), false).withRawProviderResponse(capture.body());
        } catch (GenAiIOException exception) {
            throw new GatewayException(502, "GEMINI_NETWORK_ERROR", "Gemini 网络异常").withRawProviderResponse(capture.body());
        } catch (GatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "AI_UPSTREAM_ERROR", "AI 调用失败").withRawProviderResponse(capture.body());
        }
    }

    public String createBatch(List<BatchImage> images, String displayName) {
        var capture = AiProviderResponseLogger.capture();
        try (capture) {
            BatchJob job = client.batches.create(model, batchSource(images, promptProvider.get()),
                CreateBatchJobConfig.builder().displayName(displayName).build());
            return job.name().orElseThrow(() ->
                new GatewayException(502, "EMPTY_BATCH_JOB_ID", "Gemini Batch 未返回作业ID").withRawProviderResponse(capture.body()));
        } catch (ApiException exception) {
            throw mapApiStatus(exception.code(), false).withRawProviderResponse(capture.body());
        } catch (GenAiIOException exception) {
            throw new GatewayException(502, "GEMINI_NETWORK_ERROR", "Gemini 网络异常").withRawProviderResponse(capture.body());
        } catch (GatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "AI_UPSTREAM_ERROR", "AI 调用失败").withRawProviderResponse(capture.body());
        }
    }

    static BatchJobSource batchSource(List<BatchImage> images, String prompt) {
        List<InlinedRequest> requests = images.stream().map(image -> InlinedRequest.builder()
            .contents(Content.fromParts(Part.fromUri(image.url(), image.contentType()), Part.fromText(prompt)))
            .config(STANDARD_CONFIG)
            .build()).toList();
        return BatchJobSource.builder().inlinedRequests(requests).build();
    }

    public BatchStatus getBatch(String jobId) {
        var capture = AiProviderResponseLogger.capture();
        try (capture) {
            BatchJob job = client.batches.get(jobId, null);
            String rawJob = capture.body();
            JsonNode rawItems = rawJob == null ? null : JSON.readTree(rawJob).findValue("inlinedResponses");
            if (rawItems != null && rawItems.isObject()) rawItems = rawItems.path("inlinedResponses");
            JsonNode originalItems = rawItems;
            String state = job.state().map(JobState::knownEnum).map(Enum::name)
                .map(value -> value.replace("JOB_STATE_", "")).orElse("UNKNOWN");
            List<InlinedResponse> inlineResponses = job.dest().flatMap(destination -> destination.inlinedResponses())
                .orElse(List.of());
            List<BatchItem> results = java.util.stream.IntStream.range(0, inlineResponses.size())
                .mapToObj(index -> toBatchItem(index, inlineResponses.get(index),
                    originalItems != null && originalItems.has(index) ? originalItems.get(index).toString() : null)).toList();
            if (results.isEmpty()) {
                results = readFileResults(job);
            }
            String error = job.error().flatMap(value -> value.message()).orElse(null);
            return new BatchStatus(state, error, results, rawJob);
        } catch (ApiException exception) {
            throw mapBatchLookupStatus(exception.code()).withRawProviderResponse(capture.body());
        } catch (GenAiIOException exception) {
            throw new GatewayException(502, "GEMINI_NETWORK_ERROR", "Gemini 网络异常").withRawProviderResponse(capture.body());
        } catch (GatewayException exception) {
            throw exception.withRawProviderResponse(capture.body());
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "AI_UPSTREAM_ERROR", "AI 调用失败").withRawProviderResponse(capture.body());
        }
    }

    private List<BatchItem> readFileResults(BatchJob job) {
        String fileName = job.dest().flatMap(destination -> destination.fileName()).orElse(null);
        if (fileName == null || fileName.isBlank()) return List.of();
        Path output = null;
        try {
            output = Files.createTempFile("gemini-batch-result-", ".jsonl");
            client.files.download(fileName, output.toString(), null);
            List<BatchItem> results = new java.util.ArrayList<>();
            try (var lines = Files.lines(output, StandardCharsets.UTF_8)) {
                lines.filter(line -> !line.isBlank()).forEach(line -> results.add(parseFileResult(line)));
            }
            return results;
        } catch (IOException exception) {
            throw new GatewayException(502, "BATCH_RESULT_READ_ERROR", "Gemini Batch 结果文件读取失败");
        } finally {
            if (output != null) try { Files.deleteIfExists(output); } catch (IOException ignored) { }
        }
    }

    private BatchItem parseFileResult(String line) {
        AiProviderResponseLogger.log("OFFICIAL BATCH_RESULT_FILE", 200, line);
        try {
            JsonNode node = JSON.readTree(line);
            String key = node.path("key").asText("");
            int index = key.startsWith("request-") ? Integer.parseInt(key.substring("request-".length())) : -1;
            JsonNode response = node.get("response");
            if (response != null && !response.isNull()) {
                GenerateContentResponse generated = GenerateContentResponse.fromJson(response.toString());
                String text = generated.text();
                return text == null || text.isBlank()
                    ? new BatchItem(index, null, "EMPTY_AI_RESPONSE", "Gemini 返回为空", line)
                    : new BatchItem(index, withSearchUsage(text, generated).withRawProviderResponse(line), null, null, line);
            }
            JsonNode error = node.get("error");
            return new BatchItem(index, null, "GEMINI_BATCH_ITEM_FAILED",
                error == null ? "Gemini Batch 单项失败" : error.path("message").asText("Gemini Batch 单项失败"), line);
        } catch (RuntimeException exception) {
            return new BatchItem(-1, null, "BATCH_RESULT_PARSE_ERROR", "Gemini Batch 结果格式无法解析", line);
        }
    }

    private static BatchItem toBatchItem(int index, InlinedResponse response, String rawProviderResponse) {
        if (response.response().isPresent()) {
            GenerateContentResponse generated = response.response().orElseThrow();
            String text = generated.text();
            if (text == null || text.isBlank()) {
                return new BatchItem(index, null, "EMPTY_AI_RESPONSE", "Gemini 返回为空", rawProviderResponse);
            }
            return new BatchItem(index, withSearchUsage(text, generated).withRawProviderResponse(rawProviderResponse),
                null, null, rawProviderResponse);
        }
        String message = response.error().flatMap(value -> value.message()).orElse("Gemini Batch 单项失败");
        return new BatchItem(index, null, "GEMINI_BATCH_ITEM_FAILED", message, rawProviderResponse);
    }

    static GatewayException mapApiStatus(int status) {
        return mapApiStatus(status, true);
    }

    static GatewayException mapApiStatus(int status, boolean flex) {
        return switch (status) {
            case 401, 403 -> new GatewayException(status, "GEMINI_AUTH_ERROR", "Gemini API Key 或项目权限异常（HTTP " + status + "）");
            case 404 -> new GatewayException(400, "GEMINI_MODEL_NOT_FOUND", "Gemini 模型不可用（HTTP 404）");
            case 429 -> new GatewayException(429, "GEMINI_RATE_LIMITED", "Gemini 请求限流或资源不足（HTTP 429）");
            case 500 -> new GatewayException(502, "GEMINI_INTERNAL_ERROR", "Gemini 服务内部错误（HTTP 500）");
            case 503 -> new GatewayException(503, "GEMINI_SERVICE_UNAVAILABLE",
                flex ? "Gemini Flex 容量不足（HTTP 503）" : "Gemini 服务暂时不可用（HTTP 503）");
            case 504 -> new GatewayException(504, "GEMINI_TIMEOUT", "Gemini 请求超时（HTTP 504）");
            default -> status >= 500
                ? new GatewayException(502, "GEMINI_HTTP_" + status, "Gemini 上游异常（HTTP " + status + "）")
                : new GatewayException(400, "GEMINI_CLIENT_ERROR", "Gemini 请求被拒绝（HTTP " + status + "）");
        };
    }

    static GatewayException mapBatchLookupStatus(int status) {
        return switch (status) {
            case 400 -> new GatewayException(400, "GEMINI_BATCH_INVALID_JOB", "Gemini Batch 作业查询被拒绝（HTTP 400）");
            case 404 -> new GatewayException(404, "GEMINI_BATCH_NOT_FOUND", "Gemini Batch 作业不存在或当前项目无权访问（HTTP 404）");
            default -> mapApiStatus(status, false);
        };
    }

    static GeminiClient.Analysis withSearchUsage(String rawAiResponse, GenerateContentResponse response) {
        int queries = response.candidates().orElse(List.of()).stream()
            .map(Candidate::groundingMetadata).flatMap(java.util.Optional::stream)
            .flatMap(metadata -> metadata.webSearchQueries().orElse(List.of()).stream())
            .filter(query -> query != null && !query.isBlank())
            .toList().size();
        GeminiClient.Usage usage = response.usageMetadata().map(value -> new GeminiClient.Usage(
            value.promptTokenCount().orElse(null),
            value.cachedContentTokenCount().orElse(null),
            value.candidatesTokenCount().orElse(null),
            value.thoughtsTokenCount().orElse(null),
            value.toolUsePromptTokenCount().orElse(null),
            value.totalTokenCount().orElse(null),
            value.toJson())).orElse(null);
        return new GeminiClient.Analysis(rawAiResponse, queries > 0, queries, usage);
    }

    static GenerateContentConfig structuredOutputConfig() {
        return structuredOutputConfig(true);
    }

    static GenerateContentConfig structuredOutputConfig(boolean flex) {
        Schema riskLevel = Schema.builder()
            .type(Type.Known.STRING)
            .enum_("HIGH", "LOW", "needs_review")
            .description("侵权风险等级")
            .build();
        Schema riskReason = Schema.builder()
            .type(Type.Known.STRING)
            .maxLength(100L)
            .description("LOW 时为空字符串；HIGH 或 needs_review 时使用简体中文说明原因或疑点")
            .build();
        Schema confidence = Schema.builder().type(Type.Known.INTEGER).minimum(0D).maximum(100D).build();
        Schema detectedElements = Schema.builder().type(Type.Known.ARRAY)
            .items(Schema.builder().type(Type.Known.STRING).build()).maxItems(5L).build();
        Schema descriptionKeywords = Schema.builder()
            .type(Type.Known.STRING)
            .maxLength(50L)
            .description("英文逗号分隔的可见画面描述关键词，最多 50 个字符")
            .build();
        Schema tag = Schema.builder().type(Type.Known.STRING).build();
        Schema visualTags = Schema.builder().type(Type.Known.ARRAY).items(tag).maxItems(5L).build();
        Schema colors = Schema.builder().type(Type.Known.ARRAY).items(tag).maxItems(1L).build();
        Schema response = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(Map.ofEntries(
                Map.entry("riskLevel", riskLevel), Map.entry("riskReason", riskReason),
                Map.entry("confidence", confidence), Map.entry("detectedElements", detectedElements),
                Map.entry("descriptionKeywords", descriptionKeywords), Map.entry("subjects", visualTags),
                Map.entry("scenes", visualTags), Map.entry("themes", visualTags),
                Map.entry("visualStyles", visualTags), Map.entry("colors", colors),
                Map.entry("CaseNumber", Schema.builder().type(Type.Known.ARRAY).items(tag).build())))
            .propertyOrdering("riskLevel", "riskReason", "descriptionKeywords", "subjects", "scenes", "themes", "visualStyles", "colors", "CaseNumber")
            .required("riskLevel", "riskReason", "descriptionKeywords", "subjects", "scenes", "themes", "visualStyles", "colors", "CaseNumber")
            .build();
        GenerateContentConfig.Builder builder = GenerateContentConfig.builder()
            .tools(Tool.builder().googleSearch(GoogleSearch.builder()).build())
            .responseMimeType("application/json")
            .responseSchema(response);
        if (flex) builder.serviceTier(ServiceTier.Known.FLEX);
        return builder.build();
    }

    public record BatchImage(String url, String contentType) {
    }

    public record BatchStatus(String state, String errorMessage, List<BatchItem> items, String rawProviderResponse) {
        public BatchStatus(String state, String errorMessage, List<BatchItem> items) {
            this(state, errorMessage, items, null);
        }
    }

    public record BatchItem(int index, Analysis analysis, String errorCode, String errorMessage,
                            String rawProviderResponse) {
        public BatchItem(int index, Analysis analysis, String errorCode, String errorMessage) {
            this(index, analysis, errorCode, errorMessage, analysis == null ? null : analysis.rawProviderResponse());
        }
    }
}
