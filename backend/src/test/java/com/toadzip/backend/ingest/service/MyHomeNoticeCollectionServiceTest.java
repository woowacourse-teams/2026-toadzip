package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalDataSnapshot;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSupplyType;
import com.toadzip.backend.ingest.repository.ExternalDataCollectionStore;
import com.toadzip.backend.ingest.repository.MyHomeNoticeExternalRepository;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MyHomeNoticeCollectionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private MyHomeNoticeExternalRepository externalRepository;

    @Mock
    private ExternalDataCollectionStore store;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private MyHomeNoticeCollectionService service;

    @BeforeEach
    void setUp() {
        service = new MyHomeNoticeCollectionService(CLOCK, externalRepository, store, failureRecorder);
    }

    @Test
    @DisplayName("공급유형별 원문을 조회하고 페이지 원문을 저장한다")
    void storesNoticeSourcePages() {
        MyHomeNoticeCollectionRequest request = new MyHomeNoticeCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeNoticeSupplyType supplyType = invocation.getArgument(0);
            if (supplyType == MyHomeNoticeSupplyType.HAPPY_HOUSE) {
                return response("[{\"pblancId\":\"1\"}]");
            }
            return response("[]");
        });

        var result = service.collect(request);

        ArgumentCaptor<List<ExternalDataSnapshot>> snapshots = ArgumentCaptor.captor();
        verify(store, org.mockito.Mockito.times(MyHomeNoticeSupplyType.values().length))
                .storeSnapshots(snapshots.capture());
        assertThat(snapshots.getAllValues()).allSatisfy(value -> assertThat(value).hasSize(1));
        assertThat(result.storedSnapshotCount()).isEqualTo(MyHomeNoticeSupplyType.values().length);
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName("공급유형 조회 실패는 실패 이력 기록 대상으로 전달한다")
    void reportsNoticeSourceFailure() {
        MyHomeNoticeCollectionRequest request = new MyHomeNoticeCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("조회 실패"));

        var result = service.collect(request);

        verify(failureRecorder, org.mockito.Mockito.times(MyHomeNoticeSupplyType.values().length))
                .record(any(), any(), any(), any(), any());
        assertThat(result.storedSnapshotCount()).isZero();
        assertThat(result.failedRequestCount()).isEqualTo(MyHomeNoticeSupplyType.values().length);
    }

    private ExternalDataResponse response(String items) {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"item\":" + items + "}}}";
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
