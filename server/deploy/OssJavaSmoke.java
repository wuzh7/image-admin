import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.dromara.image.config.ImageOssConfiguration;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.storage.OssImageStorage;

/** Temporary ECS probe using the actual production Java storage implementation. */
public class OssJavaSmoke {
    public static void main(String[] args) throws Exception {
        var properties = new ImageProperties();
        var config = properties.getStorage().getOss();
        config.setBucket("image-admin-prod-ww");
        config.setRegion("cn-hangzhou");
        config.setRoleName("image-admin-prod-ecs-role");
        config.setEndpoint("https://s3.oss-cn-hangzhou-internal.aliyuncs.com");
        config.setPublicEndpoint("https://s3.oss-cn-hangzhou.aliyuncs.com");
        var factory = new ImageOssConfiguration();
        try (var internal = factory.imageOssInternal(properties); var external = factory.imageOssExternal(properties)) {
            var storage = new OssImageStorage(properties, internal, external);
            String name = "oss-smoke-java-" + UUID.randomUUID() + ".png";
            String source = "review/" + name, target = "library/" + name;
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", bytes);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            try {
                String url = storage.signUpload(source, "image/png", bytes.size());
                var upload = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "image/png").header("x-oss-forbid-overwrite", "true")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
                int status = http.send(upload, HttpResponse.BodyHandlers.discarding()).statusCode();
                check(status == 200, "signed upload HTTP " + status);
                storage.verifyUpload(source, "image/png", bytes.size());
                System.out.println("PASS Java signed upload and internal HEAD");
                check(http.send(upload, HttpResponse.BodyHandlers.discarding()).statusCode() == 409, "overwrite must fail");
                System.out.println("PASS Java signed upload overwrite protection");
                try (var file = storage.open(source)) {
                    check(java.util.Arrays.equals(bytes.toByteArray(), Files.readAllBytes(file.path())), "download bytes mismatch");
                }
                System.out.println("PASS Java internal bounded download");
                var thumbnail = HttpRequest.newBuilder(URI.create(storage.accessUrl(source, true).orElseThrow()))
                    .timeout(Duration.ofSeconds(30)).GET().build();
                check(http.send(thumbnail, HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "thumbnail failed");
                System.out.println("PASS Java public signed thumbnail");
                storage.copy(source, target);
                storage.verifyUpload(target, "image/png", bytes.size());
                System.out.println("PASS Java copy and verification");
            } finally {
                for (String key : new String[]{source, target}) {
                    storage.delete(key);
                    System.out.println("CLEANED " + key);
                }
            }
        } catch (Exception exception) {
            // Do not print SDK exceptions containing signed URLs or credentials.
            System.err.println("FAIL Java smoke: " + exception.getClass().getSimpleName());
            System.exit(1);
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
