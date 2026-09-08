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
    private static final List<String> STAGING_VECTOR_NAMES = java.util.stream.IntStream.range(0, 11)
        .mapToObj(index -> "view_" + index).toList();

    private final RestClient client;
    private final String collection;
    private final String stagingCollection;
    private final Object initializationLock = new Object();
    private final Object stagingInitializationLock = new Object();
    private volatile boolean initialized;
    private volatile boolean stagingInitialized;

    public QdrantVectorStore(ImageProperties properties) {
        String configuredCollection = properties.getQdrant().getCollection();
        if (!configuredCollection.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Qdrant collection名称只能包含字母、数字、下划线和中划线");
        }
        collection = configuredCollection;
        stagingCollection = properties.getQdrant().getStagingCollection();
        if (!stagingCollection.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Qdrant临时collection名称只能包含字母、数字、下划线和中划线");
        }
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
        ensureMainCollection();
        ensureStagingCollection();
    }

    public String collectionName() {
        return collection;
    }

    public List<Long> recall(List<float[]> queryVectors, int limit) {
        return recallMany(List.of(queryVectors), limit).getFirst();
    }

    public List<List<Long>> recallMany(List<List<float[]>> queryVectorGroups, int limit) {
        ensureMainCollection();
        List<List<Map<String, Object>>> groups = new ArrayList<>(queryVectorGroups.size());
        for (List<float[]> queryVectors : queryVectorGroups) {
            List<Map<String, Object>> searches = new ArrayList<>(queryVectors.size() * VECTOR_NAMES.size());
            for (float[] vector : queryVectors) {
                for (String vectorName : VECTOR_NAMES) {
                    searches.add(search(vector, vectorName, limit, null));
                }
            }
            groups.add(searches);
        }
        return queryMany(collection, groups, limit);
    }

    public List<Long> recallBatch(long batchId, List<float[]> queryVectors, int limit) {
        return recallBatchMany(batchId, List.of(queryVectors), limit).getFirst();
    }

    public List<List<Long>> recallBatchMany(long batchId, List<List<float[]>> queryVectorGroups, int limit) {
        ensureStagingCollection();
        return queryMany(stagingCollection, queryVectorGroups.stream()
            .map(queryVectors -> buildBatchSearches(batchId, queryVectors, limit)).toList(), limit);
    }

    public void upsert(long id, long batchId, float[] globalVector, float[] centerVector) {
        upsert(List.of(new Point(id, batchId, globalVector, centerVector)));
    }

    public void upsert(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        ensureMainCollection();
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

    public void upsertBatch(long id, long batchId, SscdFeatureExtractor.Features features) {
        upsertBatch(List.of(new StagingPoint(id, batchId, features)));
    }

    public void upsertBatch(List<StagingPoint> points) {
        if (points == null || points.isEmpty()) return;
        ensureStagingCollection();
        List<Map<String, Object>> requestPoints = new ArrayList<>(points.size());
        for (StagingPoint point : points) {
            if (point.features().queryVectors().size() != STAGING_VECTOR_NAMES.size()) {
                throw new IllegalArgumentException("批内索引必须包含11组SSCD视图");
            }
            Map<String, Object> vectors = new LinkedHashMap<>();
            for (int index = 0; index < STAGING_VECTOR_NAMES.size(); index++) {
                vectors.put(STAGING_VECTOR_NAMES.get(index), point.features().queryVectors().get(index));
            }
            requestPoints.add(Map.of("id", point.id(), "vector", vectors,
                "payload", Map.of("asset_id", point.id(), "batch_id", point.batchId())));
        }
        client.put()
            .uri(uri -> uri.path("/collections/{collection}/points")
                .queryParam("wait", true).build(stagingCollection))
            .body(Map.of("points", requestPoints))
            .retrieve()
            .toBodilessEntity();
    }

    public void deleteBatch(long batchId) {
        ensureStagingCollection();
        client.post()
            .uri(uri -> uri.path("/collections/{collection}/points/delete")
                .queryParam("wait", true).build(stagingCollection))
            .body(Map.of("filter", batchFilter(batchId)))
            .retrieve()
            .toBodilessEntity();
    }

    public void deleteBatchPoints(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        ensureStagingCollection();
        client.post()
            .uri(uri -> uri.path("/collections/{collection}/points/delete")
                .queryParam("wait", true).build(stagingCollection))
            .body(Map.of("points", ids))
            .retrieve()
            .toBodilessEntity();
    }

    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ensureMainCollection();
        client.post()
            .uri(uri -> uri.path("/collections/{collection}/points/delete")
                .queryParam("wait", true).build(collection))
            .body(Map.of("points", ids))
            .retrieve()
            .toBodilessEntity();
    }

    public long pointCount() {
        ensureMainCollection();
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
        ensureMainCollection();
        Map<?, ?> response = client.post()
            .uri("/collections/{collection}/points", collection)
            .body(Map.of("ids", ids, "with_payload", false, "with_vector", false))
            .retrieve()
            .body(Map.class);
        return pointIds(response);
    }

    public Set<Long> allPointIds() {
        ensureMainCollection();
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

    static List<Map<String, Object>> buildBatchSearches(long batchId, List<float[]> queryVectors, int limit) {
        Map<String, Object> filter = batchFilter(batchId);
        List<Map<String, Object>> searches = new ArrayList<>();
        for (float[] vector : queryVectors) {
            for (String vectorName : STAGING_VECTOR_NAMES.subList(0, 2)) {
                searches.add(search(vector, vectorName, limit, filter));
            }
        }
        for (float[] vector : queryVectors.subList(0, Math.min(2, queryVectors.size()))) {
            for (String vectorName : STAGING_VECTOR_NAMES) {
                searches.add(search(vector, vectorName, limit, filter));
            }
        }
        return searches;
    }

    private static Map<String, Object> search(float[] vector, String vectorName, int limit,
                                               Map<String, Object> filter) {
        Map<String, Object> search = new LinkedHashMap<>();
        search.put("query", vector);
        search.put("using", vectorName);
        search.put("params", Map.of("exact", false));
        search.put("limit", limit);
        if (filter != null) search.put("filter", filter);
        search.put("with_payload", false);
        search.put("with_vector", false);
        return search;
    }

    private static Map<String, Object> batchFilter(long batchId) {
        return Map.of("must", List.of(Map.of("key", "batch_id", "match", Map.of("value", batchId))));
    }

    private List<List<Long>> queryMany(String collectionName,
                                       List<List<Map<String, Object>>> searchGroups, int limit) {
        if (searchGroups.isEmpty()) return List.of();
        List<Map<String, Object>> searches = searchGroups.stream().flatMap(List::stream).toList();
        Map<?, ?> response = client.post()
            .uri("/collections/{collection}/points/query/batch", collectionName)
            .body(Map.of("searches", searches))
            .retrieve()
            .body(Map.class);
        return candidateIdsByGroup(response, searchGroups.stream().map(List::size).toList(), limit);
    }

    static List<Long> candidateIds(Map<?, ?> response, int limit) {
        if (response == null || !(response.get("result") instanceof List<?> batches)) {
            throw new IllegalStateException("Qdrant返回数据缺少批量查询result");
        }
        return candidateIds(batches, limit);
    }

    static List<List<Long>> candidateIdsByGroup(Map<?, ?> response, List<Integer> groupSizes, int limit) {
        if (response == null || !(response.get("result") instanceof List<?> batches)) {
            throw new IllegalStateException("Qdrant返回数据缺少批量查询result");
        }
        int expected = groupSizes.stream().mapToInt(Integer::intValue).sum();
        if (batches.size() != expected) {
            throw new IllegalStateException("Qdrant批量查询结果数量不匹配: " + batches.size() + "/" + expected);
        }
        List<List<Long>> result = new ArrayList<>(groupSizes.size());
        int offset = 0;
        for (int size : groupSizes) {
            result.add(candidateIds(batches.subList(offset, offset + size), limit));
            offset += size;
        }
        return List.copyOf(result);
    }

    private static List<Long> candidateIds(List<?> batches, int limit) {
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

    private void ensureMainCollection() {
        if (initialized) {
            return;
        }
        synchronized (initializationLock) {
            if (initialized) {
                return;
            }
            ensureCollection(collection, VECTOR_NAMES);
            initialized = true;
        }
    }

    private void ensureStagingCollection() {
        if (stagingInitialized) return;
        synchronized (stagingInitializationLock) {
            if (stagingInitialized) return;
            ensureCollection(stagingCollection, STAGING_VECTOR_NAMES);
            client.put()
                .uri(uri -> uri.path("/collections/{collection}/index")
                    .queryParam("wait", true).build(stagingCollection))
                .body(Map.of("field_name", "batch_id", "field_schema", "integer"))
                .retrieve()
                .toBodilessEntity();
            stagingInitialized = true;
        }
    }

    private void ensureCollection(String collectionName, List<String> vectorNames) {
        try {
            Map<?, ?> response = client.get().uri("/collections/{collection}", collectionName)
                .retrieve().body(Map.class);
            validateCollection(response, collectionName, vectorNames);
        } catch (HttpClientErrorException.NotFound exception) {
            Map<String, Object> vectorConfig = Map.of("size", SscdFeatureExtractor.VECTOR_DIMENSION,
                "distance", "Cosine", "on_disk", false);
            Map<String, Object> vectors = new LinkedHashMap<>();
            vectorNames.forEach(name -> vectors.put(name, vectorConfig));
            client.put().uri("/collections/{collection}", collectionName)
                .body(Map.of("vectors", vectors)).retrieve().toBodilessEntity();
        }
    }

    private void validateCollection(Map<?, ?> response, String collectionName, List<String> vectorNames) {
        Object value = response == null ? null : response.get("result");
        if (!(value instanceof Map<?, ?> result)
            || !(result.get("config") instanceof Map<?, ?> config)
            || !(config.get("params") instanceof Map<?, ?> params)
            || !(params.get("vectors") instanceof Map<?, ?> vectors)) {
            throw new IllegalStateException("无法读取Qdrant集合结构: " + collectionName);
        }
        for (String name : vectorNames) {
            if (!(vectors.get(name) instanceof Map<?, ?> vector)
                || !(vector.get("size") instanceof Number size)
                || size.intValue() != SscdFeatureExtractor.VECTOR_DIMENSION
                || !"Cosine".equalsIgnoreCase(String.valueOf(vector.get("distance")))) {
                throw new IllegalStateException("Qdrant集合向量结构不符合要求: " + collectionName);
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

    public record StagingPoint(long id, long batchId, SscdFeatureExtractor.Features features) {
    }
}
