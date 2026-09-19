package org.dromara.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("local")
class RelayStageProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void bindsOrderedModelsAndSharesBothPromptsIncludingAnAdditionalStage() throws Exception {
        Path first = write("gemini_prompt.txt", "shared first prompt");
        Path second = write("prompt2.txt", "shared second prompt");
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
            "relay.prompt1-file", first.toString(), "relay.prompt2-file", second.toString(),
            "relay.stages[0].model", "gemini", "relay.stages[1].model", "doubao",
            "relay.stages[1].protocol", "CHAT_COMPLETIONS", "relay.stages[2].model", "third"));
        var properties = new org.springframework.boot.context.properties.bind.Binder(source)
            .bind("relay", RelayStageProperties.class).get();
        var stages = new RelayStageProvider(properties).get();
        assertEquals(List.of("gemini", "doubao", "third"), stages.stream().map(RelayPipeline.Stage::model).toList());
        assertEquals("CHAT_COMPLETIONS", stages.get(1).protocol());
        stages.forEach(stage -> {
            assertEquals("shared first prompt", stage.prompt1());
            assertEquals("shared second prompt", stage.prompt2());
            assertEquals("gemini_prompt.txt", stage.prompt1Name());
            assertEquals("prompt2.txt", stage.prompt2Name());
        });
    }

    @Test
    void defaultConfigurationUsesExistingGeminiPromptForBothModels() throws Exception {
        Path first = write("gemini_prompt.txt", "existing Gemini prompt");
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PromptConfiguration.class, RelayStageProvider.class)
            .withPropertyValues("GEMINI_PROMPT_FILE=" + first, "RELAY_PROMPT2_FILE=")
            .run(context -> {
                var stages = context.getBean(RelayStageProvider.class).get();
                assertEquals(List.of("gemini-3.8-flash", "doubao-seed-2-0-lite-260215"),
                    stages.stream().map(RelayPipeline.Stage::model).toList());
                stages.forEach(stage -> {
                    assertEquals("existing Gemini prompt", stage.prompt1());
                    assertNull(stage.prompt2());
                });
            });
    }

    @Test
    void configuredButUnreadablePromptIsNotTreatedAsDisabled() throws Exception {
        RelayStageProperties properties = configured(write("first.txt", "prompt"), tempDir.resolve("missing.txt"));
        GatewayException error = assertThrows(GatewayException.class, () -> new RelayStageProvider(properties).get());
        assertEquals("RELAY_PROMPT_UNAVAILABLE", error.code());
    }

    @Test
    void sharedPromptsReloadForAllModelsAndBlankPrompt2DisablesAllFollowups() throws Exception {
        Path first = write("gemini_prompt.txt", "first prompt");
        Path second = write("prompt2.txt", "   ");
        RelayStageProvider provider = new RelayStageProvider(configured(first, second));
        provider.get().forEach(stage -> assertNull(stage.prompt2()));
        Files.writeString(first, "updated first prompt");
        Files.writeString(second, "updated second prompt");
        provider.get().forEach(stage -> {
            assertEquals("updated first prompt", stage.prompt1());
            assertEquals("updated second prompt", stage.prompt2());
        });
    }

    @Test
    void replacingBothFilesReloadsNextRequestWithoutChangingTheExistingSnapshot() throws Exception {
        Path first = write("gemini_prompt.txt", "original first");
        Path second = write("prompt2.txt", "original second");
        RelayStageProvider provider = new RelayStageProvider(configured(first, second));
        var snapshot = provider.get();
        Files.move(write("replacement-first.txt", "replacement first"), first,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.move(write("replacement-second.txt", "replacement second"), second,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        provider.get().forEach(stage -> {
            assertEquals("replacement first", stage.prompt1());
            assertEquals("replacement second", stage.prompt2());
        });
        snapshot.forEach(stage -> {
            assertEquals("original first", stage.prompt1());
            assertEquals("original second", stage.prompt2());
        });
    }

    private Path write(String name, String value) throws Exception {
        Path path = tempDir.resolve(name);
        Files.writeString(path, value);
        return path;
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(RelayStageProperties.class)
    static class PromptConfiguration {
    }

    private static RelayStageProperties configured(Path first, Path second) {
        RelayStageProperties properties = new RelayStageProperties();
        properties.setPrompt1File(first.toString());
        properties.setPrompt2File(second.toString());
        RelayStageProperties.Stage gemini = new RelayStageProperties.Stage();
        gemini.setModel("gemini-3.8-flash");
        RelayStageProperties.Stage doubao = new RelayStageProperties.Stage();
        doubao.setModel("doubao-seed-2-0-lite-260215");
        doubao.setProtocol("CHAT_COMPLETIONS");
        properties.setStages(List.of(gemini, doubao));
        return properties;
    }
}
