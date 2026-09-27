package org.dromara.image.service;

import org.dromara.image.controller.ImageController;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("local")
class ImageAccessContractTest {
    @Test void previewAddressIsResponseDataNotMessage() {
        var service = mock(ImageService.class);
        when(service.accessUrl(7L, true)).thenReturn("/images/7/content?thumbnail=true");
        var response = new ImageController(service, mock(ImageUploadService.class),
            mock(ImageDataLock.class), mock(ImageResetService.class), mock(ImageLibraryLock.class)).access(7L, true);
        assertEquals("/images/7/content?thumbnail=true", response.getBody().getData());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test void oversizedImageFallbackReturnsJpeg() {
        var service = mock(ImageService.class);
        byte[] jpeg = new byte[]{1, 2, 3};
        when(service.preview(7L)).thenReturn(jpeg);
        var response = new ImageController(service, mock(ImageUploadService.class),
            mock(ImageDataLock.class), mock(ImageResetService.class), mock(ImageLibraryLock.class)).preview(7L);
        assertArrayEquals(jpeg, response.getBody());
        assertEquals("image/jpeg", response.getHeaders().getContentType().toString());
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }
}
