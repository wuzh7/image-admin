package org.dromara.image.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@Data
@ConfigurationProperties(prefix = "image")
public class ImageProperties {

    private int maxFilesPerBatch = 1000;
    private int maxFilesPerRequest = 10;
    private int maxFilesPerUploadSession = 1000;
    private BigDecimal similarityThreshold = new BigDecimal("0.60");
    private int deleteRetentionDays = 7;
    private int taskWorkers = 1;
    private int featureBatchSize = 1;
    private int uploadConcurrency = 8;
    private int uploadPolicyMinutes = 30;
    private final Infringement infringement = new Infringement();
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
        private String stagingCollection = "image_sscd_staging_v2";
    }

    @Data
    public static class Infringement {
        private String relayBaseUrl = "https://api.openlux.ai";
        private String relayApiKey;
        private String promptFile = "/app/config/image_analysis_prompt.txt";
        private int requestTimeoutSeconds = 900;
        private int relayWorkers = 10;
        private String externalApiKey;
        private int externalLeaseMinutes = 1440;
    }

}
