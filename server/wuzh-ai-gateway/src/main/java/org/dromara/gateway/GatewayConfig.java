package org.dromara.gateway;

import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import okhttp3.OkHttpClient;
import java.time.Duration;

@Configuration
public class GatewayConfig {
    @Bean(destroyMethod = "close")
    public Client geminiClient(@Value("${gemini.api-key:missing-local-key}") String apiKey,
                               @Value("${gemini.timeout-seconds:900}") int timeoutSeconds) {
        HttpOptions http = HttpOptions.builder()
            .timeout(Math.max(1, timeoutSeconds) * 1000)
            .retryOptions(HttpRetryOptions.builder().attempts(1))
            .build();
        // Match the SDK's timeout defaults when supplying its supported custom HTTP client.
        OkHttpClient transport = new OkHttpClient.Builder()
            .connectTimeout(Duration.ZERO).readTimeout(Duration.ZERO).writeTimeout(Duration.ZERO)
            .callTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
            .addInterceptor(new GeminiHttpInterceptor()).build();
        return Client.builder().apiKey(apiKey).httpOptions(http)
            .clientOptions(ClientOptions.builder().customHttpClient(transport).build()).build();
    }
}
