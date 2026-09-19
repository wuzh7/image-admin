package org.dromara.gateway;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class RelayStageProvider {
    private final RelayStageProperties properties;

    public RelayStageProvider(RelayStageProperties properties) {
        this.properties = properties;
    }

    public List<RelayPipeline.Stage> get() {
        if (properties.getStages().isEmpty()) {
            throw new GatewayException(503, "RELAY_STAGES_NOT_CONFIGURED", "中转站模型链未配置");
        }
        String prompt1 = readRequired(properties.getPrompt1File());
        String prompt2 = readOptional(properties.getPrompt2File());
        List<RelayPipeline.Stage> stages = new ArrayList<>();
        for (RelayStageProperties.Stage configured : properties.getStages()) {
            if (configured.getModel() == null || configured.getModel().isBlank()) {
                throw new GatewayException(503, "RELAY_STAGE_INVALID", "中转站模型配置为空");
            }
            String protocol = configured.getProtocol();
            if (!"GEMINI".equals(protocol) && !"CHAT_COMPLETIONS".equals(protocol)) {
                throw new GatewayException(503, "RELAY_PROTOCOL_INVALID", "中转站协议必须为 GEMINI 或 CHAT_COMPLETIONS");
            }
            stages.add(new RelayPipeline.Stage(configured.getModel().trim(),
                prompt1, prompt2, protocol, Path.of(properties.getPrompt1File()).getFileName().toString(),
                prompt2 == null ? null : Path.of(properties.getPrompt2File()).getFileName().toString()));
        }
        return List.copyOf(stages);
    }

    public String model() {
        return properties.getStages().stream()
            .map(RelayStageProperties.Stage::getModel).findFirst()
            .orElseThrow(() -> new GatewayException(503, "RELAY_STAGES_NOT_CONFIGURED", "中转站模型链未配置"));
    }

    private static String readRequired(String file) {
        if (file == null || file.isBlank()) throw new GatewayException(503, "EMPTY_RELAY_PROMPT1", "中转站 Prompt1 配置为空");
        String prompt = read(file);
        if (prompt.isBlank()) throw new GatewayException(503, "EMPTY_RELAY_PROMPT1", "中转站 Prompt1 配置为空");
        return prompt;
    }

    private static String readOptional(String file) {
        if (file == null || file.isBlank()) return null;
        String prompt = read(file);
        return prompt.isBlank() ? null : prompt;
    }

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new GatewayException(503, "RELAY_PROMPT_UNAVAILABLE", "中转站 Prompt 配置不可读取");
        }
    }
}
