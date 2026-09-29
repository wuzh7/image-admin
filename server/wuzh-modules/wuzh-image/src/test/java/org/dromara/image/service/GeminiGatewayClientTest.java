package org.dromara.image.service;

import com.sun.net.httpserver.HttpServer;
import org.dromara.image.config.ImageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class GeminiGatewayClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void retriesRateLimitsAndServerErrorsButNotClientErrors() throws Exception {
        assertTrue(callStatus(429).retryable());
        assertTrue(callStatus(500).retryable());
        assertFalse(callStatus(400).retryable());
    }

    @Test
    void retriesNetworkFailures() throws Exception {
        startServer(200);
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        server.stop(0);
        server = null;

        GeminiGatewayClient.GatewayException error = assertThrows(GeminiGatewayClient.GatewayException.class,
            () -> client(url).analyze("request", new byte[]{1}, "image/png", "a.png", "RELAY"));

        assertEquals("GATEWAY_UNAVAILABLE", error.code());
        assertTrue(error.retryable());
    }

    @Test
    void preservesGatewayFailureCodeAndMessage() throws Exception {
        stopServer();
        startServer(502, """
            {"code":"UNPARSABLE_AI_RESPONSE","message":"Gemini response cannot be parsed",
             "rawAiResponse":"not-json","rawProviderResponse":"{\\"providerExtra\\":true}","usage":{"promptTokenCount":10,"totalTokenCount":12,
             "rawJson":"{\\"promptTokenCount\\":10,\\"totalTokenCount\\":12}"}}
            """);

        GeminiGatewayClient.GatewayException error = assertThrows(GeminiGatewayClient.GatewayException.class,
            () -> client("http://127.0.0.1:" + server.getAddress().getPort())
                .analyze("request", new byte[]{1}, "image/png", "a.png", "RELAY"));

        assertEquals("UNPARSABLE_AI_RESPONSE", error.code());
        assertEquals("Gemini response cannot be parsed", error.getMessage());
        assertTrue(error.retryable());
        assertEquals("not-json", error.rawAiResponse());
        assertEquals("{\"providerExtra\":true}", error.rawProviderResponse());
        assertEquals(10, error.usage().promptTokenCount());
        assertEquals(12, error.usage().totalTokenCount());
    }

    @Test
    void acceptsSuccessfulImageAnalysisWithoutRiskLevel() throws Exception {
        startServer(200, """
            {"requestId":"request","riskLevel":null,"riskReason":null,"imageDescription":"A red flower.",
             "rawAiResponse":"{}","model":"doubao-seed-2-0-lite-260428","promptVersion":"relay_image_analysis_v1",
             "searchUsed":false,"searchQueryCount":0,"caseNumbers":[]}
            """);

        var response = client("http://127.0.0.1:" + server.getAddress().getPort())
            .analyze("request", new byte[]{1}, "image/png", "a.png", "RELAY");

        assertEquals(null, response.riskLevel());
        assertEquals("A red flower.", response.imageDescription());
    }

    @Test
    void readsCompleteUsageMetadata() throws Exception {
        startServer(200, """
            {"requestId":"request","riskLevel":"LOW","riskReason":null,
             "imageDescription":"simple pattern","rawAiResponse":"raw","rawProviderResponse":"{\\"candidates\\":[],\\"groundingMetadata\\":{}}","model":"model",
             "promptVersion":"gemini_prompt","searchUsed":false,"searchQueryCount":0,
             "usage":{"promptTokenCount":10,"cachedContentTokenCount":2,"candidatesTokenCount":3,
             "thoughtsTokenCount":4,"toolUsePromptTokenCount":5,"totalTokenCount":24,
             "rawJson":"{\\\"totalTokenCount\\\":24}"}}
            """);

        GeminiGatewayClient.GatewayResponse response = client("http://127.0.0.1:" + server.getAddress().getPort())
            .analyze("request", new byte[]{1}, "image/png", "a.png", "RELAY");

        assertEquals(10, response.usage().promptTokenCount());
        assertEquals(2, response.usage().cachedContentTokenCount());
        assertEquals(3, response.usage().candidatesTokenCount());
        assertEquals(4, response.usage().thoughtsTokenCount());
        assertEquals(5, response.usage().toolUsePromptTokenCount());
        assertEquals(24, response.usage().totalTokenCount());
        assertEquals("{\"totalTokenCount\":24}", response.usage().rawJson());
        assertEquals("{\"candidates\":[],\"groundingMetadata\":{}}", response.rawProviderResponse());
    }

    private GeminiGatewayClient.GatewayException callStatus(int status) throws Exception {
        stopServer();
        startServer(status);
        return assertThrows(GeminiGatewayClient.GatewayException.class,
            () -> client("http://127.0.0.1:" + server.getAddress().getPort())
                .analyze("request", new byte[]{1}, "image/png", "a.png", "RELAY"));
    }

    private void startServer(int status) throws IOException {
        startServer(status, "");
    }

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/image-risk/analyze", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    private static GeminiGatewayClient client(String url) {
        ImageProperties properties = new ImageProperties();
        properties.getInfringement().setGatewayUrl(url);
        properties.getInfringement().setGatewayToken("token");
        properties.getInfringement().setRequestTimeoutSeconds(1);
        return new GeminiGatewayClient(properties);
    }
}
