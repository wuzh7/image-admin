package org.dromara.image.storage;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.image.config.ImageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tags({@Tag("dev"), @Tag("local")})
class LocalImageStorageTest {

    @TempDir
    Path root;

    @Test
    void savesValidatedImageAndThumbnail() throws Exception {
        LocalImageStorage storage = storage();
        MockMultipartFile file = new MockMultipartFile(
            "files", "商品图.png", "image/png", png(640, 480));

        LocalImageStorage.StoredImage saved = storage.save(42L, file);

        assertEquals(640, saved.width());
        assertEquals(480, saved.height());
        assertEquals("image/png", saved.contentType());
        assertEquals(64, saved.sha256().length());
        assertTrue(Files.isRegularFile(storage.resolve(saved.storageKey())));
        assertTrue(Files.isRegularFile(storage.resolve(saved.thumbnailKey())));
    }

    @Test
    void acceptsImagesWithoutAnApplicationSizeLimit() throws Exception {
        LocalImageStorage storage = storage();
        MockMultipartFile file = new MockMultipartFile(
            "files", "商品图.png", "image/png", png(64, 64));

        assertEquals(64, storage.save(42L, file).width());
    }

    @Test
    void rejectsSupportedImageContentWithUnsupportedExtension() throws Exception {
        LocalImageStorage storage = storage();
        MockMultipartFile file = new MockMultipartFile(
            "files", "伪装文件.txt", "image/png", png(32, 32));

        assertThrows(ServiceException.class, () -> storage.save(42L, file));
    }

    @Test
    void rejectsImageWithoutExtension() throws Exception {
        LocalImageStorage storage = storage();
        MockMultipartFile file = new MockMultipartFile(
            "files", "没有扩展名", "image/png", png(32, 32));

        assertThrows(ServiceException.class, () -> storage.save(42L, file));
    }

    private LocalImageStorage storage() {
        ImageProperties properties = new ImageProperties();
        properties.getStorage().setRoot(root.toString());
        return new LocalImageStorage(properties);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(36, 99, 212));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
