package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.repository.ExternalDataCollectionStore;
import com.toadzip.backend.ingest.repository.LhLeaseCatalogExternalRepository;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhLeaseCatalogCollectionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private LhLeaseCatalogExternalRepository externalRepository;

    @Mock
    private ExternalDataCollectionStore store;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhLeaseCatalogCollectionService service;

    @BeforeEach
    void setUp() {
        service = new LhLeaseCatalogCollectionService(CLOCK, externalRepository, store, failureRecorder);
    }

    @Test
    @DisplayName("LH 임대 카탈로그의 마지막 페이지까지 원문을 저장한다")
    void storesCompleteCatalogPages() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        when(externalRepository.fetch(request, 1)).thenReturn(response("[{\"id\":1},{\"id\":2}]"));
        when(externalRepository.fetch(request, 2)).thenReturn(response("[{\"id\":3}]"));

        var result = service.collect(request);

        verify(store).storeSnapshots(any());
        assertThat(result.storedSnapshotCount()).isEqualTo(2);
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName("LH 임대 카탈로그 조회 실패는 원문 저장 없이 기록한다")
    void reportsCatalogFailureWithoutSaving() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        when(externalRepository.fetch(request, 1)).thenThrow(new IllegalStateException("조회 실패"));

        var result = service.collect(request);

        verify(store, never()).storeSnapshots(any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    private ExternalDataResponse response(String rows) {
        String payload = "[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsList\":" + rows + "}]";
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
