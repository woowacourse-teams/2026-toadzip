package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("dataset이 없으면 빈 결과를 반환한다")
    void returnsEmptyRowsForMissingDataset() {
        var missing = objectMapper.readTree("{}");

        assertThat(ExternalResponseRows.find(missing, "dsList")).isEmpty();
    }

    @Test
    @DisplayName("dataset이 null 또는 스칼라이면 실패한다")
    void rejectsNullOrScalarDataset() {
        var nullDataset = objectMapper.readTree("{\"dsList\":null}");
        var scalarDataset = objectMapper.readTree("{\"dsList\":\"invalid\"}");

        assertThatThrownBy(() -> ExternalResponseRows.find(nullDataset, "dsList"))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> ExternalResponseRows.find(scalarDataset, "dsList"))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    @DisplayName("dataset 배열의 행이 객체가 아니면 실패한다")
    void rejectsScalarDatasetRow() {
        var root = objectMapper.readTree("{\"dsList\":[{\"id\":1},2]}");

        assertThatThrownBy(() -> ExternalResponseRows.find(root, "dsList"))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("외부 응답 dataset의 행은 객체여야 합니다.");
    }
}
