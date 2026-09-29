package org.dromara.image.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

public final class ExternalInfringementDtos {
    private ExternalInfringementDtos() { }

    public record Claim(String imageId, String imageUrl, String claimToken, LocalDateTime expiresAt) { }

    public record Result(String imageId, String claimToken, String riskLevel, String riskReason,
                         @JsonProperty("CaseNumber") List<String> caseNumbers,
                         List<String> verificationUrls) { }

    @Data
    public static class ClaimRow {
        private Long id;
        private Long imageId;
        private String storageKey;
    }
}
