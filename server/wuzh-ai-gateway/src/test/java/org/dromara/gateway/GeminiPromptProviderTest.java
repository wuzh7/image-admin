package org.dromara.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("local")
class GeminiPromptProviderTest {
    @Test
    void readsTheLatestPromptFileContentForEachRequest() throws Exception {
        Path promptFile = Files.createTempFile("gemini-prompt", ".txt");
        try {
            GeminiPromptProvider provider = new GeminiPromptProvider(promptFile.toString());
            Files.writeString(promptFile, "first prompt");
            assertEquals("first prompt", provider.get());

            Files.writeString(promptFile, "updated prompt");
            assertEquals("updated prompt", provider.get());
        } finally {
            Files.deleteIfExists(promptFile);
        }
    }
}
