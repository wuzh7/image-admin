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
    default Optional<String> accessUrl(String key, boolean thumbnail) { return Optional.empty(); }
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

    static String libraryKey(long id, String sha256, String source, LocalDateTime created) {
        return "library/%04d/%02d/%s/%s%s".formatted(created.getYear(), created.getMonthValue(),
            sha256.substring(0, 2), id, source.substring(source.lastIndexOf('.')));
    }
}
