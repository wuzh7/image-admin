package org.dromara.gateway;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
@Tag("local")
class ProviderSelectionTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(GatewayConfig.class, GeminiPromptProvider.class, GoogleGeminiClient.class,
            OpenLuxGeminiClient.class, RelayStageProperties.class, RelayStageProvider.class, ImageRiskParser.class);

    @Test
    void configuresBothProvidersWithoutGlobalProviderSwitch() {
        contextRunner.withPropertyValues(
                "openlux.api-key=test-openlux-key",
                "gemini.api-key=test-google-key")
            .run(context -> {
                assertThat(context).getBeans(GeminiClient.class).hasSize(2);
                assertThat(context).hasSingleBean(com.google.genai.Client.class);
            });
    }
}
