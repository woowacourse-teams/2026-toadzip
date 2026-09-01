package com.toadzip.backend.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestTraceIdResolverTest {

    @Test
    void 컨테이너_requestId가_없어도_같은_요청에는_같은_traceId를_반환한다() {
        HttpServletRequest request = new HttpServletRequestWrapper(new MockHttpServletRequest()) {
            @Override
            public String getRequestId() {
                return "";
            }
        };

        String firstTraceId = RequestTraceIdResolver.resolve(request);
        String secondTraceId = RequestTraceIdResolver.resolve(request);

        assertThat(firstTraceId).isNotBlank();
        assertThat(secondTraceId).isEqualTo(firstTraceId);
    }
}
