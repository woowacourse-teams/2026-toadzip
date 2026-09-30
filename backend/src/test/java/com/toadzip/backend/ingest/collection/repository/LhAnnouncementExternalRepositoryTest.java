package com.toadzip.backend.ingest.collection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.toadzip.backend.ingest.collection.dto.ExternalDataResponse;
import com.toadzip.backend.ingest.collection.dto.LhAnnouncementRequest;
import com.toadzip.backend.ingest.collection.repository.external.DataGoKrOpenApiClient;
import com.toadzip.backend.ingest.collection.repository.external.LhAnnouncementCircuitBreaker;
import java.time.Clock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LhAnnouncementExternalRepositoryTest {

    private static final LhAnnouncementRequest REQUEST =
            new LhAnnouncementRequest("PAN-1", "03", "06", "48", "062");

    @Mock
    private DataGoKrOpenApiClient client;

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private LhAnnouncementExternalRepository repository;

    @BeforeEach
    void setUp() {
        repository = new LhAnnouncementExternalRepository(client,
                new LhAnnouncementCircuitBreaker(Clock.systemUTC(), new SimpleMeterRegistry()));
    }

    @Test
    void 공급_응답의_공고_ID가_요청과_다르면_거절한다() {
        when(client.get(anyString(), any())).thenReturn(response("PAN-2", "062"));

        assertThatThrownBy(() -> repository.fetchSupply(REQUEST))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessageContaining("조회 조건");
    }

    @Test
    void 상세_응답에_조회_조건이_없으면_거절한다() {
        String payload = "[{\"resHeader\":[{\"SS_CODE\":\"Y\"}]},{\"dsEtcInfo\":[]}]";
        when(client.get(anyString(), any())).thenReturn(new ExternalDataResponse(
                payload, objectMapper.readTree(payload)
        ));

        assertThatThrownBy(() -> repository.fetchDetail(REQUEST))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessageContaining("조회 조건");
    }

    @Test
    void 공급_응답의_공급유형이_요청과_다르면_거절한다() {
        when(client.get(anyString(), any())).thenReturn(response("PAN-1", "060"));

        assertThatThrownBy(() -> repository.fetchSupply(REQUEST))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessageContaining("조회 조건");
    }

    @Test
    void 조회_조건이_일치하면_응답을_반환한다() {
        ExternalDataResponse expected = response("PAN-1", "062");
        when(client.get(anyString(), any())).thenReturn(expected);

        assertThat(repository.fetchDetail(REQUEST)).isSameAs(expected);
        assertThat(repository.fetchSupply(REQUEST)).isSameAs(expected);
    }

    @Test
    void 선택_매물유형을_요청하지_않았다면_응답의_해당_필드를_강제하지_않는다() {
        LhAnnouncementRequest request = new LhAnnouncementRequest("PAN-1", "03", "06", null, "062");
        ExternalDataResponse expected = response("PAN-1", "062");
        when(client.get(anyString(), any())).thenReturn(expected);

        assertThat(repository.fetchSupply(request)).isSameAs(expected);
    }

    private ExternalDataResponse response(String panId, String supplyInfoTypeCode) {
        String payload = """
                [{"resHeader":[{"SS_CODE":"Y"}]},
                 {"dsSch":[{"PAN_ID":"%s","CCR_CNNT_SYS_DS_CD":"03",
                            "UPP_AIS_TP_CD":"06","AIS_TP_CD":"48",
                            "SPL_INF_TP_CD":"%s"}]},
                 {"dsList01":[]}]
                """.formatted(panId, supplyInfoTypeCode);
        return new ExternalDataResponse(payload, objectMapper.readTree(payload));
    }
}
