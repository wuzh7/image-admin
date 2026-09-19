package org.dromara.gateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("local")
class GeminiHttpInterceptorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void actualSdkStandardCaseLookupAndBatchRequestsUseSnakeCaseWithoutChangingPrompt() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        String raw = """
            {"candidates":[{"content":{"parts":[{"text":"[]"}]},
            "groundingMetadata":{"webSearchQueries":["TRO"],"groundingChunks":[{"web":{"uri":"https://example.com"}}]}}],
            "usageMetadata":{"totalTokenCount":123},"providerExtra":"keep unknown fields"}
            """;
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!request.isBlank()) requests.add(request);
            byte[] response = (path.contains("generateContent") ? raw
                : "{\"name\":\"batches/test\",\"metadata\":{\"state\":\"JOB_STATE_PENDING\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var prompt = Files.createTempFile("gemini-wire-prompt", ".txt");
        Files.writeString(prompt, "Keep literal googleSearch and google_search in prompt");
        Logger logger = (Logger) LoggerFactory.getLogger(AiProviderResponseLogger.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.addHeader("X-Request-Id", "wire-test-request");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servlet));
        server.start();
        try (Client sdk = Client.builder().apiKey("test-key")
            .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .retryOptions(HttpRetryOptions.builder().attempts(1)).build())
            .clientOptions(ClientOptions.builder().customHttpClient(new OkHttpClient.Builder()
                .addInterceptor(new GeminiHttpInterceptor()).build()).build()).build()) {
            var client = new GoogleGeminiClient(sdk, "model", new GeminiPromptProvider(prompt.toString()));
            var analysis = client.analyze(new byte[]{1}, "image/png", "OFFICIAL_STANDARD");
            assertTrue(analysis.searchUsed());
            assertEquals(raw, analysis.rawProviderResponse());
            assertEquals(raw, client.lookupCases(new byte[]{1}, "image/png", "previous assessment").rawProviderResponse());
            assertEquals("batches/test", client.createBatch(List.of(
                new GoogleGeminiClient.BatchImage("https://example.com/image.png", "image/png")), "test"));
            assertEquals("PENDING", client.getBatch("batches/test").state());
            assertEquals(3, requests.size());
            for (String request : requests) {
                var payload = JSON.readTree(request);
                assertNotNull(payload.findValue("google_search"), request);
                assertNull(payload.findValue("googleSearch"), request);
                assertTrue(request.contains("Keep literal googleSearch and google_search in prompt"));
            }
            String event = logs.list.getFirst().getFormattedMessage();
            assertTrue(event.contains("requestId=wire-test-request"));
            var envelope = JSON.readTree(event.substring(event.indexOf("response=") + 9));
            assertTrue(envelope.path("hasGroundingMetadata").asBoolean());
            assertEquals(raw, envelope.path("rawResponseJson").asText());
            assertTrue(envelope.path("rawResponseJson").asText().contains("groundingChunks"));
            assertTrue(envelope.path("rawResponseJson").asText().contains("providerExtra"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(logs);
            logs.stop();
            server.stop(0);
            Files.deleteIfExists(prompt);
        }
    }

    @Test
    void malformedAndErrorBodiesRemainCompleteInLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(AiProviderResponseLogger.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            AiProviderResponseLogger.log("RELAY", 503, "not JSON\nupstream error");
            String event = logs.list.getFirst().getFormattedMessage();
            var envelope = JSON.readTree(event.substring(event.indexOf("response=") + 9));
            assertFalse(envelope.path("hasGroundingMetadata").asBoolean());
            assertEquals("not JSON\nupstream error", envelope.path("rawResponseJson").asText());
            assertTrue(event.contains("status=503"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void batchKeepsOriginalSuccessAndErrorItemsBeforeSdkConversion() throws Exception {
        String raw = """
            {"name":"batches/test","metadata":{"state":"JOB_STATE_SUCCEEDED","output":{
            "inlinedResponses":{"inlinedResponses":[
            {"response":{"candidates":[{"content":{"parts":[{"text":"[]"}]},
            "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com"}}]}}],
            "usageMetadata":{"totalTokenCount":21},"unknownField":"keep-success"}},
            {"error":{"code":3,"message":"bad image","unknownField":"keep-error"}}
            ]}}}}
            """;
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] response = raw.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (Client sdk = Client.builder().apiKey("test-key")
            .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build())
            .clientOptions(ClientOptions.builder().customHttpClient(new OkHttpClient.Builder()
                .addInterceptor(new GeminiHttpInterceptor()).build()).build()).build()) {
            var result = new GoogleGeminiClient(sdk, "model", null).getBatch("batches/test");
            assertEquals("SUCCEEDED", result.state());
            assertEquals(raw, result.rawProviderResponse());
            assertEquals(2, result.items().size());
            assertTrue(result.items().get(0).analysis().rawProviderResponse().contains("keep-success"));
            assertTrue(result.items().get(0).analysis().rawProviderResponse().contains("groundingChunks"));
            assertEquals("[]", result.items().get(0).analysis().rawAiResponse());
            assertEquals(21, result.items().get(0).analysis().usage().totalTokenCount());
            assertTrue(result.items().get(1).rawProviderResponse().contains("keep-error"));
            assertNull(result.items().get(1).analysis());
        } finally { server.stop(0); }
    }

    @Test
    void rawHttpErrorIsReturnedAfterCaptureCloses() throws Exception {
        String raw = "{\"error\":{\"code\":503,\"message\":\"capacity\",\"status\":\"UNAVAILABLE\"},\"trace\":\"keep\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = raw.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        var prompt = Files.createTempFile("gemini-error-prompt", ".txt");
        Files.writeString(prompt, "unchanged prompt");
        server.start();
        try (Client sdk = Client.builder().apiKey("test-key")
            .httpOptions(HttpOptions.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .retryOptions(HttpRetryOptions.builder().attempts(1)).build())
            .clientOptions(ClientOptions.builder().customHttpClient(new OkHttpClient.Builder()
                .addInterceptor(new GeminiHttpInterceptor()).build()).build()).build()) {
            var client = new GoogleGeminiClient(sdk, "model", new GeminiPromptProvider(prompt.toString()));
            var error = assertThrows(GatewayException.class,
                () -> client.analyze(new byte[]{1}, "image/png", "OFFICIAL_STANDARD"));
            assertEquals(raw, error.rawProviderResponse());
            assertEquals(503, error.status());
            try (var capture = AiProviderResponseLogger.capture()) { assertNull(capture.body()); }
        } finally {
            server.stop(0);
            Files.deleteIfExists(prompt);
        }
    }
}
