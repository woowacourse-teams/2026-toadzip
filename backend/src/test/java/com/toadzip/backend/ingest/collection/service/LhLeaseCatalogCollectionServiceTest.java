package com.toadzip.backend.ingest.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.fixture.repository.LhLeaseCatalogExternalRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.dto.api.LhLeaseCatalogCollectionRequest;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.service.LhLeaseCatalogCollectionService;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhLeaseCatalogCollectionServiceTest {

    @Mock
    private LhLeaseCatalogExternalRepository externalRepository;

    @Mock
    private LhStorageFixtures sourceStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhLeaseCatalogCollectionService service;

    @BeforeEach
    void setUp() {
        service = new CollectionServiceTestFixture().lease(externalRepository, sourceStore, failureRecorder,
                new ExternalDataRetryExecutor(java.time.Duration.ZERO, new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("LH 임대 카탈로그의 마지막 페이지까지 API 데이터를 저장한다")
    void storesCompleteCatalogPages() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        when(externalRepository.fetch(request, 1))
                .thenReturn(response("[" + catalogRow("서울") + "," + catalogRow("부산") + "]", 3, 1, 2));
        when(externalRepository.fetch(request, 2)).thenReturn(response("[" + catalogRow("대구") + "]", 3, 2, 2));
        when(sourceStore.replaceCatalog(any())).thenReturn(3);

        var result = service.collect(request);

        verify(sourceStore).replaceCatalog(any());
        assertThat(result.storedRowCount()).isEqualTo(3);
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.externalApiCallCount()).isEqualTo(2);
        verify(failureRecorder).resolveStartingWith(ExternalDataSource.LH_LEASE_CATALOG, "PG_SZ=");
    }

    @Test
    @DisplayName("LH 임대 카탈로그 조회 실패는 API 데이터 저장 없이 기록한다")
    void reportsCatalogFailureWithoutSaving() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        when(externalRepository.fetch(request, 1)).thenThrow(new ExternalDataRequestException("조회 실패"));

        var result = service.collect(request);

        verify(sourceStore, never()).replaceCatalog(any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        verify(failureRecorder, never()).resolveStartingWith(any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("LH 임대 카탈로그 dataset 타입이 잘못되면 기존 원천을 교체하지 않는다")
    void preservesCatalogWhenDatasetTypeIsInvalid() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        String payload = "[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsList\":1}]";
        when(externalRepository.fetch(request, 1)).thenReturn(JsonMapper.builder().build().readTree(payload));

        var result = service.collect(request);

        verify(sourceStore, never()).replaceCatalog(any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 식별_정보가_없는_카탈로그는_기존_원천을_교체하지_않고_실패로_기록한다() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        when(externalRepository.fetch(request, 1)).thenReturn(response("[{}]", 1, 1, 2));

        var result = service.collect(request);

        verify(sourceStore, never()).replaceCatalog(any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        verify(failureRecorder, never()).resolve(any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("후속 페이지 파싱 실패는 실제 페이지와 시도 횟수로 기록하고 성공 처리하지 않는다")
    void recordsActualCatalogParseFailurePageAndAttemptCount() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(1, 10);
        when(externalRepository.fetch(request, 1))
                .thenReturn(response("[" + catalogRow("서울") + "]", 2, 1, 1));
        String invalidPayload = "[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsList\":1}]";
        when(externalRepository.fetch(request, 2)).thenReturn(JsonMapper.builder().build().readTree(invalidPayload));

        ExternalDataCollectionReport result = service.collect(request);

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription()).isEqualTo("PG_SZ=1&PAGE=2");
                    assertThat(exception.getAttemptCount()).isOne();
                }
        );
        verify(failureRecorder, never()).resolve(
                ExternalDataSource.LH_LEASE_CATALOG,
                "PG_SZ=1&PAGE=2"
        );
        verify(sourceStore, never()).replaceCatalog(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("LH 임대 카탈로그 저장 실패는 외부 API 실패로 기록하지 않는다")
    void propagatesCatalogStoreFailure() {
        LhLeaseCatalogCollectionRequest request = new LhLeaseCatalogCollectionRequest(2, 10);
        when(externalRepository.fetch(request, 1)).thenReturn(response("[" + catalogRow("서울") + "]", 1, 1, 2));
        when(sourceStore.replaceCatalog(any())).thenThrow(new IllegalStateException("DB 저장 실패"));

        assertThatThrownBy(() -> service.collect(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        verify(failureRecorder, never()).resolve(any(), any());
    }

    private JsonNode response(String rows, int total, int page, int size) {
        var mapper = JsonMapper.builder().build();
        var list = mapper.readTree(rows);
        for (int index = 0; index < list.size(); index++) {
            ((tools.jackson.databind.node.ObjectNode) list.get(index)).put("ALL_CNT", total)
                    .put("RNUM", (page - 1) * size + index + 1);
        }
        return mapper.readTree("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsSch\":[{\"PAGE\":" + page
                + ",\"PG_SZ\":" + size + "}]},{\"dsList\":" + list + "}]");
    }

    private String catalogRow(String areaName) {
        return "{\"ARA_NM\":\"" + areaName + "\",\"AIS_TP_CD_NM\":\"행복주택\","
                + "\"SBD_LGO_NM\":\"" + areaName + " 행복주택\",\"SUM_HSH_CNT\":\"100\","
                + "\"DDO_AR\":\"36.97\",\"HSH_CNT\":\"100\"}";
    }
}
