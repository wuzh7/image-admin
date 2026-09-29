package org.dromara.gateway;

public class GatewayException extends RuntimeException {
    private final int status;
    private final String code;
    private final String rawAiResponse;
    private final RelayImageClient.Usage usage;
    private final String rawProviderResponse;

    public GatewayException(int status, String code, String message) {
        this(status, code, message, null, null, null);
    }

    private GatewayException(int status, String code, String message, String rawAiResponse,
                             RelayImageClient.Usage usage, String rawProviderResponse) {
        super(message);
        this.status = status;
        this.code = code;
        this.rawAiResponse = rawAiResponse;
        this.usage = usage;
        this.rawProviderResponse = rawProviderResponse;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String rawAiResponse() {
        return rawAiResponse;
    }

    public RelayImageClient.Usage usage() {
        return usage;
    }

    public GatewayException withAiResponse(String rawAiResponse, RelayImageClient.Usage usage) {
        return new GatewayException(status, code, getMessage(), rawAiResponse, usage, rawProviderResponse);
    }

    public String rawProviderResponse() { return rawProviderResponse; }

    public GatewayException withRawProviderResponse(String response) {
        return new GatewayException(status, code, getMessage(), rawAiResponse, usage, response);
    }
}
