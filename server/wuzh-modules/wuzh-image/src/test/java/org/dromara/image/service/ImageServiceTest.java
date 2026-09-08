package org.dromara.image.service;

import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.BatchStatus;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.domain.ImageBatch;
import org.dromara.image.domain.ImageCategory;
import org.dromara.image.domain.ImageDtos;
import org.dromara.image.domain.ImageStatus;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.mapper.ImageCategoryMapper;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.LocalImageStorage;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.api.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tags({@Tag("dev"), @Tag("local")})
class ImageServiceTest {

    @Test
    void confirmPersistsDecisionWithoutCallingStorageOrQdrant() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        LocalImageStorage storage = mock(LocalImageStorage.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class), storage,
            vectorStore, mock(ImageTaskRunner.class), mock(UserService.class));

        long batchId = 10L;
        ImageBatch batch = new ImageBatch();
        batch.setId(batchId);
        batch.setCreateBy(1L);
        batch.setStatus(BatchStatus.WAIT_REVIEW.name());
        when(batchMapper.selectById(batchId)).thenReturn(batch);
        when(batchMapper.claimForConfirmation(batchId)).thenReturn(1);

        byte[] vector = SscdFeatureExtractor.encode(new float[SscdFeatureExtractor.VECTOR_DIMENSION]);
        List<ImageAsset> assets = new ArrayList<>();
        List<Long> approvedIds = new ArrayList<>();
        for (long id = 1; id <= 21; id++) {
            ImageAsset asset = new ImageAsset();
            asset.setId(id);
            asset.setBatchId(batchId);
            asset.setStatus(ImageStatus.WAIT_REVIEW.name());
            asset.setStorageKey("staging/" + id + ".jpg");
            asset.setThumbnailKey("staging/thumb/" + id + ".jpg");
            asset.setGlobalVector(vector);
            asset.setCenterVector(vector);
            assets.add(asset);
            approvedIds.add(id);
        }
        ImageAsset autoApproved = new ImageAsset();
        autoApproved.setId(22L);
        autoApproved.setBatchId(batchId);
        autoApproved.setStatus(ImageStatus.APPROVED.name());
        assets.add(autoApproved);
        when(assetMapper.selectList(any())).thenReturn(assets);
        when(assetMapper.updateById(any(ImageAsset.class))).thenReturn(1);
        when(batchMapper.updateById(any(ImageBatch.class))).thenReturn(1);
        when(storage.moveToLibrary(anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
            long id = invocation.getArgument(0);
            return new LocalImageStorage.MovedFiles("library/" + id + ".jpg", "library/thumb/" + id + ".jpg");
        });

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            service.confirm(batchId, new ImageDtos.ConfirmBatchRequest(approvedIds));
        }

        verify(vectorStore, never()).upsert(any(List.class));
        assertEquals(BatchStatus.CONFIRMING.name(), batch.getStatus());
        assertEquals(21, assets.stream().filter(a -> ImageStatus.APPROVING.name().equals(a.getStatus())).count());
        verify(storage, never()).copy(anyString(), anyString());
    }

    @Test
    void confirmRejectsSelectedImageAboveNinetyFivePercentSimilarity() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        LocalImageStorage storage = mock(LocalImageStorage.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class), storage, vectorStore,
            mock(ImageTaskRunner.class), mock(UserService.class));
        ImageBatch batch = new ImageBatch();
        batch.setId(11L);
        batch.setCreateBy(1L);
        batch.setStatus(BatchStatus.WAIT_REVIEW.name());
        byte[] vector = SscdFeatureExtractor.encode(new float[SscdFeatureExtractor.VECTOR_DIMENSION]);
        ImageAsset image = asset(111L, 11L, vector);
        image.setSimilarity(new java.math.BigDecimal("0.95001"));
        when(batchMapper.selectById(11L)).thenReturn(batch);
        when(batchMapper.claimForConfirmation(11L)).thenReturn(1);
        when(assetMapper.selectList(any())).thenReturn(List.of(image));
        when(assetMapper.updateById(any(ImageAsset.class))).thenReturn(1);
        when(batchMapper.updateById(any(ImageBatch.class))).thenReturn(1);
        when(storage.moveToLibrary(anyLong(), anyString(), anyString())).thenReturn(
            new LocalImageStorage.MovedFiles("library/111.jpg", "library/thumb/111.jpg"));

        org.dromara.common.core.exception.ServiceException exception;
        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            exception = assertThrows(org.dromara.common.core.exception.ServiceException.class,
                () -> service.confirm(11L, new ImageDtos.ConfirmBatchRequest(List.of(111L))));
        }

        assertEquals("相似度超过95%的图片不可入库：111", exception.getMessage());
        verify(vectorStore, never()).upsert(any(List.class));
    }

    @Test
    void confirmRejectsSelectedExactHashDuplicateEvenWithoutSimilarityValue() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        LocalImageStorage storage = mock(LocalImageStorage.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class), storage, vectorStore,
            mock(ImageTaskRunner.class), mock(UserService.class));
        ImageBatch batch = new ImageBatch();
        batch.setId(12L);
        batch.setCreateBy(1L);
        batch.setStatus(BatchStatus.WAIT_REVIEW.name());
        byte[] vector = SscdFeatureExtractor.encode(new float[SscdFeatureExtractor.VECTOR_DIMENSION]);
        ImageAsset image = asset(121L, 12L, vector);
        image.setMatchMethod("EXACT_HASH");
        image.setSimilarity(null);
        when(batchMapper.selectById(12L)).thenReturn(batch);
        when(batchMapper.claimForConfirmation(12L)).thenReturn(1);
        when(assetMapper.selectList(any())).thenReturn(List.of(image));
        when(assetMapper.updateById(any(ImageAsset.class))).thenReturn(1);
        when(batchMapper.updateById(any(ImageBatch.class))).thenReturn(1);
        when(storage.moveToLibrary(anyLong(), anyString(), anyString())).thenReturn(
            new LocalImageStorage.MovedFiles("library/121.jpg", "library/thumb/121.jpg"));

        org.dromara.common.core.exception.ServiceException exception;
        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            exception = assertThrows(org.dromara.common.core.exception.ServiceException.class,
                () -> service.confirm(12L, new ImageDtos.ConfirmBatchRequest(List.of(121L))));
        }

        assertEquals("相似度超过95%的图片不可入库：121", exception.getMessage());
        verify(vectorStore, never()).upsert(any(List.class));
    }

    @Test
    void confirmAllowsSelectedImageAtExactlyNinetyFivePercentSimilarity() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        LocalImageStorage storage = mock(LocalImageStorage.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class), storage, vectorStore,
            mock(ImageTaskRunner.class), mock(UserService.class));
        ImageBatch batch = new ImageBatch();
        batch.setId(13L);
        batch.setCreateBy(1L);
        batch.setStatus(BatchStatus.WAIT_REVIEW.name());
        byte[] vector = SscdFeatureExtractor.encode(new float[SscdFeatureExtractor.VECTOR_DIMENSION]);
        ImageAsset image = asset(131L, 13L, vector);
        image.setSimilarity(new java.math.BigDecimal("0.95000"));
        when(batchMapper.selectById(13L)).thenReturn(batch);
        when(batchMapper.claimForConfirmation(13L)).thenReturn(1);
        when(assetMapper.selectList(any())).thenReturn(List.of(image));
        when(assetMapper.updateById(any(ImageAsset.class))).thenReturn(1);
        when(batchMapper.updateById(any(ImageBatch.class))).thenReturn(1);
        when(storage.moveToLibrary(anyLong(), anyString(), anyString())).thenReturn(
            new LocalImageStorage.MovedFiles("library/131.jpg", "library/thumb/131.jpg"));

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            service.confirm(13L, new ImageDtos.ConfirmBatchRequest(List.of(131L)));
        }

        verify(vectorStore, never()).upsert(any(List.class));
        assertEquals(ImageStatus.APPROVING.name(), image.getStatus());
    }

    @Test
    void regularUserCannotReadAnotherUsersBatch() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class),
            mock(LocalImageStorage.class), mock(QdrantVectorStore.class), mock(ImageTaskRunner.class), mock(UserService.class));
        ImageBatch batch = new ImageBatch();
        batch.setId(40L);
        batch.setCreateBy(100L);
        when(batchMapper.selectById(40L)).thenReturn(batch);

        try (MockedStatic<LoginHelper> login = loginAs(200L, false)) {
            assertThrows(org.dromara.common.core.exception.ServiceException.class, () -> service.getBatch(40L));
        }
        verify(assetMapper, never()).selectList(any());
    }

    @Test
    void administratorCanReadAnotherUsersBatch() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class),
            mock(LocalImageStorage.class), mock(QdrantVectorStore.class), mock(ImageTaskRunner.class), mock(UserService.class));
        ImageBatch batch = new ImageBatch();
        batch.setId(50L);
        batch.setCreateBy(100L);
        batch.setStatus(BatchStatus.WAIT_REVIEW.name());
        batch.setProcessedCount(0);
        batch.setDuplicateCount(0);
        batch.setFailedCount(0);
        when(batchMapper.selectById(50L)).thenReturn(batch);
        when(assetMapper.selectList(any())).thenReturn(List.of());

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            assertEquals(50L, service.getBatch(50L).id());
        }
    }

    @Test
    void createBatchUsesTheGeneratedUniqueIdAndDefaultThreshold() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageCategoryMapper categoryMapper = mock(ImageCategoryMapper.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, mock(ImageAssetMapper.class),
            categoryMapper, mock(LocalImageStorage.class), mock(QdrantVectorStore.class), mock(ImageTaskRunner.class), mock(UserService.class));
        ImageCategory category = new ImageCategory();
        category.setId(3L);
        category.setName("美妆工具");
        category.setStatus("0");
        when(categoryMapper.selectById(3L)).thenReturn(category);
        when(batchMapper.insert(any(ImageBatch.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ImageBatch.class).setId(1980000000000000001L);
            return 1;
        });

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            ImageDtos.BatchView batch = service.createBatch(new ImageDtos.CreateBatchRequest(null, 3L));
            assertEquals(1980000000000000001L, batch.id());
            assertEquals(3L, batch.categoryId());
            assertEquals("美妆工具", batch.categoryName());
            assertEquals(new java.math.BigDecimal("0.60"), batch.similarityThreshold());
        }
        verify(batchMapper, never()).updateById(any(ImageBatch.class));
    }

    @Test
    void listBatchesIncludesCreatorNickname() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        UserService userService = mock(UserService.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, mock(ImageAssetMapper.class),
            mock(ImageCategoryMapper.class), mock(LocalImageStorage.class), mock(QdrantVectorStore.class),
            mock(ImageTaskRunner.class), userService);
        ImageBatch batch = new ImageBatch();
        batch.setId(80L);
        batch.setCreateBy(9L);
        batch.setStatus(BatchStatus.WAIT_REVIEW.name());
        batch.setTotalCount(0);
        batch.setProcessedCount(0);
        batch.setDuplicateCount(0);
        batch.setFailedCount(0);
        Page<ImageBatch> page = new Page<>(1, 10);
        page.setRecords(List.of(batch));
        page.setTotal(1);
        when(batchMapper.selectPage(any(), any())).thenReturn(page);
        when(userService.selectUserNicksByIds(any())).thenReturn(Map.of(9L, "张三"));

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            PageResult<ImageDtos.BatchSummary> result = service.listBatches(new PageQuery(10, 1), null);
            assertEquals("张三", result.getRows().iterator().next().creatorName());
        }
    }

    @Test
    void uploadCopiesTheBatchCategoryToEveryImage() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        LocalImageStorage storage = mock(LocalImageStorage.class);
        ImageBatch batch = new ImageBatch();
        batch.setId(70L);
        batch.setCategoryId(3L);
        batch.setCreateBy(1L);
        batch.setStatus(BatchStatus.UPLOADING.name());
        when(batchMapper.selectById(70L)).thenReturn(batch);
        when(storage.save(anyLong(), any(MultipartFile.class))).thenReturn(new LocalImageStorage.StoredImage(
            "staging/70/a.jpg", "staging/70/thumb/a.jpg", "a.jpg", "image/jpeg",
            100L, 10, 10, "sha256"));
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class), storage, mock(QdrantVectorStore.class), mock(ImageTaskRunner.class), mock(UserService.class));

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            assertEquals(1, service.upload(70L, List.of(mock(MultipartFile.class))));
        }

        ArgumentCaptor<ImageAsset> image = ArgumentCaptor.forClass(ImageAsset.class);
        verify(assetMapper).insert(image.capture());
        assertEquals(3L, image.getValue().getCategoryId());
        assertEquals("1:1", image.getValue().getAspectRatio());
    }

    @Test
    void repairsOnlyMissingAndOrphanedVectorPointsWhenAnAdministratorRequestsIt() {
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        ImageService service = new ImageService(new ImageProperties(), mock(ImageBatchMapper.class), assetMapper,
            mock(ImageCategoryMapper.class), mock(LocalImageStorage.class), vectorStore,
            mock(ImageTaskRunner.class), mock(UserService.class));
        byte[] vector = SscdFeatureExtractor.encode(new float[SscdFeatureExtractor.VECTOR_DIMENSION]);
        ImageAsset approved = asset(301L, 30L, vector);
        approved.setStatus(ImageStatus.APPROVED.name());
        when(assetMapper.selectList(any())).thenReturn(List.of(approved));
        when(vectorStore.allPointIds()).thenReturn(Set.of(999L));

        ImageDtos.VectorRepairView result = service.repairVectorIndex();

        assertEquals(1, result.approvedImageCount());
        assertEquals(1, result.qdrantPointsBefore());
        assertEquals(1, result.restoredPoints());
        assertEquals(1, result.removedPoints());
        verify(vectorStore).upsert(301L, 30L, SscdFeatureExtractor.decode(vector), SscdFeatureExtractor.decode(vector));
        verify(vectorStore).delete(List.of(999L));
    }

    @Test
    void marksOnlyApprovedImagesMissingFromQdrantAsIndexAbnormal() {
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
        ImageService service = new ImageService(new ImageProperties(), mock(ImageBatchMapper.class), assetMapper,
            mock(ImageCategoryMapper.class), mock(LocalImageStorage.class), vectorStore,
            mock(ImageTaskRunner.class), mock(UserService.class));
        ImageAsset indexed = asset(401L, 40L, new byte[0]);
        indexed.setStatus(ImageStatus.APPROVED.name());
        indexed.setCreateBy(1L);
        ImageAsset missing = asset(402L, 40L, new byte[0]);
        missing.setStatus(ImageStatus.APPROVED.name());
        missing.setCreateBy(1L);
        Page<ImageAsset> page = new Page<>(1, 12);
        page.setRecords(List.of(indexed, missing));
        page.setTotal(2);
        when(assetMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(vectorStore.existingPointIds(List.of(401L, 402L))).thenReturn(Set.of(401L));

        PageResult<ImageDtos.ImageView> result;
        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            result = service.listApproved(new ImageDtos.ImageQuery(), new PageQuery(12, 1));
        }
        List<ImageDtos.ImageView> rows = new ArrayList<>(result.getRows());

        assertFalse(rows.get(0).vectorIndexMissing());
        assertTrue(rows.get(1).vectorIndexMissing());
    }

    @Test
    void uploadAllowsTheOneThousandthImageInABatch() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        LocalImageStorage storage = mock(LocalImageStorage.class);
        ImageBatch batch = new ImageBatch();
        batch.setId(71L);
        batch.setCategoryId(3L);
        batch.setCreateBy(1L);
        batch.setStatus(BatchStatus.UPLOADING.name());
        when(batchMapper.selectById(71L)).thenReturn(batch);
        when(assetMapper.selectCount(any())).thenReturn(999L);
        when(storage.save(anyLong(), any(MultipartFile.class))).thenReturn(new LocalImageStorage.StoredImage(
            "staging/71/a.jpg", "staging/71/thumb/a.jpg", "a.jpg", "image/jpeg",
            100L, 10, 10, "sha256"));
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class), storage, mock(QdrantVectorStore.class), mock(ImageTaskRunner.class), mock(UserService.class));

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            assertEquals(1, service.upload(71L, List.of(mock(MultipartFile.class))));
        }
    }

    @Test
    void uploadRejectsImagesAfterTheOneThousandthImage() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        ImageBatch batch = new ImageBatch();
        batch.setId(72L);
        batch.setCreateBy(1L);
        batch.setStatus(BatchStatus.UPLOADING.name());
        when(batchMapper.selectById(72L)).thenReturn(batch);
        when(assetMapper.selectCount(any())).thenReturn(1000L);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class), mock(LocalImageStorage.class), mock(QdrantVectorStore.class),
            mock(ImageTaskRunner.class), mock(UserService.class));

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            org.dromara.common.core.exception.ServiceException exception = assertThrows(
                org.dromara.common.core.exception.ServiceException.class,
                () -> service.upload(72L, List.of(mock(MultipartFile.class))));
            assertEquals("一个批次最多上传1000张图片", exception.getMessage());
        }
    }

    @Test
    void regularUserCannotReadAnotherUsersImage() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class),
            mock(LocalImageStorage.class), mock(QdrantVectorStore.class), mock(ImageTaskRunner.class), mock(UserService.class));
        ImageAsset asset = new ImageAsset();
        asset.setId(60L);
        asset.setCreateBy(100L);
        when(assetMapper.selectById(60L)).thenReturn(asset);

        try (MockedStatic<LoginHelper> login = loginAs(200L, false)) {
            assertThrows(org.dromara.common.core.exception.ServiceException.class, () -> service.getImage(60L));
        }
    }

    @Test
    void identifiesWhetherBestMatchComesFromTheBatchOrLibrary() {
        ImageBatchMapper batchMapper = mock(ImageBatchMapper.class);
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        ImageService service = new ImageService(new ImageProperties(), batchMapper, assetMapper,
            mock(ImageCategoryMapper.class), mock(LocalImageStorage.class), mock(QdrantVectorStore.class),
            mock(ImageTaskRunner.class), mock(UserService.class));
        ImageBatch batch = new ImageBatch();
        batch.setId(90L);
        batch.setCreateBy(1L);
        batch.setStatus(ImageStatus.WAIT_REVIEW.name());
        batch.setProcessedCount(2);
        batch.setDuplicateCount(1);
        batch.setFailedCount(0);
        ImageAsset current = new ImageAsset();
        current.setId(901L);
        current.setBatchId(90L);
        current.setCreateBy(1L);
        current.setDuplicateOf(902L);
        ImageAsset inBatch = new ImageAsset();
        inBatch.setId(902L);
        inBatch.setBatchId(90L);
        inBatch.setCreateBy(1L);
        ImageAsset inLibrary = new ImageAsset();
        inLibrary.setId(903L);
        inLibrary.setBatchId(80L);
        inLibrary.setCreateBy(1L);
        when(batchMapper.selectById(90L)).thenReturn(batch);
        when(assetMapper.selectList(any())).thenReturn(List.of(current));
        when(assetMapper.selectByIds(any())).thenReturn(List.of(inBatch), List.of(inLibrary));

        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            assertEquals("BATCH", service.getBatch(90L).images().getFirst().matchSource());
        }

        current.setDuplicateOf(903L);
        try (MockedStatic<LoginHelper> login = loginAs(1L, true)) {
            assertEquals("LIBRARY", service.getBatch(90L).images().getFirst().matchSource());
        }
    }

    private static MockedStatic<LoginHelper> loginAs(long userId, boolean administrator) {
        MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
        login.when(LoginHelper::getUserId).thenReturn(userId);
        login.when(() -> LoginHelper.isSuperAdmin(userId)).thenReturn(administrator);
        return login;
    }

    @Test
    void approvedLibraryImageCanBeViewedByAnotherAuthorizedUser() {
        ImageAssetMapper assetMapper = mock(ImageAssetMapper.class);
        LocalImageStorage storage = mock(LocalImageStorage.class);
        ImageAsset approved = asset(1001L, 100L, new byte[0]);
        approved.setStatus(ImageStatus.APPROVED.name());
        approved.setCreateBy(2L);
        when(assetMapper.selectById(1001L)).thenReturn(approved);
        when(storage.accessUrl(approved.getStorageKey(), true)).thenReturn(Optional.of("https://example.test/thumb"));
        ImageService service = new ImageService(new ImageProperties(), mock(ImageBatchMapper.class), assetMapper,
            mock(ImageCategoryMapper.class), storage, mock(QdrantVectorStore.class), mock(ImageTaskRunner.class), mock(UserService.class));

        try (MockedStatic<LoginHelper> login = loginAs(1L, false)) {
            assertEquals("https://example.test/thumb", service.accessUrl(1001L, true));
        }
    }

    private static ImageAsset asset(long id, long batchId, byte[] vector) {
        ImageAsset asset = new ImageAsset();
        asset.setId(id);
        asset.setBatchId(batchId);
        asset.setStatus(ImageStatus.WAIT_REVIEW.name());
        asset.setStorageKey("staging/" + id + ".jpg");
        asset.setThumbnailKey("staging/thumb/" + id + ".jpg");
        asset.setGlobalVector(vector);
        asset.setCenterVector(vector);
        return asset;
    }
}
