package org.dromara.image.similarity;

import org.dromara.image.config.ImageProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tags({@Tag("dev"), @Tag("local")})
class SscdModelIntegrationTest {

    @Test
    void scoresMatchRecordedBaseline() throws Exception {
        Path model = pathProperty("sscd.model");
        Path demo = Path.of(SscdModelIntegrationTest.class.getResource("/sscd-baseline").toURI());
        Assumptions.assumeTrue(model != null && Files.isRegularFile(model));

        ImageProperties properties = new ImageProperties();
        properties.getModel().setPath(model.toString());
        SscdFeatureExtractor extractor = new SscdFeatureExtractor(properties);
        try {
            SscdFeatureExtractor.Features first = extractor.extract(demo.resolve("test_img1.jpg"), true);
            SscdFeatureExtractor.Features second = extractor.extract(demo.resolve("test_img2.jpg"), false);
            SscdFeatureExtractor.Features green = extractor.extract(demo.resolve("test_green.jpg"), false);

            assertEquals(0.79311484, SscdFeatureExtractor.bestScore(
                first.queryVectors(), second.globalVector(), second.centerVector()), 0.01);
            assertEquals(0.82988840, SscdFeatureExtractor.bestScore(
                first.queryVectors(), green.globalVector(), green.centerVector()), 0.01);
        } finally {
            extractor.close();
        }
    }

    @Test
    void allUserLabeledHardNegativesStayBelowThreshold() throws Exception {
        Path model = pathProperty("sscd.model");
        Path directory = pathProperty("sscd.hard-negative-dir");
        Assumptions.assumeTrue(model != null && Files.isRegularFile(model));
        Assumptions.assumeTrue(directory != null && Files.isDirectory(directory));

        List<Path> images;
        try (var paths = Files.list(directory)) {
            images = paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
        }
        assertEquals(24, images.size(), "困难负样本数量发生变化");

        ImageProperties properties = new ImageProperties();
        properties.getModel().setPath(model.toString());
        SscdFeatureExtractor extractor = new SscdFeatureExtractor(properties);
        try {
            List<SscdFeatureExtractor.Features> stored = new ArrayList<>();
            List<SscdFeatureExtractor.Features> query = new ArrayList<>();
            for (Path image : images) {
                stored.add(extractor.extract(image, false));
                query.add(extractor.extract(image, true));
            }
            double highest = 0;
            String pair = "";
            for (int left = 0; left < images.size(); left++) {
                for (int right = left + 1; right < images.size(); right++) {
                    double forward = SscdFeatureExtractor.bestScore(query.get(left).queryVectors(),
                        stored.get(right).globalVector(), stored.get(right).centerVector());
                    double reverse = SscdFeatureExtractor.bestScore(query.get(right).queryVectors(),
                        stored.get(left).globalVector(), stored.get(left).centerVector());
                    if (Math.max(forward, reverse) > highest) {
                        highest = Math.max(forward, reverse);
                        pair = images.get(left).getFileName() + " / " + images.get(right).getFileName();
                    }
                }
            }
            assertTrue(highest < 0.80, "困难负样本误判: " + pair + " score=" + highest);
        } finally {
            extractor.close();
        }
    }

    private static Path pathProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}
