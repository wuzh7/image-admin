package org.dromara.image.similarity;

import org.dromara.common.core.utils.StringUtils;
import org.dromara.image.config.ImageProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class QdrantVectorStore {

    private static final List<String> VECTOR_NAMES = List.of("global", "center");

    private final RestClient client;
    private final String collection;
    private final Object initializationLock = new Object();
    private volatile boolean initialized;

    public QdrantVectorStore(ImageProperties properties) {
        String configuredCollection = properties.getQdrant().getCollection();
        if (!configuredCollection.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Qdrant collection名称只能包含字母、数字、下划线和中划线");
        }
        collection = configuredCollection;
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        RestClient.Builder builder = RestClient.builder()
            .requestFactory(requestFactory)
            .baseUrl(properties.getQdrant().getUrl().replaceAll("/+$", ""));
        if (StringUtils.isNotBlank(properties.getQdrant().getApiKey())) {
            builder.defaultHeader("api-key", properties.getQdrant().getApiKey());
        }
        client = builder.defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json").build();
    }

    public void initialize() {
        ensureCollection();
    }

    public String collectionName() {
        return collection;
    }

    public List<Long> recall(List<float[]> queryVectors, int limit) {
        ensureCollection();
        List<Map<String, Object>> searches = new ArrayList<>(queryVectors.size() * VECTOR_NAMES.size());
        for (float[] vector : queryVectors) {
            for (String vectorName : VECTOR_NAMES) {
                searches.add(Map.of(
                    "query", vector,
                    "using", vectorName,
                    "params", Map.of("exact", false),
                    "limit", limit,
                    "with_payload", false,
                    "with_vector", false
                ));
            }
        }
        Map<?, ?> response = client.post()
            .uri("/collections/{collection}/points/query/batch", collection)
            .body(Map.of("searches", searches))
            .retrieve()
            .body(Map.class);
        return candidateIds(response, limit);
    }

    public void upsert(long id, long batchId, float[] globalVector, float[] centerVector) {
        upsert(List.of(new Point(id, batchId, globalVector, centerVector)));
    }

    public void upsert(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        ensureCollection();
        List<Map<String, Object>> requestPoints = points.stream()
            .map(point -> Map.<String, Object>of(
                "id", point.id(),
                "vector", Map.of("global", point.globalVector(), "center", point.centerVector()),
                "payload", Map.of("asset_id", point.id(), "batch_id", point.batchId(),
                    "feature_version", SscdFeatureExtractor.FEATURE_VERSION)
            ))
            .toList();
        client.put()
            .uri(uri -> uri.path("/collections/{collection}/points")
                .queryParam("wait", true).build(collection))
            .body(Map.of("points", requestPoints))
            .retrieve()
            .toBodilessEntity();
    }

    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ensureCollection();
        client.post()
            .uri(uri -> uri.path("/collections/{collection}/points/delete")
                .queryParam("wait", true).build(collection))
            .body(Map.of("points", ids))
            .retrieve()
            .toBodilessEntity();
    }

    public long pointCount() {
        ensureCollection();
        Map<?, ?> response = client.post()
            .uri("/collections/{collection}/points/count", collection)
            .body(Map.of("exact", true))
            .retrieve()
            .body(Map.class);
        Object result = response == null ? null : response.get("result");
        if (result instanceof Map<?, ?> map && map.get("count") instanceof Number count) {
            return count.longValue();
        }
        throw new IllegalStateException("Qdrant返回数据缺少count");
    }

    public Set<Long> existingPointIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        ensureCollection();
        Map<?, ?> response = client.post()
            .uri("/collections/{collection}/points", collection)
            .body(Map.of("ids", ids, "with_payload", false, "with_vector", false))
            .retrieve()
            .body(Map.class);
        return pointIds(response);
    }

    public Set<Long> allPointIds() {
        ensureCollection();
        Set<Long> ids = new HashSet<>();
        Object offset = null;
        do {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("limit", 256);
            body.put("with_payload", false);
            body.put("with_vector", false);
            if (offset != null) {
                body.put("offset", offset);
            }
            Map<?, ?> response = client.post()
                .uri("/collections/{collection}/points/scroll", collection)
                .body(body)
                .retrieve()
                .body(Map.class);
            if (response == null || !(response.get("result") instanceof Map<?, ?> result)) {
                throw new IllegalStateException("Qdrant返回数据缺少scroll result");
            }
            Object points = result.get("points");
            if (points instanceof List<?> list) {
                for (Object value : list) {
                    if (value instanceof Map<?, ?> point) {
                        Long id = parseId(point.get("id"));
                        if (id != null) {
                            ids.add(id);
                        }
                    }
                }
            }
            offset = result.get("next_page_offset");
        } while (offset != null);
        return ids;
    }

    static List<Long> candidateIds(Map<?, ?> response, int limit) {
        if (response == null || !(response.get("result") instanceof List<?> batches)) {
            throw new IllegalStateException("Qdrant返回数据缺少批量查询result");
        }
        Map<Long, Double> candidates = new LinkedHashMap<>();
        for (Object batchValue : batches) {
            if (!(batchValue instanceof Map<?, ?> batch) || !(batch.get("points") instanceof List<?> points)) {
                continue;
            }
            for (Object value : points) {
                if (!(value instanceof Map<?, ?> point) || !(point.get("score") instanceof Number score)) {
                    continue;
                }
                Long id = parseId(point.get("id"));
                if (id != null) {
                    candidates.merge(id, score.doubleValue(), Math::max);
                }
            }
        }
        return candidates.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }

    static Set<Long> pointIds(Map<?, ?> response) {
        if (response == null || !(response.get("result") instanceof List<?> points)) {
            throw new IllegalStateException("Qdrant返回数据缺少points result");
        }
        Set<Long> ids = new HashSet<>();
        for (Object value : points) {
            if (value instanceof Map<?, ?> point) {
                Long id = parseId(point.get("id"));
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private void ensureCollection() {
        if (initialized) {
            return;
        }
        synchronized (initializationLock) {
            if (initialized) {
                return;
            }
            try {
                Map<?, ?> response = client.get()
                    .uri("/collections/{collection}", collection)
                    .retrieve()
                    .body(Map.class);
                validateCollection(response);
            } catch (HttpClientErrorException.NotFound exception) {
                Map<String, Object> vectorConfig = Map.of(
                    "size", SscdFeatureExtractor.VECTOR_DIMENSION,
                    "distance", "Cosine",
                    "on_disk", false
                );
                client.put()
                    .uri("/collections/{collection}", collection)
                    .body(Map.of("vectors", Map.of("global", vectorConfig, "center", vectorConfig)))
                    .retrieve()
                    .toBodilessEntity();
            }
            initialized = true;
        }
    }

    private void validateCollection(Map<?, ?> response) {
        Object value = response == null ? null : response.get("result");
        if (!(value instanceof Map<?, ?> result)
            || !(result.get("config") instanceof Map<?, ?> config)
            || !(config.get("params") instanceof Map<?, ?> params)
            || !(params.get("vectors") instanceof Map<?, ?> vectors)) {
            throw new IllegalStateException("无法读取Qdrant集合结构: " + collection);
        }
        for (String name : VECTOR_NAMES) {
            if (!(vectors.get(name) instanceof Map<?, ?> vector)
                || !(vector.get("size") instanceof Number size)
                || size.intValue() != SscdFeatureExtractor.VECTOR_DIMENSION
                || !"Cosine".equalsIgnoreCase(String.valueOf(vector.get("distance")))) {
                throw new IllegalStateException("Qdrant集合不是global/center双512维Cosine结构: " + collection);
            }
        }
    }

    private static Long parseId(Object id) {
        if (id instanceof Number number) {
            return number.longValue();
        }
        if (id instanceof String text) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public record Point(long id, long batchId, float[] globalVector, float[] centerVector) {
    }
}
