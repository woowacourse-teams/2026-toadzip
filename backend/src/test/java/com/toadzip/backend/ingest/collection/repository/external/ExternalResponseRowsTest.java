package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExternalResponseRowsTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("배열 dataset의 모든 행을 찾는다")
    void findsArrayRows() {
        var root = objectMapper.readTree("[{\"dsList\":[{\"id\":1},{\"id\":2}]}]");

        assertThat(ExternalResponseRows.find(root, "dsList"))
                .extracting(row -> row.path("id").asInt())
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("단일 객체 dataset을 한 행으로 찾는다")
    void findsSingleObjectAsOneRow() {
        var root = objectMapper.readTree("{\"dsList\":{\"id\":1}}");

        assertThat(ExternalResponseRows.find(root, "dsList"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("id").asInt()).isOne());
    }

    @Test
    @DisplayName("dataset이 없거나 행 구조가 아니면 빈 결과를 반환한다")
    void returnsEmptyRowsForMissingOrScalarDataset() {
        var missing = objectMapper.readTree("{}");
        var scalar = objectMapper.readTree("{\"dsList\":\"invalid\"}");

        assertThat(ExternalResponseRows.find(missing, "dsList")).isEmpty();
        assertThat(ExternalResponseRows.find(scalar, "dsList")).isEmpty();
    }
}
