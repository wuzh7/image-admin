package org.dromara.image.storage;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.model.ObjectInfo;
import org.dromara.image.config.ImageProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("local")
class OssImageStorageTest {
    @Test void signsUploadAndChecksActualObjectMetadata() {
        OssClient client = mock(OssClient.class);
        OssImageStorage storage = new OssImageStorage(new ImageProperties(), client, client);
        when(client.presignPutUrl("review/42/a.png", Duration.ofMinutes(15), "image/png", 123L))
            .thenReturn("https://example.test/signed");
        assertEquals("https://example.test/signed", storage.signUpload("review/42/a.png", "image/png", 123L));
        when(client.head("review/42/a.png")).thenReturn(new ObjectInfo(123L, "image/png", "etag"));
        storage.verifyUpload("review/42/a.png", "image/png", 123L);
        assertThrows(ServiceException.class, () -> storage.verifyUpload("review/42/a.png", "image/png", 999L));
    }

    @Test void thumbnailUsesSignedImageProcessing() {
        OssClient client = mock(OssClient.class);
        OssImageStorage storage = new OssImageStorage(new ImageProperties(), client, client);
        Map<String, String> query = Map.of("x-oss-process", "image/auto-orient,1/resize,m_lfit,w_480,h_480/quality,q_82");
        when(client.presignGetUrl("review/42/a.png", Duration.ofMinutes(10), query)).thenReturn("https://example.test/thumb");
        assertEquals("https://example.test/thumb", storage.accessUrl("review/42/a.png", true).orElseThrow());
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
        assertEquals("library/2026/09/ab/101.png", ImageStorage.libraryKey(101, "ab".repeat(32), "review/42/a.png", created));
    }
}
