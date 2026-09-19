package org.dromara.image.similarity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tags({@Tag("dev"), @Tag("local")})
class QdrantVectorStoreTest {

    @Test
    void mergesBatchResultsByHighestRecallScore() {
        Map<String, Object> response = Map.of("result", List.of(
            Map.of("points", List.of(
                Map.of("id", 1, "score", 0.81),
                Map.of("id", 2, "score", 0.75)
            )),
            Map.of("points", List.of(
                Map.of("id", 2, "score", 0.93),
                Map.of("id", 3, "score", 0.72)
            ))
        ));

        assertEquals(List.of(2L, 1L), QdrantVectorStore.candidateIds(response, 2));
    }

    @Test
    void readsOnlyExistingPointIdsFromQdrantRetrieveResponse() {
        Map<String, Object> response = Map.of("result", List.of(
            Map.of("id", 101),
            Map.of("id", "103")
        ));

        assertEquals(Set.of(101L, 103L), QdrantVectorStore.pointIds(response));
    }

    @Test
    void everyIntraBatchRecallQueryIsRestrictedToItsBatch() {
        List<float[]> vectors = List.of(new float[]{1, 0}, new float[]{0, 1});
        List<Map<String, Object>> searches = QdrantVectorStore.buildBatchSearches(42L, vectors, 30);

        assertEquals(14, searches.size());
        assertTrue(searches.stream().allMatch(search -> search.get("filter").equals(Map.of(
            "must", List.of(Map.of("key", "batch_id", "match", Map.of("value", 42L)))))));
    }

    @Test
    void keepsCandidatesSeparatedWhenSeveralImagesShareOneQdrantRequest() {
        Map<String, Object> response = Map.of("result", List.of(
            Map.of("points", List.of(Map.of("id", 1, "score", 0.91))),
            Map.of("points", List.of(Map.of("id", 2, "score", 0.82))),
            Map.of("points", List.of(Map.of("id", 3, "score", 0.93))),
            Map.of("points", List.of(Map.of("id", 4, "score", 0.84)))
        ));

        assertEquals(List.of(List.of(1L, 2L), List.of(3L, 4L)),
            QdrantVectorStore.candidateIdsByGroup(response, List.of(2, 2), 30));
    }
}
