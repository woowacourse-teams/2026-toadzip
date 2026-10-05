package com.toadzip.backend.ingest.collection.service;

import static com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock.Operation.MYHOME_ANNOUNCEMENT_COLLECTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementExternalRepository;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSupplyType;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectedResponse;
import com.toadzip.backend.ingest.collection.myhome.announcement.dto.api.MyHomeAnnouncementCollectionRequest;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementCollectionService;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementLifecycleService;
import com.toadzip.backend.ingest.collection.myhome.announcement.service.MyHomeAnnouncementStorageService;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.exception.exception.ExternalDataCallFailureException;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.failure.service.ExternalDataFailureRecorder;
import com.toadzip.backend.ingest.pipeline.repository.IngestOperationLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
class MyHomeAnnouncementCollectionServiceTest {

    @Mock
    private MyHomeAnnouncementExternalRepository externalRepository;

    @Mock
    private IngestOperationLock executionLock;

    @Mock
    private MyHomeAnnouncementStorageService storage;

    @Mock
    private MyHomeAnnouncementLifecycleService lifecycle;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private MyHomeAnnouncementCollectionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(executionLock.tryRun(eq(MYHOME_ANNOUNCEMENT_COLLECTION), any())).thenAnswer(invocation -> {
            Supplier<ExternalDataCollectionReport> operation = invocation.getArgument(1);
            return Optional.of(operation.get());
        });
        service = new CollectionServiceTestFixture().announcement(externalRepository, storage, lifecycle, executionLock,
                failureRecorder, new ExternalDataRetryExecutor(Duration.ZERO, new SimpleMeterRegistry()));
    }

    @Test
    void 페이지를_확대해도_7개_공급유형의_모든_원천_행을_동일하게_저장한다() {
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            MyHomeAnnouncementCollectionRequest request = invocation.getArgument(1);
            int page = invocation.getArgument(2);
            int total = List.of(0, 1, 10, 194, 499, 500, 501).get(supplyType.ordinal());
            int offset = (page - 1) * request.pageSize();
            String items = IntStream.range(offset, Math.min(offset + request.pageSize(), total))
                    .mapToObj(index -> "{\"pblancId\":\"" + supplyType.requestCode()
                            + "\",\"houseSn\":" + index + "}")
                    .collect(Collectors.joining(",", "[", "]"));
            return responseWithTotalCount(items, total);
        });

        ExternalDataCollectionReport small = service.collect(new MyHomeAnnouncementCollectionRequest(10, 1_000));
        ExternalDataCollectionReport large = service.collect(new MyHomeAnnouncementCollectionRequest(500, 1_000));

        ArgumentCaptor<MyHomeAnnouncementCollectedResponse> collected = ArgumentCaptor.captor();
        verify(storage, times(14)).complete(any(), any(), collected.capture());
        var storedBatches = collected.getAllValues().stream().map(MyHomeAnnouncementCollectedResponse::rows).toList();
        assertThat(storedBatches.subList(7, 14)).containsExactlyElementsOf(storedBatches.subList(0, 7));
        assertThat(large.storedRowCount()).isEqualTo(1_705).isEqualTo(small.storedRowCount());
        assertThat(large.failedRequestCount()).isZero();
        assertThat(small.externalApiCallCount()).isEqualTo(174);
        assertThat(large.externalApiCallCount()).isEqualTo(8);
        verify(lifecycle, times(2)).completeRun(any());
    }

    @Test
    @DisplayName("이미 실행 중이면 중복 수집을 거절한다")
    void rejectsConcurrentCollection() {
        doReturn(Optional.empty()).when(executionLock).tryRun(eq(MYHOME_ANNOUNCEMENT_COLLECTION), any());

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
                return response("[{\"pblancId\":\"1\",\"houseSn\":1}]");
            }
            return response("[]");
        });

        var result = service.collect(request);

        ArgumentCaptor<com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest> runIds = ArgumentCaptor.captor();
        ArgumentCaptor<MyHomeAnnouncementCollectedResponse> snapshots = ArgumentCaptor.captor();
        verify(storage, org.mockito.Mockito.times(MyHomeAnnouncementSupplyType.values().length)).complete(any(), runIds.capture(), snapshots.capture());
        var runId = runIds.getAllValues().getFirst().executionId();
        assertThat(runIds.getAllValues()).extracting(com.toadzip.backend.ingest.collection.myhome.announcement.dto.MyHomeAnnouncementCollectionRequest::executionId).containsOnly(runId);
        verify(lifecycle).completeRun(runId);
        assertThat(snapshots.getAllValues().stream().map(MyHomeAnnouncementCollectedResponse::rows).toList()).filteredOn(value -> !value.isEmpty())
                .singleElement()
                .extracting(List::getFirst)
                .extracting(value -> ((com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot) value).pblancId())
                .isEqualTo("1");
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
        verify(failureRecorder).resolveStartingWith(
                ExternalDataSource.MYHOME_ANNOUNCEMENT, "suplyTy=10&pageNo="
        );
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
        verify(lifecycle, never()).completeRun(any());
        verify(failureRecorder, never()).resolveStartingWith(any(), any());
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

        verify(lifecycle, never()).completeRun(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("성공 코드에 body가 없으면 원천 저장과 미조회 공고 판정을 하지 않는다")
    void preservesSourcesWhenSuccessfulResponseSchemaIsInvalid() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"}}}";
        JsonNode invalidResponse = JsonMapper.builder().build().readTree(payload);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(invalidResponse);

        var result = service.collect(request);

        verify(storage, never()).complete(any(), any(), any());
        verify(lifecycle, never()).completeRun(any());
        assertThat(result.failedRequestCount()).isEqualTo(MyHomeAnnouncementSupplyType.values().length);
    }

    @Test
    @DisplayName("식별자 없는 공고 항목이 있으면 해당 공급유형 저장과 전체 미조회 판정을 보류한다")
    void preservesSourcesWhenAnnouncementIdentifierIsMissing() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            if (supplyType == MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                return responseWithTotalCount("[{},{}]", 2);
            }
            return response("[]");
        });

        ExternalDataCollectionReport result = service.collect(request);

        verify(storage, times(MyHomeAnnouncementSupplyType.values().length - 1)).complete(any(), any(), any());
        verify(lifecycle, never()).completeRun(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("주택 일련번호가 없으면 공급유형 저장과 전체 미조회 판정을 보류한다")
    void preservesSourcesWhenHouseSerialNumberIsMissing() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            if (supplyType == MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                return responseWithTotalCount("[{\"pblancId\":\"A-1\"},{\"pblancId\":\"A-1\"}]", 2);
            }
            return response("[]");
        });

        ExternalDataCollectionReport result = service.collect(request);

        verify(storage, times(MyHomeAnnouncementSupplyType.values().length - 1)).complete(any(), any(), any());
        verify(lifecycle, never()).completeRun(any());
        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isOne();
        assertThat(result.storedRowCount()).isZero();
    }

    @Test
    @DisplayName("서로 다른 페이지의 같은 주택 행도 원천 응답 그대로 저장한다")
    void keepsRawAnnouncementRowsRepeatedAcrossDifferentPages() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            int page = invocation.getArgument(2);
            if (supplyType != MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                return response("[]");
            }
            if (page == 1) {
                return responseWithTotalCount("[{\"pblancId\":\"A\",\"houseSn\":1},"
                        + "{\"pblancId\":\"A\",\"houseSn\":2}]", 4);
            }
            return responseWithTotalCount("[{\"pblancId\":\"A\",\"houseSn\":2},"
                    + "{\"pblancId\":\"A\",\"houseSn\":3}]", 4);
        });

        ExternalDataCollectionReport result = service.collect(request);

        ArgumentCaptor<MyHomeAnnouncementCollectedResponse> batches = ArgumentCaptor.captor();
        verify(storage, times(MyHomeAnnouncementSupplyType.values().length)).complete(any(), any(), batches.capture());
        assertThat(batches.getAllValues().stream().map(MyHomeAnnouncementCollectedResponse::rows).toList()).filteredOn(batch -> !batch.isEmpty()).singleElement()
                .satisfies(batch -> assertThat(batch).extracting(com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot::houseSn)
                        .containsExactly(1, 2, 2, 3));
        verify(lifecycle).completeRun(any());
        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName(
            "일부 페이지 수집 후 데이터 없음 응답이 오면 해당 공급유형을 저장하거나 미조회 판정하지 않는다"
    )
    void rejectsPrematureNoDataResponseAfterPartialCollection() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(1, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            int page = invocation.getArgument(2);
            if (supplyType != MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                return response("[]");
            }
            if (page == 1) {
                return responseWithTotalCount("[{\"pblancId\":\"1\",\"houseSn\":1}]", 2);
            }
            return noDataResponse();
        });

        var result = service.collect(request);

        verify(storage, times(MyHomeAnnouncementSupplyType.values().length - 1)).complete(any(), any(), any());
        verify(lifecycle, never()).completeRun(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName(
            "일부 페이지 수집 후 전체 건수 0의 빈 응답이 오면 해당 공급유형을 저장하지 않는다"
    )
    void rejectsPrematureEmptyResponseAfterPartialCollection() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(1, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            int page = invocation.getArgument(2);
            if (supplyType != MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                return response("[]");
            }
            if (page == 1) {
                return responseWithTotalCount("[{\"pblancId\":\"1\",\"houseSn\":1}]", 2);
            }
            return response("[]");
        });

        var result = service.collect(request);

        verify(storage, times(MyHomeAnnouncementSupplyType.values().length - 1)).complete(any(), any(), any());
        verify(lifecycle, never()).completeRun(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName(
            "후속 페이지의 전체 건수가 첫 페이지와 다르면 해당 공급유형을 저장하지 않는다"
    )
    void rejectsChangedTotalCountBetweenPages() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(1, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(invocation -> {
            MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
            int page = invocation.getArgument(2);
            if (supplyType != MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                return response("[]");
            }
            if (page == 1) {
                return responseWithTotalCount("[{\"pblancId\":\"1\",\"houseSn\":1}]", 3);
            }
            return responseWithTotalCount("[{\"pblancId\":\"2\",\"houseSn\":2}]", 2);
        });

        var result = service.collect(request);

        verify(storage, times(MyHomeAnnouncementSupplyType.values().length - 1)).complete(any(), any(), any());
        verify(lifecycle, never()).completeRun(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("후속 페이지 파싱 실패는 실제 페이지와 시도 횟수로 기록하고 성공 처리하지 않는다")
    void recordsActualAnnouncementParseFailurePageAndAttemptCount() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(1, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
                    int page = invocation.getArgument(2);
                    if (supplyType != MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                        return response("[]");
                    }
                    if (page == 1) {
                        return responseWithTotalCount("[{\"pblancId\":\"1\",\"houseSn\":1}]", 2);
                    }
                    return responseWithTotalCount("[{\"pblancId\":{}}]", 2);
                });

        ExternalDataCollectionReport result = service.collect(request);

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription())
                            .isEqualTo("suplyTy=10&pageNo=2&numOfRows=1");
                    assertThat(exception.getAttemptCount()).isOne();
                }
        );
        verify(failureRecorder, never())
                .resolve(ExternalDataSource.MYHOME_ANNOUNCEMENT, "suplyTy=10&pageNo=2&numOfRows=1");
        verify(lifecycle, never()).completeRun(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("후속 페이지 totalCount 불일치는 실제 페이지와 시도 횟수로 기록한다")
    void recordsActualAnnouncementTotalCountFailurePageAndAttemptCount() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(1, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    MyHomeAnnouncementSupplyType supplyType = invocation.getArgument(0);
                    int page = invocation.getArgument(2);
                    if (supplyType != MyHomeAnnouncementSupplyType.HAPPY_HOUSE) {
                        return response("[]");
                    }
                    if (page == 1) {
                        return responseWithTotalCount("[{\"pblancId\":\"1\",\"houseSn\":1}]", 2);
                    }
                    return responseWithTotalCount("[{\"pblancId\":\"2\",\"houseSn\":2}]", 3);
                });

        ExternalDataCollectionReport result = service.collect(request);

        ArgumentCaptor<RuntimeException> failure = ArgumentCaptor.captor();
        verify(failureRecorder).record(any(), any(), failure.capture(), any(), any());
        assertThat(failure.getValue()).isInstanceOfSatisfying(
                ExternalDataCallFailureException.class,
                exception -> {
                    assertThat(exception.getRequestDescription())
                            .isEqualTo("suplyTy=10&pageNo=2&numOfRows=1");
                    assertThat(exception.getAttemptCount()).isOne();
                }
        );
        verify(failureRecorder, never())
                .resolve(ExternalDataSource.MYHOME_ANNOUNCEMENT, "suplyTy=10&pageNo=2&numOfRows=1");
        verify(lifecycle, never()).completeRun(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    @DisplayName("호출 제한이 발생하면 남은 공급유형을 조회하지 않는다")
    void stopsRemainingSupplyTypesAfterRateLimit() {
        MyHomeAnnouncementCollectionRequest request =
                new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(ExternalDataRequestException.rateLimited(
                        "resultCode=22, 일일 요청 한도 초과",
                        null,
                        false
                ));

        var result = service.collect(request);

        assertThat(result.rateLimitedRequestCount()).isOne();
        verify(externalRepository, times(1)).fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(lifecycle, never()).completeRun(any());
    }

    @Test
    @DisplayName("마이홈 공고 저장 실패는 외부 API 실패로 기록하지 않는다")
    void propagatesAnnouncementStoreFailure() {
        MyHomeAnnouncementCollectionRequest request = new MyHomeAnnouncementCollectionRequest(2, 10);
        when(externalRepository.fetch(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(response("[]"));
        doThrow(new IllegalStateException("DB 저장 실패")).when(storage).complete(any(), any(), any());

        assertThatThrownBy(() -> service.collect(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DB 저장 실패");

        verify(failureRecorder, never()).record(any(), any(), any(), any(), any());
        verify(failureRecorder, never()).resolve(any(), any());
    }

    private JsonNode response(String items) {
        return responseWithTotalCount(items, totalCountOf(items));
    }

    private JsonNode responseWithTotalCount(String items, int totalCount) {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"totalCount\":" + totalCount + ",\"item\":" + items + "}}}";
        return JsonMapper.builder().build().readTree(payload);
    }

    private JsonNode noDataResponse() {
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"03\"}}}";
        return JsonMapper.builder().build().readTree(payload);
    }

    private int totalCountOf(String items) {
        if ("[]".equals(items)) {
            return 0;
        }
        return 1;
    }
}
