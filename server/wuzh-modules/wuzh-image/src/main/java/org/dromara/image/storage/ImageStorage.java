package org.dromara.image.storage;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public interface ImageStorage {
    StoredImage save(long batchId, MultipartFile file);
    LocalFile open(String key);
    void copy(String source, String target);
    void delete(String key);

    default boolean directUpload() { return false; }
    default Optional<String> accessUrl(String key, String contentType, boolean thumbnail) { return Optional.empty(); }
    default Optional<String> signGetUrl(String key, Duration expiry) { return Optional.empty(); }
    default String publishArchive(Path archive, String fileName) {
        throw new ServiceException("当前存储不支持导出文件直传");
    }
    default UploadPolicy signUploadPolicy(String keyPrefix, Duration expiry) {
        throw new ServiceException("当前存储不支持批次直传");
    }

    record StoredImage(String storageKey, String thumbnailKey, String originalName,
                       String contentType, long fileSize, int width, int height, String sha256) {}

    record UploadPolicy(String host, String keyPrefix, Map<String, String> formFields, Instant expiresAt) {}

    record LocalFile(Path path, boolean temporary) implements AutoCloseable {
        @Override public void close() throws java.io.IOException {
            if (temporary) Files.deleteIfExists(path);
        }
    }

    static String reviewPrefix(long batchId, LocalDateTime created) {
        return "review/%04d/%02d/%02d/%d/".formatted(created.getYear(), created.getMonthValue(),
            created.getDayOfMonth(), batchId);
    }

    static String libraryKey(long id, long batchId, String sha256, String source, LocalDateTime created) {
        String extension = source.substring(Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\')) + 1).replaceFirst("^.*(\\.[^.]+)$", "$1");
        if (!extension.startsWith(".")) extension = "";
        String shortSha = sha256 == null ? "unknown" : sha256.substring(0, Math.min(12, sha256.length()));
        return "library/%04d/%02d/%02d/%d/%d_%s%s".formatted(created.getYear(), created.getMonthValue(),
            created.getDayOfMonth(), batchId, id, shortSha, extension);
    }
}
