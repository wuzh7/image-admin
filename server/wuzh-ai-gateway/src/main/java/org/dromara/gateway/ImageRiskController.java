package org.dromara.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class ImageRiskController {
    private static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final String PROMPT_VERSION = "relay_image_analysis_v1";

    private final RelayImageClient client;
    private final ImageRiskParser parser;
    private final byte[] expectedToken;

    public ImageRiskController(RelayImageClient client, ImageRiskParser parser,
                               @Value("${gateway.token:local-gateway-token}") String token) {
        this.client = client;
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
                                   @RequestHeader(value = "X-Infringement-Mode", defaultValue = "RELAY") String mode,
                                   @RequestHeader(value = "X-Image-Content-Type", required = false) String requestedContentType,
                                   @RequestPart("image") MultipartFile image) throws IOException {
        if (!MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            throw new GatewayException(401, "UNAUTHORIZED", "Gateway 令牌无效");
        }
        if (!"RELAY".equals(mode)) throw new GatewayException(400, "INVALID_ANALYSIS_MODE", "只支持中转站图片分析");
        String contentType = requestedContentType == null ? image.getContentType() : requestedContentType;
        if (image.isEmpty() || !CONTENT_TYPES.contains(contentType)) {
            throw new GatewayException(400, "INVALID_IMAGE", "只接受 JPEG、PNG 或 WebP 图片");
        }
        RelayImageClient.Analysis analysis = client.analyze(image.getBytes(), contentType);
        ImageRiskParser.ParsedResult parsed;
        try {
            parsed = parser.parseVisual(analysis.rawAiResponse());
        } catch (GatewayException exception) {
            throw exception.withAiResponse(analysis.rawAiResponse(), analysis.usage())
                .withRawProviderResponse(analysis.rawProviderResponse());
        }
        return new AnalyzeResponse(requestId, null, null, parsed.imageDescription(), parsed.rawAiResponse(),
            client.model(), PROMPT_VERSION, false, 0, analysis.usage(), List.of(), analysis.rawProviderResponse());
    }

    public record AnalyzeResponse(String requestId, String riskLevel, String riskReason,
                                  String imageDescription, String rawAiResponse, String model, String promptVersion,
                                  boolean searchUsed, int searchQueryCount, RelayImageClient.Usage usage,
                                  List<String> caseNumbers, String rawProviderResponse) { }
}
