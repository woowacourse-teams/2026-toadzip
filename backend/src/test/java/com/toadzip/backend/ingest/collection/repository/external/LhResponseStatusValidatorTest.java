package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LhResponseStatusValidatorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final LhResponseStatusValidator validator = new LhResponseStatusValidator();

    @Test
    @DisplayName("LH 성공 상태 응답을 허용한다")
    void acceptsSuccessStatus() {
        var root = objectMapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]}]");

        assertThatCode(() -> validator.validate(root)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("LH 오류 상태 응답을 원천 오류로 변환한다")
    void rejectsFailureStatus() {
        var root = objectMapper.readTree("""
                [{"resHeader":[{"SS_CODE":"N","RS_MSG":"조회 실패"}]}]
                """);

        assertThatThrownBy(() -> validator.validate(root))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessage("원천 오류 SS_CODE=N, 조회 실패");
    }
}
