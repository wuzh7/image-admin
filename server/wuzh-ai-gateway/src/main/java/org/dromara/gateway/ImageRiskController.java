package org.dromara.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;

@RestController
public class ImageRiskController {
    private static final int MAX_BATCH_IMAGES = 200;
    private static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final String PROMPT_VERSION = "gemini_prompt";
    private static final String RELAY_PROMPT_VERSION = "relay_pipeline_v1";

    private final Map<String, GeminiClient> clients;
    private final GoogleGeminiClient officialClient;
    private final ImageRiskParser parser;
    private final byte[] expectedToken;

    public ImageRiskController(List<GeminiClient> clients, ImageRiskParser parser,
        @Value("${gateway.token:local-gateway-token}") String token) {
        this.clients = clients.stream()
            .flatMap(client -> client.modes().stream().map(mode -> Map.entry(mode, client)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        this.officialClient = clients.stream().filter(GoogleGeminiClient.class::isInstance)
            .map(GoogleGeminiClient.class::cast).findFirst().orElse(null);
        this.parser = parser;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @PostMapping(value = "/internal/v1/image-risk/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalyzeResponse analyze(@RequestHeader("X-Gateway-Token") String token,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader(value = "X-Infringement-Mode", defaultValue = "RELAY") String infringementMode,
        @RequestHeader(value = "X-Image-Content-Type", required = false) String requestedContentType,
        @RequestPart("image") MultipartFile image) throws IOException {
        validateToken(token);
        String contentType = requestedContentType == null ? image.getContentType() : requestedContentType;
        if (image.isEmpty() || !CONTENT_TYPES.contains(contentType)) {
            throw new GatewayException(400, "INVALID_IMAGE", "只接受 JPEG、PNG 或 WebP 图片");
        }
        GeminiClient gemini = clients.get(infringementMode);
        if (gemini == null) throw new GatewayException(400, "INVALID_INFRINGEMENT_MODE", "侵权检测模式不可识别");
        GeminiClient.Analysis analysis;
        try {
            analysis = gemini.analyze(image.getBytes(), contentType, infringementMode);
        } catch (GatewayException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GatewayException(502, "AI_UPSTREAM_ERROR", "AI 调用失败");
        }
        ImageRiskParser.ParsedResult result;
        try {
            result = "RELAY".equals(infringementMode)
                ? parser.parseRelayFinal(analysis.rawAiResponse())
                : parser.parse(analysis.rawAiResponse());
        } catch (GatewayException exception) {
            throw exception.withAiResponse(analysis.rawAiResponse(), analysis.usage())
                .withRawProviderResponse(analysis.rawProviderResponse());
        }
        return response(requestId, result, analysis, gemini.model(),
            "RELAY".equals(infringementMode) ? RELAY_PROMPT_VERSION : PROMPT_VERSION);
    }

    @PostMapping(value = "/internal/v1/image-risk/batches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BatchCreateResponse createBatch(@RequestHeader("X-Gateway-Token") String token,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestBody BatchCreateRequest request) {
        validateToken(token);
        if (officialClient == null) throw new GatewayException(503, "GEMINI_NOT_CONFIGURED", "Gemini 官方客户端未配置");
        if (request == null || request.images() == null || request.images().isEmpty()
            || request.images().size() > MAX_BATCH_IMAGES) {
            throw new GatewayException(400, "INVALID_BATCH", "Batch 每组必须包含1至200张图片");
        }
        List<GoogleGeminiClient.BatchImage> images = request.images().stream().map(image -> {
            if (image == null || !validHttpsUrl(image.url()) || image.contentType() == null
                || !CONTENT_TYPES.contains(image.contentType())) {
                throw new GatewayException(400, "INVALID_BATCH_IMAGE", "Batch 图片必须是有效的 HTTPS 签名URL");
            }
            return new GoogleGeminiClient.BatchImage(image.url(), image.contentType());
        }).toList();
        String jobId = officialClient.createBatch(images, "image-risk-" + requestId);
        return new BatchCreateResponse(requestId, jobId, images.size());
    }

    @PostMapping(value = "/internal/v1/image-risk/case-lookup", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CaseLookupResponse lookupCases(@RequestHeader("X-Gateway-Token") String token,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Infringement-Mode") String mode,
        @RequestHeader("X-Image-Content-Type") String contentType,
        @RequestPart("image") MultipartFile image,
        @RequestParam("previousResponse") String previousResponse) throws IOException {
        validateToken(token);
        if (image.isEmpty() || !CONTENT_TYPES.contains(contentType)
            || previousResponse == null || previousResponse.isBlank() || previousResponse.length() > 65536) {
            throw new GatewayException(400, "INVALID_CASE_LOOKUP", "案号补查需要有效图片和首次检测结果");
        }
        GeminiClient gemini = clients.get(mode);
        if (gemini == null) throw new GatewayException(400, "INVALID_INFRINGEMENT_MODE", "侵权检测模式不可识别");
        GeminiClient.Analysis analysis = gemini.lookupCases(image.getBytes(), contentType, previousResponse);
        try {
            return new CaseLookupResponse(requestId, parser.parseCases(analysis.rawAiResponse()),
                analysis.rawAiResponse(), gemini.model(), analysis.searchUsed(), analysis.searchQueryCount(), analysis.usage(),
                analysis.rawProviderResponse());
        } catch (GatewayException exception) {
            throw exception.withAiResponse(analysis.rawAiResponse(), analysis.usage())
                .withRawProviderResponse(analysis.rawProviderResponse());
        }
    }

    @GetMapping("/internal/v1/image-risk/batches")
    public BatchStatusResponse getBatch(@RequestHeader("X-Gateway-Token") String token,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestParam("jobId") String jobId) {
        validateToken(token);
        if (officialClient == null) throw new GatewayException(503, "GEMINI_NOT_CONFIGURED", "Gemini 官方客户端未配置");
        GoogleGeminiClient.BatchStatus batch = officialClient.getBatch(jobId);
        List<BatchItemResponse> results = new java.util.ArrayList<>(batch.items().size());
        for (GoogleGeminiClient.BatchItem item : batch.items()) {
            int index = item.index();
            if (item.analysis() == null) {
                results.add(new BatchItemResponse(index, null, item.errorCode(), item.errorMessage(), null, null,
                    item.rawProviderResponse()));
                continue;
            }
            try {
                ImageRiskParser.ParsedResult parsed = parser.parse(item.analysis().rawAiResponse());
                AnalyzeResponse response = response(requestId, parsed, item.analysis(), officialClient.model(), PROMPT_VERSION);
                results.add(new BatchItemResponse(index, response, null, null,
                    item.analysis().rawAiResponse(), item.analysis().usage(), item.analysis().rawProviderResponse()));
            } catch (GatewayException exception) {
                results.add(new BatchItemResponse(index, null, exception.code(), exception.getMessage(),
                    item.analysis().rawAiResponse(), item.analysis().usage(), item.analysis().rawProviderResponse()));
            }
        }
        return new BatchStatusResponse(jobId, batch.state(), batch.errorMessage(), results, batch.rawProviderResponse());
    }

    private static AnalyzeResponse response(String requestId, ImageRiskParser.ParsedResult result,
                                            GeminiClient.Analysis analysis, String model, String promptVersion) {
        return new AnalyzeResponse(requestId, result.riskLevel(), result.riskReason(),
            result.imageDescription(), result.rawAiResponse(), model, promptVersion,
            analysis.searchUsed(), analysis.searchQueryCount(), analysis.usage(), result.caseNumbers(), analysis.rawProviderResponse());
    }

    private void validateToken(String token) {
        if (!MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new GatewayException(401, "UNAUTHORIZED", "Gateway 令牌无效");
        }
    }

    private static boolean validHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public record AnalyzeResponse(String requestId, String riskLevel, String riskReason,
                                  String imageDescription, String rawAiResponse,
                                  String model, String promptVersion,
                                  boolean searchUsed, int searchQueryCount, GeminiClient.Usage usage,
                                  List<String> caseNumbers, String rawProviderResponse) {
    }

    public record CaseLookupResponse(String requestId, List<String> caseNumbers, String rawAiResponse,
                                     String model, boolean searchUsed, int searchQueryCount, GeminiClient.Usage usage,
                                     String rawProviderResponse) {
    }

    public record BatchCreateResponse(String requestId, String providerJobId, int requestCount) {
    }

    public record BatchCreateRequest(List<BatchImageRequest> images) {
    }

    public record BatchImageRequest(String url, String contentType) {
    }

    public record BatchStatusResponse(String providerJobId, String state, String errorMessage,
                                      List<BatchItemResponse> items, String rawProviderResponse) {
    }

    public record BatchItemResponse(int index, AnalyzeResponse result, String errorCode, String errorMessage,
                                    String rawAiResponse, GeminiClient.Usage usage, String rawProviderResponse) {
    }

}
