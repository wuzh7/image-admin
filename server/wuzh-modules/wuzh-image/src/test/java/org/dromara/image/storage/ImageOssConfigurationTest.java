package org.dromara.image.storage;

import org.dromara.image.config.ImageOssConfiguration;
import org.dromara.image.config.ImageProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("local")
class ImageOssConfigurationTest {
    @Test
    void configuredClientIsReadyAndSignsRequiredHeadersAndThumbnailQuery() throws Exception {
        var properties = new ImageProperties();
        var config = properties.getStorage().getOss();
        config.setBucket("image-admin-test");
        config.setRegion("cn-hangzhou");
        config.setPublicEndpoint("https://s3.oss-cn-hangzhou.aliyuncs.com");
        config.setAccessKey("unit-test-not-real");
        config.setSecretKey("unit-test-not-real-secret");
        try (var client = new ImageOssConfiguration().imageOssExternal(properties)) {
            assertTrue(client.isInitialized(), "Spring bean must initialize the existing OSS client");
            URI upload = URI.create(client.presignPutUrl("review/1/a.png", Duration.ofMinutes(15), "image/png", 123));
            assertEquals("image-admin-test.s3.oss-cn-hangzhou.aliyuncs.com", upload.getHost());
            String query = URLDecoder.decode(upload.getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(query.contains("content-length;content-type;host;x-oss-forbid-overwrite"));
            URI preview = URI.create(client.presignGetUrl("review/1/a.png", Duration.ofMinutes(10),
                Map.of("x-oss-process", "image/resize,w_480")));
            assertTrue(URLDecoder.decode(preview.getRawQuery(), StandardCharsets.UTF_8).contains("x-oss-process=image/resize,w_480"));
            URI thumbnail = URI.create(new OssImageStorage(properties, client, client)
                .accessUrl("review/1/a.png", "image/png", true).orElseThrow());
            String thumbnailQuery = URLDecoder.decode(thumbnail.getRawQuery(), StandardCharsets.UTF_8);
            assertTrue(thumbnailQuery.toLowerCase().contains("expires=86400"));
            assertTrue(thumbnailQuery.contains("x-oss-process=image/auto-orient,1/resize,m_lfit,w_480,h_480/quality,q_82"));
            assertFalse(thumbnailQuery.contains("response-content-type"));
        }
    }
}
