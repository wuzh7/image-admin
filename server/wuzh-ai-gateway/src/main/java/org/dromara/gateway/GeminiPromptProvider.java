package org.dromara.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class GeminiPromptProvider {
    private final Path promptFile;

    public GeminiPromptProvider(@Value("${gemini.prompt-file:/app/config/gemini_prompt.txt}") String promptFile) {
        this.promptFile = Path.of(promptFile);
    }

    public String get() {
        try {
            String prompt = Files.readString(promptFile, StandardCharsets.UTF_8);
            if (prompt.isBlank()) {
                throw new GatewayException(503, "EMPTY_GEMINI_PROMPT", "Gemini Prompt 配置为空");
            }
            return prompt;
        } catch (IOException exception) {
            throw new GatewayException(503, "GEMINI_PROMPT_UNAVAILABLE", "Gemini Prompt 配置不可读取");
        }
    }
}
