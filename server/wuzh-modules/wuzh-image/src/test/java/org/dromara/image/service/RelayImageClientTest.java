package org.dromara.image.service;

import com.sun.net.httpserver.HttpServer;
import org.dromara.image.config.ImageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class RelayImageClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsImageDirectlyToRelayAndReadsLatestPrompt(@TempDir Path directory) throws Exception {
        Path prompt = Files.writeString(directory.resolve("prompt.txt"), "Describe visible content only");
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String visual = """
                {"descriptionKeywords":"A red flower.","subjects":["花卉"],"scenes":[],"themes":[],"visualStyles":[],"colors":["红色"]}
                """;
            String content = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(visual);
            respond(exchange, 200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + content + "}}],\"usage\":{\"prompt_tokens\":10,\"total_tokens\":23}}");
        });
        server.start();
        RelayImageClient client = client(prompt);
        var result = client.analyze(new byte[]{1}, "image/png");
        assertEquals("A red flower.", result.imageDescription());
        assertEquals(23, result.usage().totalTokenCount());
        assertTrue(result.rawProviderResponse().contains("choices"));
        Files.writeString(prompt, "Updated visible-content instruction");
        client.analyze(new byte[]{2}, "image/png");
        assertEquals(2, requests.size());
        assertTrue(requests.getFirst().contains("Describe visible content only"));
        assertTrue(requests.getLast().contains("Updated visible-content instruction"));
        assertTrue(requests.getFirst().contains("data:image/png;base64,AQ=="));
        assertFalse(requests.getFirst().contains("googleSearch"));
    }

    @Test
    void preservesInvalidAiOutputAndNeverTreatsItAsDescription(@TempDir Path directory) throws Exception {
        Path prompt = Files.writeString(directory.resolve("prompt.txt"), "Describe");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200,
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"not-json\"}}]}"));
        server.start();
        var error = assertThrows(RelayImageClient.RelayException.class,
            () -> client(prompt).analyze(new byte[]{1}, "image/png"));
        assertEquals("UNPARSABLE_AI_RESPONSE", error.code());
        assertEquals("not-json", error.rawAiResponse());
        assertTrue(error.rawProviderResponse().contains("choices"));
    }

    @Test
    void distinguishesRetryableRelayFailures(@TempDir Path directory) throws Exception {
        Path prompt = Files.writeString(directory.resolve("prompt.txt"), "Describe");
        AtomicInteger status = new AtomicInteger(429);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, status.get(), "{\"error\":\"failed\"}"));
        server.start();
        RelayImageClient client = client(prompt);
        var rateLimit = assertThrows(RelayImageClient.RelayException.class,
            () -> client.analyze(new byte[]{1}, "image/png"));
        assertEquals("OPENLUX_RATE_LIMITED", rateLimit.code());
        assertTrue(rateLimit.retryable());
        status.set(400);
        var badRequest = assertThrows(RelayImageClient.RelayException.class,
            () -> client.analyze(new byte[]{1}, "image/png"));
        assertFalse(badRequest.retryable());
    }

    @Test
    void enforcesVisualOnlyResponseSchema() {
        assertEquals("A red flower.", RelayImageClient.parseDescription("""
            {"descriptionKeywords":"A red flower.","subjects":["花卉"],"scenes":[],"themes":[],"visualStyles":[],"colors":["红色"]}
            """));
        assertEquals("UNPARSABLE_AI_RESPONSE", code("""
            {"descriptionKeywords":"Flower","subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"riskLevel":"LOW"}
            """));
        assertEquals("INVALID_VISUAL_TAG", code("""
            {"descriptionKeywords":"Flower","subjects":["BrandName"],"scenes":[],"themes":[],"visualStyles":[],"colors":[]}
            """));
        assertEquals("INVALID_IMAGE_DESCRIPTION", code("""
            {"descriptionKeywords":"水彩花卉","subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[]}
            """));
    }

    private static String code(String raw) {
        return assertThrows(RelayImageClient.RelayException.class,
            () -> RelayImageClient.parseDescription(raw)).code();
    }

    private RelayImageClient client(Path prompt) {
        ImageProperties properties = new ImageProperties();
        properties.getInfringement().setRelayBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getInfringement().setRelayApiKey("test-key");
        properties.getInfringement().setPromptFile(prompt.toString());
        properties.getInfringement().setRequestTimeoutSeconds(1);
        return new RelayImageClient(properties);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
