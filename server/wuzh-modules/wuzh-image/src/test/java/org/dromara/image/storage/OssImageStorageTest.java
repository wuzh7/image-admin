package org.dromara.image.storage;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.model.ObjectInfo;
import org.dromara.image.config.ImageProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("local")
class OssImageStorageTest {
    @Test void signsOneRestrictedPostPolicyForTheWholeBatch() {
        ImageProperties properties = new ImageProperties();
        var config = properties.getStorage().getOss();
        config.setBucket("image-admin-test");
        config.setRegion("cn-hangzhou");
        config.setPublicEndpoint("https://s3.oss-cn-hangzhou.aliyuncs.com");
        config.setAccessKey("test-access-key");
        config.setSecretKey("test-secret-key");
        OssClient client = mock(OssClient.class);

        ImageStorage.UploadPolicy result = new OssImageStorage(properties, client, client)
            .signUploadPolicy("review/42/", Duration.ofMinutes(30));

        assertEquals("https://image-admin-test.oss-cn-hangzhou.aliyuncs.com/", result.host());
        assertEquals("review/42/", result.keyPrefix());
        assertEquals("OSS4-HMAC-SHA256", result.formFields().get("x-oss-signature-version"));
        assertFalse(result.formFields().containsKey("accessKeySecret"));
        String policy = new String(Base64.getDecoder().decode(result.formFields().get("policy")), StandardCharsets.UTF_8);
        assertTrue(policy.contains("[\"starts-with\",\"$key\",\"review/42/\"]"));
        assertFalse(policy.contains("content-length-range"));
        assertTrue(policy.contains("[\"in\",\"$content-type\",[\"image/jpeg\",\"image/png\",\"image/webp\"]]"));
    }

    @Test void thumbnailUsesSignedImageProcessing() {
        OssClient client = mock(OssClient.class);
        OssImageStorage storage = new OssImageStorage(new ImageProperties(), client, client);
        Map<String, String> query = Map.of(
            "x-oss-process", "image/auto-orient,1/resize,m_lfit,w_480,h_480/quality,q_82");
        when(client.presignGetUrl("review/42/a.png", Duration.ofMinutes(10), query)).thenReturn("https://example.test/thumb");
        assertEquals("https://example.test/thumb", storage.accessUrl("review/42/a.png", "image/png", true).orElseThrow());
    }

    @Test void batchInputUsesRequestedSignedUrlLifetimeWithoutImageProcessing() {
        OssClient client = mock(OssClient.class);
        OssImageStorage storage = new OssImageStorage(new ImageProperties(), client, client);
        Duration expiry = Duration.ofHours(72);
        when(client.presignGetUrl("library/42/a.png", expiry, Map.of()))
            .thenReturn("https://example.test/signed-source");

        assertEquals("https://example.test/signed-source",
            storage.signGetUrl("library/42/a.png", expiry).orElseThrow());
    }

    @Test void copyMustBeVerifiedAndNeverDeletesSource() {
        OssClient client = mock(OssClient.class);
        OssImageStorage storage = new OssImageStorage(new ImageProperties(), client, client);
        when(client.head("review/a.png")).thenReturn(new ObjectInfo(123L, "image/png", "a"));
        when(client.head("library/a.png")).thenReturn(new ObjectInfo(123L, "image/png", "b"));
        assertThrows(ServiceException.class, () -> storage.copy("review/a.png", "library/a.png"));
        verify(client, never()).delete(anyString());
    }

    @Test void libraryDestinationIsStableAcrossRestarts() {
        var created = java.time.LocalDateTime.of(2026, 9, 5, 12, 0);
        assertEquals("library/2026/09/05/42/101_abababababab.png", ImageStorage.libraryKey(101, 42, "ab".repeat(32), "review/42/a.png", created));
        assertEquals("review/2026/09/05/42/", ImageStorage.reviewPrefix(42, created));
    }
}
