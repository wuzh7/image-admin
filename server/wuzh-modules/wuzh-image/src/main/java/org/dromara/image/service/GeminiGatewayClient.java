package org.dromara.image.service;

import org.dromara.image.config.ImageProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

@Component
public class GeminiGatewayClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient client;
    private final String token;

    public GeminiGatewayClient(ImageProperties properties) {
        ImageProperties.Infringement config = properties.getInfringement();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, config.getRequestTimeoutSeconds()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.client = RestClient.builder().baseUrl(config.getGatewayUrl()).requestFactory(requestFactory).build();
        this.token = config.getGatewayToken();
    }

    public GatewayResponse analyze(String requestId, byte[] image, String contentType, String filename,
                                   String infringementMode) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        try {
            GatewayResponse response = client.post()
                .uri("/internal/v1/image-risk/analyze")
                .header("X-Gateway-Token", token)
                .header("X-Request-Id", requestId)
                .header("X-Image-Content-Type", contentType)
                .header("X-Infringement-Mode", infringementMode)
                .body(body)
                .retrieve()
                .body(GatewayResponse.class);
            if (response == null || response.riskLevel() == null) {
                throw new GatewayException("INVALID_GATEWAY_RESPONSE", "Gateway 返回为空", true);
            }
            return response;
        } catch (RestClientResponseException exception) {
            HttpStatusCode status = exception.getStatusCode();
            boolean retryable = status.value() == 429 || status.is5xxServerError();
            GatewayError error = parseError(exception.getResponseBodyAsString());
            if (error != null) throw new GatewayException(error.code(), error.message(), retryable,
                error.rawAiResponse(), error.usage(), error.rawProviderResponse());
            throw new GatewayException("GATEWAY_HTTP_" + status.value(), "Gateway 请求失败", retryable);
        } catch (ResourceAccessException exception) {
            throw new GatewayException("GATEWAY_UNAVAILABLE", "Gateway 网络或超时异常", true);
        } catch (RestClientException exception) {
            throw new GatewayException("GATEWAY_RESPONSE_ERROR", "AI Gateway 响应异常或超时", true);
        }
    }

    public BatchCreateResponse createBatch(String requestId, List<BatchImage> images) {
        try {
            BatchCreateResponse response = client.post()
                .uri("/internal/v1/image-risk/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Gateway-Token", token)
                .header("X-Request-Id", requestId)
                .body(new BatchCreateRequest(images))
                .retrieve()
                .body(BatchCreateResponse.class);
            if (response == null || response.providerJobId() == null || response.providerJobId().isBlank()
                || response.requestCount() != images.size()) {
                throw new GatewayException("INVALID_GATEWAY_RESPONSE", "Gateway Batch 返回为空", true);
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw responseException(exception);
        } catch (ResourceAccessException exception) {
            throw new GatewayException("GATEWAY_UNAVAILABLE", "Gateway 网络或超时异常", true);
        } catch (RestClientException exception) {
            throw new GatewayException("GATEWAY_RESPONSE_ERROR", "AI Gateway 响应异常或超时", true);
        }
    }

    public CaseLookupResponse lookupCases(String requestId, byte[] image, String contentType, String filename,
                                         String mode, String previousResponse) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(image) {
            @Override public String getFilename() { return filename; }
        });
        body.add("previousResponse", previousResponse);
        try {
            CaseLookupResponse response = client.post().uri("/internal/v1/image-risk/case-lookup")
                .header("X-Gateway-Token", token).header("X-Request-Id", requestId)
                .header("X-Image-Content-Type", contentType).header("X-Infringement-Mode", mode)
                .body(body).retrieve().body(CaseLookupResponse.class);
            if (response == null || response.caseNumbers() == null) {
                throw new GatewayException("INVALID_CASE_RESPONSE", "Gateway 案号返回为空", false);
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw responseException(exception);
        } catch (ResourceAccessException exception) {
            throw new GatewayException("GATEWAY_UNAVAILABLE", "Gateway 网络或超时异常", true);
        } catch (RestClientException exception) {
            throw new GatewayException("GATEWAY_RESPONSE_ERROR", "AI Gateway 响应异常", true);
        }
    }

    public BatchStatusResponse getBatch(String requestId, String jobId) {
        try {
            BatchStatusResponse response = client.get()
                .uri(builder -> builder.path("/internal/v1/image-risk/batches").queryParam("jobId", jobId).build())
                .header("X-Gateway-Token", token)
                .header("X-Request-Id", requestId)
                .retrieve()
                .body(BatchStatusResponse.class);
            if (response == null || response.state() == null) {
                throw new GatewayException("INVALID_GATEWAY_RESPONSE", "Gateway Batch 状态返回为空", true);
            }
            return response;
        } catch (RestClientResponseException exception) {
            throw responseException(exception);
        } catch (ResourceAccessException exception) {
            throw new GatewayException("GATEWAY_UNAVAILABLE", "Gateway 网络或超时异常", true);
        } catch (RestClientException exception) {
            throw new GatewayException("GATEWAY_RESPONSE_ERROR", "AI Gateway 响应异常或超时", true);
        }
    }

    private static GatewayException responseException(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        boolean retryable = status.value() == 429 || status.is5xxServerError();
        GatewayError error = parseError(exception.getResponseBodyAsString());
        if (error != null) return new GatewayException(error.code(), error.message(), retryable,
            error.rawAiResponse(), error.usage(), error.rawProviderResponse());
        return new GatewayException("GATEWAY_HTTP_" + status.value(), "Gateway 请求失败", retryable);
    }

    private static GatewayError parseError(String response) {
        try {
            GatewayError error = JSON.readValue(response, GatewayError.class);
            return error.code() == null || error.code().isBlank() || error.message() == null || error.message().isBlank()
                ? null : error;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record GatewayResponse(String requestId, String riskLevel, String riskReason,
                                  String imageDescription, String rawAiResponse,
                                  String model, String promptVersion,
                                  boolean searchUsed, int searchQueryCount,
                                   UsageMetadata usage, List<String> caseNumbers, String rawProviderResponse) {
        public GatewayResponse {
            caseNumbers = caseNumbers == null ? List.of() : caseNumbers.stream()
                .filter(java.util.Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        }
        public GatewayResponse(String requestId, String riskLevel, String riskReason,
                               String imageDescription, String rawAiResponse, String model, String promptVersion,
                               boolean searchUsed, int searchQueryCount, UsageMetadata usage, List<String> caseNumbers) {
            this(requestId, riskLevel, riskReason, imageDescription, rawAiResponse, model, promptVersion,
                searchUsed, searchQueryCount, usage, caseNumbers, null);
        }
        public GatewayResponse withRawProviderResponse(String response) {
            return new GatewayResponse(requestId, riskLevel, riskReason, imageDescription, rawAiResponse, model,
                promptVersion, searchUsed, searchQueryCount, usage, caseNumbers, response);
        }
        public GatewayResponse(String requestId, String riskLevel, String riskReason,
                               String imageDescription, String rawAiResponse, String model, String promptVersion,
                               boolean searchUsed, int searchQueryCount, UsageMetadata usage) {
            this(requestId, riskLevel, riskReason, imageDescription, rawAiResponse, model, promptVersion,
                searchUsed, searchQueryCount, usage, List.of());
        }
        public GatewayResponse(String requestId, String riskLevel, String riskReason,
                               String imageDescription, String rawAiResponse,
                               String model, String promptVersion,
                               boolean searchUsed, int searchQueryCount) {
            this(requestId, riskLevel, riskReason, imageDescription, rawAiResponse, model, promptVersion,
                searchUsed, searchQueryCount, null);
        }
    }

    public record CaseLookupResponse(String requestId, List<String> caseNumbers, String rawAiResponse,
                                     String model, boolean searchUsed, int searchQueryCount, UsageMetadata usage,
                                     String rawProviderResponse) {
        public CaseLookupResponse(String requestId, List<String> caseNumbers, String rawAiResponse,
                                  String model, boolean searchUsed, int searchQueryCount, UsageMetadata usage) {
            this(requestId, caseNumbers, rawAiResponse, model, searchUsed, searchQueryCount, usage, null);
        }
    }

    public record UsageMetadata(Integer promptTokenCount, Integer cachedContentTokenCount,
                                Integer candidatesTokenCount, Integer thoughtsTokenCount,
                                Integer toolUsePromptTokenCount, Integer totalTokenCount,
                                String rawJson) {
    }

    public record BatchImage(String url, String contentType) {
    }

    private record BatchCreateRequest(List<BatchImage> images) {
    }

    public record BatchCreateResponse(String requestId, String providerJobId, int requestCount) {
    }

    public record BatchStatusResponse(String providerJobId, String state, String errorMessage,
                                      List<BatchItemResponse> items, String rawProviderResponse) {
        public BatchStatusResponse(String providerJobId, String state, String errorMessage, List<BatchItemResponse> items) {
            this(providerJobId, state, errorMessage, items, null);
        }
    }

    public record BatchItemResponse(int index, GatewayResponse result, String errorCode, String errorMessage,
                                    String rawAiResponse, UsageMetadata usage, String rawProviderResponse) {
        public BatchItemResponse(int index, GatewayResponse result, String errorCode, String errorMessage,
                                 String rawAiResponse, UsageMetadata usage) {
            this(index, result, errorCode, errorMessage, rawAiResponse, usage, null);
        }
        public BatchItemResponse(int index, GatewayResponse result, String errorCode, String errorMessage) {
            this(index, result, errorCode, errorMessage, null, null);
        }
    }

    private record GatewayError(String code, String message, String rawAiResponse, UsageMetadata usage,
                                String rawProviderResponse) {
    }

    public static class GatewayException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final String rawAiResponse;
        private final UsageMetadata usage;
        private final String rawProviderResponse;

        public GatewayException(String code, String message, boolean retryable) {
            this(code, message, retryable, null, null);
        }

        public GatewayException(String code, String message, boolean retryable,
                                String rawAiResponse, UsageMetadata usage) {
            this(code, message, retryable, rawAiResponse, usage, null);
        }

        public GatewayException(String code, String message, boolean retryable,
                                String rawAiResponse, UsageMetadata usage, String rawProviderResponse) {
            super(message);
            this.code = code;
            this.retryable = retryable;
            this.rawAiResponse = rawAiResponse;
            this.usage = usage;
            this.rawProviderResponse = rawProviderResponse;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }

        public String rawAiResponse() {
            return rawAiResponse;
        }

        public UsageMetadata usage() {
            return usage;
        }

        public String rawProviderResponse() { return rawProviderResponse; }
    }
}
