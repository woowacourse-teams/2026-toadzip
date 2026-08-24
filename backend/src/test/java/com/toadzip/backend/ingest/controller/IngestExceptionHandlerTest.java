package com.toadzip.backend.ingest.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;

class IngestExceptionHandlerTest {

    private final IngestExceptionHandler handler = new IngestExceptionHandler();

    @Test
    void 필수_파라미터_누락은_공통_입력_오류_응답으로_변환한다() {
        var exception = new MissingServletRequestParameterException("requiredCode", "String");

        var response = handler.handleInvalidRequest(exception, new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_INGEST_REQUEST");
        assertThat(response.getBody().message()).isEqualTo("필수 요청 파라미터가 없습니다: requiredCode");
        assertThat(response.getBody().traceId()).isNotBlank();
    }
}
