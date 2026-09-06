package org.dromara.image.similarity;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.dromara.image.config.ImageProperties;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

@Component
@RequiredArgsConstructor
public class SscdFeatureExtractor {

    public static final int VECTOR_DIMENSION = 512;
    public static final String FEATURE_VERSION = "sscd_v1";
    public static final String EXPECTED_MODEL_SHA256 =
        "9f26bd4c848cc19b73d2ae92eea6e04886f61a7b764ceb7a13aeee62e6a6db56";
    private static final int INPUT_SIZE = 320;
    private static final int[] QUERY_ROTATIONS = {-15, -10, -5, 5, 10, 15, 90, 180, 270};
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final ImageProperties properties;
    private final Object modelLock = new Object();
    private volatile ZooModel<ModelInput, float[][]> model;
    private volatile List<Predictor<ModelInput, float[][]>> predictors = List.of();
    private volatile ArrayBlockingQueue<Predictor<ModelInput, float[][]>> predictorPool;
    private volatile String modelSha256;

    public void warmup() throws Exception {
        int workerCount = Math.max(1, properties.getTaskWorkers());
        for (int index = 0; index < workerCount; index++) {
            float[][] output = predict(new ModelInput(new float[3 * INPUT_SIZE * INPUT_SIZE], 1));
            if (output.length != 1 || output[0].length != VECTOR_DIMENSION) {
                throw new IllegalStateException("SSCD模型预热输出不正确");
            }
        }
    }

    public boolean isReady() {
        return predictorPool != null;
    }

    public String modelSha256() throws Exception {
        if (modelSha256 == null) {
            synchronized (modelLock) {
                if (modelSha256 == null) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (DigestInputStream input = new DigestInputStream(Files.newInputStream(configuredModelPath()), digest)) {
                        input.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    modelSha256 = HexFormat.of().formatHex(digest.digest());
                }
            }
        }
        return modelSha256;
    }

    public Features extract(Path imagePath, boolean includeRotations) throws Exception {
        BufferedImage image = readOriented(imagePath);
        List<BufferedImage> views = new ArrayList<>();
        views.add(image);
        views.add(centerCrop(image));
        if (includeRotations) {
            Color fill = medianCornerColor(image);
            for (int angle : QUERY_ROTATIONS) {
                views.add(centerCrop(rotateExpanded(image, angle, fill)));
            }
        }

        float[] input = new float[views.size() * 3 * INPUT_SIZE * INPUT_SIZE];
        int offset = 0;
        for (BufferedImage view : views) {
            offset = writeNormalized(resize(view), input, offset);
        }

        float[][] vectors = predict(new ModelInput(input, views.size()));
        if (vectors.length != views.size()) {
            throw new IllegalStateException("SSCD模型输出批次数量不正确: " + vectors.length);
        }
        List<float[]> queryVectors = new ArrayList<>(vectors.length);
        for (float[] vector : vectors) {
            if (vector.length != VECTOR_DIMENSION) {
                throw new IllegalStateException("SSCD模型输出维度不是512: " + vector.length);
            }
            queryVectors.add(normalize(vector));
        }
        return new Features(queryVectors.get(0), queryVectors.get(1), List.copyOf(queryVectors));
    }

    private void ensureLoaded() throws Exception {
        if (predictorPool != null) {
            return;
        }
        synchronized (modelLock) {
            if (predictorPool != null) {
                return;
            }
            Path configured = configuredModelPath();
            if (!Files.isRegularFile(configured)) {
                throw new IllegalStateException("SSCD模型文件不存在: " + configured);
            }
            Path modelPath = asciiSafePath(configured);
            Criteria<ModelInput, float[][]> criteria = Criteria.builder()
                .setTypes(ModelInput.class, float[][].class)
                .optEngine("PyTorch")
                .optModelPath(modelPath.getParent())
                .optModelName(modelPath.getFileName().toString())
                .optTranslator(new SscdTranslator())
                .build();
            model = criteria.loadModel();
            int workerCount = Math.max(1, properties.getTaskWorkers());
            List<Predictor<ModelInput, float[][]>> created = new ArrayList<>(workerCount);
            for (int index = 0; index < workerCount; index++) {
                created.add(model.newPredictor());
            }
            predictors = List.copyOf(created);
            predictorPool = new ArrayBlockingQueue<>(workerCount, true, created);
        }
    }

    private float[][] predict(ModelInput input) throws Exception {
        ensureLoaded();
        ArrayBlockingQueue<Predictor<ModelInput, float[][]>> pool = predictorPool;
        Predictor<ModelInput, float[][]> current;
        try {
            current = pool.take();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
        try {
            return current.predict(input);
        } finally {
            pool.offer(current);
        }
    }

    private Path configuredModelPath() {
        return Path.of(properties.getModel().getPath()).toAbsolutePath().normalize();
    }

    private static Path asciiSafePath(Path source) throws IOException {
        if (source.toString().chars().allMatch(value -> value < 128)) {
            return source;
        }
        Path targetDirectory = Path.of(System.getProperty("java.io.tmpdir"), "image-admin-model");
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve(source.getFileName().toString());
        if (!Files.isRegularFile(target) || Files.size(target) != Files.size(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public static BufferedImage readOriented(Path path) throws Exception {
        BufferedImage source = ImageIO.read(path.toFile());
        if (source == null) {
            throw new IOException("无法解析图片内容: " + path.getFileName());
        }
        int orientation = 1;
        try {
            ExifIFD0Directory directory = ImageMetadataReader.readMetadata(path.toFile())
                .getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (Exception ignored) {
            orientation = 1;
        }
        return orient(toRgb(source), orientation);
    }

    private static BufferedImage toRgb(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return rgb;
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation < 2 || orientation > 8) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage target = new BufferedImage(swap ? height : width, swap ? width : height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int targetX;
                int targetY;
                switch (orientation) {
                    case 2 -> { targetX = width - 1 - x; targetY = y; }
                    case 3 -> { targetX = width - 1 - x; targetY = height - 1 - y; }
                    case 4 -> { targetX = x; targetY = height - 1 - y; }
                    case 5 -> { targetX = y; targetY = x; }
                    case 6 -> { targetX = height - 1 - y; targetY = x; }
                    case 7 -> { targetX = height - 1 - y; targetY = width - 1 - x; }
                    case 8 -> { targetX = y; targetY = width - 1 - x; }
                    default -> { targetX = x; targetY = y; }
                }
                target.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return target;
    }

    private static BufferedImage centerCrop(BufferedImage source) {
        int marginX = (int) (source.getWidth() * 0.1);
        int marginY = (int) (source.getHeight() * 0.1);
        int width = Math.max(1, source.getWidth() - marginX * 2);
        int height = Math.max(1, source.getHeight() - marginY * 2);
        return toRgb(source.getSubimage(marginX, marginY, width, height));
    }

    private static Color medianCornerColor(BufferedImage source) {
        int[] pixels = {
            source.getRGB(0, 0),
            source.getRGB(source.getWidth() - 1, 0),
            source.getRGB(0, source.getHeight() - 1),
            source.getRGB(source.getWidth() - 1, source.getHeight() - 1)
        };
        int[] red = new int[4];
        int[] green = new int[4];
        int[] blue = new int[4];
        for (int index = 0; index < pixels.length; index++) {
            Color color = new Color(pixels[index]);
            red[index] = color.getRed();
            green[index] = color.getGreen();
            blue[index] = color.getBlue();
        }
        return new Color(median(red), median(green), median(blue));
    }

    private static int median(int[] values) {
        Arrays.sort(values);
        return (values[1] + values[2]) / 2;
    }

    private static BufferedImage rotateExpanded(BufferedImage source, int angle, Color fill) {
        double radians = Math.toRadians(-angle);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));
        int targetWidth = Math.max(1, (int) Math.ceil(source.getWidth() * cos + source.getHeight() * sin));
        int targetHeight = Math.max(1, (int) Math.ceil(source.getHeight() * cos + source.getWidth() * sin));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setColor(fill);
        graphics.fillRect(0, 0, targetWidth, targetHeight);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        AffineTransform transform = new AffineTransform();
        transform.translate((targetWidth - source.getWidth()) / 2.0, (targetHeight - source.getHeight()) / 2.0);
        transform.rotate(radians, source.getWidth() / 2.0, source.getHeight() / 2.0);
        graphics.drawRenderedImage(source, transform);
        graphics.dispose();
        return target;
    }

    private static BufferedImage resize(BufferedImage source) {
        BufferedImage target = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        graphics.dispose();
        return target;
    }

    private static int writeNormalized(BufferedImage image, float[] target, int offset) {
        int planeSize = INPUT_SIZE * INPUT_SIZE;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int pixel = image.getRGB(x, y);
                int index = y * INPUT_SIZE + x;
                target[offset + index] = ((((pixel >> 16) & 0xff) / 255.0f) - MEAN[0]) / STD[0];
                target[offset + planeSize + index] = ((((pixel >> 8) & 0xff) / 255.0f) - MEAN[1]) / STD[1];
                target[offset + planeSize * 2 + index] = (((pixel & 0xff) / 255.0f) - MEAN[2]) / STD[2];
            }
        }
        return offset + planeSize * 3;
    }

    private static float[] normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            throw new IllegalStateException("SSCD模型返回了零向量");
        }
        float[] normalized = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = (float) (vector[index] / norm);
        }
        return normalized;
    }

    public static double cosine(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("向量维度不一致");
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return Math.max(-1, Math.min(1, dot / Math.sqrt(leftNorm * rightNorm)));
    }

    public static double bestScore(List<float[]> queryVectors, float[] candidateGlobal, float[] candidateCenter) {
        double best = 0;
        for (float[] query : queryVectors) {
            best = Math.max(best, cosine(query, candidateGlobal));
            best = Math.max(best, cosine(query, candidateCenter));
        }
        return best;
    }

    public static double symmetricBestScore(Features left, Features right) {
        return Math.max(
            bestScore(left.queryVectors(), right.globalVector(), right.centerVector()),
            bestScore(right.queryVectors(), left.globalVector(), left.centerVector()));
    }

    public static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length != VECTOR_DIMENSION * Float.BYTES) {
            throw new IllegalArgumentException("无效的SSCD向量数据");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[VECTOR_DIMENSION];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }
        return vector;
    }

    @PreDestroy
    public void close() {
        synchronized (modelLock) {
            predictorPool = null;
            predictors.forEach(Predictor::close);
            predictors = List.of();
            if (model != null) {
                model.close();
                model = null;
            }
        }
    }

    public record Features(float[] globalVector, float[] centerVector, List<float[]> queryVectors) {
    }

    private record ModelInput(float[] data, int batchSize) {
    }

    private static final class SscdTranslator implements Translator<ModelInput, float[][]> {

        @Override
        public NDList processInput(TranslatorContext context, ModelInput input) {
            NDArray array = context.getNDManager().create(
                input.data(), new Shape(input.batchSize(), 3, INPUT_SIZE, INPUT_SIZE));
            return new NDList(array);
        }

        @Override
        public float[][] processOutput(TranslatorContext context, NDList list) {
            NDArray output = list.singletonOrThrow();
            Shape shape = output.getShape();
            if (shape.dimension() != 2 || shape.get(1) != VECTOR_DIMENSION) {
                throw new IllegalStateException("SSCD模型输出形状不正确: " + shape);
            }
            int rows = Math.toIntExact(shape.get(0));
            float[] values = output.toFloatArray();
            float[][] result = new float[rows][VECTOR_DIMENSION];
            for (int row = 0; row < rows; row++) {
                System.arraycopy(values, row * VECTOR_DIMENSION, result[row], 0, VECTOR_DIMENSION);
            }
            return result;
        }

        @Override
        public Batchifier getBatchifier() {
            return null;
        }
    }
}
