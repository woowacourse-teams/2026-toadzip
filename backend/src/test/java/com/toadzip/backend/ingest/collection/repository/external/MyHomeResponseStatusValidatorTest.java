package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MyHomeResponseStatusValidatorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final MyHomeResponseStatusValidator validator = new MyHomeResponseStatusValidator();

    @Test
    @DisplayName("마이홈 성공과 데이터 없음 상태 응답을 허용한다")
    void acceptsSuccessStatuses() {
        var success = objectMapper.readTree("{\"response\":{\"header\":{\"resultCode\":\"00\"}}}");
        var noData = objectMapper.readTree("{\"response\":{\"header\":{\"resultCode\":\"03\"}}}");

        assertThatCode(() -> validator.validate(success)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(noData)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("마이홈 초당 요청 한도 상태를 재시도 가능한 오류로 변환한다")
    void marksPerSecondLimitAsRetryable() {
        var root = objectMapper.readTree("""
                {"response":{"header":{"resultCode":"23","resultMsg":"초당 호출 한도 초과"}}}
                """);

        assertThatThrownBy(() -> validator.validate(root))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, exception -> {
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.isRateLimited()).isTrue();
                });
    }
}
