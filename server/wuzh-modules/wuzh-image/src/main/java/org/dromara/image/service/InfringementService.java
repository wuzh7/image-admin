package org.dromara.image.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageBatch;
import org.dromara.image.domain.InfringementDtos;
import org.dromara.image.domain.InfringementScanItem;
import org.dromara.image.domain.InfringementScanTask;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.mapper.InfringementCallLogMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.mapper.InfringementScanTaskMapper;
import org.dromara.system.api.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class InfringementService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String PENDING = "PENDING";
    static final String PROCESSING = "PROCESSING";
    static final String SUCCESS = "SUCCESS";
    static final String FAILED = "FAILED";

    private final InfringementScanTaskMapper tasks;
    private final InfringementScanItemMapper items;
    private final InfringementCallLogMapper callLogs;
    private final ImageAssetMapper assets;
    private final ImageBatchMapper batches;
    private final UserService userService;

    @Transactional(rollbackFor = Exception.class)
    public Long createInitialTask(ImageBatch batch, List<ImageAsset> selected) {
        if (selected.isEmpty()) return null;
        InfringementScanTask task = newTask("INITIAL", batch.getId(), batch.getCreateBy());
        String executionMode = executionMode(batch);
        for (ImageAsset image : selected) {
            InfringementScanItem item = newItem(task.getId(), image, executionMode);
            items.insert(item);
            int updated = assets.update(null, Wrappers.<ImageAsset>lambdaUpdate()
                .eq(ImageAsset::getId, image.getId())
                .eq(ImageAsset::getStatus, "APPROVED")
                .set(ImageAsset::getInfringementScanItemId, item.getId()));
            if (updated != 1) throw new ServiceException("侵权检测任务关联失败");
            image.setInfringementScanItemId(item.getId());
        }
        return task.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public InfringementDtos.RetryResult retryFailedTask(long sourceTaskId) {
        InfringementScanTask source = requireTask(sourceTaskId);
        long ownerId = LoginHelper.getUserId();
        List<ImageAsset> failed = items.selectCurrentFailedImagesByTask(sourceTaskId, source.getCreateBy());
        if (failed.isEmpty()) return new InfringementDtos.RetryResult(null, 0);
        InfringementScanTask task = newTask("RETRY_FAILED", source.getSourceBatchId(), ownerId);
        String executionMode = executionMode(source.getSourceBatchId());
        int created = 0;
        for (ImageAsset image : failed) {
            Long previousItemId = image.getInfringementScanItemId();
            InfringementScanItem item = newItem(task.getId(), image, executionMode);
            items.insert(item);
            int updated = assets.update(null, Wrappers.<ImageAsset>lambdaUpdate()
                .eq(ImageAsset::getId, image.getId())
                .eq(ImageAsset::getStatus, "APPROVED")
                .eq(ImageAsset::getInfringementScanItemId, previousItemId)
                .set(ImageAsset::getInfringementScanItemId, item.getId()));
            if (updated == 1) created++;
            else items.deleteById(item.getId());
        }
        if (created == 0) {
            tasks.deleteById(task.getId());
            return new InfringementDtos.RetryResult(null, 0);
        }
        return new InfringementDtos.RetryResult(task.getId(), created);
    }

    @Transactional(rollbackFor = Exception.class)
    public InfringementDtos.RetryResult rescanImage(long imageId) {
        if (!LoginHelper.isAdmin()) throw new ServiceException("只有管理员可以重新检测图片");
        ImageAsset image = assets.selectById(imageId);
        if (image == null || !"APPROVED".equals(image.getStatus())) throw new ServiceException("仅正式图库图片可以重新检测");

        Long previousItemId = image.getInfringementScanItemId();
        InfringementScanItem previous = previousItemId == null ? null : items.selectById(previousItemId);
        if (previous != null && SUCCESS.equals(previous.getStatus())) {
            return new InfringementDtos.RetryResult(null, 0);
        }
        InfringementScanTask task = newTask("MANUAL_RESCAN", image.getBatchId(), LoginHelper.getUserId());
        InfringementScanItem item = newItem(task.getId(), image, executionMode(image.getBatchId()));
        items.insert(item);
        int updated = assets.update(null, Wrappers.<ImageAsset>lambdaUpdate()
            .eq(ImageAsset::getId, imageId)
            .eq(previousItemId != null, ImageAsset::getInfringementScanItemId, previousItemId)
            .isNull(previousItemId == null, ImageAsset::getInfringementScanItemId)
            .set(ImageAsset::getInfringementScanItemId, item.getId()));
        if (updated != 1) {
            items.deleteById(item.getId());
            tasks.deleteById(task.getId());
            throw new ServiceException("检测记录已更新，请刷新后重试");
        }
        return new InfringementDtos.RetryResult(task.getId(), 1);
    }

    public PageResult<InfringementDtos.TaskSummary> listTasks(PageQuery pageQuery) {
        long userId = LoginHelper.getUserId();
        LambdaQueryWrapper<InfringementScanTask> query = Wrappers.<InfringementScanTask>lambdaQuery()
            .eq(!LoginHelper.isAdmin(), InfringementScanTask::getCreateBy, userId)
            .orderByDesc(InfringementScanTask::getCreateTime);
        Page<InfringementScanTask> page = tasks.selectPage(pageQuery.build(), query);
        List<Long> taskIds = page.getRecords().stream().map(InfringementScanTask::getId).toList();
        Map<Long, InfringementDtos.TaskStats> stats = new HashMap<>();
        if (!taskIds.isEmpty()) {
            items.selectTaskStats(taskIds).forEach(row -> stats.put(row.getTaskId(), row));
        }
        Map<Long, String> names = userService.selectUserNicksByIds(page.getRecords().stream()
            .map(InfringementScanTask::getCreateBy).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        Map<Long, String> modes = new HashMap<>();
        List<Long> batchIds = page.getRecords().stream().map(InfringementScanTask::getSourceBatchId)
            .filter(Objects::nonNull).distinct().toList();
        if (!batchIds.isEmpty()) {
            batches.selectByIds(batchIds).forEach(batch -> modes.put(batch.getId(), executionMode(batch)));
        }
        List<InfringementDtos.TaskSummary> rows = page.getRecords().stream()
            .map(task -> toSummary(task, names.get(task.getCreateBy()), modes.get(task.getSourceBatchId()), stats.get(task.getId())))
            .toList();
        return PageResult.build(rows, page.getTotal());
    }

    public PageResult<InfringementDtos.ItemView> listItems(long taskId, String status,
                                                            String riskLevel, PageQuery pageQuery) {
        InfringementScanTask task = requireTask(taskId);
        String fallbackExecutionMode = executionMode(task.getSourceBatchId());
        Page<InfringementDtos.ItemRow> page = items.selectItemPage(pageQuery.build(), taskId, status, riskLevel);
        List<InfringementDtos.ItemView> rows = page.getRecords().stream().map(row -> {
            VisualTags tags = visualTags(row.getRawAiResponse());
            return new InfringementDtos.ItemView(row.getId(), row.getImageId(), row.getImageName(),
                "/images/" + row.getImageId() + "/content?thumbnail=true",
                row.getImageStatus(), row.getStatus(), row.getRiskLevel(), row.getRiskReason(),
                tags.confidence(), tags.detectedElements(), row.getImageDescription(), row.getModel(), row.getPromptVersion(),
                StringUtils.defaultIfBlank(row.getExecutionMode(), fallbackExecutionMode),
                row.getSearchUsed(), row.getSearchQueryCount(),
                row.getAttemptCount() == null ? 0 : row.getAttemptCount(), row.getErrorMessage(), row.getFinishedAt(),
                tags.subjects(), tags.scenes(), tags.themes(), tags.visualStyles(), tags.colors(),
                InfringementRiskRules.cases(row.getCaseNumbersJson()));
        }).toList();
        return PageResult.build(rows, page.getTotal());
    }

    public PageResult<InfringementDtos.CallLogView> listCallLogs(long taskId, Long imageId, String status,
                                                                 String executionMode, PageQuery pageQuery) {
        requireTask(taskId);
        Page<InfringementDtos.CallLogRow> page = callLogs.selectPageByTask(pageQuery.build(), taskId, imageId,
            status, executionMode);
        List<InfringementDtos.CallLogView> rows = page.getRecords().stream().map(row ->
            new InfringementDtos.CallLogView(row.getId(), row.getTaskId(), row.getScanItemId(), row.getBatchId(),
                row.getImageId(), row.getImageName(), row.getRequestId(), row.getProviderJobId(),
                row.getProviderRequestIndex(), row.getExecutionMode(), row.getModel(), row.getStatus(),
                row.getRiskLevel(), row.getCallType(), row.getRawAiResponse(), row.getUsageMetadata(), row.getPromptTokenCount(),
                row.getCachedContentTokenCount(), row.getCandidatesTokenCount(), row.getThoughtsTokenCount(),
                row.getToolUsePromptTokenCount(), row.getTotalTokenCount(), row.getSearchUsed(),
                row.getSearchQueryCount(), row.getDurationMs(), row.getErrorCode(), row.getErrorMessage(),
                row.getStartedAt(), row.getFinishedAt())).toList();
        return PageResult.build(rows, page.getTotal());
    }

    public Map<Long, InfringementScanItem> currentResults(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) return Map.of();
        Map<Long, InfringementScanItem> result = new HashMap<>();
        for (InfringementScanItem item : items.selectByIds(itemIds)) {
            result.put(item.getId(), item);
        }
        return result;
    }

    public static String aiListingKeywords(InfringementScanItem result) {
        if (result == null || !SUCCESS.equals(result.getStatus())) return null;
        String keywords = StringUtils.trimToNull(result.getImageDescription());
        return keywords != null && keywords.matches("(?s).*[A-Za-z].*")
            && !keywords.matches("(?i).*\\.(jpg|jpeg|png|webp)$") ? keywords : null;
    }

    public InfringementDtos.CallResponseView callResponse(long taskId, long callId) {
        requireTask(taskId);
        InfringementDtos.CallResponseView result = callLogs.selectRawResponse(taskId, callId);
        if (result == null) throw new ServiceException("调用日志不存在");
        return result;
    }

    private InfringementScanTask newTask(String type, Long batchId, Long ownerId) {
        long id = IdWorker.getId();
        InfringementScanTask task = new InfringementScanTask();
        task.setId(id);
        task.setTaskNo("IR" + id);
        task.setTaskType(type);
        task.setSourceBatchId(batchId);
        task.setCreateBy(ownerId);
        task.setUpdateBy(ownerId);
        tasks.insert(task);
        return task;
    }

    private InfringementScanItem newItem(long taskId, ImageAsset image, String executionMode) {
        InfringementScanItem item = new InfringementScanItem();
        item.setId(IdWorker.getId());
        item.setTaskId(taskId);
        item.setImageId(image.getId());
        item.setStatus(PENDING);
        item.setCallType("RISK_SCAN");
        item.setExecutionMode(executionMode);
        item.setAttemptCount(0);
        item.setCreateBy(image.getCreateBy());
        item.setUpdateBy(image.getCreateBy());
        return item;
    }

    private InfringementScanTask requireTask(long taskId) {
        InfringementScanTask task = tasks.selectById(taskId);
        if (task == null) throw new ServiceException("侵权检测任务不存在");
        long userId = LoginHelper.getUserId();
        if (!LoginHelper.isAdmin() && !Objects.equals(task.getCreateBy(), userId)) {
            throw new ServiceException("无权查看该侵权检测任务");
        }
        return task;
    }

    private InfringementDtos.TaskSummary toSummary(InfringementScanTask task, String creator, String mode,
                                                    InfringementDtos.TaskStats stats) {
        long total = value(stats == null ? null : stats.getTotalCount());
        long success = value(stats == null ? null : stats.getSuccessCount());
        long processing = value(stats == null ? null : stats.getProcessingCount());
        long failed = value(stats == null ? null : stats.getFailedCount());
        long completed = success + failed;
        String status;
        if (completed == total && total > 0) status = failed == 0 ? "COMPLETED" : success == 0 ? "FAILED" : "PARTIAL_FAILED";
        else if (processing > 0 || completed > 0) status = "PROCESSING";
        else status = "PENDING";
        return new InfringementDtos.TaskSummary(task.getId(), task.getTaskNo(), task.getTaskType(),
            task.getSourceBatchId(), StringUtils.defaultIfBlank(creator, String.valueOf(task.getCreateBy())),
            StringUtils.defaultIfBlank(mode, "RELAY"), status,
            total, completed, value(stats == null ? null : stats.getHighRiskCount()),
            value(stats == null ? null : stats.getLowRiskCount()),
            value(stats == null ? null : stats.getNeedsReviewCount()), failed, task.getCreateTime());
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private String executionMode(Long batchId) {
        return executionMode(batchId == null ? null : batches.selectById(batchId));
    }

    static String executionMode(ImageBatch batch) {
        if (batch == null || "RELAY".equals(batch.getInfringementMode())) return "RELAY";
        if (!"OFFICIAL_HYBRID".equals(batch.getInfringementMode())) return "OFFICIAL_STANDARD";
        return uniqueCount(batch) <= 100 ? "OFFICIAL_STANDARD" : "OFFICIAL_BATCH";
    }

    private static int uniqueCount(ImageBatch batch) {
        return Math.max(0, value(batch.getProcessedCount()) - value(batch.getDuplicateCount()) - value(batch.getFailedCount()));
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    static VisualTags visualTags(String rawAiResponse) {
        return imageVisualTags(rawAiResponse).normalized();
    }

    static VisualTags imageVisualTags(String rawAiResponse) {
        if (rawAiResponse == null || rawAiResponse.isBlank()) return VisualTags.EMPTY;
        try {
            VisualTags value = JSON.readValue(rawAiResponse, VisualTags.class);
            if (value == null) return VisualTags.EMPTY;
            return new VisualTags(rawValues(value.subjects()), rawValues(value.scenes()), rawValues(value.themes()),
                rawValues(value.visualStyles()), rawValues(value.colors()), value.confidence(), rawValues(value.detectedElements()));
        } catch (RuntimeException ignored) {
            return VisualTags.EMPTY;
        }
    }

    private static List<String> rawValues(List<String> values) {
        return values == null ? List.of() : values;
    }

    record VisualTags(List<String> subjects, List<String> scenes, List<String> themes,
                              List<String> visualStyles, List<String> colors,
                              Integer confidence, List<String> detectedElements) {
        private static final VisualTags EMPTY = new VisualTags(List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of());

        private VisualTags normalized() {
            return new VisualTags(values(subjects, 5), values(scenes, 5), values(themes, 5),
                values(visualStyles, 5), values(colors, 1),
                confidence == null ? null : Math.max(0, Math.min(100, confidence)), values(detectedElements, 5));
        }

        private static List<String> values(List<String> source, int max) {
            if (source == null) return List.of();
            return source.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .distinct().limit(max).toList();
        }
    }
}
