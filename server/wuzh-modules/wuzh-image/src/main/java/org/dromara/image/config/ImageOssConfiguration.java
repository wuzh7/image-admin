package org.dromara.image.config;

import org.dromara.common.oss.client.DefaultOssClientImpl;
import org.dromara.common.oss.client.OssClient;
import org.dromara.common.oss.config.OssClientConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "image.storage.type", havingValue = "oss")
public class ImageOssConfiguration {
    @Bean(destroyMethod = "close")
    public OssClient imageOssInternal(ImageProperties properties) {
        return client(properties.getStorage().getOss(), false);
    }

    @Bean(destroyMethod = "close")
    public OssClient imageOssExternal(ImageProperties properties) {
        return client(properties.getStorage().getOss(), true);
    }

    private OssClient client(ImageProperties.Oss config, boolean external) {
        for (String value : new String[]{config.getBucket(), config.getRegion()}) {
            if (value == null || value.isBlank()) throw new IllegalStateException("OSS配置不完整，拒绝退回本地存储");
        }
        String endpoint = external ? config.getPublicEndpoint() : config.getEndpoint();
        URI uri = URI.create(endpoint == null ? "" : endpoint);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalStateException("OSS Endpoint必须是HTTPS端点且不能包含路径");
        }
        if (external && uri.getHost().contains("-internal")) throw new IllegalStateException("浏览器不能使用OSS内网Endpoint");
        software.amazon.awssdk.auth.credentials.AwsCredentialsProvider provider = null;
        if (config.getRoleName() != null && !config.getRoleName().isBlank()) {
            try {
                var credentials = new com.aliyun.credentials.Client(new com.aliyun.credentials.models.Config()
                    .setType("ecs_ram_role").setRoleName(config.getRoleName()).setDisableIMDSv1(true));
                provider = () -> {
                    try {
                        var value = credentials.getCredential();
                        return software.amazon.awssdk.auth.credentials.AwsSessionCredentials.create(
                            value.getAccessKeyId(), value.getAccessKeySecret(), value.getSecurityToken());
                    } catch (Exception exception) { throw new IllegalStateException("无法获取ECS角色临时凭证"); }
                };
            } catch (Exception exception) { throw new IllegalStateException("ECS角色凭证初始化失败", exception); }
        }
        return new DefaultOssClientImpl(external ? "image-external" : "image-internal", OssClientConfig.builder()
            .endpoint(endpoint).useHttps(true).usePathStyleAccess(false).bucket(config.getBucket())
            .region(Region.of(config.getRegion())).credentialsProvider(provider)
            .accessKey(config.getAccessKey()).secretKey(config.getSecretKey()).build());
    }
}
