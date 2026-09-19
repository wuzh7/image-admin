package org.dromara.image.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.*;
import org.dromara.image.mapper.*;
import org.dromara.image.storage.ImageStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ImageUploadService {
    private final ImageBatchMapper batches;
    private final ImageAssetMapper assets;
    private final ImageStorage storage;
    private final ImageProperties properties;
    private final ImageService imageService;

    public record FileRequest(@NotBlank @Size(max = 255) String name,
                              @NotBlank String contentType, @Positive long size) {}
    public record PrepareRequest(@NotEmpty @Size(max = 1000) List<@Valid FileRequest> files) {}
    public record UploadTarget(Long imageId, String objectKey, String contentType, long size) {}
    public record UploadSession(String host, String keyPrefix, Map<String, String> formFields,
                                Instant expiresAt, int uploadConcurrency, List<UploadTarget> files) {}
    public record ReplacementSession(Long batchId, UploadSession upload) {}
    public record StorageMode(boolean directUpload, int uploadConcurrency, java.math.BigDecimal similarityThreshold) {}
    public record CompleteRequest(@NotNull @Size(max = 1000) List<@NotNull Long> imageIds,
                                  @NotNull @Size(max = 1000) List<@NotNull Long> failedIds) {}

    @Transactional(rollbackFor = Exception.class)
    public ReplacementSession createReplacementSession(long oldId, PrepareRequest request) {
        if (!LoginHelper.isAdmin()) throw new ServiceException("只有管理员可以操作");
        if (request.files().size() != 1) throw new ServiceException("替换时只能上传一张图片");
        ImageAsset old = assets.lockAsset(oldId);
        if (old == null || !ImageStatus.APPROVED.name().equals(old.getStatus())) {
            throw new ServiceException("只能替换正式图库中的图片");
        }
        if (assets.selectCount(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getReplacesImageId, oldId)
            .in(ImageAsset::getStatus, ImageStatus.UPLOADING.name(), ImageStatus.UPLOADED.name(),
                ImageStatus.CHECKING.name(), ImageStatus.WAIT_REVIEW.name(), ImageStatus.APPROVING.name(),
                ImageStatus.APPROVAL_FAILED.name(), ImageStatus.FAILED.name())) > 0) {
            throw new ServiceException("这张图片已有替换任务，请先处理该任务");
        }
        ImageBatch sourceBatch = batches.selectById(old.getBatchId());
        if (sourceBatch == null) throw new ServiceException("原图片批次不存在");
        var batch = imageService.createBatch(new ImageDtos.CreateBatchRequest(
            properties.getSimilarityThreshold(), old.getCategoryId(), sourceBatch.getInfringementMode()));
        UploadSession upload = createSession(batch.id(), request);
        UploadTarget target = upload.files().getFirst();
        int updated = assets.update(null, Wrappers.<ImageAsset>lambdaUpdate()
            .eq(ImageAsset::getId, target.imageId())
            .eq(ImageAsset::getBatchId, batch.id())
            .eq(ImageAsset::getStatus, ImageStatus.UPLOADING.name())
            .set(ImageAsset::getReplacesImageId, oldId));
        if (updated != 1) throw new ServiceException("创建图片替换任务失败");
        return new ReplacementSession(batch.id(), upload);
    }

    @Transactional(rollbackFor = Exception.class)
    public UploadSession createSession(long batchId, PrepareRequest request) {
        ImageBatch batch = requireUploading(batchId);
        if (!storage.directUpload()) throw new ServiceException("当前环境使用本地上传");
        long count = assets.selectCount(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getBatchId, batchId));
        if (request.files().size() > properties.getMaxFilesPerUploadSession()
            || count + request.files().size() > properties.getMaxFilesPerBatch()) throw new ServiceException("超过批次上传数量限制");
        List<ImageAsset> pending = new ArrayList<>(request.files().size());
        for (FileRequest file : request.files()) {
            String name = file.name().replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
            if (file.size() <= 0) throw new ServiceException("图片大小不合法");
            String type = file.contentType().startsWith("image/") ? file.contentType() : "application/octet-stream";
            String key = ImageStorage.reviewPrefix(batchId, batch.getCreateTime() == null ? java.time.LocalDateTime.now() : batch.getCreateTime())
                + UUID.randomUUID().toString().replace("-", "") + extension;
            ImageAsset asset = new ImageAsset();
            asset.setBatchId(batchId);
            asset.setCategoryId(batch.getCategoryId());
            asset.setCreateBy(batch.getCreateBy());
            asset.setCreateDept(batch.getCreateDept());
            asset.setOriginalName(name);
            asset.setDisplayName(name);
            asset.setStorageKey(key);
            asset.setContentType(type);
            asset.setFileSize(file.size());
            asset.setWidth(0);
            asset.setHeight(0);
            asset.setSha256("");
            asset.setStatus(ImageStatus.UPLOADING.name());
            asset.setDelFlag("0");
            pending.add(asset);
        }
        assets.insert(pending);
        batch.setTotalCount(Math.toIntExact(count + request.files().size()));
        batches.updateById(batch);
        String keyPrefix = ImageStorage.reviewPrefix(batchId, batch.getCreateTime() == null ? java.time.LocalDateTime.now() : batch.getCreateTime());
        ImageStorage.UploadPolicy policy = storage.signUploadPolicy(keyPrefix,
            Duration.ofMinutes(properties.getUploadPolicyMinutes()));
        List<UploadTarget> targets = pending.stream().map(asset -> new UploadTarget(asset.getId(),
            asset.getStorageKey(), asset.getContentType(), asset.getFileSize())).toList();
        return new UploadSession(policy.host(), policy.keyPrefix(), policy.formFields(), policy.expiresAt(),
            Math.max(1, Math.min(16, properties.getUploadConcurrency())), targets);
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(long batchId, CompleteRequest request) {
        ImageBatch batch = requireUploading(batchId);
        Set<Long> successfulIds = new LinkedHashSet<>(request.imageIds());
        Set<Long> failedIds = new LinkedHashSet<>(request.failedIds());
        if (successfulIds.isEmpty() && failedIds.isEmpty()) throw new ServiceException("上传结果不能为空");
        if (successfulIds.size() + failedIds.size() > properties.getMaxFilesPerUploadSession()) {
            throw new ServiceException("超过批次上传数量限制");
        }
        if (successfulIds.stream().anyMatch(failedIds::contains)) throw new ServiceException("同一图片不能同时标记成功和失败");
        List<Long> ids = new ArrayList<>(successfulIds);
        ids.addAll(failedIds);
        Map<Long, ImageAsset> found = new HashMap<>();
        assets.selectByIds(ids).forEach(asset -> found.put(asset.getId(), asset));
        List<ImageAsset> changed = new ArrayList<>();
        int newlyFailed = 0;
        for (Long id : ids) {
            ImageAsset asset = found.get(id);
            if (asset == null || !Objects.equals(asset.getBatchId(), batchId)) throw new ServiceException("上传记录不存在");
            if (successfulIds.contains(id) && ImageStatus.UPLOADED.name().equals(asset.getStatus())) continue;
            if (failedIds.contains(id) && ImageStatus.DELETE_PENDING.name().equals(asset.getStatus())) continue;
            if (!ImageStatus.UPLOADING.name().equals(asset.getStatus())) throw new ServiceException("图片已进入处理流程");
            if (successfulIds.contains(id)) {
                asset.setStatus(ImageStatus.UPLOADED.name());
            } else {
                asset.setStatus(ImageStatus.DELETE_PENDING.name());
                asset.setDeletedAt(java.time.LocalDateTime.now());
                newlyFailed++;
            }
            changed.add(asset);
        }
        if (!changed.isEmpty()) assets.updateById(changed);
        if (newlyFailed > 0) {
            batch.setTotalCount(Math.max(0, Objects.requireNonNullElse(batch.getTotalCount(), 0) - newlyFailed));
            batches.updateById(batch);
        }
    }

    public StorageMode storageMode() {
        return new StorageMode(storage.directUpload(), Math.max(1, Math.min(16, properties.getUploadConcurrency())), properties.getSimilarityThreshold());
    }

    // 仅清理尚未开始检测的过期上传，不给整个 review/ 设置会误删待审图片的生命周期。
    @Scheduled(fixedDelay = 600000, scheduler = "imageCleanupScheduler")
    @Transactional(rollbackFor = Exception.class)
    public void expireIncompleteUploads() {
        var cutoff = java.time.LocalDateTime.now().minusHours(24);
        for (ImageBatch candidate : batches.selectList(Wrappers.<ImageBatch>lambdaQuery()
            .eq(ImageBatch::getStatus, BatchStatus.UPLOADING.name()).lt(ImageBatch::getCreateTime, cutoff).last("LIMIT 100"))) {
            ImageBatch batch = batches.lockBatch(candidate.getId());
            if (batch == null || !BatchStatus.UPLOADING.name().equals(batch.getStatus())) continue;
            assets.update(null, Wrappers.<ImageAsset>lambdaUpdate().eq(ImageAsset::getBatchId, batch.getId())
                .in(ImageAsset::getStatus, ImageStatus.UPLOADING.name(), ImageStatus.UPLOADED.name())
                .set(ImageAsset::getStatus, ImageStatus.DELETE_PENDING.name())
                .set(ImageAsset::getDeletedAt, java.time.LocalDateTime.now()));
            batch.setStatus(BatchStatus.FAILED.name());
            batch.setErrorMessage("上传超过24小时未提交检测，文件已进入清理队列，请重新上传");
            batches.updateById(batch);
        }
    }

    private ImageBatch requireUploading(long id) {
        ImageBatch batch = batches.lockBatch(id);
        Long userId = LoginHelper.getUserId();
        if (batch == null || userId == null || (!LoginHelper.isAdmin()
            && !Objects.equals(userId, batch.getCreateBy()))) throw new ServiceException("图片批次不存在");
        if (!BatchStatus.UPLOADING.name().equals(batch.getStatus())) throw new ServiceException("批次已开始检测");
        return batch;
    }
}
