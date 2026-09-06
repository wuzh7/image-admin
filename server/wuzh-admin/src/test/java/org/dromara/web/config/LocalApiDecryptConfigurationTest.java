package org.dromara.web.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local")
class LocalApiDecryptConfigurationTest {

    @Test
    void enablesApiDecryptionForEncryptedLoginRequests() throws IOException {
        var propertySources = new YamlPropertySourceLoader()
            .load("application-local.yml", new ClassPathResource("application-local.yml"));

        assertThat(propertySources)
            .anySatisfy(source -> assertThat(source.getProperty("api-decrypt.enabled")).isEqualTo(true));
    }
}
