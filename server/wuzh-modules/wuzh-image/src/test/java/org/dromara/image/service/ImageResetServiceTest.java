package org.dromara.image.service;

import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.mapper.InfringementScanItemMapper;
import org.dromara.image.mapper.InfringementCallLogMapper;
import org.dromara.image.mapper.InfringementScanTaskMapper;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tags({@Tag("dev"), @Tag("local")})
class ImageResetServiceTest {

    @Test
    void clearsImageRecordsVectorsAndEveryAssociatedFileKey() {
        ImageAssetMapper assets = mock(ImageAssetMapper.class);
        ImageBatchMapper batches = mock(ImageBatchMapper.class);
        InfringementScanItemMapper items = mock(InfringementScanItemMapper.class);
        InfringementCallLogMapper callLogs = mock(InfringementCallLogMapper.class);
        InfringementScanTaskMapper tasks = mock(InfringementScanTaskMapper.class);
        ImageStorage storage = mock(ImageStorage.class);
        QdrantVectorStore vectors = mock(QdrantVectorStore.class);
        var records = mock(org.dromara.image.mapper.ImageRecordMapper.class);
        when(assets.lockLibrary()).thenReturn(1L);
        ImageAsset asset = new ImageAsset();
        asset.setId(7L); asset.setBatchId(3L); asset.setSha256("ab".repeat(32));
        asset.setCreateTime(java.time.LocalDateTime.of(2026, 9, 19, 12, 0));
        asset.setStorageKey("library/a.jpg");
        asset.setThumbnailKey("library/a.jpg.thumb.jpg");
        asset.setCleanupKey("review/a.jpg");
        asset.setCleanupThumbnailKey("review/a.jpg.thumb.jpg");
        when(assets.selectAllForReset()).thenReturn(List.of(asset));
        when(items.deleteAllForReset()).thenReturn(4);
        when(tasks.deleteAllForReset()).thenReturn(3);
        when(assets.deleteAllForReset()).thenReturn(2);
        when(batches.deleteAllForReset()).thenReturn(1);

        ImageResetService.ResetResult result = new ImageResetService(assets, batches, items, callLogs, tasks,
            storage, vectors, records).clearAll();

        assertEquals(new ImageResetService.ResetResult(1, 2, 3, 4), result);
        verify(storage).delete("library/a.jpg");
        verify(storage).delete("library/a.jpg.thumb.jpg");
        verify(storage).delete("review/a.jpg");
        verify(storage).delete("review/a.jpg.thumb.jpg");
        verify(storage).delete("library/2026/09/19/3/7_abababababab.jpg");
        verify(storage).delete("library/2026/09/19/3/7_abababababab.jpg.thumb.jpg");
        verify(vectors).clearAll();
        verify(items).deleteAllForReset();
        verify(callLogs).deleteAllForReset();
        verify(tasks).deleteAllForReset();
        verify(assets).deleteAllForReset();
        verify(batches).deleteAllForReset();
        verify(records).clearReplacements();
        verify(records).clearExports();
        verify(records).clearTodos();
        verify(records).clearScans();
    }

    @Test void refusesResetWhileAdmissionIsActive() {
        ImageAssetMapper assets = mock(ImageAssetMapper.class);
        when(assets.lockLibrary()).thenReturn(1L);
        when(assets.countResetBlockingAssets()).thenReturn(1L);
        ImageStorage storage = mock(ImageStorage.class);
        QdrantVectorStore vectors = mock(QdrantVectorStore.class);
        var records = mock(org.dromara.image.mapper.ImageRecordMapper.class);
        var service = new ImageResetService(assets, mock(ImageBatchMapper.class), mock(InfringementScanItemMapper.class),
            mock(InfringementCallLogMapper.class), mock(InfringementScanTaskMapper.class), storage, vectors, records);
        org.junit.jupiter.api.Assertions.assertThrows(org.dromara.common.core.exception.ServiceException.class, service::clearAll);
        org.mockito.Mockito.verifyNoInteractions(storage, vectors, records);
        org.mockito.Mockito.verify(assets, org.mockito.Mockito.never()).deleteAllForReset();
    }

    @Test void clearsUncommittedReplacementCopiesAndHandlesAlreadyCleanedRecords() {
        ImageAssetMapper assets = mock(ImageAssetMapper.class);
        when(assets.lockLibrary()).thenReturn(1L);
        ImageAsset old = new ImageAsset(); old.setId(6L); old.setBatchId(2L);
        old.setCreateTime(java.time.LocalDateTime.of(2026, 9, 19, 12, 0));
        old.setStorageKey("library/old.png");
        ImageAsset failed = new ImageAsset(); failed.setId(7L); failed.setBatchId(3L);
        failed.setCreateTime(old.getCreateTime()); failed.setSha256("ab".repeat(32));
        failed.setStorageKey("review/new.png"); failed.setReplacesImageId(6L);
        ImageAsset cleaned = new ImageAsset(); cleaned.setId(8L); cleaned.setBatchId(3L);
        cleaned.setCreateTime(old.getCreateTime()); cleaned.setOriginalName("deleted.jpg");
        when(assets.selectAllForReset()).thenReturn(List.of(old, failed, cleaned));
        ImageStorage storage = mock(ImageStorage.class);
        new ImageResetService(assets, mock(ImageBatchMapper.class), mock(InfringementScanItemMapper.class),
            mock(InfringementCallLogMapper.class), mock(InfringementScanTaskMapper.class), storage,
            mock(QdrantVectorStore.class), mock(org.dromara.image.mapper.ImageRecordMapper.class)).clearAll();
        verify(storage).delete("library/2026/09/19/3/7_abababababab.png");
        verify(storage).delete("replaced/6/7/old.png");
        verify(storage).delete("replaced/6/7/old.png.thumb.jpg");
        verify(storage).delete("library/2026/09/19/3/8_unknown.jpg");
    }
}
