package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.repository.LhLeaseCatalogApiRepository;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhLeaseCatalogCollectionServiceTest {

    @Mock
    private LhLeaseCatalogApiRepository apiRepository;

    @Mock
    private LhSourceStore sourceStore;

    @Mock
    private ExternalApiFailureRecorder failureRecorder;

    private LhLeaseCatalogCollectionService service;

    @BeforeEach
    void setUp() {
        service = new LhLeaseCatalogCollectionService(apiRepository, sourceStore, failureRecorder);
    }

    @Test
    @DisplayName("LH 임대 카탈로그의 마지막 페이지까지 API 데이터를 저장한다")
    void storesCompleteCatalogPages() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        when(apiRepository.fetch(request, 1))
                .thenReturn(response("[{\"ARA_NM\":\"서울\"},{\"ARA_NM\":\"부산\"}]"));
        when(apiRepository.fetch(request, 2)).thenReturn(response("[{\"ARA_NM\":\"대구\"}]"));
        when(sourceStore.replaceCatalog(any())).thenReturn(3);

        var result = service.collect(request);

        verify(sourceStore).replaceCatalog(any());
        assertThat(result.storedRowCount()).isEqualTo(3);
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName("LH 임대 카탈로그 조회 실패는 API 데이터 저장 없이 기록한다")
    void reportsCatalogFailureWithoutSaving() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        when(apiRepository.fetch(request, 1)).thenThrow(new IllegalStateException("조회 실패"));

        var result = service.collect(request);

        verify(sourceStore, never()).replaceCatalog(any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    private ExternalApiResponse response(String rows) {
        String payload = "[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsList\":" + rows + "}]";
        return new ExternalApiResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
