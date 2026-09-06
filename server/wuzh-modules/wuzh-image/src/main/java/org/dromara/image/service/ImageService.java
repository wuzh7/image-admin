package org.dromara.image.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.BatchStatus;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageAspectRatio;
import org.dromara.image.domain.ImageBatch;
import org.dromara.image.domain.ImageCategory;
import org.dromara.image.domain.ImageDtos;
import org.dromara.image.domain.ImageStatus;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.mapper.ImageCategoryMapper;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.ImageStorage;
import org.dromara.system.api.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private static final BigDecimal MAX_ADMISSION_SIMILARITY = new BigDecimal("0.95");

    private final ImageProperties properties;
    private final ImageBatchMapper batchMapper;
    private final ImageAssetMapper assetMapper;
    private final ImageCategoryMapper categoryMapper;
    private final ImageStorage storage;
    private final QdrantVectorStore vectorStore;
    private final ImageTaskRunner taskRunner;
    private final UserService userService;

    @Transactional(rollbackFor = Exception.class)
    public ImageDtos.BatchView createBatch(ImageDtos.CreateBatchRequest request) {
        requireCurrentUserId();
        ImageCategory category = requireCategory(request.categoryId());
        if (!"0".equals(category.getStatus())) {
            throw new ServiceException("所选类目已停用，请重新选择");
        }
        ImageBatch batch = new ImageBatch();
        batch.setName("图片批次");
        batch.setCategoryId(category.getId());
        batch.setStatus(BatchStatus.UPLOADING.name());
        batch.setSimilarityThreshold(request.similarityThreshold() == null
            ? properties.getSimilarityThreshold() : request.similarityThreshold());
        batch.setTotalCount(0);
        batch.setProcessedCount(0);
        batch.setDuplicateCount(0);
        batch.setFailedCount(0);
        batchMapper.insert(batch);
        return toBatchView(batch, List.of());
    }

    @Transactional(rollbackFor = Exception.class)
    public int upload(long batchId, List<MultipartFile> files) {
        batchMapper.lockBatch(batchId);
        ImageBatch batch = requireBatch(batchId);
        if (!BatchStatus.UPLOADING.name().equals(batch.getStatus())) {
            throw new ServiceException("当前批次已开始检测，不能继续上传");
        }
        if (files == null || files.isEmpty()) {
            throw new ServiceException("请至少选择一张图片");
        }
        if (files.size() > properties.getMaxFilesPerRequest()) {
            throw new ServiceException("单次最多上传" + properties.getMaxFilesPerRequest() + "张图片");
        }
        long existing = assetMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getBatchId, batchId));
        if (existing + files.size() > properties.getMaxFilesPerBatch()) {
            throw new ServiceException("一个批次最多上传" + properties.getMaxFilesPerBatch() + "张图片");
        }

        List<ImageStorage.StoredImage> savedFiles = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                ImageStorage.StoredImage saved = storage.save(batchId, file);
                savedFiles.add(saved);
                ImageAsset asset = new ImageAsset();
                asset.setCreateBy(batch.getCreateBy());
                asset.setCreateDept(batch.getCreateDept());
                asset.setUpdateBy(batch.getCreateBy());
                asset.setBatchId(batchId);
                asset.setCategoryId(batch.getCategoryId());
                asset.setStorageKey(saved.storageKey());
                asset.setThumbnailKey(saved.thumbnailKey());
                asset.setOriginalName(saved.originalName());
                asset.setDisplayName(saved.originalName());
                asset.setContentType(saved.contentType());
                asset.setFileSize(saved.fileSize());
                asset.setWidth(saved.width());
                asset.setHeight(saved.height());
                asset.setAspectRatio(ImageAspectRatio.format(saved.width(), saved.height()));
                asset.setSha256(saved.sha256());
                asset.setStatus(ImageStatus.UPLOADED.name());
                asset.setDelFlag("0");
                assetMapper.insert(asset);
            }
            batch.setTotalCount(Math.toIntExact(existing + files.size()));
            batchMapper.updateById(batch);
            return files.size();
        } catch (RuntimeException exception) {
            for (ImageStorage.StoredImage saved : savedFiles) {
                try {
                    storage.delete(saved.storageKey());
                    storage.delete(saved.thumbnailKey());
                } catch (RuntimeException cleanupError) {
                    log.warn("回滚上传文件失败: {}", saved.storageKey(), cleanupError);
                }
            }
            throw exception;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void start(long batchId) {
        batchMapper.lockBatch(batchId);
        ImageBatch batch = requireBatch(batchId);
        if (!BatchStatus.UPLOADING.name().equals(batch.getStatus())) {
            throw new ServiceException("只有上传中的批次可以开始检测");
        }
        if (batch.getTotalCount() == null || batch.getTotalCount() == 0) {
            throw new ServiceException("批次中还没有图片");
        }
        if (assetMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getBatchId, batchId).eq(ImageAsset::getStatus, ImageStatus.UPLOADING.name())) > 0) {
            throw new ServiceException("还有图片未上传完成，请继续上传或重新创建批次");
        }
        if (batchMapper.queueForDetection(batchId) != 1) {
            throw new ServiceException("该批次已开始检测");
        }
        // 持久化队列由调度器在事务提交后读取。
    }

    public ImageDtos.BatchView getBatch(long batchId) {
        ImageBatch batch = requireBatch(batchId);
        List<ImageAsset> assets = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getBatchId, batchId)
            .orderByAsc(ImageAsset::getId));
        return toBatchView(batch, assets);
    }

    public PageResult<ImageDtos.BatchSummary> listBatches(PageQuery pageQuery, String status) {
        Long currentUserId = requireCurrentUserId();
        LambdaQueryWrapper<ImageBatch> wrapper = Wrappers.<ImageBatch>lambdaQuery()
            .eq(!LoginHelper.isSuperAdmin(currentUserId), ImageBatch::getCreateBy, currentUserId)
            .eq(StringUtils.isNotBlank(status), ImageBatch::getStatus, status)
            .orderByDesc(ImageBatch::getCreateTime);
        Page<ImageBatch> page = batchMapper.selectPage(pageQuery.build(),
            wrapper);
        Map<Long, String> creatorNames = userService.selectUserNicksByIds(page.getRecords().stream()
            .map(ImageBatch::getCreateBy)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet()));
        List<ImageDtos.BatchSummary> rows = page.getRecords().stream()
            .map(batch -> toBatchSummary(batch, creatorNames.get(batch.getCreateBy())))
            .toList();
        return PageResult.build(rows, page.getTotal());
    }

    @Transactional(rollbackFor = Exception.class)
    public void retry(long batchId) {
        batchMapper.lockBatch(batchId);
        ImageBatch batch = requireBatch(batchId);
        if (!BatchStatus.FAILED.name().equals(batch.getStatus())) {
            throw new ServiceException("当前批次没有需要重试的失败图片");
        }
        List<ImageAsset> admissionFailed = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getBatchId, batchId).eq(ImageAsset::getStatus, ImageStatus.APPROVAL_FAILED.name()));
        if (!admissionFailed.isEmpty()) {
            admissionFailed.forEach(asset -> assetMapper.update(null, Wrappers.<ImageAsset>lambdaUpdate()
                .eq(ImageAsset::getId, asset.getId()).set(ImageAsset::getStatus, ImageStatus.APPROVING.name())
                .set(ImageAsset::getErrorMessage, null)));
            batch.setStatus(BatchStatus.CONFIRMING.name());
            batch.setErrorMessage(null);
            batch.setFailedCount(0);
            batchMapper.updateById(batch);
            return;
        }
        List<ImageAsset> failed = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getBatchId, batchId)
            .eq(ImageAsset::getStatus, ImageStatus.FAILED.name()));
        if (failed.isEmpty()) throw new ServiceException("没有可重试图片，请重新上传");
        for (ImageAsset asset : failed) {
            asset.setStatus(ImageStatus.UPLOADED.name());
            asset.setErrorMessage(null);
            assetMapper.updateById(asset);
        }
        batch.setStatus(BatchStatus.QUEUED.name());
        batch.setErrorMessage(null);
        batchMapper.updateById(batch);
    }

    @Transactional(rollbackFor = Exception.class)
    public void confirm(long batchId, ImageDtos.ConfirmBatchRequest request) {
        ImageBatch batch = requireBatch(batchId);
        if (!BatchStatus.WAIT_REVIEW.name().equals(batch.getStatus())) {
            throw new ServiceException("只有待确认批次可以入库");
        }
        int claimed = batchMapper.claimForConfirmation(batchId);
        if (claimed != 1) {
            throw new ServiceException("当前批次正在确认或已完成，请勿重复提交");
        }
        List<ImageAsset> assets = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getBatchId, batchId)
            .orderByAsc(ImageAsset::getId));
        Set<Long> selected = new HashSet<>(request.approvedIds());
        Set<Long> batchIds = new HashSet<>();
        for (ImageAsset asset : assets) {
            batchIds.add(asset.getId());
        }
        if (!batchIds.containsAll(selected)) {
            throw new ServiceException("提交的数据包含不属于当前批次的图片");
        }
        if (assets.stream().anyMatch(asset -> ImageStatus.FAILED.name().equals(asset.getStatus()))) {
            throw new ServiceException("批次中仍有失败图片，请先重试");
        }

        List<ImageAsset> selectedAssets = new ArrayList<>();
        for (ImageAsset asset : assets) {
            if (ImageStatus.WAIT_REVIEW.name().equals(asset.getStatus()) && selected.contains(asset.getId())) {
                selectedAssets.add(asset);
            }
        }
        List<Long> prohibitedIds = selectedAssets.stream()
            .filter(asset -> "EXACT_HASH".equals(asset.getMatchMethod())
                || asset.getSimilarity() != null
                && asset.getSimilarity().compareTo(MAX_ADMISSION_SIMILARITY) > 0)
            .map(ImageAsset::getId)
            .toList();
        if (!prohibitedIds.isEmpty()) {
            String ids = String.join(",", prohibitedIds.stream().map(String::valueOf).toList());
            throw new ServiceException("相似度超过95%的图片不可入库：" + ids);
        }

        for (ImageAsset asset : assets) {
            if (!ImageStatus.WAIT_REVIEW.name().equals(asset.getStatus())) continue;
            asset.setStatus(selected.contains(asset.getId())
                ? ImageStatus.APPROVING.name() : ImageStatus.DELETE_PENDING.name());
            if (!selected.contains(asset.getId())) asset.setDeletedAt(LocalDateTime.now());
            if (assetMapper.updateById(asset) != 1) throw new ServiceException("审核决定保存失败");
        }
        batch.setStatus(BatchStatus.CONFIRMING.name());
        batch.setErrorMessage(null);
        batchMapper.updateById(batch);
    }

    public PageResult<ImageDtos.ImageView> listApproved(ImageDtos.ImageQuery query, PageQuery pageQuery) {
        Long currentUserId = requireCurrentUserId();
        LambdaQueryWrapper<ImageAsset> wrapper = Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getStatus, ImageStatus.APPROVED.name())
            .eq(!LoginHelper.isSuperAdmin(currentUserId), ImageAsset::getCreateBy, currentUserId)
            .eq(query.getCategoryId() != null, ImageAsset::getCategoryId, query.getCategoryId())
            .eq(query.getBatchId() != null, ImageAsset::getBatchId, query.getBatchId())
            .eq(StringUtils.isNotBlank(query.getAspectRatio()), ImageAsset::getAspectRatio, query.getAspectRatio())
            .and(StringUtils.isNotBlank(query.getKeyword()), nested -> nested
                .like(ImageAsset::getOriginalName, query.getKeyword())
                .or()
                .like(ImageAsset::getDisplayName, query.getKeyword()))
            .orderByDesc(ImageAsset::getCreateTime);
        Page<ImageAsset> page = assetMapper.selectPage(pageQuery.build(), wrapper);
        List<ImageDtos.ImageView> rows = toImageViews(page.getRecords(), true);
        return PageResult.build(rows, page.getTotal());
    }

    public ImageDtos.ImageView getImage(long imageId) {
        ImageAsset asset = requireAsset(imageId);
        return toImageViews(List.of(asset), true).getFirst();
    }

    public ImageContent content(long imageId, boolean thumbnail) {
        ImageAsset asset = requireAsset(imageId);
        String key = thumbnail ? asset.getThumbnailKey() : asset.getStorageKey();
        if (storage.directUpload()) throw new ServiceException("请通过签名URL读取OSS图片");
        if (key == null || key.isBlank()) throw new ServiceException("图片文件已被清理或不存在");
        Path path = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize().resolve(key).normalize();
        if (!path.startsWith(Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize())) throw new ServiceException("非法图片路径");
        if (!Files.isRegularFile(path)) {
            throw new ServiceException("图片文件已被清理或不存在");
        }
        return new ImageContent(path, thumbnail ? "image/jpeg" : asset.getContentType(), asset.getOriginalName());
    }

    public String accessUrl(long imageId, boolean thumbnail) {
        ImageAsset asset = requireAsset(imageId);
        if (ImageStatus.UPLOADING.name().equals(asset.getStatus())
            || ImageStatus.DELETE_PENDING.name().equals(asset.getStatus())
            || ImageStatus.REJECTED.name().equals(asset.getStatus())) throw new ServiceException("图片当前不可访问");
        return storage.accessUrl(asset.getStorageKey(), thumbnail)
            .orElse(contentUrl(imageId, thumbnail));
    }

    public void delete(long imageId) {
        ImageAsset asset = requireAsset(imageId);
        if (!ImageStatus.APPROVED.name().equals(asset.getStatus())) {
            throw new ServiceException("只有正式图库图片可以删除");
        }
        vectorStore.delete(List.of(imageId));
        asset.setDeletedAt(LocalDateTime.now());
        asset.setStatus(ImageStatus.DELETE_PENDING.name());
        assetMapper.updateById(asset);
        assetMapper.deleteById(imageId);
    }

    public ImageDtos.HealthView health() {
        try {
            long approvedImageCount = assetMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery()
                .eq(ImageAsset::getStatus, ImageStatus.APPROVED.name()));
            long qdrantPoints = vectorStore.pointCount();
            return new ImageDtos.HealthView("UP", SscdFeatureExtractor.FEATURE_VERSION,
                taskRunner.modelSha256(), taskRunner.modelReady(), vectorStore.collectionName(), qdrantPoints,
                approvedImageCount, approvedImageCount == qdrantPoints);
        } catch (Exception exception) {
            return new ImageDtos.HealthView("DOWN", SscdFeatureExtractor.FEATURE_VERSION,
                null, taskRunner.modelReady(), vectorStore.collectionName(), -1, -1, false);
        }
    }

    public ImageDtos.VectorRepairView repairVectorIndex() {
        // 单业务实例：避免修复误删异步入库正在提交的 Point。
        synchronized (vectorStore) {
        List<ImageAsset> approved = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getStatus, ImageStatus.APPROVED.name())
            .isNotNull(ImageAsset::getGlobalVector)
            .isNotNull(ImageAsset::getCenterVector));
        Set<Long> databaseIds = new HashSet<>();
        for (ImageAsset asset : approved) {
            databaseIds.add(asset.getId());
        }
        Set<Long> qdrantIds = new HashSet<>(vectorStore.allPointIds());
        long qdrantPointsBefore = qdrantIds.size();
        int restored = 0;
        for (ImageAsset asset : approved) {
            if (!qdrantIds.contains(asset.getId())) {
                vectorStore.upsert(asset.getId(), asset.getBatchId(),
                    SscdFeatureExtractor.decode(asset.getGlobalVector()),
                    SscdFeatureExtractor.decode(asset.getCenterVector()));
                restored++;
            }
        }
        qdrantIds.removeAll(databaseIds);
        if (!qdrantIds.isEmpty()) {
            vectorStore.delete(List.copyOf(qdrantIds));
        }
        return new ImageDtos.VectorRepairView(approved.size(), qdrantPointsBefore, restored, qdrantIds.size());
        }
    }

    public int backfillMissingAspectRatios() {
        int updated = 0;
        List<String> supportedValues = new ArrayList<>(ImageAspectRatio.supportedValues());
        supportedValues.add("其他");
        while (true) {
            List<ImageAsset> assets = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery()
                .and(query -> query.isNull(ImageAsset::getAspectRatio)
                    .or()
                    .notIn(ImageAsset::getAspectRatio, supportedValues))
                .last("LIMIT 500"));
            if (assets.isEmpty()) {
                return updated;
            }
            for (ImageAsset asset : assets) {
                asset.setAspectRatio(ImageAspectRatio.format(asset.getWidth(), asset.getHeight()));
                assetMapper.updateById(asset);
                updated++;
            }
        }
    }

    public List<String> listAspectRatios() {
        Long currentUserId = requireCurrentUserId();
        return assetMapper.selectApprovedAspectRatios(currentUserId, LoginHelper.isSuperAdmin(currentUserId));
    }

    public List<ImageDtos.CategoryView> categoryOptions() {
        return categoryMapper.selectList(Wrappers.<ImageCategory>lambdaQuery()
                .eq(ImageCategory::getStatus, "0")
                .orderByAsc(ImageCategory::getSortOrder, ImageCategory::getId))
            .stream().map(this::toCategoryView).toList();
    }

    public List<ImageDtos.CategoryView> listCategories() {
        return categoryMapper.selectList(Wrappers.<ImageCategory>lambdaQuery()
                .orderByAsc(ImageCategory::getSortOrder, ImageCategory::getId))
            .stream().map(this::toCategoryView).toList();
    }

    public ImageDtos.CategoryView createCategory(ImageDtos.SaveCategoryRequest request) {
        String name = request.name().trim();
        ensureCategoryNameAvailable(name, null);
        ImageCategory category = new ImageCategory();
        category.setName(name);
        category.setSortOrder(request.sortOrder());
        category.setStatus(request.status());
        categoryMapper.insert(category);
        return toCategoryView(category);
    }

    public ImageDtos.CategoryView updateCategory(long id, ImageDtos.SaveCategoryRequest request) {
        ImageCategory category = requireCategory(id);
        String name = request.name().trim();
        ensureCategoryNameAvailable(name, id);
        category.setName(name);
        category.setSortOrder(request.sortOrder());
        category.setStatus(request.status());
        categoryMapper.updateById(category);
        return toCategoryView(category);
    }

    public void deleteCategory(long id) {
        requireCategory(id);
        boolean used = batchMapper.selectCount(Wrappers.<ImageBatch>lambdaQuery()
            .eq(ImageBatch::getCategoryId, id)) > 0
            || assetMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getCategoryId, id)) > 0;
        if (used) {
            throw new ServiceException("类目已被图片使用，不能删除，可改为停用");
        }
        categoryMapper.deleteById(id);
    }

    private ImageBatch requireBatch(long id) {
        ImageBatch batch = batchMapper.selectById(id);
        if (batch == null || !canAccess(batch.getCreateBy())) {
            throw new ServiceException("图片批次不存在");
        }
        return batch;
    }

    private ImageCategory requireCategory(long id) {
        ImageCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new ServiceException("图片类目不存在");
        }
        return category;
    }

    private void ensureCategoryNameAvailable(String name, Long excludedId) {
        long count = categoryMapper.selectCount(Wrappers.<ImageCategory>lambdaQuery()
            .eq(ImageCategory::getName, name)
            .ne(excludedId != null, ImageCategory::getId, excludedId));
        if (count > 0) {
            throw new ServiceException("类目名称已存在");
        }
    }

    private ImageAsset requireAsset(long id) {
        ImageAsset asset = assetMapper.selectById(id);
        if (asset == null || !canAccess(asset.getCreateBy())) {
            throw new ServiceException("图片不存在");
        }
        return asset;
    }

    private ImageDtos.BatchView toBatchView(ImageBatch batch, List<ImageAsset> assets) {
        return new ImageDtos.BatchView(batch.getId(), batch.getCategoryId(),
            categoryName(batch.getCategoryId()), batch.getStatus(),
            batch.getSimilarityThreshold(), batch.getTotalCount(), batch.getProcessedCount(),
            uniqueCount(batch), batch.getDuplicateCount(), batch.getFailedCount(), batch.getErrorMessage(),
            batch.getCreateTime(), toImageViews(assets, false));
    }

    private ImageDtos.BatchSummary toBatchSummary(ImageBatch batch, String creatorName) {
        return new ImageDtos.BatchSummary(batch.getId(), StringUtils.defaultIfBlank(creatorName, "未知用户"), batch.getCategoryId(),
            categoryName(batch.getCategoryId()), batch.getStatus(),
            batch.getSimilarityThreshold(), batch.getTotalCount(), batch.getProcessedCount(), uniqueCount(batch),
            batch.getDuplicateCount(), batch.getFailedCount(), batch.getErrorMessage(), batch.getCreateTime());
    }

    private static int uniqueCount(ImageBatch batch) {
        return Math.max(0, batch.getProcessedCount() - batch.getDuplicateCount() - batch.getFailedCount());
    }

    private List<ImageDtos.ImageView> toImageViews(List<ImageAsset> assets, boolean checkVectorIndex) {
        Map<Long, String> categoryNames = new HashMap<>();
        Set<Long> categoryIds = new HashSet<>();
        for (ImageAsset asset : assets) {
            if (asset.getCategoryId() != null) {
                categoryIds.add(asset.getCategoryId());
            }
        }
        if (!categoryIds.isEmpty()) {
            for (ImageCategory category : categoryMapper.selectByIds(categoryIds)) {
                categoryNames.put(category.getId(), category.getName());
            }
        }
        Set<Long> duplicateIds = new HashSet<>();
        for (ImageAsset asset : assets) {
            if (asset.getDuplicateOf() != null) {
                duplicateIds.add(asset.getDuplicateOf());
            }
        }
        Map<Long, ImageAsset> duplicateMap = new HashMap<>();
        if (!duplicateIds.isEmpty()) {
            for (ImageAsset duplicate : assetMapper.selectByIds(duplicateIds)) {
                if (canAccess(duplicate.getCreateBy())) {
                    duplicateMap.put(duplicate.getId(), duplicate);
                }
            }
        }
        Set<Long> indexedIds = Set.of();
        if (checkVectorIndex) {
            List<Long> approvedIds = assets.stream()
                .filter(asset -> ImageStatus.APPROVED.name().equals(asset.getStatus()))
                .map(ImageAsset::getId)
                .toList();
            if (!approvedIds.isEmpty()) {
                try {
                    indexedIds = vectorStore.existingPointIds(approvedIds);
                } catch (RuntimeException exception) {
                    log.warn("检查当前页图片向量索引失败，将标记为异常 imageIds={}", approvedIds, exception);
                }
            }
        }
        Set<Long> finalIndexedIds = indexedIds;
        return assets.stream().map(asset -> {
            ImageAsset duplicate = duplicateMap.get(asset.getDuplicateOf());
            return new ImageDtos.ImageView(
                asset.getId(), asset.getBatchId(), asset.getCategoryId(),
                categoryNames.getOrDefault(asset.getCategoryId(), "未分类"),
                asset.getOriginalName(), asset.getDisplayName(),
                asset.getContentType(), asset.getFileSize(), asset.getWidth(), asset.getHeight(), asset.getAspectRatio(),
                asset.getStatus(), asset.getDuplicateFlag(),
                duplicate == null ? null : duplicate.getId(), duplicate == null ? null : duplicate.getDisplayName(),
                duplicate == null ? null : contentUrl(duplicate.getId(), true),
                duplicate == null ? null : Objects.equals(asset.getBatchId(), duplicate.getBatchId()) ? "BATCH" : "LIBRARY",
                asset.getSimilarity(),
                asset.getMatchMethod(), contentUrl(asset.getId(), true), contentUrl(asset.getId(), false),
                ImageStatus.APPROVED.name().equals(asset.getStatus()) && !finalIndexedIds.contains(asset.getId()),
                asset.getErrorMessage(), asset.getCreateTime());
        }).toList();
    }

    private String categoryName(Long categoryId) {
        if (categoryId == null) {
            return "未分类";
        }
        ImageCategory category = categoryMapper.selectById(categoryId);
        return category == null ? "未分类" : category.getName();
    }

    private ImageDtos.CategoryView toCategoryView(ImageCategory category) {
        return new ImageDtos.CategoryView(category.getId(), category.getName(),
            category.getSortOrder(), category.getStatus());
    }

    private static String contentUrl(long imageId, boolean thumbnail) {
        return "/images/" + imageId + "/content?thumbnail=" + thumbnail;
    }

    private static Long requireCurrentUserId() {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            throw new ServiceException("登录状态无效，请重新登录");
        }
        return userId;
    }

    private static boolean canAccess(Long ownerUserId) {
        Long currentUserId = requireCurrentUserId();
        return LoginHelper.isSuperAdmin(currentUserId) || Objects.equals(currentUserId, ownerUserId);
    }

    public record ImageContent(Path path, String contentType, String filename) {
    }

}
