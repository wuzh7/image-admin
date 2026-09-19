package org.dromara.image.service;

import lombok.RequiredArgsConstructor;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.mapper.InfringementCallLogMapper;
import org.dromara.image.mapper.InfringementScanTaskMapper;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.storage.ImageStorage;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ImageResetService {

    private final ImageAssetMapper assetMapper;
    private final ImageBatchMapper batchMapper;
    private final InfringementScanItemMapper itemMapper;
    private final InfringementCallLogMapper callLogMapper;
    private final InfringementScanTaskMapper taskMapper;
    private final ImageStorage storage;
    private final QdrantVectorStore vectorStore;

    private final org.dromara.image.mapper.ImageRecordMapper records;

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public ResetResult clearAll() {
        if (assetMapper.lockLibrary() == null) {
            throw new org.dromara.common.core.exception.ServiceException("图片互斥锁未初始化，请先执行数据库迁移");
        }
        if (assetMapper.countResetBlockingAssets() > 0 || batchMapper.countResetBlockingBatches() > 0
            || itemMapper.countProcessingForReset() > 0) {
            throw new org.dromara.common.core.exception.ServiceException("图片正在上传、检测或入库，请等待处理结束后清空");
        }
        records.lockDailyScan();
        List<ImageAsset> assets = assetMapper.selectAllForReset();
        var byId = assets.stream().collect(java.util.stream.Collectors.toMap(ImageAsset::getId, asset -> asset));
        Set<String> keys = new LinkedHashSet<>();
        for (ImageAsset asset : assets) {
            addKey(keys, asset.getStorageKey());
            addKey(keys, asset.getThumbnailKey());
            addKey(keys, asset.getCleanupKey());
            addKey(keys, asset.getCleanupThumbnailKey());
            // 外部文件复制不参与数据库事务，同时清理失败或重启时留下的确定路径。
            String source = asset.getStorageKey() == null ? asset.getOriginalName() : asset.getStorageKey();
            if (source != null) {
                String target = ImageStorage.libraryKey(asset.getId(), asset.getBatchId(), asset.getSha256(), source, asset.getCreateTime());
                addKey(keys, target);
                addKey(keys, target + ".thumb.jpg");
            }
            if (asset.getReplacesImageId() != null) {
                ImageAsset old = byId.get(asset.getReplacesImageId());
                if (old != null && old.getStorageKey() != null) {
                    String archive = "replaced/" + old.getId() + "/" + asset.getId() + "/"
                        + old.getStorageKey().substring(old.getStorageKey().lastIndexOf('/') + 1);
                    addKey(keys, archive);
                    addKey(keys, archive + ".thumb.jpg");
                }
            }
        }
        keys.forEach(storage::delete);
        vectorStore.clearAll();

        records.clearReplacements();
        records.clearExports();
        records.clearTodos();
        records.clearScans();
        callLogMapper.deleteAllForReset();
        int items = itemMapper.deleteAllForReset();
        int tasks = taskMapper.deleteAllForReset();
        int deletedAssets = assetMapper.deleteAllForReset();
        int batches = batchMapper.deleteAllForReset();
        return new ResetResult(batches, deletedAssets, tasks, items);
    }

    private static void addKey(Set<String> keys, String key) {
        if (key != null && !key.isBlank()) {
            keys.add(key);
        }
    }

    public record ResetResult(int batches, int assets, int tasks, int items) {
    }
}
