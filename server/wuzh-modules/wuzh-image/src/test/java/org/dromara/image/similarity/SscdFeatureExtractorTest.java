package org.dromara.image.similarity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tags({@Tag("dev"), @Tag("local")})
class SscdFeatureExtractorTest {

    @Test
    void vectorCodecKeepsFloat32Values() {
        float[] source = new float[SscdFeatureExtractor.VECTOR_DIMENSION];
        source[0] = 0.125f;
        source[257] = -0.75f;
        source[511] = 1.0f;

        assertArrayEquals(source, SscdFeatureExtractor.decode(SscdFeatureExtractor.encode(source)));
    }

    @Test
    void cosineUsesNormalizedDirection() {
        assertEquals(1.0, SscdFeatureExtractor.cosine(new float[]{2, 0}, new float[]{5, 0}), 1e-9);
        assertEquals(0.0, SscdFeatureExtractor.cosine(new float[]{1, 0}, new float[]{0, 1}), 1e-9);
    }

    @Test
    void batchScoreDoesNotDependOnUploadOrder() {
        SscdFeatureExtractor.Features left = new SscdFeatureExtractor.Features(
            new float[]{1, 0}, new float[]{1, 0},
            List.of(new float[]{1, 0}, new float[]{0, 1}));
        SscdFeatureExtractor.Features right = new SscdFeatureExtractor.Features(
            new float[]{0, 1}, new float[]{0, 1},
            List.of(new float[]{0, 1}));

        double leftFirst = SscdFeatureExtractor.symmetricBestScore(left, right);
        double rightFirst = SscdFeatureExtractor.symmetricBestScore(right, left);

        assertEquals(1.0, leftFirst, 1e-9);
        assertEquals(leftFirst, rightFirst, 1e-9);
    }
}
