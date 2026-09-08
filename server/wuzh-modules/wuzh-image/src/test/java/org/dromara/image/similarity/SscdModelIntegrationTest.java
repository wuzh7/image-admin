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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tags({@Tag("dev"), @Tag("local")})
class SscdModelIntegrationTest {

    @Test
    void benchmarkRealImagesWithMicroBatches() throws Exception {
        Path model = pathProperty("sscd.model");
        Path directory = pathProperty("sscd.benchmark-dir");
        Assumptions.assumeTrue(model != null && Files.isRegularFile(model));
        Assumptions.assumeTrue(directory != null && Files.isDirectory(directory));
        int count = Integer.getInteger("sscd.benchmark-count", 12);
        List<Path> images;
        try (var paths = Files.walk(directory)) {
            images = paths.filter(Files::isRegularFile)
                .filter(SscdModelIntegrationTest::isSupportedImage)
                .sorted(Comparator.naturalOrder()).limit(count).toList();
        }
        assertEquals(count, images.size(), "真实图片样本数不足");

        ImageProperties properties = new ImageProperties();
        properties.setTaskWorkers(1);
        properties.getModel().setPath(model.toString());
        SscdFeatureExtractor extractor = new SscdFeatureExtractor(properties);
        try {
            extractor.extract(images.getFirst(), true);
            List<SscdFeatureExtractor.Features> baseline = null;
            for (int batchSize : List.of(1, 2, 4)) {
                long started = System.nanoTime();
                List<SscdFeatureExtractor.Features> current = extractInGroups(extractor, images, batchSize);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                System.out.printf(Locale.ROOT,
                    "SSCD_BENCHMARK batchSize=%d images=%d elapsedMs=%d imagesPerMinute=%.2f%n",
                    batchSize, images.size(), elapsedMs, images.size() * 60_000d / elapsedMs);
                if (baseline == null) {
                    baseline = current;
                } else {
                    assertSameFeatures(baseline, current);
                }
            }
        } finally {
            extractor.close();
        }
    }

    @Test
    void microBatchProducesTheSameVectorsAsSingleImageInference() throws Exception {
        Path model = pathProperty("sscd.model");
        Path demo = Path.of(SscdModelIntegrationTest.class.getResource("/sscd-baseline").toURI());
        Assumptions.assumeTrue(model != null && Files.isRegularFile(model));
        ImageProperties properties = new ImageProperties();
        properties.getModel().setPath(model.toString());
        SscdFeatureExtractor extractor = new SscdFeatureExtractor(properties);
        try {
            List<Path> paths = List.of(demo.resolve("test_img1.jpg"), demo.resolve("test_img2.jpg"));
            List<SscdFeatureExtractor.Features> batch = extractor.extractBatch(paths, true);
            assertArrayEquals(extractor.extract(paths.get(0), true).globalVector(), batch.get(0).globalVector(), 0.00001f);
            assertArrayEquals(extractor.extract(paths.get(1), true).globalVector(), batch.get(1).globalVector(), 0.00001f);
        } finally {
            extractor.close();
        }
    }

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

    private static List<SscdFeatureExtractor.Features> extractInGroups(
        SscdFeatureExtractor extractor, List<Path> images, int batchSize) throws Exception {
        List<SscdFeatureExtractor.Features> result = new ArrayList<>();
        for (int offset = 0; offset < images.size(); offset += batchSize) {
            result.addAll(extractor.extractBatch(
                images.subList(offset, Math.min(offset + batchSize, images.size())), true));
        }
        return result;
    }

    private static void assertSameFeatures(List<SscdFeatureExtractor.Features> expected,
                                           List<SscdFeatureExtractor.Features> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).globalVector(), actual.get(index).globalVector(), 0.00001f);
            assertArrayEquals(expected.get(index).centerVector(), actual.get(index).centerVector(), 0.00001f);
        }
    }

    private static boolean isSupportedImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".png") || name.endsWith(".webp");
    }
}
