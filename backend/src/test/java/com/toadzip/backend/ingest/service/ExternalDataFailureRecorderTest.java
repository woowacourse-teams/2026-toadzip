package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import com.toadzip.backend.ingest.domain.ExternalDataCollectionFailure;
import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.repository.ExternalDataFailureStore;
import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;

class ExternalDataFailureRecorderTest {

    @Test
    void 외부_API_예외의_서비스키와_원본_cause를_로그에_남기지_않는다() {
        ExternalDataFailureStore store = mock(ExternalDataFailureStore.class);
        Logger logger = mock(Logger.class);
        ExternalDataFailureRecorder recorder = new ExternalDataFailureRecorder(
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC),
                store
        );
        String secret = "real-secret-key";
        ExternalDataRequestException requestFailure = ExternalDataRequestException.retryable(
                "마이홈 외부 API 연결에 실패했습니다.",
                new IllegalStateException(
                        "GET https://apis.data.go.kr/list?serviceKey=" + secret + "&pageNo=1"
                )
        );
        ExternalDataCallFailureException failure = new ExternalDataCallFailureException(
                ExternalDataSource.MYHOME_COMPLEX,
                "pageNo=1",
                3,
                requestFailure
        );

        recorder.record(
                ExternalDataSource.MYHOME_COMPLEX,
                "pageNo=1",
                failure,
                logger,
                "마이홈 단지 수집에 실패했습니다"
        );

        ArgumentCaptor<ExternalDataCollectionFailure> storedFailure = ArgumentCaptor.captor();
        verify(store).store(storedFailure.capture());
        assertThat(storedFailure.getValue().getReason()).doesNotContain(secret);

        ArgumentCaptor<Object> loggedException = ArgumentCaptor.captor();
        verify(logger).warn(anyString(), any(), any(), any(), loggedException.capture());
        assertThat(loggedException.getValue()).isInstanceOfSatisfying(RuntimeException.class, logged -> {
            assertThat(logged.getMessage()).doesNotContain(secret);
            assertThat(logged.getCause()).isNull();
            assertThat(logged.getSuppressed()).isEmpty();
        });
    }

    @Test
    void 실패_원인과_요청_설명에_있는_서비스키를_마스킹한다() {
        ExternalDataFailureStore store = mock(ExternalDataFailureStore.class);
        ExternalDataFailureRecorder recorder = new ExternalDataFailureRecorder(
                Clock.systemUTC(),
                store
        );

        recorder.record(
                ExternalDataSource.LH_LEASE_CATALOG,
                "serviceKey=request-secret&page=1",
                new IllegalStateException("serviceKey=reason-secret 호출 실패"),
                mock(Logger.class),
                "LH 수집 실패"
        );

        ArgumentCaptor<ExternalDataCollectionFailure> storedFailure = ArgumentCaptor.captor();
        verify(store).store(storedFailure.capture());
        assertThat(storedFailure.getValue().getRequestDescription())
                .isEqualTo("serviceKey=[REDACTED]&page=1");
        assertThat(storedFailure.getValue().getReason())
                .isEqualTo("serviceKey=[REDACTED] 호출 실패");
    }
}
