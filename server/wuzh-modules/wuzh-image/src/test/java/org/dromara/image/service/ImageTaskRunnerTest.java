package org.dromara.image.service;

import org.dromara.image.domain.ImageStatus;
import org.dromara.image.domain.ImageAsset;
import org.dromara.image.similarity.SscdFeatureExtractor;
import org.dromara.image.storage.ImageStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@org.junit.jupiter.api.Tag("local")
class ImageTaskRunnerTest {

    @Test
    void everyValidDetectionResultWaitsForHumanReview() {
        assertEquals(ImageStatus.WAIT_REVIEW,
            ImageStatus.forSimilarity(new BigDecimal("0.10"), new BigDecimal("0.60")));
        assertEquals(ImageStatus.WAIT_REVIEW,
            ImageStatus.forSimilarity(new BigDecimal("0.80"), new BigDecimal("0.60")));
        assertEquals(ImageStatus.WAIT_REVIEW,
            ImageStatus.forSimilarity(BigDecimal.ONE, new BigDecimal("0.60")));
    }

    @Test
    void recoveredCandidateFallsBackToMysqlVectorWhenStagingRecallMissesIt() {
        float[] vector = new float[SscdFeatureExtractor.VECTOR_DIMENSION];
        vector[0] = 1;
        ImageAsset recovered = new ImageAsset();
        recovered.setId(7L);
        recovered.setGlobalVector(SscdFeatureExtractor.encode(vector));
        recovered.setCenterVector(SscdFeatureExtractor.encode(vector));
        SscdFeatureExtractor.Features current = new SscdFeatureExtractor.Features(vector, vector, List.of(vector));

        ImageTaskRunner.Match match = ImageTaskRunner.findBatchMatch(List.of(),
            Map.of(7L, new ImageTaskRunner.BatchCandidate(recovered, null)), current);

        assertEquals(7L, match.imageId());
        assertEquals(1d, match.score(), 0.00001);
    }

    @Test
    void closesAllTemporaryFiles(@TempDir Path directory) throws Exception {
        Path first = Files.writeString(directory.resolve("first.jpg"), "first");
        Path second = Files.writeString(directory.resolve("second.jpg"), "second");

        ImageTaskRunner.closeLocalFiles(List.of(
            new ImageTaskRunner.PreparedImage(new ImageAsset(), new ImageStorage.LocalFile(first, true)),
            new ImageTaskRunner.PreparedImage(new ImageAsset(), new ImageStorage.LocalFile(second, true))));

        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }
}
