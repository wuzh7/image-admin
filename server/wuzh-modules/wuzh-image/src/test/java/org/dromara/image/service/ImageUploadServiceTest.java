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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
            assertThrows(ServiceException.class, () -> service.createSession(10L,
                new ImageUploadService.PrepareRequest(List.of(new ImageUploadService.FileRequest("a.png", "image/png", 10)))));
        }
        verifyNoInteractions(storage, assets);
    }

    @Test void onePolicyAndOneDatabaseBatchPrepareTheWholeUpload() {
        batch();
        when(storage.directUpload()).thenReturn(true);
        when(assets.selectCount(any())).thenReturn(0L);
        var expiresAt = Instant.now().plusSeconds(1800);
        when(storage.signUploadPolicy(eq("review/10/"), any(Duration.class))).thenReturn(
            new ImageStorage.UploadPolicy("https://bucket.example/", "review/10/", Map.of("policy", "signed"), expiresAt));
        AtomicLong ids = new AtomicLong(100);
        doAnswer(invocation -> {
            List<ImageAsset> inserted = invocation.getArgument(0);
            inserted.forEach(asset -> asset.setId(ids.incrementAndGet()));
            return List.of();
        }).when(assets).insert(anyCollection());

        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            var result = service.createSession(10L, new ImageUploadService.PrepareRequest(List.of(
                new ImageUploadService.FileRequest("a.png", "image/png", 10),
                new ImageUploadService.FileRequest("b.jpg", "image/jpeg", 20))));
            assertEquals(2, result.files().size());
            assertEquals("https://bucket.example/", result.host());
            assertEquals(8, result.uploadConcurrency());
            assertTrue(result.files().stream().allMatch(file -> file.objectKey().startsWith("review/10/")));
        }
        verify(storage, times(1)).signUploadPolicy(eq("review/10/"), any(Duration.class));
        verify(assets, times(1)).insert(anyCollection());
    }

    @Test void completionRejectsImageFromAnotherBatch() {
        batch();
        var asset = new ImageAsset();
        asset.setBatchId(99L);
        when(assets.selectByIds(List.of(7L))).thenReturn(List.of(asset));
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            assertThrows(ServiceException.class, () -> service.complete(10L,
                new ImageUploadService.CompleteRequest(List.of(7L), List.of())));
        }
        verifyNoInteractions(storage);
        verify(assets, never()).updateById(anyCollection());
    }

    @Test void repeatedCompletionDoesNotChangeAnAlreadyUploadedImage() {
        batch();
        var asset = new ImageAsset();
        asset.setId(7L);
        asset.setBatchId(10L);
        asset.setStatus("UPLOADED");
        when(assets.selectByIds(List.of(7L))).thenReturn(List.of(asset));
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            service.complete(10L, new ImageUploadService.CompleteRequest(List.of(7L), List.of()));
        }
        verifyNoInteractions(storage);
        verify(assets, never()).updateById(anyCollection());
    }

    @Test void completionPersistsSuccessAndDiscardsOnlyFailedUploadsInOneBatch() {
        ImageBatch batch = batch();
        batch.setTotalCount(2);
        ImageAsset successful = new ImageAsset();
        successful.setId(7L);
        successful.setBatchId(10L);
        successful.setStatus("UPLOADING");
        ImageAsset failed = new ImageAsset();
        failed.setId(8L);
        failed.setBatchId(10L);
        failed.setStatus("UPLOADING");
        when(assets.selectByIds(List.of(7L, 8L))).thenReturn(List.of(successful, failed));

        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            service.complete(10L, new ImageUploadService.CompleteRequest(List.of(7L), List.of(8L)));
        }

        assertEquals("UPLOADED", successful.getStatus());
        assertEquals("DELETE_PENDING", failed.getStatus());
        assertNotNull(failed.getDeletedAt());
        assertEquals(1, batch.getTotalCount());
        verify(assets).updateById(argThat((java.util.Collection<ImageAsset> changed) -> changed.size() == 2));
    }
}
