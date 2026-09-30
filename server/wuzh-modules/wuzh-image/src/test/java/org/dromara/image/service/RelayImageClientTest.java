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
    void sendsImageToArkAndReadsLatestPrompt(@TempDir Path directory) throws Exception {
        Path prompt = Files.writeString(directory.resolve("prompt.txt"), "Describe visible content only");
        List<String> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/responses", exchange -> {
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String visual = """
                {"descriptionKeywords":"A red flower.","subjects":["花卉"],"scenes":[],"themes":[],"visualStyles":[],"colors":["红色"]}
                """;
            String content = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(visual);
            respond(exchange, 200, "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"text\":\"internal\"}]},"
                + "{\"type\":\"message\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + content + "}]}],\"usage\":{\"input_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":2},"
                + "\"output_tokens\":13,\"output_tokens_details\":{\"reasoning_tokens\":3},\"total_tokens\":23}}");
        });
        server.start();
        RelayImageClient client = client(prompt);
        var result = client.analyze(new byte[]{1}, "image/png");
        assertEquals("A red flower.", result.imageDescription());
        assertEquals(23, result.usage().totalTokenCount());
        assertEquals(10, result.usage().promptTokenCount());
        assertEquals(2, result.usage().cachedContentTokenCount());
        assertEquals(13, result.usage().candidatesTokenCount());
        assertEquals(3, result.usage().thoughtsTokenCount());
        assertTrue(result.rawProviderResponse().contains("output_text"));
        Files.writeString(prompt, "Updated visible-content instruction");
        client.analyze(new byte[]{2}, "image/png");
        assertEquals(2, requests.size());
        assertTrue(requests.getFirst().contains("Describe visible content only"));
        assertTrue(requests.getLast().contains("Updated visible-content instruction"));
        assertTrue(requests.getFirst().contains("data:image/png;base64,AQ=="));
        assertTrue(requests.getFirst().contains("doubao-seed-2-0-lite-260428"));
        assertTrue(requests.getFirst().contains("input_image"));
        assertTrue(requests.getFirst().contains("input_text"));
        assertFalse(requests.getFirst().contains("googleSearch"));
    }

    @Test
    void preservesInvalidAiOutputAndNeverTreatsItAsDescription(@TempDir Path directory) throws Exception {
        Path prompt = Files.writeString(directory.resolve("prompt.txt"), "Describe");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/responses", exchange -> respond(exchange, 200,
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"not-json\"}]}]}"));
        server.start();
        var error = assertThrows(RelayImageClient.RelayException.class,
            () -> client(prompt).analyze(new byte[]{1}, "image/png"));
        assertEquals("UNPARSABLE_AI_RESPONSE", error.code());
        assertEquals("not-json", error.rawAiResponse());
        assertTrue(error.rawProviderResponse().contains("output_text"));
    }

    @Test
    void distinguishesRetryableArkFailures(@TempDir Path directory) throws Exception {
        Path prompt = Files.writeString(directory.resolve("prompt.txt"), "Describe");
        AtomicInteger status = new AtomicInteger(429);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/responses", exchange -> respond(exchange, status.get(), "{\"error\":\"failed\"}"));
        server.start();
        RelayImageClient client = client(prompt);
        var rateLimit = assertThrows(RelayImageClient.RelayException.class,
            () -> client.analyze(new byte[]{1}, "image/png"));
        assertEquals("ARK_RATE_LIMITED", rateLimit.code());
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
        String longDescription = "A painting of " + "colorful flowers ".repeat(7) + "in a garden.";
        assertEquals(longDescription, RelayImageClient.parseDescription("""
            {"descriptionKeywords":"%s","subjects":[],"scenes":[],"themes":[],"visualStyles":[],"colors":[]}
            """.formatted(longDescription)));
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
        properties.getInfringement().setArkBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getInfringement().setArkApiKey("test-key");
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
