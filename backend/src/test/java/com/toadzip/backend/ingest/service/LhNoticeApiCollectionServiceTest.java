package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.LhNoticeProcessingStatus;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.ExternalApiDataRepository;
import com.toadzip.backend.ingest.repository.LhNoticeApiRepository;
import com.toadzip.backend.ingest.repository.LhNoticeCollectionExecutionLock;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhNoticeApiCollectionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

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

    private LhNoticeApiCollectionService service;

    @BeforeEach
    void setUp() {
        lenient().when(executionLock.<ExternalApiCollectionReport>tryRun(any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<ExternalApiCollectionReport> operation = invocation.getArgument(1);
                    return Optional.of(operation.get());
                });
        service = new LhNoticeApiCollectionService(
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
    void LH_상세_수집은_상세_API_원본만_저장한다() {
        ExternalApiData sourceApiData = sourceApiData(noticePayload());
        pendingSourceApiData(sourceApiData);
        when(apiRepository.fetchDetail(any())).thenReturn(response("detail"));
        when(store.storeApiData(any())).thenReturn(1);

        ExternalApiCollectionReport result = service.collect(ExternalApi.LH_NOTICE_DETAIL);

        ArgumentCaptor<List<ExternalApiData>> storedApiData = ArgumentCaptor.captor();
        verify(store).storeApiData(storedApiData.capture());
        verify(apiRepository).fetchDetail(any());
        verify(apiRepository, never()).fetchSupply(any());
        assertThat(storedApiData.getValue())
                .singleElement()
                .extracting(ExternalApiData::getExternalApi)
                .isEqualTo(ExternalApi.LH_NOTICE_DETAIL);
        assertThat(result.operation()).isEqualTo("lh-notice-detail");
        assertThat(result.storedApiDataCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void LH_공급_수집은_공급_API_원본만_저장한다() {
        ExternalApiData sourceApiData = sourceApiData(noticePayload());
        pendingSourceApiData(sourceApiData);
        when(apiRepository.fetchSupply(any())).thenReturn(response("supply"));
        when(store.storeApiData(any())).thenReturn(1);

        ExternalApiCollectionReport result = service.collect(ExternalApi.LH_NOTICE_SUPPLY);

        ArgumentCaptor<List<ExternalApiData>> storedApiData = ArgumentCaptor.captor();
        verify(store).storeApiData(storedApiData.capture());
        verify(apiRepository).fetchSupply(any());
        verify(apiRepository, never()).fetchDetail(any());
        assertThat(storedApiData.getValue())
                .singleElement()
                .extracting(ExternalApiData::getExternalApi)
                .isEqualTo(ExternalApi.LH_NOTICE_SUPPLY);
        assertThat(result.operation()).isEqualTo("lh-notice-supply");
        assertThat(result.storedApiDataCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 상세_API_실패는_공급_API_수집을_막지_않는다() {
        ExternalApiData sourceApiData = sourceApiData(noticePayload());
        pendingSourceApiData(sourceApiData);
        when(apiRepository.fetchDetail(any())).thenThrow(new IllegalStateException("상세 조회 실패"));
        when(apiRepository.fetchSupply(any())).thenReturn(response("supply"));
        when(store.storeApiData(any())).thenReturn(1);

        ExternalApiCollectionReport detailResult = service.collect(ExternalApi.LH_NOTICE_DETAIL);
        ExternalApiCollectionReport supplyResult = service.collect(ExternalApi.LH_NOTICE_SUPPLY);

        verify(apiRepository).fetchDetail(any());
        verify(apiRepository).fetchSupply(any());
        verify(store, never()).completeLhNoticeProcessing(any(), any());
        verify(store, never()).failLhNoticeProcessing(any(), any());
        assertThat(detailResult.failedRequestCount()).isOne();
        assertThat(supplyResult.storedApiDataCount()).isOne();
    }

    @Test
    void 해당_API_원본이_이미_저장되어_있으면_그_API만_건너뛴다() {
        ExternalApiData sourceApiData = sourceApiData(noticePayload());
        pendingSourceApiData(sourceApiData);
        when(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                eq(ExternalApi.LH_NOTICE_SUPPLY),
                any()
        )).thenReturn(true);

        ExternalApiCollectionReport result = service.collect(ExternalApi.LH_NOTICE_SUPPLY);

        verify(apiRepository, never()).fetchSupply(any());
        verify(apiRepository, never()).fetchDetail(any());
        verify(store, never()).storeApiData(any());
        assertThat(result.storedApiDataCount()).isZero();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 상세와_공급_원본이_모두_있으면_마이홈_snapshot을_완료한다() {
        ExternalApiData sourceApiData = sourceApiData(noticePayload());
        pendingSourceApiData(sourceApiData);
        when(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                eq(ExternalApi.LH_NOTICE_DETAIL),
                any()
        )).thenReturn(true);
        when(apiDataRepository.existsByExternalApiAndRequestDescriptionIn(
                eq(ExternalApi.LH_NOTICE_SUPPLY),
                any()
        )).thenReturn(true);

        service.collect(ExternalApi.LH_NOTICE_DETAIL);

        verify(store).completeLhNoticeProcessing(sourceApiData, CLOCK.instant());
    }

    @Test
    void 잘못된_JSON_snapshot은_현재_API의_실패로_기록하고_다음_snapshot을_계속한다() {
        ExternalApiData malformedSnapshot = sourceApiData("{");
        ExternalApiData validSnapshot = sourceApiData(noticePayload());
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        )).thenReturn(List.of(malformedSnapshot, validSnapshot));
        when(apiRepository.fetchDetail(any())).thenReturn(response("detail"));
        when(store.storeApiData(any())).thenReturn(1);

        ExternalApiCollectionReport result = service.collect(ExternalApi.LH_NOTICE_DETAIL);

        verify(failureRecorder).record(
                eq(ExternalApi.LH_NOTICE_DETAIL),
                any(),
                any(),
                any(),
                any()
        );
        verify(store).failLhNoticeProcessing(malformedSnapshot, CLOCK.instant());
        assertThat(result.storedApiDataCount()).isOne();
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 같은_원본_snapshot이_이미_완료되었다면_현재_API를_호출하지_않는다() {
        ExternalApiData sourceApiData = sourceApiData(noticePayload());
        pendingSourceApiData(sourceApiData);
        when(apiDataRepository
                .existsByExternalApiAndRequestDescriptionAndContentHashAndLhNoticeProcessingStatus(
                        ExternalApi.MYHOME_NOTICE,
                        sourceApiData.getRequestDescription(),
                        sourceApiData.getContentHash(),
                        LhNoticeProcessingStatus.COMPLETED
                )).thenReturn(true);

        service.collect(ExternalApi.LH_NOTICE_SUPPLY);

        verify(apiRepository, never()).fetchDetail(any());
        verify(apiRepository, never()).fetchSupply(any());
        verify(store).completeLhNoticeProcessing(sourceApiData, CLOCK.instant());
    }

    @Test
    void 같은_API_수집이_실행_중이면_외부_API를_호출하지_않는다() {
        doReturn(Optional.empty()).when(executionLock).tryRun(
                eq(ExternalApi.LH_NOTICE_DETAIL),
                any()
        );

        assertThatThrownBy(() -> service.collect(ExternalApi.LH_NOTICE_DETAIL))
                .isInstanceOf(IngestAlreadyRunningException.class)
                .hasMessage("lh-notice-detail 수집이 이미 실행 중입니다.");

        verify(apiRepository, never()).fetchDetail(any());
        verify(apiRepository, never()).fetchSupply(any());
    }

    @Test
    void LH_상세나_공급이_아닌_API는_거절한다() {
        assertThatThrownBy(() -> service.collect(ExternalApi.MYHOME_NOTICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LH 공고 API가 아닙니다.");
    }

    private void pendingSourceApiData(ExternalApiData sourceApiData) {
        when(apiDataRepository.findAllPendingLhNoticeApiData(
                ExternalApi.MYHOME_NOTICE,
                LhNoticeProcessingStatus.PENDING
        )).thenReturn(List.of(sourceApiData));
    }

    private ExternalApiData sourceApiData(String apiData) {
        return ExternalApiData.create(
                ExternalApi.MYHOME_NOTICE,
                "suplyTy=10&pageNo=1",
                1,
                CLOCK.instant(),
                apiData
        );
    }

    private String noticePayload() {
        return "{\"response\":{\"body\":{\"item\":[{"
                + "\"suplyTyNm\":\"행복주택\","
                + "\"url\":\"https://apply.lh.or.kr/panDetail?panId=100"
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06\"}]}}}";
    }

    private ExternalApiResponse response(String name) {
        String payload = "{\"externalApi\":\"" + name + "\"}";
        return new ExternalApiResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
