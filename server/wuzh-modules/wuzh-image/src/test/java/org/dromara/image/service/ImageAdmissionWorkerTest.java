package org.dromara.image.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.mapper.ImageAssetMapper;
import org.dromara.image.mapper.ImageBatchMapper;
import org.dromara.image.similarity.QdrantVectorStore;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("local")
class ImageAdmissionWorkerTest {
    private final ImageAssetMapper assets = mock(ImageAssetMapper.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final QdrantVectorStore vectors = mock(QdrantVectorStore.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ImageAdmissionWorker worker = new ImageAdmissionWorker(mock(ImageBatchMapper.class), assets, storage, vectors, transactions);
    private final List<String> savedStates = new ArrayList<>();

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ImageAsset.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(assets.update(isNull(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<ImageAsset> update = call.getArgument(1);
            update.getSqlSet();
            for (Object value : update.getParamNameValuePairs().values()) {
                if ("APPROVED".equals(value) || "APPROVAL_FAILED".equals(value)) savedStates.add(value.toString());
            }
            return 1;
        });
    }

    private ImageAsset image() {
        ImageAsset image = new ImageAsset();
        image.setId(7L);
        image.setBatchId(3L);
        image.setStatus("APPROVING");
        image.setStorageKey("review/3/test.png");
        image.setCreateTime(LocalDateTime.of(2026, 9, 6, 12, 0));
        image.setSha256("ab".repeat(32));
        image.setSimilarity(new BigDecimal("0.3"));
        image.setGlobalVector(SscdFeatureExtractor.encode(new float[512]));
        image.setCenterVector(image.getGlobalVector());
        return image;
    }

    @Test
    void savesApprovalOnlyAfterCopyAndVectorWriteAndKeepsSourceUntilCommit() {
        worker.approve(image());
        var order = inOrder(storage, vectors, assets, transactions);
        order.verify(storage).copy("review/3/test.png", "library/2026/09/ab/7.png");
        order.verify(vectors).upsert(eq(7L), eq(3L), any(float[].class), any(float[].class));
        order.verify(assets).update(isNull(), any());
        order.verify(transactions).commit(any());
        assertEquals(List.of("APPROVED"), savedStates);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void copyFailureCannotBecomeApproved() {
        doThrow(new IllegalStateException("copy failed")).when(storage).copy(anyString(), anyString());
        worker.approve(image());
        assertEquals(List.of("APPROVAL_FAILED"), savedStates);
        verify(vectors, never()).upsert(anyLong(), anyLong(), any(float[].class), any(float[].class));
        verify(storage, never()).delete("review/3/test.png");
    }

    @Test
    void vectorFailureCannotBecomeApprovedAndPreservesSourceForRetry() {
        doThrow(new IllegalStateException("vector unavailable")).when(vectors)
            .upsert(anyLong(), anyLong(), any(float[].class), any(float[].class));
        worker.approve(image());
        assertEquals(List.of("APPROVAL_FAILED"), savedStates);
        verify(vectors).delete(List.of(7L));
        verify(storage, never()).delete("review/3/test.png");
    }

    @Test
    void uncertainCommitDoesNotDeleteSuccessfullyCommittedVector() {
        ImageAsset persisted = image();
        persisted.setStatus("APPROVED");
        when(assets.selectById(7L)).thenReturn(persisted);
        doThrow(new IllegalStateException("lost connection after commit")).when(transactions).commit(any());
        worker.approve(image());
        verify(vectors, never()).delete(anyList());
        assertEquals(List.of("APPROVED"), savedStates);
    }
}
