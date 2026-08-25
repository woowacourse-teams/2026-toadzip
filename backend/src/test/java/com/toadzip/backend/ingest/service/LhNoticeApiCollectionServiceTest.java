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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.domain.MyHomeNoticeSource;
import com.toadzip.backend.ingest.dto.ExternalApiCollectionReport;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.MyHomeNoticeSourceItem;
import com.toadzip.backend.ingest.repository.LhNoticeApiRepository;
import com.toadzip.backend.ingest.repository.LhNoticeCollectionExecutionLock;
import com.toadzip.backend.ingest.repository.LhSourceStore;
import com.toadzip.backend.ingest.repository.MyHomeNoticeSourceRepository;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhNoticeApiCollectionServiceTest {

    @Mock
    private MyHomeNoticeSourceRepository myHomeNoticeRepository;

    @Mock
    private LhNoticeApiRepository apiRepository;

    @Mock
    private LhNoticeCollectionExecutionLock executionLock;

    @Mock
    private LhSourceStore sourceStore;

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
                myHomeNoticeRepository,
                apiRepository,
                executionLock,
                sourceStore,
                new LhNoticeSourceMapper(),
                failureRecorder,
                new LhSupplyInfoTypeCodeResolver(),
                new ExternalApiRetryExecutor(Duration.ZERO)
        );
    }

    @Test
    void LH_상세_수집은_상세_행만_저장한다() {
        source(noticeSource());
        when(apiRepository.fetchDetail(any())).thenReturn(detailResponse());
        when(sourceStore.replaceDetails(eq("100"), any())).thenReturn(1);

        ExternalApiCollectionReport result = service.collect(ExternalApi.LH_NOTICE_DETAIL);

        verify(sourceStore).replaceDetails(eq("100"), any());
        verify(sourceStore, never()).replaceSupplies(any(), any());
        verify(apiRepository).fetchDetail(any());
        verify(apiRepository, never()).fetchSupply(any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void LH_공급_수집은_공급_행만_저장한다() {
        source(noticeSource());
        when(apiRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);

        ExternalApiCollectionReport result = service.collect(ExternalApi.LH_NOTICE_SUPPLY);

        verify(sourceStore).replaceSupplies(eq("100"), any());
        verify(sourceStore, never()).replaceDetails(any(), any());
        verify(apiRepository).fetchSupply(any());
        verify(apiRepository, never()).fetchDetail(any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 상세_API_실패는_공급_API_수집을_막지_않는다() {
        source(noticeSource());
        when(apiRepository.fetchDetail(any())).thenThrow(new IllegalStateException("상세 조회 실패"));
        when(apiRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);

        ExternalApiCollectionReport detail = service.collect(ExternalApi.LH_NOTICE_DETAIL);
        ExternalApiCollectionReport supply = service.collect(ExternalApi.LH_NOTICE_SUPPLY);

        assertThat(detail.failedRequestCount()).isOne();
        assertThat(supply.storedRowCount()).isOne();
    }

    @Test
    void 기존_행이_있어도_명시적_수집은_해당_API_snapshot을_교체한다() {
        source(noticeSource());
        when(apiRepository.fetchSupply(any())).thenReturn(supplyResponse());
        when(sourceStore.replaceSupplies(eq("100"), any())).thenReturn(1);

        ExternalApiCollectionReport result = service.collect(ExternalApi.LH_NOTICE_SUPPLY);

        verify(apiRepository).fetchSupply(any());
        verify(sourceStore).replaceSupplies(eq("100"), any());
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isZero();
    }

    @Test
    void 조회_조건이_없는_공고는_실패_이력으로_남긴다() {
        source(invalidNoticeSource());

        ExternalApiCollectionReport result = service.collect(ExternalApi.LH_NOTICE_DETAIL);

        verify(failureRecorder).record(eq(ExternalApi.LH_NOTICE_DETAIL), any(), any(), any(), any());
        verify(apiRepository, never()).fetchDetail(any());
        assertThat(result.failedRequestCount()).isOne();
    }

    @Test
    void 같은_API_수집이_실행_중이면_외부_API를_호출하지_않는다() {
        doReturn(Optional.empty()).when(executionLock).tryRun(eq(ExternalApi.LH_NOTICE_DETAIL), any());

        assertThatThrownBy(() -> service.collect(ExternalApi.LH_NOTICE_DETAIL))
                .isInstanceOf(IngestAlreadyRunningException.class);

        verify(apiRepository, never()).fetchDetail(any());
    }

    @Test
    void LH_상세나_공급이_아닌_API는_거절한다() {
        assertThatThrownBy(() -> service.collect(ExternalApi.MYHOME_NOTICE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LH 공고 API가 아닙니다.");
    }

    private void source(MyHomeNoticeSource source) {
        when(myHomeNoticeRepository.findAll()).thenReturn(List.of(source));
    }

    private MyHomeNoticeSource noticeSource() {
        return MyHomeNoticeSource.from(0, item(
                "행복주택",
                "https://apply.lh.or.kr/panDetail?panId=100"
                        + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=06"
        ));
    }

    private MyHomeNoticeSource invalidNoticeSource() {
        return MyHomeNoticeSource.from(0, item(null, null));
    }

    private MyHomeNoticeSourceItem item(String supplyType, String url) {
        return new MyHomeNoticeSourceItem(
                "100", 1, null, "공고", null, null, supplyType, null, null, null,
                null, null, null, url, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private ExternalApiResponse detailResponse() {
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},"
                + "{\"dsEtcInfo\":[{\"CRC_RSN\":\"정정\"}]}]");
    }

    private ExternalApiResponse supplyResponse() {
        return response("[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},"
                + "{\"dsList01\":[{\"SBD_LGO_NM\":\"행복주택\"}]}]");
    }

    private ExternalApiResponse response(String payload) {
        return new ExternalApiResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
