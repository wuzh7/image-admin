package org.dromara.image.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageBatch;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
class ImageUploadServiceTest {
    private final ImageBatchMapper batches = mock(ImageBatchMapper.class);
    private final ImageAssetMapper assets = mock(ImageAssetMapper.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final ImageUploadService service = new ImageUploadService(batches, assets, storage, new ImageProperties());

    private ImageBatch batch() {
        var batch = new ImageBatch();
        batch.setId(10L);
        batch.setCreateBy(2L);
        batch.setStatus("UPLOADING");
        when(batches.lockBatch(10L)).thenReturn(batch);
        return batch;
    }

    @Test void anotherUsersBatchCannotIssueUploadTickets() {
        batch();
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(3L);
            assertThrows(ServiceException.class, () -> service.prepare(10L,
                new ImageUploadService.PrepareRequest(List.of(new ImageUploadService.FileRequest("a.png", "image/png", 10)))));
        }
        verifyNoInteractions(storage, assets);
    }

    @Test void completionRejectsImageFromAnotherBatch() {
        batch();
        var asset = new ImageAsset();
        asset.setBatchId(99L);
        when(assets.selectById(7L)).thenReturn(asset);
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            assertThrows(ServiceException.class, () -> service.complete(10L,
                new ImageUploadService.CompleteRequest(List.of(7L))));
        }
        verifyNoInteractions(storage);
        verify(assets, never()).updateById(any(ImageAsset.class));
    }

    @Test void repeatedCompletionDoesNotChangeAnAlreadyUploadedImage() {
        batch();
        var asset = new ImageAsset();
        asset.setBatchId(10L);
        asset.setStatus("UPLOADED");
        when(assets.selectById(7L)).thenReturn(asset);
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            service.complete(10L, new ImageUploadService.CompleteRequest(List.of(7L)));
        }
        verifyNoInteractions(storage);
        verify(assets, never()).updateById(any(ImageAsset.class));
    }
}
