package com.toadzip.backend.ingest.collection.gh.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementPage.Entry;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GhHousingTypeDetailsTest {

    private static final Row ROW = new Row("801", "국민임대", "다산 국민임대주택 예비입주자 모집공고", "2026-07-10");
    private final GhHousingTypeDetails details = new GhHousingTypeDetails();

    @Test
    void 주택형마다_팝업_요청을_만들고_응답의_면적_세대수를_그_주택형에_붙인다() throws Exception {
        String districts = districts();
        var requests = details.requests(districts);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).label()).isEqualTo("다산 센트럴파크6단지 36A 청년");
        assertThat(requests.get(1).form()).containsEntry("houseTyCd", "36A").containsEntry("splySlsrtCd", "30050")
                .containsKeys(GhHousingTypeDetails.REQUEST_FIELDS.toArray(String[]::new));

        var detail = details.parse(GhAnnouncementFixtures.housingTypeDetail("36A"), requests.get(1));
        var merged = JsonMapper.builder().build().readTree(details.withDetails(districts, Map.of(requests.get(1), detail)));

        var housingTypes = merged.get(0).path("housingTypes");
        assertThat(housingTypes.get(0).has("detail")).isFalse();
        var stored = housingTypes.get(1).path("detail");
        assertThat(stored.path("supplyArea").asString()).isEqualTo("48.8048");
        assertThat(stored.path("residentialCommonArea").asString()).isEqualTo("15.0948");
        assertThat(stored.path("otherCommonArea").asString()).isEqualTo("1.9698");
        assertThat(stored.path("undergroundParkingArea").asString()).isEqualTo("17.0702");
        assertThat(stored.path("contractArea").asString()).isEqualTo("67.8448");
        assertThat(stored.path("householdCount").asString()).isEqualTo("522");
        assertThat(stored.path("currentSupplyCount").asString()).isEqualTo("120");
        assertThat(stored.path("rentalDeposit").asString()).isEqualTo("공고문 확인");
        assertThat(stored.path("images").get(0).asString())
                .isEqualTo("https://apply.gh.or.kr/sb/sr/sr7150/selectAtchImage.do?atchFileSn=9&atchFlag=gp");
    }

    @Test
    void 다른_주택형이나_필드가_빠진_팝업은_거절한다() {
        var request = details.requests(districts()).get(1);
        String missingField = GhAnnouncementFixtures.housingTypeDetail("36A")
                .replace("<th>계약면적(m²)</th><td>67.8448</td>", "");

        for (String html : new String[]{GhAnnouncementFixtures.housingTypeDetail("33A"), missingField, ""}) {
            assertThatThrownBy(() -> details.parse(html, request)).isInstanceOf(ExternalDataRequestException.class);
        }
    }

    private String districts() {
        return new GhAnnouncementHtmlParser().parse(GhAnnouncementFixtures.detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW),
                GhAnnouncementBoard.RENTAL_HOUSE, new Entry("801", "01", "국민임대", ROW.title(), "남양주시",
                        LocalDate.parse("2026-07-10"), null, null)).districts();
    }
}
