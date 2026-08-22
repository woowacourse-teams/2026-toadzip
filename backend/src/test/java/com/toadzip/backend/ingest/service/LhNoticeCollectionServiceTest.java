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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.toadzip.backend.ingest.domain.ExternalApiData;
import com.toadzip.backend.ingest.domain.ExternalApi;
import com.toadzip.backend.ingest.dto.ExternalApiResponse;
import com.toadzip.backend.ingest.dto.LhNoticeRequest;
import com.toadzip.backend.ingest.repository.ExternalApiCollectionStore;
import com.toadzip.backend.ingest.repository.ExternalApiDataRepository;
import com.toadzip.backend.ingest.repository.LhNoticeApiRepository;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhNoticeCollectionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ExternalApiDataRepository apiDataRepository;

    @Mock
    private LhNoticeApiRepository apiRepository;

    @Mock
    private ExternalApiCollectionStore store;

    @Mock
    private ExternalApiFailureRecorder failureRecorder;

    private LhNoticeCollectionService service;

    @BeforeEach
    void setUp() {
        service = new LhNoticeCollectionService(
                CLOCK,
                JsonMapper.builder().build(),
                apiDataRepository,
                apiRepository,
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
        when(apiDataRepository.findAllByExternalApiOrderByCollectedAtAscIdAsc(ExternalApi.MYHOME_NOTICE))
                .thenReturn(List.of(myHomeNotice));
        when(apiRepository.fetchDetail(any())).thenReturn(response("detail"));
        when(apiRepository.fetchSupply(any())).thenReturn(response("supply"));

        var result = service.collect();

        verify(store).storeApiData(any());
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
        when(apiDataRepository.findAllByExternalApiOrderByCollectedAtAscIdAsc(ExternalApi.MYHOME_NOTICE))
                .thenReturn(List.of(myHomeNotice));

        var result = service.collect();

        verify(failureRecorder).record(any(), any(), any(), any(), any());
        assertThat(result.storedApiDataCount()).isZero();
        assertThat(result.failedRequestCount()).isOne();
    }

    private String noticePayload() {
        return noticePayload("행복주택");
    }

    private String noticePayload(String supplyType) {
        return "{\"response\":{\"body\":{\"item\":[{"
                + "\"suplyTyNm\":\"" + supplyType + "\","
                + "\"url\":\"https://apply.lh.or.kr/panDetail?panId=100&ccrCnntSysDsCd=03"
                + "&uppAisTpCd=06&aisTpCd=06\"}]}}}";
    }

    private ExternalApiResponse response(String name) {
        String payload = "{\"externalApi\":\"" + name + "\"}";
        return new ExternalApiResponse(payload, JsonMapper.builder().build().readTree(payload));
    }
}
