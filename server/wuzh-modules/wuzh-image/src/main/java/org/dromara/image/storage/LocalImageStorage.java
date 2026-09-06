package org.dromara.image.storage;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "image.storage.type", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalImageStorage implements ImageStorage {

    private static final Set<String> ALLOWED_FORMATS = Set.of("JPEG", "PNG", "WEBP");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");
    private static final int THUMBNAIL_SIZE = 320;

    private final ImageProperties properties;

    public StoredImage save(long batchId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择要上传的图片");
        }
        if (file.getSize() > properties.getMaxFileBytes()) {
            throw new ServiceException("图片不能超过20MB: " + safeName(file.getOriginalFilename()));
        }
        if (!ALLOWED_EXTENSIONS.contains(uploadedExtension(safeName(file.getOriginalFilename())))) {
            throw new ServiceException("图片扩展名仅支持JPG、JPEG、PNG和WebP: " + safeName(file.getOriginalFilename()));
        }

        Path temporary = null;
        try {
            Path root = root();
            Path temporaryDirectory = root.resolve(".tmp");
            Files.createDirectories(temporaryDirectory);
            temporary = Files.createTempFile(temporaryDirectory, "upload-", ".tmp");
            String sha256 = copyAndHash(file, temporary);
            ImageInfo info = inspect(temporary);
            if ((long) info.width() * info.height() > properties.getMaxPixels()) {
                throw new ServiceException("图片像素不能超过5000万: " + safeName(file.getOriginalFilename()));
            }

            String token = UUID.randomUUID().toString().replace("-", "");
            String base = "staging/" + batchId + "/" + token;
            String storageKey = base + info.extension();
            String thumbnailKey = "staging/" + batchId + "/thumb/" + token + ".jpg";
            Path target = resolve(storageKey);
            Files.createDirectories(target.getParent());
            move(temporary, target);
            temporary = null;
            writeThumbnail(target, resolve(thumbnailKey));
            return new StoredImage(storageKey, thumbnailKey, safeName(file.getOriginalFilename()),
                info.contentType(), Files.size(target), info.width(), info.height(), sha256);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("图片保存失败: " + exception.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件由下一次启动清理。
                }
            }
        }
    }

    public MovedFiles moveToLibrary(long imageId, String storageKey, String thumbnailKey) {
        try {
            LocalDate now = LocalDate.now();
            String prefix = "library/%d/%02d/".formatted(now.getYear(), now.getMonthValue());
            String token = imageId + "-" + UUID.randomUUID().toString().replace("-", "");
            String targetImage = prefix + token + extension(storageKey);
            String targetThumbnail = prefix + "thumb/" + token + ".jpg";
            move(resolve(storageKey), prepare(targetImage));
            try {
                move(resolve(thumbnailKey), prepare(targetThumbnail));
            } catch (Exception exception) {
                move(resolve(targetImage), prepare(storageKey));
                throw exception;
            }
            return new MovedFiles(targetImage, targetThumbnail);
        } catch (Exception exception) {
            throw new ServiceException("图片移入正式图库失败: " + exception.getMessage());
        }
    }

    public void restoreFromLibrary(MovedFiles moved, String storageKey, String thumbnailKey) {
        try {
            if (Files.exists(resolve(moved.storageKey()))) {
                move(resolve(moved.storageKey()), prepare(storageKey));
            }
            if (Files.exists(resolve(moved.thumbnailKey()))) {
                move(resolve(moved.thumbnailKey()), prepare(thumbnailKey));
            }
        } catch (IOException exception) {
            throw new ServiceException("回滚图片文件失败: " + exception.getMessage());
        }
    }

    @Override
    public LocalFile open(String key) {
        return new LocalFile(resolve(key), false);
    }

    @Override
    public void copy(String source, String target) {
        try {
            Files.copy(resolve(source), prepare(target), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new ServiceException("复制图片失败");
        }
    }

    public static StoredImage validate(Path path, String name, ImageProperties properties) throws Exception {
        long size = Files.size(path);
        if (size == 0 || size > properties.getMaxFileBytes()) throw new ServiceException("图片大小不合法");
        ImageInfo info = inspect(path);
        if (info.width() <= 0 || info.height() <= 0
            || (long) info.width() * info.height() > properties.getMaxPixels()) {
            throw new ServiceException("图片像素超出限制");
        }
        // 完整解码并修正 EXIF 后确认真实尺寸，元数据和模型使用同一方向。
        BufferedImage decoded = SscdFeatureExtractor.readOriented(path);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return new StoredImage(null, null, safeName(name), info.contentType(), size,
            decoded.getWidth(), decoded.getHeight(), HexFormat.of().formatHex(digest.digest()));
    }

    public Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new ServiceException("图片文件不存在");
        }
        Path target = root().resolve(key.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root())) {
            throw new ServiceException("非法图片路径");
        }
        return target;
    }

    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new ServiceException("图片文件清理失败: " + exception.getMessage());
        }
    }

    private String copyAndHash(MultipartFile file, Path target) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest);
             OutputStream output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > properties.getMaxFileBytes()) {
                    throw new ServiceException("图片不能超过20MB: " + safeName(file.getOriginalFilename()));
                }
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static ImageInfo inspect(Path path) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                throw new IOException("无法读取图片内容");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("文件不是受支持的图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if ("JPG".equals(format)) {
                    format = "JPEG";
                }
                if (!ALLOWED_FORMATS.contains(format)) {
                    throw new IOException("仅支持JPEG、PNG和WebP");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return switch (format) {
                    case "JPEG" -> new ImageInfo(width, height, ".jpg", "image/jpeg");
                    case "PNG" -> new ImageInfo(width, height, ".png", "image/png");
                    case "WEBP" -> new ImageInfo(width, height, ".webp", "image/webp");
                    default -> throw new IOException("不支持的图片编码");
                };
            } finally {
                reader.dispose();
            }
        }
    }

    private static void writeThumbnail(Path source, Path target) throws Exception {
        BufferedImage image = SscdFeatureExtractor.readOriented(source);
        double scale = Math.min(1.0, (double) THUMBNAIL_SIZE / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();

        Files.createDirectories(target.getParent());
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("缺少JPEG编码器");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.82f);
            }
            writer.write(null, new IIOImage(thumbnail, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private Path prepare(String key) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        return target;
    }

    private Path root() {
        Path root = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            throw new ServiceException("无法创建图片存储目录: " + root);
        }
        return root;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String extension(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? ".jpg" : key.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String uploadedExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "image";
        }
        String cleaned = name.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1).trim();
        if (cleaned.isEmpty()) {
            return "image";
        }
        return cleaned.length() > 255 ? cleaned.substring(cleaned.length() - 255) : cleaned;
    }

    public record MovedFiles(String storageKey, String thumbnailKey) {
    }

    private record ImageInfo(int width, int height, String extension, String contentType) {
    }
}
