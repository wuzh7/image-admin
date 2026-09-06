package org.dromara.image.storage;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.model.ObjectInfo;
import org.dromara.image.config.ImageProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "image.storage.type", havingValue = "oss")
public class OssImageStorage implements ImageStorage {
    private final ImageProperties properties;
    private final OssClient internal;
    private final OssClient external;

    public OssImageStorage(ImageProperties properties, @Qualifier("imageOssInternal") OssClient internal,
                           @Qualifier("imageOssExternal") OssClient external) {
        this.properties = properties;
        this.internal = internal;
        this.external = external;
    }

    @Override public boolean directUpload() { return true; }

    @Override public StoredImage save(long batchId, MultipartFile file) {
        throw new ServiceException("OSS模式请使用浏览器直传");
    }

    @Override public String signUpload(String key, String contentType, long size) {
        return external.presignPutUrl(key, Duration.ofMinutes(15), contentType, size);
    }

    @Override public void verifyUpload(String key, String contentType, long size) {
        ObjectInfo info = internal.head(key);
        if (info.size() != size || size <= 0 || size > properties.getMaxFileBytes()
            || !contentType.equals(info.contentType())) {
            throw new ServiceException("OSS图片大小或类型与上传申请不符");
        }
    }

    @Override public Optional<String> accessUrl(String key, boolean thumbnail) {
        if (key == null || key.isBlank()) return Optional.empty();
        return Optional.of(external.presignGetUrl(key, Duration.ofMinutes(10), thumbnail
            ? Map.of("x-oss-process", "image/auto-orient,1/resize,m_lfit,w_480,h_480/quality,q_82") : Map.of()));
    }

    @Override public LocalFile open(String key) {
        Path path = null;
        try {
            if (internal.head(key).size() > properties.getMaxFileBytes()) throw new ServiceException("图片超过大小限制");
            path = Files.createTempFile("image-oss-", ".tmp");
            // 流式读取且限制实际字节数，避免远端对象填满本机磁盘。
            try (OutputStream output = Files.newOutputStream(path)) {
                internal.download(key, (metadata, input) -> {
                    try {
                        byte[] buffer = new byte[8192];
                        long total = 0;
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > properties.getMaxFileBytes()) throw new ServiceException("图片超过大小限制");
                            output.write(buffer, 0, count);
                        }
                        return total;
                    } catch (java.io.IOException exception) { throw new ServiceException("读取OSS图片失败"); }
                });
            }
            return new LocalFile(path, true);
        } catch (Exception exception) {
            if (path != null) try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
            throw new ServiceException("读取OSS图片失败，请检查存储配置后重试");
        }
    }

    @Override public void copy(String source, String target) {
        ObjectInfo original = internal.head(source);
        internal.copy(source, target);
        ObjectInfo copied = internal.head(target);
        if (original.size() != copied.size() || !original.etag().equals(copied.etag())) {
            throw new ServiceException("OSS复制校验失败");
        }
    }

    @Override public void delete(String key) {
        if (key != null && !key.isBlank() && !internal.delete(key)) throw new ServiceException("OSS删除失败");
    }
}
