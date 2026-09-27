package org.dromara.order.store.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

public final class StoreDtos {
    private StoreDtos() {}

    public record ShopRequest(@NotBlank String name, String platform, String configJson,
                              String shopCode, String operator, String market, List<Long> categoryIds, String notes,
                              Long operatorUserId, Long platformId, List<Long> siteIds) {}
    public record ShopView(Long id, String name, String platform, String status, String configJson, String shopCode) {}
    public record CategoryView(Long id, String name, Integer sortOrder, String status) {}
    public record CalibrationRequest(@NotBlank String name, @NotNull Long categoryId,
                                     @NotBlank String calibrationConfigJson, @NotBlank String backgroundOssKey,
                                     String maskOssKey) {}
    public record CalibrationView(Long id, String name, Long categoryId, String calibrationConfigJson,
                                  String backgroundOssKey, String backgroundUrl, String maskOssKey,
                                  String maskUrl, String status, Integer version) {}
    public record TemplateInput(String name, String ossKey, String imageUrl, String templateConfigJson,
                                Integer width, Integer height) {}
    public record TemplateGroupRequest(@NotBlank String name, @NotNull Long categoryId,
                                       String calibrationConfigJson, String maskOssKey, List<TemplateInput> templates) {}
    public record TemplateView(Long id, String name, String ossKey, String imageUrl, String templateConfigJson,
                               Integer width, Integer height) {}
    public record TemplateGroupView(Long id, String name, Long categoryId, String calibrationConfigJson, String maskOssKey,
                                    String status, List<TemplateView> templates) {}
    public record GroupStatusRequest(@NotBlank String status) {}
    public record RenderDestinationRequest(@NotNull Long shopId, @NotNull Long listingTemplateId, Map<String, Integer> titleSelection) {}
    public record RenderBatchRequest(@NotNull Long categoryId, @NotNull Long templateGroupId,
                                     @NotEmpty List<Long> imageIds, Long shopId, Long listingTemplateId,
                                     List<@Valid RenderDestinationRequest> destinations,
                                     String format, Integer quality, @NotBlank String idempotencyKey) {}
    public record RenderBatchView(Long id, String status, Long categoryId, Long templateGroupId, Long shopId,
                                  Long listingTemplateId, Integer totalCount, Integer completedCount,
                                  Integer failedCount, Integer skippedCount) {}
    public record RenderLogView(Long id, Long batchId, Long imageId, String imageName,
                                String userName, String renderedAt, Integer renderUseCount) {}
    public record RenderGroupView(Long imageId, String originalName, List<StoreRenderItem> items) {}
    public record ClaimRequest(@NotBlank String workerId, Integer limit) {}
    public record ClaimedItem(Long itemId, Long batchId, Long imageId, Long templateId,
                              String imageUrl, String templateUrl, String maskUrl, String calibrationConfigJson,
                              String templateConfigJson, String outputFormat, Integer quality, String leaseToken) {}
    public record ClaimView(List<ClaimedItem> items, Integer nextPollMs) {}
    public record RenewRequest(@NotBlank String workerId, @NotBlank String leaseToken) {}
    public record CompleteRequest(@NotBlank String workerId, @NotBlank String leaseToken, @NotNull Result result) {}
    public record Result(String ossKey, String sha256, Integer width, Integer height) {}
    public record FailRequest(@NotBlank String workerId, @NotBlank String leaseToken, @NotBlank String errorMessage) {}
    public record UploadView(String ossKey, String imageUrl, Integer width, Integer height) {}
    public record ArchiveFile(String ossKey, String filename, boolean original) {}
    public record PageView<T>(List<T> records, long total) {}
    public record ListingTemplateRequest(@NotNull Long shopId, @NotNull Long categoryId,
                                         @NotBlank String name, @NotBlank String ossKey, String originalFilename, String configJson) {}
    public record ListingTemplateView(Long id, Long shopId, Long categoryId, String name, String ossKey, String originalFilename, String status, String configJson, Integer versionNo, String templateCode) {}
    public record ListingProfileRequest(@NotBlank String configJson) {}
}
