package org.dromara.image.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.excel.utils.ExcelBuilder;
import org.dromara.common.json.utils.JsonUtils;
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
import org.dromara.image.domain.ImageSimilarityMatch;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.mapper.ImageCategoryMapper;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.ImageStorage;
import javax.imageio.ImageIO;
import org.dromara.system.api.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    private final InfringementService infringementService;
    private final org.dromara.image.mapper.ImageRecordMapper records;

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
        batch.setInfringementMode(requireInfringementMode(request.infringementMode()));
        if (!LoginHelper.isAdmin() && request.similarityThreshold() != null
            && request.similarityThreshold().compareTo(properties.getSimilarityThreshold()) != 0) {
            throw new ServiceException("只有管理员可以修改重复阈值");
        }
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

    public ImageDtos.BatchSummary getBatchSummary(long batchId) {
        ImageBatch batch = requireBatch(batchId);
        Map<Long, String> creatorNames = batch.getCreateBy() == null
            ? Map.of() : userNames(Set.of(batch.getCreateBy()));
        return toBatchSummary(batch, creatorNames.get(batch.getCreateBy()));
    }

    public PageResult<ImageDtos.ImageView> listReviewImages(long batchId, PageQuery pageQuery) {
        requireBatch(batchId);
        Page<ImageAsset> requestedPage = pageQuery.build();
        Page<ImageAsset> page = assetMapper.selectPage(
            new Page<>(Math.max(1, requestedPage.getCurrent()), Math.min(20, Math.max(1, requestedPage.getSize()))),
            Wrappers.<ImageAsset>lambdaQuery()
                .eq(ImageAsset::getBatchId, batchId)
                .eq(ImageAsset::getStatus, ImageStatus.WAIT_REVIEW.name())
                .orderByAsc(ImageAsset::getId));
        return PageResult.build(toImageViews(page.getRecords(), false), page.getTotal());
    }

    public PageResult<ImageDtos.ImageView> listFailedImages(long batchId, PageQuery pageQuery) {
        requireBatch(batchId);
        Page<ImageAsset> requestedPage = pageQuery.build();
        Page<ImageAsset> page = assetMapper.selectPage(
            new Page<>(Math.max(1, requestedPage.getCurrent()), Math.min(20, Math.max(1, requestedPage.getSize()))),
            Wrappers.<ImageAsset>lambdaQuery()
                .eq(ImageAsset::getBatchId, batchId)
                .in(ImageAsset::getStatus, ImageStatus.FAILED.name(), ImageStatus.APPROVAL_FAILED.name())
                .orderByAsc(ImageAsset::getId));
        return PageResult.build(toImageViews(page.getRecords(), false), page.getTotal());
    }

    public PageResult<ImageDtos.BatchSummary> listBatches(PageQuery pageQuery, String status) {
        return listBatches(pageQuery, status, null);
    }

    public PageResult<ImageDtos.BatchSummary> listBatches(PageQuery pageQuery, String status, String userName) {
        Long currentUserId = requireCurrentUserId();
        LambdaQueryWrapper<ImageBatch> wrapper = Wrappers.<ImageBatch>lambdaQuery()
            .eq(!LoginHelper.isAdmin(), ImageBatch::getCreateBy, currentUserId)
            .apply(StringUtils.isNotBlank(userName), "create_by IN (SELECT user_id FROM sys_user WHERE user_name = {0} AND del_flag = '0')", userName)
            .eq(StringUtils.isNotBlank(status), ImageBatch::getStatus, status)
            .orderByDesc(ImageBatch::getCreateTime);
        Page<ImageBatch> page = batchMapper.selectPage(pageQuery.build(),
            wrapper);
        Map<Long, String> creatorNames = userNames(page.getRecords().stream()
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
        batch.setDetectionCompletedAt(null);
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
            .filter(this::isAdmissionProhibited)
            .map(ImageAsset::getId)
            .toList();
        if (!prohibitedIds.isEmpty()) {
            String ids = String.join(",", prohibitedIds.stream().map(String::valueOf).toList());
            throw new ServiceException("相似度超过95%的图片不可入库：" + ids);
        }

        for (ImageAsset asset : assets) {
            if (!ImageStatus.WAIT_REVIEW.name().equals(asset.getStatus())) continue;
            asset.setManuallyApproved(selected.contains(asset.getId()));
            asset.setStatus(selected.contains(asset.getId())
                ? ImageStatus.APPROVING.name() : ImageStatus.DELETE_PENDING.name());
            if (!selected.contains(asset.getId())) asset.setDeletedAt(LocalDateTime.now());
            if (assetMapper.updateById(asset) != 1) throw new ServiceException("审核决定保存失败");
        }
        batch.setStatus(BatchStatus.CONFIRMING.name());
        batch.setErrorMessage(null);
        batchMapper.updateById(batch);
    }

    private boolean isAdmissionProhibited(ImageAsset asset) {
        return "EXACT_HASH".equals(asset.getMatchMethod())
            || asset.getSimilarity() != null
            && asset.getSimilarity().compareTo(MAX_ADMISSION_SIMILARITY) > 0;
    }

    public PageResult<ImageDtos.ImageView> listApproved(ImageDtos.ImageQuery query, PageQuery pageQuery) {
        requireCurrentUserId();
        Page<ImageAsset> page = assetMapper.selectPage(pageQuery.build(), approvedQuery(query));
        List<ImageDtos.ImageView> rows = toImageViews(page.getRecords(), true);
        return PageResult.build(rows, page.getTotal());
    }

    public void exportApproved(ImageDtos.ImageQuery query, HttpServletResponse response) {
        long userId = requireCurrentUserId();
        boolean admin = LoginHelper.isAdmin();
        var selection = approvedQuery(query);
        if (!admin) {
            selection.apply("infringement_scan_item_id IN (SELECT id FROM infringement_scan_item WHERE status = 'SUCCESS' AND (risk_level = 'LOW' OR (prompt_version = 'relay_image_analysis_v1' AND risk_level IS NULL AND image_description IS NOT NULL)))")
                .apply("NOT EXISTS (SELECT 1 FROM img_image_export e WHERE e.image_id = img_asset.id AND e.user_id = {0})", userId);
        }
        List<ImageAsset> candidates = assetMapper.selectList(selection.last("LIMIT 1001"));
        if (candidates.size() > 1000) throw new ServiceException("单次最多导出1000张图片，请缩小筛选范围");
        String requestId = java.util.UUID.randomUUID().toString();
        List<ImageAsset> selected = new ArrayList<>();
        Path archive = null;
        try {
            for (ImageAsset image : candidates) {
                if (admin || records.reserveExport(userId, image.getId(), requestId) == 1) selected.add(image);
            }
            if (selected.isEmpty()) throw new ServiceException("没有可导出的图片：普通用户仅可导出分析完成或历史低风险且未导出过的本人图片");
            Map<Long, String> categoryNames = categoryNames(selected);
            Map<Long, InfringementScanItem> riskResults = infringementService.currentResults(selected.stream()
                .map(ImageAsset::getInfringementScanItemId).filter(Objects::nonNull).distinct().toList());
            // 完整生成压缩包后再响应；生成或传输失败会释放本次预占记录。
            archive = Files.createTempFile("image-library-export-", ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                List<ImageDtos.LibraryExportRow> rows = selected.stream().map(image -> exportRow(image,
                    image.getCategoryId() == null ? "未分类" : categoryNames.getOrDefault(image.getCategoryId(), "未分类"),
                    image.getInfringementScanItemId() == null ? null : riskResults.get(image.getInfringementScanItemId()))).toList();
                ByteArrayOutputStream manifest = new ByteArrayOutputStream();
                ExcelBuilder.of(rows, ImageDtos.LibraryExportRow.class).sheetName("图片清单").toStream(manifest);
                zip.putNextEntry(new ZipEntry("图片清单.xlsx")); zip.write(manifest.toByteArray()); zip.closeEntry();
                for (ImageAsset image : selected) {
                    zip.putNextEntry(new ZipEntry(exportZipPath(image)));
                    try (ImageStorage.LocalFile file = storage.open(image.getStorageKey()); InputStream input = Files.newInputStream(file.path())) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=library_" + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now()) + ".zip");
            response.setContentLengthLong(Files.size(archive));
            try (InputStream input = Files.newInputStream(archive)) { input.transferTo(response.getOutputStream()); }
            response.getOutputStream().flush();
            if (!admin) records.completeExport(requestId);
        } catch (IOException | RuntimeException exception) {
            if (!admin) records.releaseExport(requestId);
            if (exception instanceof ServiceException serviceException) throw serviceException;
            throw new ServiceException("图片导出失败：" + exception.getMessage(), 500);
        } finally {
            if (archive != null) {
                try { Files.deleteIfExists(archive); } catch (IOException cleanup) { log.warn("清理导出临时文件失败", cleanup); }
            }
        }
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
        return storage.accessUrl(asset.getStorageKey(), asset.getContentType(), thumbnail)
            .orElse(contentUrl(imageId, thumbnail));
    }

    public byte[] preview(long imageId) {
        accessUrl(imageId, false);
        ImageAsset asset = requireAsset(imageId);
        try (ImageStorage.LocalFile file = storage.open(asset.getStorageKey());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(SscdFeatureExtractor.readOriented(file.path(), 640), "jpeg", output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ServiceException("图片预览生成失败: " + exception.getMessage(), 500);
        }
    }

    public void delete(long imageId) {
        requireAdmin();
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
                .gt(ImageAsset::getWidth, 0).gt(ImageAsset::getHeight, 0)
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
        return assetMapper.selectApprovedAspectRatios(currentUserId, LoginHelper.isAdmin());
    }

    public List<ImageDtos.CategoryView> categoryOptions() {
        return categoryMapper.selectList(Wrappers.<ImageCategory>lambdaQuery()
                .eq(ImageCategory::getStatus, "0")
                .orderByAsc(ImageCategory::getId))
            .stream().map(this::toCategoryView).toList();
    }

    public List<ImageDtos.CategoryView> listCategories() {
        requireAdmin();
        return categoryMapper.selectList(Wrappers.<ImageCategory>lambdaQuery()
                .orderByAsc(ImageCategory::getId))
            .stream().map(this::toCategoryView).toList();
    }

    public ImageDtos.CategoryView createCategory(ImageDtos.SaveCategoryRequest request) {
        requireAdmin();
        String name = request.name().trim();
        ensureCategoryNameAvailable(name, null);
        ImageCategory category = new ImageCategory();
        category.setName(name);
        category.setSortOrder(0);
        category.setStatus(request.status());
        categoryMapper.insert(category);
        return toCategoryView(category);
    }

    public ImageDtos.CategoryView updateCategory(long id, ImageDtos.SaveCategoryRequest request) {
        requireAdmin();
        ImageCategory category = requireCategory(id);
        String name = request.name().trim();
        ensureCategoryNameAvailable(name, id);
        category.setName(name);
        category.setStatus(request.status());
        categoryMapper.updateById(category);
        return toCategoryView(category);
    }

    public void deleteCategory(long id) {
        requireAdmin();
        requireCategory(id);
        boolean used = assetMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery()
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
        if (asset == null || !canAccess(asset.getCreateBy()) || "REPLACED".equals(asset.getStatus())) {
            throw new ServiceException("图片不存在");
        }
        return asset;
    }

    private ImageDtos.BatchView toBatchView(ImageBatch batch, List<ImageAsset> assets) {
        return new ImageDtos.BatchView(batch.getId(), batch.getCategoryId(),
            categoryName(batch.getCategoryId()), batch.getStatus(),
            batch.getInfringementMode(),
            batch.getSimilarityThreshold(), batch.getTotalCount(), batch.getProcessedCount(),
            uniqueCount(batch), batch.getDuplicateCount(), batch.getFailedCount(), batch.getDetectionCompletedAt(), batch.getErrorMessage(),
            batch.getCreateTime(), toImageViews(assets, false));
    }

    private ImageDtos.BatchSummary toBatchSummary(ImageBatch batch, String creatorName) {
        return new ImageDtos.BatchSummary(batch.getId(), StringUtils.defaultIfBlank(creatorName, "未知用户"), batch.getCategoryId(),
            categoryName(batch.getCategoryId()), batch.getStatus(),
            batch.getInfringementMode(),
            batch.getSimilarityThreshold(), batch.getTotalCount(), batch.getProcessedCount(), uniqueCount(batch),
            batch.getDuplicateCount(), batch.getFailedCount(), batch.getDetectionCompletedAt(), batch.getErrorMessage(), batch.getCreateTime());
    }

    private static String requireInfringementMode(String mode) {
        if ("RELAY".equals(mode)) return mode;
        throw new ServiceException("AI 分析渠道只支持中转站");
    }

    private LambdaQueryWrapper<ImageAsset> approvedQuery(ImageDtos.ImageQuery query) {
        Long userId = requireCurrentUserId();
        if (query.getUploadStart() != null && query.getUploadEnd() != null && !query.getUploadStart().isBefore(query.getUploadEnd())) {
            throw new ServiceException("上传开始时间必须早于结束时间");
        }
        return Wrappers.<ImageAsset>lambdaQuery()
            .eq(ImageAsset::getStatus, ImageStatus.APPROVED.name())
            .eq(!LoginHelper.isAdmin(), ImageAsset::getCreateBy, userId)
            .eq(query.getImageId() != null, ImageAsset::getId, query.getImageId())
            .apply(StringUtils.isNotBlank(query.getUserName()), "create_by IN (SELECT user_id FROM sys_user WHERE user_name = {0} AND del_flag = '0')", query.getUserName())
            .apply(StringUtils.isNotBlank(query.getRiskLevel()), "infringement_scan_item_id IN (SELECT id FROM infringement_scan_item WHERE status = 'SUCCESS' AND risk_level = {0})", query.getRiskLevel())
            .ge(query.getUploadStart() != null, ImageAsset::getCreateTime, query.getUploadStart())
            .lt(query.getUploadEnd() != null, ImageAsset::getCreateTime, query.getUploadEnd())
            .eq(query.getCategoryId() != null, ImageAsset::getCategoryId, query.getCategoryId())
            .eq(query.getBatchId() != null, ImageAsset::getBatchId, query.getBatchId())
            .eq(StringUtils.isNotBlank(query.getAspectRatio()), ImageAsset::getAspectRatio, query.getAspectRatio())
            .and(StringUtils.isNotBlank(query.getKeyword()), nested -> nested
                .like(ImageAsset::getOriginalName, query.getKeyword())
                .or()
                .like(ImageAsset::getDisplayName, query.getKeyword()))
            .orderByDesc(ImageAsset::getApprovedAt, ImageAsset::getId);
    }

    private Map<Long, String> categoryNames(List<ImageAsset> assets) {
        Set<Long> categoryIds = assets.stream().map(ImageAsset::getCategoryId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (categoryIds.isEmpty()) return Map.of();
        Map<Long, String> names = new HashMap<>();
        categoryMapper.selectByIds(categoryIds).forEach(category -> names.put(category.getId(), category.getName()));
        return names;
    }

    private static ImageDtos.LibraryExportRow exportRow(ImageAsset asset, String categoryName, InfringementScanItem result) {
        ImageDtos.LibraryExportRow row = new ImageDtos.LibraryExportRow();
        row.setImageId(asset.getId());
        row.setZipPath(exportZipPath(asset));
        row.setOriginalName(asset.getOriginalName());
        row.setCategoryName(categoryName);
        row.setBatchId(asset.getBatchId());
        row.setDimensions(asset.getWidth() == null || asset.getHeight() == null ? "" : asset.getWidth() + "x" + asset.getHeight());
        row.setAspectRatio(asset.getAspectRatio());
        row.setApprovedAt(asset.getApprovedAt());
        row.setInfringementStatus(result == null ? "未检测" : result.getStatus());
        row.setRiskLevel(result == null ? null : result.getRiskLevel());
        row.setRiskReason(result == null ? null : result.getRiskReason());
        row.setDescriptionKeywords(result == null ? null : result.getImageDescription());
        row.setInfringementError(result == null ? null : result.getErrorMessage());
        return row;
    }

    private static String exportZipPath(ImageAsset asset) {
        String original = StringUtils.defaultIfBlank(asset.getOriginalName(), "image").replace('\\', '/');
        original = original.substring(original.lastIndexOf('/') + 1).replaceAll("[\\r\\n]", "_");
        return "images/" + asset.getBatchId() + "/" + asset.getId() + "_" + original;
    }

    private static int uniqueCount(ImageBatch batch) {
        return Math.max(0, batch.getProcessedCount() - batch.getDuplicateCount() - batch.getFailedCount());
    }

    private List<ImageDtos.ImageView> toImageViews(List<ImageAsset> assets, boolean checkVectorIndex) {
        Map<Long, String> uploaders = userNames(assets.stream().map(ImageAsset::getCreateBy).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        Set<Long> exported = assets.isEmpty() ? Set.of() : new HashSet<>(records.exportedIds(requireCurrentUserId(), assets.stream().map(ImageAsset::getId).toList()));
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
        Map<Long, List<ImageSimilarityMatch>> similaritySnapshots = new HashMap<>();
        Set<Long> similarityImageIds = new HashSet<>();
        for (ImageAsset asset : assets) {
            if (StringUtils.isBlank(asset.getSimilarMatchesJson())) continue;
            try {
                List<ImageSimilarityMatch> matches = JsonUtils.parseObject(asset.getSimilarMatchesJson(),
                    new TypeReference<List<ImageSimilarityMatch>>() { });
                if (matches == null) continue;
                similaritySnapshots.put(asset.getId(), matches);
                matches.forEach(match -> similarityImageIds.add(match.imageId()));
            } catch (RuntimeException exception) {
                log.warn("解析相似图片快照失败 imageId={}", asset.getId(), exception);
            }
        }
        Map<Long, ImageAsset> similarityImageMap = new HashMap<>();
        if (!similarityImageIds.isEmpty()) {
            for (ImageAsset similar : assetMapper.selectByIds(similarityImageIds)) {
                if (canAccess(similar.getCreateBy()) && !"REPLACED".equals(similar.getStatus())) {
                    similarityImageMap.put(similar.getId(), similar);
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
        List<Long> infringementItemIds = assets.stream()
            .map(ImageAsset::getInfringementScanItemId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, InfringementScanItem> infringementResults = infringementService.currentResults(infringementItemIds);
        return assets.stream().map(asset -> {
            ImageAsset duplicate = duplicateMap.get(asset.getDuplicateOf());
            InfringementScanItem infringement = asset.getInfringementScanItemId() == null
                ? null
                : infringementResults.get(asset.getInfringementScanItemId());
            var tags = InfringementService.imageVisualTags(infringement == null ? null : infringement.getRawAiResponse());
            boolean detectionSucceeded = infringement != null && InfringementService.SUCCESS.equals(infringement.getStatus());
            List<ImageDtos.SimilarImageView> similarImages = similaritySnapshots
                .getOrDefault(asset.getId(), List.of()).stream()
                .map(match -> {
                    ImageAsset similar = similarityImageMap.get(match.imageId());
                    return similar == null ? null : new ImageDtos.SimilarImageView(
                        similar.getId(), similar.getDisplayName(), contentUrl(similar.getId(), true),
                        match.source(), match.similarity());
                })
                .filter(Objects::nonNull)
                .toList();
            return new ImageDtos.ImageView(
                asset.getId(), asset.getBatchId(), asset.getCategoryId(),
                categoryNames.getOrDefault(asset.getCategoryId(), "未分类"),
                asset.getOriginalName(), asset.getDisplayName(), InfringementService.aiListingKeywords(infringement),
                asset.getContentType(), asset.getFileSize(), asset.getWidth(), asset.getHeight(), asset.getAspectRatio(),
                asset.getStatus(), asset.getDuplicateFlag(),
                duplicate == null ? null : duplicate.getId(), duplicate == null ? null : duplicate.getDisplayName(),
                duplicate == null ? null : contentUrl(duplicate.getId(), true),
                duplicate == null ? null : Objects.equals(asset.getBatchId(), duplicate.getBatchId()) ? "BATCH" : "LIBRARY",
                asset.getSimilarity(),
                similarImages,
                asset.getMatchMethod(), contentUrl(asset.getId(), true), contentUrl(asset.getId(), false),
                ImageStatus.APPROVED.name().equals(asset.getStatus()) && !finalIndexedIds.contains(asset.getId()),
                asset.getErrorMessage(), asset.getApprovedAt(),
                infringement == null ? null : infringement.getStatus(),
                infringement == null ? null : infringement.getPromptVersion(),
                detectionSucceeded ? infringement.getRiskLevel() : null,
                detectionSucceeded ? infringement.getRiskReason() : null,
                detectionSucceeded ? InfringementRiskRules.cases(infringement.getCaseNumbersJson()) : List.of(),
                infringement == null ? null : infringement.getErrorMessage(),
                asset.getRenderUseCount(),
                asset.getCreateTime(), uploaders.getOrDefault(asset.getCreateBy(), "未知用户"),
                tags.subjects(),
                tags.scenes(),
                tags.themes(),
                tags.visualStyles(),
                tags.colors(), exported.contains(asset.getId()));
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
        return LoginHelper.isAdmin() || Objects.equals(currentUserId, ownerUserId);
    }

    private Map<Long, String> userNames(java.util.Collection<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        if (ids.isEmpty()) return names;
        userService.selectListByIds(ids).forEach(user -> names.put(user.getUserId(), user.getUserName()));
        return names;
    }

    private static void requireAdmin() {
        if (!LoginHelper.isAdmin()) throw new ServiceException("只有管理员可以操作");
    }

    @Transactional(rollbackFor = Exception.class)
    public ImageDtos.BatchView replace(long oldId, MultipartFile file) {
        requireAdmin();
        ImageAsset old = assetMapper.lockAsset(oldId);
        if (old == null || !"APPROVED".equals(old.getStatus())) throw new ServiceException("只能替换正式图库中的图片");
        if (assetMapper.selectCount(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getReplacesImageId, oldId)
            .in(ImageAsset::getStatus, "UPLOADING", "UPLOADED", "CHECKING", "WAIT_REVIEW", "APPROVING", "APPROVAL_FAILED", "FAILED")) > 0) {
            throw new ServiceException("这张图片已有替换任务，请先处理该任务");
        }
        ImageDtos.BatchView batch = createBatch(new ImageDtos.CreateBatchRequest(properties.getSimilarityThreshold(), old.getCategoryId(), "RELAY"));
        upload(batch.id(), List.of(file));
        ImageAsset replacement = assetMapper.selectList(Wrappers.<ImageAsset>lambdaQuery().eq(ImageAsset::getBatchId, batch.id())).getFirst();
        assetMapper.update(null, Wrappers.<ImageAsset>lambdaUpdate().eq(ImageAsset::getId, replacement.getId()).set(ImageAsset::getReplacesImageId, oldId));
        start(batch.id());
        return getBatch(batch.id());
    }

    public List<org.dromara.image.domain.ImageRecords.ReplacementLog> replacementLogs(String userName) {
        requireAdmin();
        return records.replacements(userName);
    }

    public List<org.dromara.image.domain.ImageRecords.DuplicateTodo> duplicateTodos(String userName) {
        requireAdmin();
        return records.todos(userName);
    }

    public void completeDuplicateTodo(long id) {
        requireAdmin();
        if (records.completeTodo(id, requireCurrentUserId()) != 1) throw new ServiceException("待办不存在或已处理");
    }

    public record ImageContent(Path path, String contentType, String filename) {
    }

}
