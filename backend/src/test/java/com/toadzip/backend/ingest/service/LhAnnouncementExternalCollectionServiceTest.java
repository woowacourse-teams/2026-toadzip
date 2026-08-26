package com.toadzip.backend.ingest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalDataSource;
import com.toadzip.backend.ingest.domain.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.dto.ExternalDataCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.dto.MyHomeAnnouncementSourceItem;
import com.toadzip.backend.ingest.exception.exception.IngestAlreadyRunningException;
import com.toadzip.backend.ingest.repository.LhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.repository.LhAnnouncementCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import com.toadzip.backend.ingest.repository.MyHomeAnnouncementSourceRepository;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementExternalCollectionServiceTest {

    @Mock
    private MyHomeAnnouncementSourceRepository myHomeAnnouncementRepository;

    @Mock
    private LhAnnouncementExternalRepository externalRepository;

    @Mock
    private LhAnnouncementCollectionExecutionLock executionLock;

    @Mock
    private LhSourceStore sourceStore;

    @Mock
    private LhAnnouncementCollectionProgressStore progressStore;

    @Mock
    private ExternalDataFailureRecorder failureRecorder;

    private LhAnnouncementExternalCollectionService service;

    @BeforeEach
    void setUp() {
        lenient().when(executionLock.<ExternalDataCollectionReport>tryRun(any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<ExternalDataCollectionReport> operation = invocation.getArgument(1);
                    return Optional.of(operation.get());
                });
        service = new LhAnnouncementExternalCollectionService(
                myHomeAnnouncementRepository,
                externalRepository,
                executionLock,
                sourceStore,
                progressStore,
                new LhAnnouncementSourceMapper(),
                failureRecorder,
                new LhSupplyInfoTypeCodeResolver(),
                new ExternalDataRetryExecutor(Duration.ZERO)
        );
    }

    @Test
    void LH_상세_수집은_상세_행만_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(sourceStore).replaceDetails(eq("100"), any());
        verify(sourceStore, never()).replaceSupplies(any(), any());
        verify(externalRepository).fetchDetail(any());
        verify(externalRepository, never()).fetchSupply(any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void LH_공급_수집은_공급_행만_저장한다() {
        source(announcementSource());
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(sourceStore).replaceSupplies(eq("100"), any());
        verify(sourceStore, never()).replaceDetails(any(), any());
        verify(externalRepository).fetchSupply(any());
        verify(externalRepository, never()).fetchDetail(any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 상세_API_실패는_공급_API_수집을_막지_않는다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any())).thenThrow(new IllegalStateException("상세 조회 실패"));
        when(externalRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport detail = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        ExternalDataCollectionReport supply = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        assertThat(detail.failedRequestCount()).isOne();
        assertThat(supply.storedRowCount()).isOne();
    }

    @Test
    void 완료된_동일_요청은_외부_API를_재호출하지_않는다() {
        source(announcementSource());
        when(progressStore.isCompleted(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY),
                any()
        )).thenReturn(true);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(externalRepository, never()).fetchSupply(any());
        verify(sourceStore, never()).replaceSupplies(any(), any());
        assertThat(result.storedRowCount()).isZero();
        assertThat(result.failedRequestCount()).isZero();
        assertThat(result.externalApiCallCount()).isZero();
    }

    @Test
    void 실패한_요청은_다음_실행에서_다시_호출한다() {
        source(announcementSource());
        when(externalRepository.fetchDetail(any()))
                .thenThrow(new IllegalStateException("일시적 실패"))
                .thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport failed = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);
        ExternalDataCollectionReport retried = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository, org.mockito.Mockito.times(2)).fetchDetail(any());
        verify(progressStore).complete(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                eq("100"),
                any(),
                eq("100")
        );
        assertThat(failed.failedRequestCount()).isOne();
        assertThat(retried.storedRowCount()).isOne();
    }

    @Test
    void 기존_적재_행은_첫_증분_실행에서_호출하지_않고_체크포인트만_생성한다() {
        source(announcementSource());
        when(progressStore.hasStoredRows(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "100")).thenReturn(true);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository, never()).fetchDetail(any());
        verify(progressStore).complete(
                eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL),
                eq("100"),
                any(),
                eq("100")
        );
        assertThat(result.externalApiCallCount()).isZero();
    }

    @Test
    void 수집_이력이_있는_panId의_조회_조건이_바뀌면_다시_호출한다() {
        source(announcementSource());
        when(progressStore.hasStoredRows(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "100")).thenReturn(true);
        when(progressStore.hasCollectionHistory(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL, "100")).thenReturn(true);
        when(externalRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(externalRepository).fetchDetail(any());
        assertThat(result.externalApiCallCount()).isOne();
    }

    @Test
    void 한_실행에서는_동일한_LH_요청을_한_번만_호출한다() {
        MyHomeAnnouncementSource first = announcementSource("announcement-100");
        MyHomeAnnouncementSource second = announcementSource("announcement-101");
        when(myHomeAnnouncementRepository.findAll()).thenReturn(List.of(first, second));
        when(externalRepository.fetchSupply(any())).thenThrow(new IllegalStateException("일시적 실패"));

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY);

        verify(externalRepository, times(1)).fetchSupply(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 조회_조건이_없는_공고는_실패_이력으로_남긴다() {
        source(invalidAnnouncementSource());

        ExternalDataCollectionReport result = service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL);

        verify(failureRecorder).record(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), any(), any(), any(), any());
        verify(externalRepository, never()).fetchDetail(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 같은_API_수집이_실행_중이면_외부_API를_호출하지_않는다() {
        doReturn(Optional.empty()).when(executionLock).tryRun(eq(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL), any());

        assertThatThrownBy(() -> service.collect(ExternalDataSource.LH_ANNOUNCEMENT_DETAIL))
                .isInstanceOf(IngestAlreadyRunningException.class);

        verify(externalRepository, never()).fetchDetail(any());
    }

    @Test
    void LH_상세나_공급이_아닌_API는_거절한다() {
        assertThatThrownBy(() -> service.collect(ExternalDataSource.MYHOME_ANNOUNCEMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LH 공고 API가 아닙니다.");
    }

    private void source(MyHomeAnnouncementSource source) {
        when(myHomeAnnouncementRepository.findAll()).thenReturn(List.of(source));
    }

    private MyHomeAnnouncementSource announcementSource() {
        return announcementSource("100");
    }

    private MyHomeAnnouncementSource announcementSource(String pblancId) {
        return MyHomeAnnouncementSource.from(0, item(
                pblancId,
                "행복주택",
                "https://apply.lh.or.kr/panDetail?panId=100"
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"
        ));
    }

    private MyHomeAnnouncementSource invalidAnnouncementSource() {
        return MyHomeAnnouncementSource.from(0, item("100", null, null));
    }

    private MyHomeAnnouncementSourceItem item(String supplyType, String url) {
        return item("100", supplyType, url);
    }

    private MyHomeAnnouncementSourceItem item(String pblancId, String supplyType, String url) {
        return new MyHomeAnnouncementSourceItem(
                pblancId, 1, null, "공고", null, null, supplyType, null, null, null,
                null, null, null, url, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private ExternalDataResponse detailResponse() {
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},"
                + "{\"dsEtcInfo\":[{\"CRC_RSN\":\"정정\"}]}]");
    }

    private ExternalDataResponse supplyResponse() {
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},"
                + "{\"dsList01\":[{\"SBD_LGO_NM\":\"행복주택\"}]}]");
    }

    private ExternalDataResponse response(String payload) {
        return new ExternalDataResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
