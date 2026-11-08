package com.toadzip.backend.ingest.gh.collection.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.failure.domain.ExternalDataFailureStatus;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementSourceRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 실제 GH 외부 저장소 빈을 거쳐, 보낼 수 없는 주택형 상세 요청 하나가 수집 단계를 멈추지 않는지 본다. */
@ExtendWith(IngestExecutionExtension.class)
@Import(GhAnnouncementCollectionIntegrationTest.ClockConfiguration.class)
@SpringBootTest(properties = {"ingest.gh.request-interval=0s", "ingest.gh.lookback-days=0"})
@ActiveProfiles("test")
class GhHousingTypeDetailRejectionIntegrationTest {

    private static final Row ROW = new Row("801", "국민임대", "다산 국민임대주택 예비입주자 모집공고", "2026-07-10");
    private static final RestClient.Builder BUILDER = RestClient.builder();
    private static final MockRestServiceServer SERVER = MockRestServiceServer.bindTo(BUILDER).build();

    @TestBean(name = "ghAnnouncementRestClient")
    private RestClient ghAnnouncementRestClient;

    @Autowired
    private GhAnnouncementCollectionService service;
    @Autowired
    private GhAnnouncementSourceRepository sources;
    @Autowired
    private ExternalDataCollectionFailureRepository failures;
    @Autowired
    private JdbcClient jdbc;

    static RestClient ghAnnouncementRestClient() {
        return BUILDER.build();
    }

    @AfterEach
    void cleanUp() {
        SERVER.reset();
        jdbc.sql("DELETE FROM pending_collection_targets").update();
        sources.deleteAll();
        failures.deleteAll();
    }

    @Test
    void 보낼_수_없는_주택형_값은_그_주택형만_실패로_남기고_공고는_저장한다() {
        // 두 번째 주택형 값에 허용하지 않는 문자를 넣어, 팝업 요청을 보내기 전에 거절되게 한다.
        String detail = GhAnnouncementFixtures.detail(RENTAL_HOUSE, ROW)
                .replace("data-houseTyCd=\"36A\"", "data-houseTyCd=\"36A/1\"");
        SERVER.expect(requestTo(RENTAL_HOUSE.listUrl(1)))
                .andRespond(withSuccess(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, ROW), MediaType.TEXT_HTML));
        SERVER.expect(requestTo(RENTAL_HOUSE.detailUrl("801", "01")))
                .andRespond(withSuccess(detail, MediaType.TEXT_HTML));
        SERVER.expect(requestTo(RENTAL_HOUSE.housingTypeDetailUrl())).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(GhAnnouncementFixtures.housingTypeDetail("33A"), MediaType.TEXT_HTML));

        var result = service.collect();

        SERVER.verify();
        assertThat(result.storedRowCount()).isOne();
        assertThat(result.failedRequestCount()).isOne();
        String districts = sources.findBySourceKey("GH:801").orElseThrow().getDistricts();
        assertThat(districts).contains("\"contractArea\"").contains("36A/1");
        assertThat(failures.findAllBySourceAndRequestDescriptionAndStatus(
                ExternalDataSource.GH_ANNOUNCEMENT, "pbancNo=801", ExternalDataFailureStatus.PENDING))
                .singleElement().satisfies(failure -> assertThat(failure.getReason()).contains("주택형 상세(다산 센트럴파크6단지 36A 청년)"));
    }
}
