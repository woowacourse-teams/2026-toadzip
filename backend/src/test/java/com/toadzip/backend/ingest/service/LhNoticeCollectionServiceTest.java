package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.domain.LhNoticeProcessingStatus;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhNoticeCollectionRequest;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.ExternalApiDataRepository;
import com.toadzip.backend.ingest.repository.LhNoticeCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.LhNoticeApiRepository;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhNoticeCollectionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

    private static final LhNoticeCollectionRequest REQUEST = new LhNoticeCollectionRequest();

    @Mock
    private ExternalApiDataRepository apiDataRepository;

    @Mock
    private LhNoticeApiRepository apiRepository;

    @Mock
    private LhNoticeCollectionExecutionLock executionLock;

    @Mock
    private ExternalApiCollectionStore store;

    @Mock
    private ExternalApiFailureRecorder failureRecorder;

    private LhNoticeCollectionService service;

    @BeforeEach
    void setUp() {
        lenient().when(executionLock.<ExternalApiCollectionReport>tryRun(any()))
                .thenAnswer(invocation -> {
                    Supplier<ExternalApiCollectionReport> operation = invocation.getArgument(0);
                    return Optional.of(operation.get());
                });
        service = new LhNoticeCollectionService(
                CLOCK,
                JsonMapper.builder().build(),
                apiDataRepository,
                apiRepository,
                executionLock,
                store,
                failureRecorder,
                new LhSupplyInfoTypeCodeResolver()
        );
    }

    @Test
    @DisplayName("마이홈 공고 API 데이터에서 LH 상세와 공급 API 데이터를 조회해 저장한다")
    void storesLhNoticeApiData() {
        ExternalApiData myHomeNotice = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                noticePayload()
        );
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        ))
                .thenReturn(List.of(myHomeNotice));
        when(apiRepository.fetchDetail(any())).thenReturn(response("detail"));
        when(apiRepository.fetchSupply(any())).thenReturn(response("supply"));
        when(store.storeApiData(any())).thenReturn(1);

        var result = service.collect(REQUEST);

        verify(store, times(2)).storeApiData(any());
        verify(store).completeLhNoticeProcessing(myHomeNotice, CLOCK.instant());
        assertThat(result.storedApiDataCount()).isEqualTo(2);
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 이미_저장된_LH_상세와_공급_API_데이터는_다시_조회하지_않는다() {
        ExternalApiData myHomeNotice = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                noticePayload()
        );
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        ))
                .thenReturn(List.of(myHomeNotice));
        when(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                eq(ExternalApi.LH_NOTICE_DETAIL),
                any()
        )).thenReturn(true);
        when(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                eq(ExternalApi.LH_NOTICE_SUPPLY),
                any()
        )).thenReturn(true);

        var result = service.collect(REQUEST);

        verify(apiRepository, never()).fetchDetail(any());
        verify(apiRepository, never()).fetchSupply(any());
        verify(store, never()).storeApiData(any());
        verify(store).completeLhNoticeProcessing(myHomeNotice, CLOCK.instant());
        assertThat(result.storedApiDataCount()).isZero();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 동일한_원본_snapshot은_저장_이력을_남기고_LH_후속_API만_건너뛴다() {
        ExternalApiData myHomeNotice = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                noticePayload()
        );
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        )).thenReturn(List.of(myHomeNotice));
        when(apiDataRepository
                .existsByExternalApiAndRequestDescriptionAndContentHashAndLhNoticeProcessingStatus(
                        ExternalApi.MYHOME_NOTICE,
                        myHomeNotice.getRequestDescription(),
                        myHomeNotice.getContentHash(),
                        LhNoticeProcessingStatus.COMPLETED
                )).thenReturn(true);

        var result = service.collect(REQUEST);

        verify(apiRepository, never()).fetchDetail(any());
        verify(apiRepository, never()).fetchSupply(any());
        verify(store).completeLhNoticeProcessing(myHomeNotice, CLOCK.instant());
        assertThat(result.storedApiDataCount()).isZero();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 잘못된_JSON_snapshot은_실패_처리하고_다음_snapshot_수집을_계속한다() {
        ExternalApiData malformedSnapshot = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                "{"
        );
        ExternalApiData validSnapshot = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=2",
                2,
                CLOCK.instant().plusSeconds(1),
                noticePayload()
        );
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        )).thenReturn(List.of(malformedSnapshot, validSnapshot));
        when(apiRepository.fetchDetail(any())).thenReturn(response("detail"));
        when(apiRepository.fetchSupply(any())).thenReturn(response("supply"));
        when(store.storeApiData(any())).thenReturn(1);

        var result = service.collect(REQUEST);

        verify(store).failLhNoticeProcessing(malformedSnapshot, CLOCK.instant());
        verify(store).completeLhNoticeProcessing(validSnapshot, CLOCK.instant());
        verify(apiRepository).fetchDetail(any());
        verify(apiRepository).fetchSupply(any());
        assertThat(result.storedApiDataCount()).isEqualTo(2);
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void LH_공고_수집이_실행_중이면_중복_실행은_외부_API를_호출하지_않는다() {
        doReturn(Optional.empty()).when(executionLock).tryRun(any());

        assertThatThrownBy(() -> service.collect(REQUEST))
                .isInstanceOf(IngestAlreadyRunningException.class)
                .hasMessage("LH 공고 수집이 이미 실행 중입니다.");

        verify(apiRepository, never()).fetchDetail(any());
        verify(apiRepository, never()).fetchSupply(any());
    }

    @Test
    void 상세_API_저장_후_공급_API만_실패하면_공급_API만_재시도_대상으로_기록한다() {
        ExternalApiData myHomeNotice = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                noticePayload()
        );
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        ))
                .thenReturn(List.of(myHomeNotice));
        when(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                ExternalApi.LH_NOTICE_DETAIL,
                List.of(
                        "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=063&AIS_TP_CD=06",
                        "PAN_ID=100&CCR_CNNT_SYS_DS_CD=03&UPP_AIS_TP_CD=06&SPL_INF_TP_CD=063"
                )
        )).thenReturn(true);
        when(apiRepository.fetchSupply(any())).thenThrow(new IllegalStateException("공급 조회 실패"));

        var result = service.collect(REQUEST);

        verify(apiRepository, never()).fetchDetail(any());
        verify(apiRepository).fetchSupply(any());
        verify(failureRecorder).record(
                eq(ExternalApi.LH_NOTICE_SUPPLY),
                anyString(),
                any(),
                any(),
                anyString()
        );
        verify(store, never()).storeApiData(any());
        verify(store, never()).completeLhNoticeProcessing(any(), any());
        verify(store, never()).failLhNoticeProcessing(any(), any());
        assertThat(result.storedApiDataCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 상세_API_성공과_공급_API_실패를_서로_독립적으로_저장하고_집계한다() {
        ExternalApiData myHomeNotice = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                noticePayload()
        );
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        ))
                .thenReturn(List.of(myHomeNotice));
        when(apiRepository.fetchDetail(any())).thenReturn(response("detail"));
        when(apiRepository.fetchSupply(any())).thenThrow(new IllegalStateException("공급 조회 실패"));
        when(store.storeApiData(any())).thenReturn(1);

        var result = service.collect(REQUEST);

        verify(apiRepository).fetchDetail(any());
        verify(apiRepository).fetchSupply(any());
        verify(store).storeApiData(any());
        verify(failureRecorder).record(
                eq(ExternalApi.LH_NOTICE_SUPPLY),
                anyString(),
                any(),
                any(),
                anyString()
        );
        verify(store, never()).completeLhNoticeProcessing(any(), any());
        verify(store, never()).failLhNoticeProcessing(any(), any());
        assertThat(result.storedApiDataCount()).isOne();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 새_식별자로_나타난_정정공고만_LH_상세와_공급_API를_추가_수집한다() {
        ExternalApiData myHomeNotice = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                noticePayload("행복주택", "100", "101")
        );
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        )).thenReturn(List.of(myHomeNotice));
        when(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                eq(ExternalApi.LH_NOTICE_DETAIL),
                argThat(descriptions -> descriptions.stream().anyMatch(value -> value.contains("PAN_ID=100")))
        )).thenReturn(true);
        when(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                eq(ExternalApi.LH_NOTICE_SUPPLY),
                argThat(descriptions -> descriptions.stream().anyMatch(value -> value.contains("PAN_ID=100")))
        )).thenReturn(true);
        when(apiRepository.fetchDetail(argThat(request -> request.panId().equals("101"))))
                .thenReturn(response("correction-detail"));
        when(apiRepository.fetchSupply(argThat(request -> request.panId().equals("101"))))
                .thenReturn(response("correction-supply"));
        when(store.storeApiData(any())).thenReturn(1);

        var result = service.collect(REQUEST);

        verify(apiRepository).fetchDetail(argThat(request -> request.panId().equals("101")));
        verify(apiRepository).fetchSupply(argThat(request -> request.panId().equals("101")));
        verify(store).completeLhNoticeProcessing(myHomeNotice, CLOCK.instant());
        assertThat(result.storedApiDataCount()).isEqualTo(2);
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    @DisplayName("지원하지 않는 공급유형은 LH API를 호출하지 않고 실패로 기록한다")
    void recordsUnsupportedSupplyType() {
        ExternalApiData myHomeNotice = ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                noticePayload("매입임대")
        );
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        ))
                .thenReturn(List.of(myHomeNotice));

        var result = service.collect(REQUEST);

        verify(failureRecorder).record(any(), any(), any(), any(), any());
        verify(store).failLhNoticeProcessing(myHomeNotice, CLOCK.instant());
        assertThat(result.storedApiDataCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    private String noticePayload() {
        return noticePayload("행복주택");
    }

    private String noticePayload(String supplyType) {
        return noticePayload(supplyType, "100");
    }

    private String noticePayload(String supplyType, String... panIds) {
        String rows = java.util.Arrays.stream(panIds)
                .map(panId -> "{\"suplyTyNm\":\"" + supplyType + "\","
                        + "\"url\":\"https://apply.lh.or.kr/panDetail?panId=" + panId
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06\"}")
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"response\":{\"body\":{\"item\":[" + rows + "]}}}";
    }

    private ExternalApiResponse response(String name) {
        String payload = "{\"externalApi\":\"" + name + "\"}";
        return new ExternalApiResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
