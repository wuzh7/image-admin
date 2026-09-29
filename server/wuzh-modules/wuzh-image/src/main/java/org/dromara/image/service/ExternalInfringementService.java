package org.dromara.image.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ExternalInfringementDtos;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.storage.ImageStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExternalInfringementService {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InfringementScanItemMapper items;
    private final ImageStorage storage;
    private final ImageProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public List<ExternalInfringementDtos.Claim> claim() {
        int minutes = properties.getInfringement().getExternalLeaseMinutes();
        if (minutes < 1) throw new ServiceException("三方领取超时时间必须大于0", 500);
        LocalDateTime now = LocalDateTime.now();
        var rows = items.selectExternalUnclaimed(now.minusMinutes(minutes));
        if (rows.isEmpty()) return List.of();
        String token = UUID.randomUUID().toString();
        int updated = items.claimExternal(rows.stream().map(ExternalInfringementDtos.ClaimRow::getId).toList(), token, now);
        if (updated != rows.size()) throw new ServiceException("图片领取冲突，请重试", 409);
        return rows.stream().map(row -> new ExternalInfringementDtos.Claim(String.valueOf(row.getImageId()),
            storage.signGetUrl(row.getStorageKey(), Duration.ofMinutes(minutes))
                .orElse("/prod-api/open-api/infringement/images/" + row.getImageId() + "/content"),
            token, now.plusMinutes(minutes))).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(ExternalInfringementDtos.Result result) {
        if (result == null || result.imageId() == null
            || result.claimToken() == null || result.claimToken().isBlank()) {
            throw new ServiceException("imageId和claimToken不能为空");
        }
        long imageId;
        try {
            imageId = Long.parseLong(result.imageId());
            if (imageId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new ServiceException("imageId必须是正整数ID字符串");
        }
        String riskLevel = result.riskLevel() == null ? "" : result.riskLevel().trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("HIGH", "LOW", "NEEDS_REVIEW").contains(riskLevel)) {
            throw new ServiceException("riskLevel仅支持high、low、needs_review");
        }
        String reason = result.riskReason() == null ? "" : result.riskReason().trim();
        if (reason.length() > 255) throw new ServiceException("riskReason不能超过255字符");
        List<String> cases = normalized(result.caseNumbers(), 100, 32, "CaseNumber");
        List<String> urls = normalized(result.verificationUrls(), 100, 2000, "verificationUrls");
        if (urls.stream().anyMatch(url -> {
            try {
                URI uri = URI.create(url);
                return !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null;
            } catch (IllegalArgumentException e) {
                return true;
            }
        })) throw new ServiceException("verificationUrls只能包含HTTP或HTTPS链接");
        String caseJson = JSON.writeValueAsString(cases);
        String urlJson = JSON.writeValueAsString(urls);
        if (items.completeExternal(imageId, result.claimToken(), riskLevel, reason, caseJson, urlJson) == 1) return;
        InfringementScanItem current = items.selectCurrentExternal(imageId);
        if (current != null && current.getExternalCompletedAt() != null
            && Objects.equals(current.getExternalClaimToken(), result.claimToken())
            && Objects.equals(current.getRiskLevel(), riskLevel) && Objects.equals(current.getRiskReason(), reason)
            && Objects.equals(current.getCaseNumbersJson(), caseJson)
            && Objects.equals(current.getVerificationUrlsJson(), urlJson)) return;
        throw new ServiceException("领取凭证已失效或图片结果已变更", 409);
    }

    public String contentKey(long imageId, String token) {
        if (token == null || token.isBlank()) throw new ServiceException("图片领取凭证无效", 403);
        String key = items.selectExternalContentKey(imageId, token,
            LocalDateTime.now().minusMinutes(properties.getInfringement().getExternalLeaseMinutes()));
        if (key == null) throw new ServiceException("图片领取凭证无效", 403);
        return key;
    }

    private static List<String> normalized(List<String> values, int maxCount, int maxLength, String field) {
        if (values == null) return List.of();
        if (values.size() > maxCount || values.stream().anyMatch(value -> value == null || value.isBlank()
            || value.trim().length() > maxLength)) throw new ServiceException(field + "格式或长度不合法");
        return values.stream().map(String::trim).distinct().toList();
    }
}
