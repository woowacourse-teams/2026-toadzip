package com.toadzip.backend.ingest.pipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.pipeline.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
class IngestExecutionLockInterceptorTest {

    @Mock
    private DataPipelineExecutionLock executionLock;

    @Mock
    private DataPipelineExecutionLock.Lease lease;

    private IngestExecutionLockInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new IngestExecutionLockInterceptor(new IngestExecutionOwnershipService(executionLock));
    }

    @Test
    void 개별_ingest_POST는_전역_실행_잠금을_요청_종료까지_보유한다() {
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        MockHttpServletRequest request = request("POST", "/api/admin/ingest/lh/lease-catalog");

        assertThat(interceptor.preHandle(request, response(), new Object())).isTrue();
        interceptor.afterCompletion(request, response(), new Object(), null);

        verify(lease).close();
    }

    @Test
    void 다른_작업이_실행_중이면_개별_ingest_POST를_거부한다() {
        when(executionLock.tryAcquire()).thenReturn(Optional.empty());
        MockHttpServletRequest request = request("POST", "/api/admin/ingest/myhome/complexes");

        assertThatThrownBy(() -> interceptor.preHandle(request, response(), new Object()))
                .isInstanceOf(IngestAlreadyRunningException.class)
                .hasMessage("다른 데이터 수집·정제 작업이 이미 실행 중입니다.");
    }

    @Test
    void 자체적으로_잠금을_보유하는_파이프라인_POST는_통과시킨다() {
        MockHttpServletRequest request = request(
                "POST",
                "/api/admin/ingest/pipelines/announcement-collection"
        );

        assertThat(interceptor.preHandle(request, response(), new Object())).isTrue();

        verify(executionLock, never()).tryAcquire();
    }

    @Test
    void 비동기_등록_접수는_서비스가_잠금을_소유하고_다른_등록_변경은_요청_잠금을_유지한다() {
        var registration = request("POST", "/api/admin/ingest/myhome/initial-registration/register");
        assertThat(interceptor.preHandle(registration, response(), new Object())).isTrue();
        interceptor.afterCompletion(registration, response(), new Object(), null);
        var retry = request("POST", "/api/admin/ingest/myhome/initial-registration/retry");
        assertThat(interceptor.preHandle(retry, response(), new Object())).isTrue();
        interceptor.afterCompletion(retry, response(), new Object(), null);
        verify(executionLock, never()).tryAcquire();
        when(executionLock.tryAcquire()).thenReturn(Optional.of(lease));
        var refresh = request("POST", "/api/admin/ingest/myhome/initial-registration/refresh");
        assertThat(interceptor.preHandle(refresh, response(), new Object())).isTrue();
        interceptor.afterCompletion(refresh, response(), new Object(), null);
        verify(executionLock).tryAcquire();
        verify(lease).close();
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }
}
