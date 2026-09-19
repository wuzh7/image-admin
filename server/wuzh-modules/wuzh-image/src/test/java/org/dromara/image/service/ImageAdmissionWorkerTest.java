package org.dromara.image.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.image.domain.*;
import org.dromara.image.mapper.*;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.ImageStorage;
import org.dromara.system.api.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Tags({@Tag("dev"), @Tag("local")})
class ImageAdmissionWorkerTest {
    @org.junit.jupiter.api.BeforeAll static void initializeJsonMapper() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("jsonMapper", tools.jackson.databind.json.JsonMapper.builder().build());
        new org.dromara.common.core.utils.SpringUtils().postProcessBeanFactory(beans);
    }

    private final ImageBatchMapper batches = mock(ImageBatchMapper.class);
    private final ImageAssetMapper assets = mock(ImageAssetMapper.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final QdrantVectorStore vectors = mock(QdrantVectorStore.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ImageDuplicateChecker checker = mock(ImageDuplicateChecker.class);
    private final InfringementService infringement = mock(InfringementService.class);
    private final ImageRecordMapper records = mock(ImageRecordMapper.class);
    private final UserService users = mock(UserService.class);
    private final ImageAdmissionWorker worker = new ImageAdmissionWorker(batches, assets, storage, vectors,
        new ImageLibraryLock(assets, transactions), checker, infringement, records, users, new ImageDataLock());
    private final List<String> savedStates = new ArrayList<>();
    private ImageAsset pending;
    private ImageBatch batch;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ImageAsset.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ImageBatch.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(assets.lockLibrary()).thenReturn(1L);
        pending = image(7L, "APPROVING");
        batch = new ImageBatch(); batch.setId(3L); batch.setStatus("WAIT_REVIEW");
        batch.setSimilarityThreshold(new BigDecimal("0.6"));
        when(assets.selectById(7L)).thenReturn(pending);
        when(batches.selectById(3L)).thenReturn(batch);
        when(batches.lockBatch(3L)).thenReturn(batch);
        when(assets.selectList(any())).thenAnswer(call -> "APPROVED".equals(pending.getStatus()) ? List.of(pending) : List.of());
        when(assets.selectCount(any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ImageAsset> query = call.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue(pending.getStatus()) ? 1L : 0L;
        });
        when(assets.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<ImageAsset> update = call.getArgument(1);
            update.getSqlSet();
            for (Object value : update.getParamNameValuePairs().values()) {
                if (value != null && List.of("APPROVED", "APPROVAL_FAILED", "WAIT_REVIEW", "REPLACED").contains(value)) {
                    savedStates.add(value.toString());
                    if (!"REPLACED".equals(value)) pending.setStatus(value.toString());
                }
            }
            return 1;
        });
    }

    private ImageAsset image(long id, String status) {
        ImageAsset image = new ImageAsset(); image.setId(id); image.setBatchId(3L); image.setStatus(status);
        image.setCreateBy(9L); image.setStorageKey("review/3/test.png");
        image.setCreateTime(LocalDateTime.of(2026, 9, 6, 12, 0)); image.setSha256("ab".repeat(32));
        image.setGlobalVector(SscdFeatureExtractor.encode(new float[512])); image.setCenterVector(image.getGlobalVector());
        return image;
    }

    @Test void savesApprovalAndDetectionQueueOnlyAfterCopyAndVectorWrite() {
        worker.approve(pending);
        var order = inOrder(assets, checker, storage, vectors, infringement, transactions);
        order.verify(assets).lockLibrary(); order.verify(checker).check(pending, null);
        order.verify(storage).copy("review/3/test.png", "library/2026/09/06/3/7_abababababab.png");
        order.verify(vectors).upsert(eq(7L), eq(3L), any(float[].class), any(float[].class));
        order.verify(assets).update(isNull(), any());
        order.verify(infringement).createInitialTask(batch, List.of(pending)); order.verify(transactions).commit(any());
        assertEquals(List.of("APPROVED"), savedStates); verify(storage, never()).delete(anyString());
        ArgumentCaptor<LambdaUpdateWrapper<ImageAsset>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(assets).update(isNull(), update.capture()); assertTrue(update.getValue().getSqlSet().contains("approved_at"));
    }

    @Test void lateExactDuplicateReturnsToReviewWithoutCopyOrDetection() {
        ImageAsset existing = image(8L, "APPROVED"); when(assets.selectById(8L)).thenReturn(existing);
        pending.setManuallyApproved(true);
        when(checker.check(pending, null)).thenReturn(List.of(new ImageSimilarityMatch(8L, BigDecimal.ONE, "LIBRARY")));
        worker.approve(pending);
        assertEquals(List.of("WAIT_REVIEW"), savedStates); verifyNoInteractions(storage, infringement);
        verify(vectors, never()).upsert(anyLong(), anyLong(), any(), any());
        ArgumentCaptor<LambdaUpdateWrapper<ImageAsset>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(assets).update(isNull(), update.capture());
        assertTrue(update.getValue().getParamNameValuePairs().containsValue("EXACT_HASH"));
    }

    @Test void newSimilarCandidateInvalidatesPreviousHumanApproval() {
        pending.setManuallyApproved(true);
        pending.setSimilarMatchesJson("[{\"imageId\":8,\"similarity\":0.8,\"source\":\"LIBRARY\"}]");
        when(checker.check(pending, null)).thenReturn(List.of(new ImageSimilarityMatch(10L, new BigDecimal("0.82"), "LIBRARY")));
        worker.approve(pending);
        assertEquals(List.of("WAIT_REVIEW"), savedStates); verifyNoInteractions(storage, infringement);
        verify(vectors, never()).upsert(anyLong(), anyLong(), any(), any());
    }

    @Test void existingReviewedCandidateCanStillBeApproved() {
        pending.setManuallyApproved(true);
        pending.setSimilarMatchesJson("[{\"imageId\":8,\"similarity\":0.8,\"source\":\"LIBRARY\"}]");
        when(checker.check(pending, null)).thenReturn(List.of(new ImageSimilarityMatch(8L, new BigDecimal("0.8"), "LIBRARY")));
        worker.approve(pending); assertEquals(List.of("APPROVED"), savedStates);
    }

    @Test void copyFailureCannotBecomeApproved() {
        doThrow(new IllegalStateException("copy failed")).when(storage).copy(anyString(), anyString());
        worker.approve(pending); assertEquals(List.of("APPROVAL_FAILED"), savedStates);
        verify(vectors, never()).upsert(anyLong(), anyLong(), any(), any()); verifyNoInteractions(infringement);
        verify(storage, never()).delete("review/3/test.png");
    }

    @Test void defersDetectionUntilAllApprovingImagesInBatchAreAdmitted() {
        doAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ImageAsset> query = call.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue("APPROVING") ? 1L : 0L;
        }).when(assets).selectCount(any());
        worker.approve(pending);
        assertEquals(List.of("APPROVED"), savedStates); verifyNoInteractions(infringement);
        verify(batches, never()).update(isNull(), any());
    }

    @Test void groupsAdmittedImagesIntoOneDetectionTask() {
        ImageAsset second = image(8L, "APPROVED");
        when(assets.selectList(any())).thenReturn(List.of(pending, second));
        worker.approve(pending);
        verify(infringement).createInitialTask(batch, List.of(pending, second));
    }

    @Test void vectorFailurePreservesSourceForRetry() {
        doThrow(new IllegalStateException("vector unavailable")).when(vectors).upsert(anyLong(), anyLong(), any(), any());
        worker.approve(pending); assertEquals(List.of("APPROVAL_FAILED"), savedStates);
        verify(vectors).delete(List.of(7L)); verifyNoInteractions(infringement);
        verify(storage, never()).delete("review/3/test.png");
        verify(storage).delete("library/2026/09/06/3/7_abababababab.png");
        verify(storage).delete("library/2026/09/06/3/7_abababababab.png.thumb.jpg");
    }

    @Test void uncertainCommitDoesNotDeleteSuccessfullyCommittedVector() {
        doThrow(new IllegalStateException("lost connection after commit")).doNothing().when(transactions).commit(any());
        worker.approve(pending); assertEquals(List.of("APPROVED"), savedStates); verify(vectors, never()).delete(anyList());
    }

    @Test void replacementArchivesOldImageAndRecordsBothIdsButDefersOldVectorCleanup() {
        ImageAsset old = image(6L, "APPROVED"); old.setStorageKey("library/old.png");
        when(assets.selectById(6L)).thenReturn(old); when(users.selectUserNameById(9L)).thenReturn("admin2");
        pending.setReplacesImageId(6L);
        worker.approve(pending);
        verify(checker).check(pending, 6L); verify(storage).copy("library/old.png", "replaced/6/7/old.png");
        ArgumentCaptor<ImageRecords.ReplacementLog> log = ArgumentCaptor.forClass(ImageRecords.ReplacementLog.class);
        verify(records).insertReplacement(log.capture()); assertEquals(6L, log.getValue().getOldImageId());
        assertEquals(7L, log.getValue().getNewImageId()); assertEquals("admin2", log.getValue().getUserName());
        assertEquals("replaced/6/7/old.png", log.getValue().getOldStorageKey());
        assertEquals(List.of("REPLACED", "APPROVED"), savedStates); verify(vectors, never()).delete(List.of(6L));
    }

    @Test void replacementCopyFailureLeavesOldImageUntouched() {
        pending.setReplacesImageId(6L);
        doThrow(new IllegalStateException("copy failed")).when(storage).copy(anyString(), anyString());
        worker.approve(pending); assertEquals(List.of("APPROVAL_FAILED"), savedStates);
        verifyNoInteractions(records); verify(vectors, never()).delete(List.of(6L));
        verify(storage, never()).copy(eq("library/old.png"), anyString());
    }

    @Test void partialArchiveFailureDeletesOnlyNewCopiesAndPreservesOldAndSource() {
        ImageAsset old = image(6L, "APPROVED");
        old.setStorageKey("library/old.png"); old.setThumbnailKey("library/old.png.thumb.jpg");
        when(assets.selectById(6L)).thenReturn(old); pending.setReplacesImageId(6L);
        doThrow(new IllegalStateException("archive thumbnail failed")).when(storage)
            .copy("library/old.png.thumb.jpg", "replaced/6/7/old.png.thumb.jpg");
        worker.approve(pending);
        assertEquals(List.of("APPROVAL_FAILED"), savedStates);
        verify(storage).delete("library/2026/09/06/3/7_abababababab.png");
        verify(storage).delete("replaced/6/7/old.png");
        verify(storage).delete("replaced/6/7/old.png.thumb.jpg");
        verify(storage, never()).delete("library/old.png");
        verify(storage, never()).delete("library/old.png.thumb.jpg");
        verify(storage, never()).delete("review/3/test.png");
        verify(vectors, never()).delete(List.of(6L)); verifyNoInteractions(records, infringement);
    }
}
