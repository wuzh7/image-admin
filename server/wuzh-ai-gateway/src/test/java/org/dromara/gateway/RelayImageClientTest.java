package org.dromara.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class RelayImageClientTest {
    @Test
    void oneRequestPerImageAndPromptChangesApplyToNextRequest(@TempDir Path directory) throws Exception {
        Path prompt = Files.writeString(directory.resolve("image_analysis_prompt.txt"), "Describe visible content only");
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"descriptionKeywords\\":\\"A red flower.\\",\\"subjects\\":[\\"花卉\\"],\\"scenes\\":[],\\"themes\\":[],\\"visualStyles\\":[],\\"colors\\":[\\"红色\\"]}"}}],"usage":{"total_tokens":23}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new RelayImageClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", 5, "doubao-seed-2-0-lite-260428", prompt.toString());
            assertEquals("A red flower.", new ImageRiskParser().parseVisual(client.analyze(new byte[]{1}, "image/png").rawAiResponse()).imageDescription());
            Files.writeString(prompt, "Updated visible-content instruction");
            assertEquals(23, client.analyze(new byte[]{2}, "image/png").usage().totalTokenCount());
            assertEquals(2, requests.size());
            assertTrue(requests.getFirst().contains("Describe visible content only"));
            assertTrue(requests.getLast().contains("Updated visible-content instruction"));
            assertTrue(requests.getFirst().contains("doubao-seed-2-0-lite-260428"));
            assertTrue(requests.getFirst().contains("data:image/png;base64,AQ=="));
            assertFalse(requests.getFirst().contains("googleSearch"));
        } finally {
            server.stop(0);
        }
    }
}
