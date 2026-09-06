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

@Service
@RequiredArgsConstructor
public class ImageUploadService {
    private final ImageBatchMapper batches;
    private final ImageAssetMapper assets;
    private final ImageStorage storage;
    private final ImageProperties properties;

    public record FileRequest(@NotBlank @Size(max = 255) String name,
                              @NotBlank String contentType, @Positive long size) {}
    public record PrepareRequest(@NotEmpty @Size(max = 10) List<@Valid FileRequest> files) {}
    public record Ticket(Long imageId, String uploadUrl, Map<String, String> headers) {}
    public record CompleteRequest(@NotEmpty @Size(max = 10) List<@NotNull Long> imageIds) {}

    @Transactional(rollbackFor = Exception.class)
    public List<Ticket> prepare(long batchId, PrepareRequest request) {
        ImageBatch batch = requireUploading(batchId);
        if (!storage.directUpload()) throw new ServiceException("当前环境使用本地上传");
        long count = assets.selectCount(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getBatchId, batchId));
        if (request.files().size() > properties.getMaxFilesPerRequest()
            || count + request.files().size() > properties.getMaxFilesPerBatch()) throw new ServiceException("超过批次上传数量限制");
        List<Ticket> tickets = new ArrayList<>();
        for (FileRequest file : request.files()) {
            String name = file.name().replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            String extension = name.substring(Math.max(0, name.lastIndexOf('.'))).toLowerCase(Locale.ROOT);
            String type = switch (extension) {
                case ".jpg", ".jpeg" -> "image/jpeg";
                case ".png" -> "image/png";
                case ".webp" -> "image/webp";
                default -> throw new ServiceException("仅支持JPEG、PNG和WebP图片");
            };
            if (file.size() <= 0 || file.size() > properties.getMaxFileBytes() || !type.equals(file.contentType())) {
                throw new ServiceException("图片大小或类型不合法");
            }
            String key = "review/" + batchId + "/" + UUID.randomUUID().toString().replace("-", "") + extension;
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
            assets.insert(asset);
            tickets.add(new Ticket(asset.getId(), storage.signUpload(key, type, file.size()),
                Map.of("Content-Type", type, "x-oss-forbid-overwrite", "true")));
        }
        batch.setTotalCount(Math.toIntExact(count + request.files().size()));
        batches.updateById(batch);
        return tickets;
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(long batchId, CompleteRequest request) {
        requireUploading(batchId);
        for (Long id : new LinkedHashSet<>(request.imageIds())) {
            ImageAsset asset = assets.selectById(id);
            if (asset == null || !Objects.equals(asset.getBatchId(), batchId)) throw new ServiceException("上传记录不存在");
            if (ImageStatus.UPLOADED.name().equals(asset.getStatus())) continue;
            if (!ImageStatus.UPLOADING.name().equals(asset.getStatus())) throw new ServiceException("图片已进入处理流程");
            storage.verifyUpload(asset.getStorageKey(), asset.getContentType(), asset.getFileSize());
            asset.setStatus(ImageStatus.UPLOADED.name());
            assets.updateById(asset);
        }
    }

    public boolean directUpload() { return storage.directUpload(); }

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
        if (batch == null || userId == null || (!LoginHelper.isSuperAdmin(userId)
            && !Objects.equals(userId, batch.getCreateBy()))) throw new ServiceException("图片批次不存在");
        if (!BatchStatus.UPLOADING.name().equals(batch.getStatus())) throw new ServiceException("批次已开始检测");
        return batch;
    }
}
