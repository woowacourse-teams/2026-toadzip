package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.MyHomeNoticeCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSupplyType;
import com.toadzip.backend.ingest.repository.MyHomeNoticeApiRepository;
import com.toadzip.backend.ingest.repository.MyHomeSourceStore;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MyHomeNoticeCollectionServiceTest {

    @Mock
    private MyHomeNoticeApiRepository apiRepository;

    @Mock
    private MyHomeSourceStore sourceStore;

    @Mock
    private ExternalApiFailureRecorder failureRecorder;

    private MyHomeNoticeCollectionService service;

    @BeforeEach
    void setUp() {
        service = new MyHomeNoticeCollectionService(
                JsonMapper.builder().build(),
                apiRepository,
                sourceStore,
                failureRecorder,
                new ExternalApiRetryExecutor(Duration.ZERO)
        );
    }

    @Test
    @DisplayName("공급유형별 API 데이터를 조회하고 페이지 API 데이터를 저장한다")
    void storesNoticeApiDataPages() {
        MyHomeNoticeCollectionRequest request = new MyHomeNoticeCollectionRequest(2, 10);
        when(apiRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeNoticeSupplyType supplyType = invocation.getArgument(0);
            if (supplyType == MyHomeNoticeSupplyType.HAPPY_HOUSE) {
                return response("[{\"pblancId\":\"1\"}]");
            }
            return response("[]");
        });
        when(sourceStore.storeNotices(any())).thenAnswer(invocation -> {
            List<?> items = invocation.getArgument(0);
            return items.size();
        });

        var result = service.collect(request);

        ArgumentCaptor<List<MyHomeNoticeSourceItem>> items = ArgumentCaptor.captor();
        verify(sourceStore, org.mockito.Mockito.times(MyHomeNoticeSupplyType.values().length))
                .storeNotices(items.capture());
        assertThat(items.getAllValues()).filteredOn(value -> !value.isEmpty())
                .singleElement()
                .extracting(List::getFirst)
                .extracting(value -> ((MyHomeNoticeSourceItem) value).pblancId())
                .isEqualTo("1");
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName("공급유형 조회 실패는 실패 이력 기록 대상으로 전달한다")
    void reportsNoticeApiDataFailure() {
        MyHomeNoticeCollectionRequest request = new MyHomeNoticeCollectionRequest(2, 10);
        when(apiRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("조회 실패"));

        var result = service.collect(request);

        verify(failureRecorder, org.mockito.Mockito.times(MyHomeNoticeSupplyType.values().length))
                .record(any(), any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isEqualTo(MyHomeNoticeSupplyType.values().length);
    }

    private ExternalApiResponse response(String items) {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"item\":" + items + "}}}";
        return new ExternalApiResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
