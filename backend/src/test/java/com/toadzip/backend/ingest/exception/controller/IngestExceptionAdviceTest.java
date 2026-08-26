package com.toadzip.backend.ingest.exception.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.toadzip.backend.ingest.exception.exception.InvalidIngestRequestException;

class IngestExceptionAdviceTest {

    private final IngestExceptionAdvice advice = new IngestExceptionAdvice();

    @Test
    void 기능_예외를_고정된_오류_계약으로_변환한다() {
        var exception = new InvalidIngestRequestException("수집 요청값이 올바르지 않습니다.");

        var response = advice.handleInvalidIngestRequest(exception, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_INGEST_REQUEST");
        assertThat(response.getBody().message()).isEqualTo("수집 요청값이 올바르지 않습니다.");
        assertThat(response.getBody().traceId()).isNotBlank();
        assertThat(response.getBody().errors()).isEmpty();
    }
}
