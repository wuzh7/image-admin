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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

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

    @Override public UploadPolicy signUploadPolicy(String keyPrefix, Duration expiry) {
        ImageProperties.Oss config = properties.getStorage().getOss();
        AwsCredentials credentials = resolveCredentials(config);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(expiry);
        String date = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(now);
        String requestTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC).format(now);
        String credential = "%s/%s/%s/oss/aliyun_v4_request"
            .formatted(credentials.accessKeyId(), date, config.getRegion());
        String tokenCondition = credentials instanceof AwsSessionCredentials session
            ? ",[\"eq\",\"$x-oss-security-token\",\"%s\"]".formatted(session.sessionToken()) : "";
        String policyJson = "{\"expiration\":\"%s\",\"conditions\":[[\"eq\",\"$bucket\",\"%s\"],"
            + "[\"eq\",\"$x-oss-signature-version\",\"OSS4-HMAC-SHA256\"],"
            + "[\"eq\",\"$x-oss-credential\",\"%s\"],[\"eq\",\"$x-oss-date\",\"%s\"]%s,"
            + "[\"starts-with\",\"$key\",\"%s\"],[\"content-length-range\",1,%d],"
            + "[\"in\",\"$content-type\",[\"image/jpeg\",\"image/png\",\"image/webp\"]],"
            + "[\"eq\",\"$x-oss-forbid-overwrite\",\"true\"]]}";
        policyJson = policyJson.formatted(expiresAt, config.getBucket(), credential, requestTime,
            tokenCondition, keyPrefix, properties.getMaxFileBytes());
        String policy = Base64.getEncoder().encodeToString(policyJson.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = hmac(("aliyun_v4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), date);
        signingKey = hmac(signingKey, config.getRegion());
        signingKey = hmac(signingKey, "oss");
        signingKey = hmac(signingKey, "aliyun_v4_request");

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("policy", policy);
        fields.put("x-oss-signature-version", "OSS4-HMAC-SHA256");
        fields.put("x-oss-credential", credential);
        fields.put("x-oss-date", requestTime);
        fields.put("x-oss-signature", hex(hmac(signingKey, policy)));
        fields.put("x-oss-forbid-overwrite", "true");
        if (credentials instanceof AwsSessionCredentials session) {
            fields.put("x-oss-security-token", session.sessionToken());
        }
        URI publicEndpoint = URI.create(config.getPublicEndpoint());
        String endpointHost = publicEndpoint.getHost().replaceFirst("^s3\\.", "");
        return new UploadPolicy("https://" + config.getBucket() + "." + endpointHost + "/", keyPrefix,
            Map.copyOf(fields), expiresAt);
    }

    private AwsCredentials resolveCredentials(ImageProperties.Oss config) {
        if (external.config() != null && external.config().credentialsProvider() != null) {
            return external.config().credentialsProvider().resolveCredentials();
        }
        if (config.getAccessKey() == null || config.getSecretKey() == null) {
            throw new ServiceException("OSS上传凭证不可用");
        }
        return AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey());
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("OSS上传签名生成失败", exception);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
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
