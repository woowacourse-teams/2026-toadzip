package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSourceItem;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementExternalRepository;
import com.toadzip.backend.ingest.repository.MyHomeSourceStore;
import com.toadzip.backend.ingest.repository.external.ExternalDataRequestException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MyHomeAnnouncementCollectionServiceTest {

    @Mock
    private MyHomeAnnouncementExternalRepository externalRepository;

    @Mock
    private MyHomeAnnouncementCollectionExecutionLock executionLock;

    @Mock
    private MyHomeSourceStore sourceStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private MyHomeAnnouncementCollectionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(executionLock.tryRun(any())).thenAnswer(invocation -> {
            Supplier<ExternalDataCollectionReport> operation = invocation.getArgument(0);
            return Optional.of(operation.get());
        });
        service = new MyHomeAnnouncementCollectionService(
                JsonMapper.builder().build(),
                externalRepository,
                executionLock,
                sourceStore,
                failureRecorder,
                new ExternalDataRetryExecutor(Duration.ZERO)
        );
    }

    @Test
    @DisplayName("이미 실행 중이면 중복 수집을 거절한다")
    void rejectsConcurrentCollection() {
        doReturn(Optional.empty()).when(executionLock).tryRun(any());

        assertThatThrownBy(() -> service.collect(new MyHomeAnnouncementCollectionRequest(2, 10)))
                .isInstanceOf(IngestAlreadyRunningException.class)
                .hasMessage("마이홈 공고 수집이 이미 실행 중입니다.");

        verify(externalRepository, never()).fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt());
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
        when(sourceStore.storeAnnouncements(anyString(), any())).thenAnswer(invocation -> {
            List<?> items = invocation.getArgument(1);
            return items.size();
        });

        var result = service.collect(request);

        ArgumentCaptor<String> runIds = ArgumentCaptor.captor();
        ArgumentCaptor<List<MyHomeAnnouncementSourceItem>> items = ArgumentCaptor.captor();
        verify(sourceStore, org.mockito.Mockito.times(MyHomeAnnouncementSupplyType.values().length))
                .storeAnnouncements(runIds.capture(), items.capture());
        String runId = runIds.getAllValues().getFirst();
        assertThat(runIds.getAllValues()).containsOnly(runId);
        verify(sourceStore).completeAnnouncementCollection(runId);
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
        verify(sourceStore, never()).completeAnnouncementCollection(anyString());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isEqualTo(MyHomeAnnouncementSupplyType.values().length);
    }

    @Test
    @DisplayName("일부 공급유형 조회가 실패하면 미조회 공고를 판정하지 않는다")
    void skipsLifecycleCompletionWhenAnySupplyTypeFails() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            if (supplyType == MyHomeAnnouncementSupplyType.PERMANENT_RENTAL) {
                throw new ExternalDataRequestException("영구임대 조회 실패");
            }
            return response("[]");
        });

        var result = service.collect(request);

        verify(sourceStore, never()).completeAnnouncementCollection(anyString());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("마이홈 공고 저장 실패는 외부 API 실패로 기록하지 않는다")
    void propagatesAnnouncementStoreFailure() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(response("[]"));
        when(sourceStore.storeAnnouncements(anyString(), any())).thenThrow(new IllegalStateException("DB 저장 실패"));

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
