package org.dromara.image.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Data
@ConfigurationProperties(prefix = "image")
public class ImageProperties {

    private int maxFilesPerBatch = 1000;
    private int maxFilesPerRequest = 10;
    private long maxFileBytes = 20L * 1024 * 1024;
    private long maxPixels = 50_000_000L;
    private BigDecimal similarityThreshold = new BigDecimal("0.60");
    private int deleteRetentionDays = 7;
    private int taskWorkers = 2;
    private final Storage storage = new Storage();
    private final Model model = new Model();
    private final Qdrant qdrant = new Qdrant();

    @Data
    public static class Storage {
        private String type = "local";
        private String root = "./data/images";
        private final Oss oss = new Oss();
    }

    @Data
    public static class Oss {
        private String roleName;
        private String endpoint;
        private String publicEndpoint;
        private String region;
        private String bucket;
        private String accessKey;
        private String secretKey;
    }

    @Data
    public static class Model {
        private String path = "./models/sscd_disc_mixup.torchscript.pt";
    }

    @Data
    public static class Qdrant {
        private String url = "http://127.0.0.1:6333";
        private String apiKey;
        private String collection = "image_sscd_v1";
    }

}
