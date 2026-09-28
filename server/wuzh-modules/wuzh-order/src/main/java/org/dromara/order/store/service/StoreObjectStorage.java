package org.dromara.order.store.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.client.OssClient;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.storage.ImageStorage;
import org.dromara.order.store.domain.StoreDtos;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class StoreObjectStorage {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private final ImageProperties properties;
    private final ImageStorage imageStorage;
    private final ObjectProvider<OssClient> ossClient;

    public StoreObjectStorage(ImageProperties properties, ImageStorage imageStorage,
                              @Qualifier("imageOssInternal") ObjectProvider<OssClient> ossClient) {
        this.properties = properties;
        this.imageStorage = imageStorage;
        this.ossClient = ossClient;
    }

    public StoreDtos.UploadView save(long ownerId, String kind, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ServiceException("请选择上传文件");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        boolean excel = "listing".equals(kind);
        if (excel ? !"xlsx".equals(extension) : !IMAGE_EXTENSIONS.contains(extension)) throw new ServiceException(excel ? "仅支持 XLSX 文件" : "仅支持 JPG、PNG、WebP 图片");
        if (file.getSize() > (excel ? 20L : 50L) * 1024 * 1024) throw new ServiceException("文件过大");
        Path temporary = null;
        try {
            temporary = Files.createTempFile("store-upload-", "." + extension);
            file.transferTo(temporary);
            Integer width = null, height = null;
            if (excel) {
                try (XSSFWorkbook ignored = new XSSFWorkbook(temporary.toFile())) { }
            } else {
                BufferedImage image = ImageIO.read(temporary.toFile());
                if (image == null) throw new ServiceException("图片格式无效");
                width = image.getWidth(); height = image.getHeight();
            }
            String prefix = "results".equals(kind) && "oss".equalsIgnoreCase(properties.getStorage().getType())
                ? "render-public/results" : "store/" + kind;
            String key = prefix + "/" + ownerId + "/" + UUID.randomUUID() + "." + extension;
            if ("oss".equalsIgnoreCase(properties.getStorage().getType())) {
                OssClient client = ossClient.getIfAvailable();
                if (client == null) throw new ServiceException("OSS 存储不可用");
                client.bucketUpload(properties.getStorage().getOss().getBucket(), key, temporary);
            } else {
                Path root = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize();
                Path target = root.resolve(key);
                Files.createDirectories(target.getParent());
                Files.move(temporary, target);
                temporary = null;
            }
            String contentType = excel ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "image/" + ("jpg".equals(extension) ? "jpeg" : extension);
            String url = url(key, contentType);
            return new StoreDtos.UploadView(key, url, width, height);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("文件保存失败: " + exception.getMessage(), 500);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }

    public String saveGenerated(long ownerId, Path source) {
        String key = "store/listing/" + ownerId + "/" + UUID.randomUUID() + ".xlsx";
        try {
            if ("oss".equalsIgnoreCase(properties.getStorage().getType())) {
                OssClient client = ossClient.getIfAvailable();
                if (client == null) throw new ServiceException("OSS 存储不可用");
                client.bucketUpload(properties.getStorage().getOss().getBucket(), key, source);
            } else {
                Path target = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize().resolve(key);
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            }
            return key;
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("上架表保存失败: " + exception.getMessage(), 500);
        }
    }

    public String url(String key, String contentType) {
        if ("oss".equalsIgnoreCase(properties.getStorage().getType()) && key.startsWith("render-public/results/"))
            return publicUrl(key);
        return imageStorage.accessUrl(key, contentType, false).orElse("/prod-api/store/files?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
    }

    public Optional<String> renderInputUrl(String key) {
        if (!"oss".equalsIgnoreCase(properties.getStorage().getType())) return Optional.empty();
        String publicKey = "render-public/inputs/" + key;
        imageStorage.copy(key, publicKey);
        return Optional.of(publicUrl(publicKey));
    }

    private String publicUrl(String key) {
        ImageProperties.Oss config = properties.getStorage().getOss();
        String host = URI.create(config.getPublicEndpoint()).getHost().replaceFirst("^s3\\.", "");
        return "https://" + config.getBucket() + "." + host + "/" + key;
    }

    public ImageStorage.LocalFile open(String key) {
        if ("oss".equalsIgnoreCase(properties.getStorage().getType())) return imageStorage.open(key);
        Path root = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize();
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new ServiceException("文件不存在");
        return new ImageStorage.LocalFile(path, false);
    }

    public void delete(String key) {
        if (key == null || !key.matches("(store/results|render-public/results)/[0-9]+/[0-9a-f-]+\\.(jpg|jpeg|png|webp)"))
            throw new ServiceException("渲染结果文件路径无效");
        imageStorage.delete(key);
    }
}
