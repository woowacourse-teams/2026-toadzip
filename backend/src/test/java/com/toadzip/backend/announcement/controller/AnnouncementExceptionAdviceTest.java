package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.toadzip.backend.global.exception.RequestTraceIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AnnouncementExceptionAdviceTest {

    private final AnnouncementExceptionAdvice advice = new AnnouncementExceptionAdvice();

    @Test
    void 공통_요청_추적_식별자를_오류_응답에_사용한다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String traceId = RequestTraceIdResolver.resolve(request);

        var response = advice.handleInvalidRequest(request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().traceId()).isEqualTo(traceId);
    }
}
