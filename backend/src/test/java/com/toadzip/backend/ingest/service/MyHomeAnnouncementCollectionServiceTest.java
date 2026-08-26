package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementExternalRepository;
import com.toadzip.backend.ingest.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MyHomeAnnouncementCollectionServiceTest {

    @Mock
    private MyHomeAnnouncementExternalRepository externalRepository;

    @Mock
    private MyHomeSourceStore sourceStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private MyHomeAnnouncementCollectionService service;

    @BeforeEach
    void setUp() {
        service = new MyHomeAnnouncementCollectionService(
                JsonMapper.builder().build(),
                externalRepository,
                sourceStore,
                failureRecorder,
                new ExternalDataRetryExecutor(Duration.ZERO)
        );
    }

    @Test
    @DisplayName("공급유형별 API 데이터를 조회하고 페이지 API 데이터를 저장한다")
    void storesAnnouncementApiDataPages() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            if (supplyType == MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                return response("[{\"pblancId\":\"1\"}]");
            }
            return response("[]");
        });
        when(sourceStore.storeAnnouncements(any())).thenAnswer(invocation -> {
            List<?> items = invocation.getArgument(0);
            return items.size();
        });

        var result = service.collect(request);

        ArgumentCaptor<List<MyHomeAnnouncementSourceItem>> items = ArgumentCaptor.captor();
        verify(sourceStore, org.mockito.Mockito.times(MyHomeAnnouncementSupplyType.values().length))
                .storeAnnouncements(items.capture());
        assertThat(items.getAllValues()).filteredOn(value -> !value.isEmpty())
                .singleElement()
                .extracting(List::getFirst)
                .extracting(value -> ((MyHomeAnnouncementSourceItem) value).pblancId())
                .isEqualTo("1");
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName("공급유형 조회 실패는 실패 이력 기록 대상으로 전달한다")
    void reportsAnnouncementApiDataFailure() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new ExternalDataRequestException("조회 실패"));

        var result = service.collect(request);

        verify(failureRecorder, org.mockito.Mockito.times(MyHomeAnnouncementSupplyType.values().length))
                .record(any(), any(), any(), any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isEqualTo(MyHomeAnnouncementSupplyType.values().length);
    }

    @Test
    @DisplayName("마이홈 공고 저장 실패는 외부 API 실패로 기록하지 않는다")
    void propagatesAnnouncementStoreFailure() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(response("[]"));
        when(sourceStore.storeAnnouncements(any())).thenThrow(new IllegalStateException("DB 저장 실패"));

        assertThatThrownBy(() -> service.collect(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
    }

    private ExternalDataResponse response(String items) {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"item\":" + items + "}}}";
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
