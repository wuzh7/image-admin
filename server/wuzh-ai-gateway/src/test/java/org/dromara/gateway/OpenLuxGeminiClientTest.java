package org.dromara.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("local")
class OpenLuxGeminiClientTest {
    @Test
    void executesBothModelsFollowupsUsingTheirOwnProtocolsAndRetainsStageOneFields(@TempDir Path directory) throws Exception {
        Path first = Files.writeString(directory.resolve("first.txt"), "first prompt");
        Path lookup = Files.writeString(directory.resolve("lookup.txt"), "lookup prompt");
        RelayStageProperties.Stage gemini = stage("gemini-3.8-flash");
        RelayStageProperties.Stage doubao = stage("doubao-seed-2-0-lite-260215");
        doubao.setProtocol("CHAT_COMPLETIONS");
        RelayStageProperties properties = new RelayStageProperties();
        properties.setPrompt1File(first.toString());
        properties.setPrompt2File(lookup.toString());
        properties.setStages(List.of(gemini, doubao));
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        List<String> outputs = List.of("""
            {"riskLevel":"LOW","riskReason":null,"descriptionKeywords":"flower art",
             "subjects":["flower"],"scenes":[],"themes":[],"visualStyles":[],"colors":[],"CaseNumber":[]}
            """, "{\"riskReason\":\"first lookup reason\",\"CaseNumber\":[]}",
            "{\"riskLevel\":\"LOW\",\"riskReason\":\"review reason\",\"CaseNumber\":[],\"descriptionKeywords\":\"must not replace\"}",
            "[\"1:26-cv-12345\"]");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String output = outputs.get(paths.size() - 1);
            String encoded = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(output);
            String payload = paths.size() <= 2 ? providerResponse(output)
                : "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":" + encoded + "}}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new OpenLuxGeminiClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 5,
                new RelayStageProvider(properties), new ImageRiskParser());
            var analysis = client.analyze(new byte[]{1}, "image/png");
            var result = new ImageRiskParser().parseRelayFinal(analysis.rawAiResponse());
            var audit = tools.jackson.databind.json.JsonMapper.builder().build().readTree(analysis.rawProviderResponse());
            assertEquals("first.txt", audit.get(0).path("promptName").asText());
            assertEquals("lookup.txt", audit.get(1).path("promptName").asText());
            assertEquals("first.txt", audit.get(2).path("promptName").asText());
            assertEquals("lookup.txt", audit.get(3).path("promptName").asText());
            assertEquals(List.of("/v1beta/models/gemini-3.8-flash:generateContent",
                "/v1beta/models/gemini-3.8-flash:generateContent", "/v1/chat/completions", "/v1/chat/completions"), paths);
            assertTrue(bodies.get(2).contains("prompt1Result"));
            assertTrue(bodies.get(2).contains("first lookup reason"));
            assertTrue(bodies.get(2).contains("NEEDS_REVIEW"));
            assertTrue(bodies.get(0).contains("first prompt"));
            assertTrue(bodies.get(2).contains("first prompt"));
            assertTrue(bodies.get(1).contains("lookup prompt"));
            assertTrue(bodies.get(3).contains("lookup prompt"));
            assertTrue(bodies.get(3).contains("\"assistant\""));
            assertEquals("HIGH", result.riskLevel());
            assertEquals("review reason", result.riskReason());
            assertEquals("flower art", result.imageDescription());
            assertEquals(List.of("flower"), result.visualTags().subjects());
            assertEquals(List.of("1:26-cv-12345"), result.caseNumbers());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void analyzeRunsConfiguredRelayModelsInOrderWithPreviousMergedResult() throws Exception {
        List<String> paths = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        Path stage1Prompt = Files.createTempFile("stage1-prompt", ".txt");
        Files.writeString(stage1Prompt, "stage one prompt");
        RelayStageProperties properties = new RelayStageProperties();
        properties.setPrompt1File(stage1Prompt.toString());
        properties.setStages(List.of(
            stage("gemini-3.8-flash"),
            stage("doubao-seed-2-0-lite-260215")));
        properties.getStages().get(1).setProtocol("CHAT_COMPLETIONS");
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String content = paths.size() == 1
                ? "{\"riskLevel\":\"LOW\",\"riskReason\":null,\"descriptionKeywords\":\"flower art\",\"subjects\":[],\"scenes\":[],\"themes\":[],\"visualStyles\":[],\"colors\":[],\"CaseNumber\":[]}"
                : "{\"riskLevel\":\"HIGH\",\"riskReason\":\"找到案号\",\"CaseNumber\":[\"1:26-cv-12345\"]}";
            String encoded = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(content);
            byte[] response = (paths.size() == 1 ? providerResponse(content)
                : "{\"choices\":[{\"message\":{\"content\":" + encoded + "}}],\"usage\":{\"total_tokens\":23}}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenLuxGeminiClient client = new OpenLuxGeminiClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 5,
                new RelayStageProvider(properties), new ImageRiskParser());

            GeminiClient.Analysis result = client.analyze(new byte[]{1}, "image/png");

            assertEquals(List.of(
                "/v1beta/models/gemini-3.8-flash:generateContent",
                "/v1/chat/completions"), paths);
            assertEquals("Bearer test-key", authorization.get());
            assertTrue(bodies.get(1).contains("data:image/png;base64,AQ=="));
            assertTrue(bodies.get(1).contains("doubao-seed-2-0-lite-260215"));
            assertTrue(!bodies.get(1).contains("googleSearch"));
            assertTrue(bodies.get(1).contains("flower art"));
            assertTrue(bodies.get(1).contains("stage one prompt"));
            assertTrue(result.rawAiResponse().contains("1:26-cv-12345"));
            // Finishing request metadata must not reread hot prompt files.
            Files.delete(stage1Prompt);
            assertEquals("gemini-3.8-flash", client.model());
            assertEquals(23, result.usage().totalTokenCount());
        } finally {
            server.stop(0);
            Files.deleteIfExists(stage1Prompt);
        }
    }

    @Test
    void caseLookupReusesImageAndAssessmentThenSendsExactSecondInstruction() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        Path promptFile = Files.createTempFile("gemini-prompt", ".txt");
        Files.writeString(promptFile, "latest prompt");
        Path lookupFile = Files.createTempFile("relay-prompt2", ".txt");
        Files.writeString(lookupFile, "configured second instruction");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/model:generateContent", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"candidates":[{"content":{"parts":[{"text":"[\\"1:26-cv-12345\\"]"}]},
                "groundingMetadata":{"webSearchQueries":["artist TRO case"]}}],"usageMetadata":{"totalTokenCount":123}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            RelayStageProperties properties = new RelayStageProperties();
            properties.setPrompt1File(promptFile.toString());
            properties.setPrompt2File(lookupFile.toString());
            properties.setStages(List.of(stage("model")));
            var client = new OpenLuxGeminiClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", 5, new RelayStageProvider(properties), new ImageRiskParser());
            var result = client.lookupCases(new byte[]{1, 2}, "image/png", "previous assessment");
            var request = tools.jackson.databind.json.JsonMapper.builder().build().readTree(requestBody.get());
            assertEquals(3, request.path("contents").size());
            assertEquals("model", request.path("contents").get(1).path("role").asText());
            assertEquals("previous assessment", request.path("contents").get(1).path("parts").get(0).path("text").asText());
            assertEquals("configured second instruction", request.path("contents").get(2).path("parts").get(0).path("text").asText());
            assertEquals("ARRAY", request.path("generationConfig").path("responseSchema").path("type").asText());
            assertTrue(request.path("tools").get(0).has("googleSearch"));
            assertEquals(java.util.List.of("1:26-cv-12345"), new ImageRiskParser().parseCases(result.rawAiResponse()));
            assertEquals(123, result.usage().totalTokenCount());
            assertTrue(result.rawProviderResponse().contains("groundingMetadata"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(promptFile);
            Files.deleteIfExists(lookupFile);
        }
    }

    @Test
    void legacyRelayLookupAlsoSkipsEmptyPrompt2WithoutCallingProvider(@TempDir Path directory) throws Exception {
        Path first = Files.writeString(directory.resolve("gemini_prompt.txt"), "shared first prompt");
        var client = new OpenLuxGeminiClient("http://127.0.0.1:1", "test-key", 1,
            configured("model", first), new ImageRiskParser());
        assertEquals("[]", client.lookupCases(new byte[]{1}, "image/png", "previous assessment").rawAiResponse());
    }

    @Test
    void sendsOneNativeGeminiRequestWithApiKeyAndSearch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        byte[] image = new byte[]{1, 2};
        Path promptFile = Files.createTempFile("gemini-prompt", ".txt");
        Files.writeString(promptFile, "test prompt");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-3.8-flash:generateContent", exchange -> {
            calls.incrementAndGet();
            query.set(exchange.getRequestURI().getRawQuery());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"candidates":[{"content":{"parts":[{"thought":true,"text":"internal reasoning"},
                {"text":"{\\"riskLevel\\":\\"LOW\\",\\"riskReason\\":null,\\"descriptionKeywords\\":\\"lion art\\",\\"subjects\\":[],\\"scenes\\":[],\\"themes\\":[],\\"visualStyles\\":[],\\"colors\\":[],\\"CaseNumber\\":[]}"}]},
                "groundingMetadata":{"webSearchQueries":["lion artwork trademark","lion illustration"]}}],
                "usageMetadata":{"promptTokenCount":321,"candidatesTokenCount":45,
                "thoughtsTokenCount":67,"toolUsePromptTokenCount":23,"totalTokenCount":456}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenLuxGeminiClient client = new OpenLuxGeminiClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 5,
                configured("gemini-3.8-flash", promptFile), new ImageRiskParser());

            GeminiClient.Analysis result = client.analyze(image, "image/png");

            assertEquals(1, calls.get());
            assertEquals("key=test-key", query.get());
            assertTrue(requestBody.get().contains("\"googleSearch\":{}"));
            assertTrue(requestBody.get().contains("\"inlineData\""));
            assertTrue(requestBody.get().contains(Base64.getEncoder().encodeToString(image)));
            assertTrue(requestBody.get().contains("test prompt"));
            assertTrue(requestBody.get().indexOf("\"inlineData\"") < requestBody.get().indexOf("\"text\""));
            assertTrue(requestBody.get().contains("\"responseMimeType\":\"application/json\""));
            assertTrue(result.rawAiResponse().contains("\"riskLevel\":\"LOW\""));
            assertTrue(result.rawAiResponse().contains("\"descriptionKeywords\":\"lion art\""));
            assertTrue(result.searchUsed());
            assertEquals(2, result.searchQueryCount());
            assertEquals(321, result.usage().promptTokenCount());
            assertEquals(45, result.usage().candidatesTokenCount());
            assertEquals(67, result.usage().thoughtsTokenCount());
            assertEquals(23, result.usage().toolUsePromptTokenCount());
            assertEquals(456, result.usage().totalTokenCount());
            assertTrue(result.usage().rawJson().contains("\"promptTokenCount\":321"));
            assertEquals("gemini-3.8-flash", client.model());
            assertTrue(result.rawProviderResponse().contains("candidates"));
            assertTrue(result.rawProviderResponse().contains("usageMetadata"));
        } finally {
            server.stop(0);
            Files.deleteIfExists(promptFile);
        }
    }

    private static RelayStageProperties.Stage stage(String model) {
        RelayStageProperties.Stage stage = new RelayStageProperties.Stage();
        stage.setModel(model);
        return stage;
    }

    private static RelayStageProvider configured(String model, Path prompt1) {
        RelayStageProperties properties = new RelayStageProperties();
        properties.setPrompt1File(prompt1.toString());
        properties.setStages(List.of(stage(model)));
        return new RelayStageProvider(properties);
    }

    private static String providerResponse(String content) {
        String encoded = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(content);
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + encoded + "}]}}]}";
    }
}
