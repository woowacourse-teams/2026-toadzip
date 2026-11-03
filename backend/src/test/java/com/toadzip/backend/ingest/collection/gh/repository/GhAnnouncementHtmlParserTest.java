package com.toadzip.backend.ingest.collection.gh.repository;

import static com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.detail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementPage.Entry;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.time.LocalDate;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GhAnnouncementHtmlParserTest {

    private static final Row ROW = new Row("801", "국민임대", "다산 국민임대주택 예비입주자 모집공고", "2026-07-10");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final GhAnnouncementHtmlParser parser = new GhAnnouncementHtmlParser();

    @Test
    void 숨은_탭까지_지구마다_공급정보와_주택형_일정을_원문_값으로_나눈다() throws Exception {
        var content = parser.parse(detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW), GhAnnouncementBoard.RENTAL_HOUSE,
                entry(ROW));

        assertThat(content.title()).isEqualTo(ROW.title());
        assertThat(content.supplyType()).isEqualTo("국민임대");
        assertThat(content.postedDate()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(content.unreadParts()).isEmpty();
        var districts = JSON.readTree(content.districts());
        assertThat(districts).hasSize(2);
        var first = districts.get(0);
        assertThat(first.path("name").asString()).isEqualTo("다산 센트럴파크6단지");
        assertThat(first.path("address").asString()).isEqualTo("경기도 남양주시 다산동 6107");
        assertThat(first.path("exclusiveAreaRange").asString()).isEqualTo("33.71 ~ 46.5");
        assertThat(first.path("recruitmentCount").asString()).isEqualTo("280");
        assertThat(first.path("heatingType").asString()).isEmpty();
        assertThat(first.path("expectedMoveIn").asString()).isEqualTo("2027.03");
        assertThat(first.path("notice").asString()).isEqualTo("자세한 사항은 공고문을 확인해 주시기 바랍니다.");
        assertThat(first.has("reception")).isFalse();
        var housingType = first.path("housingTypes").get(1);
        assertThat(first.path("housingTypes")).hasSize(2);
        assertThat(housingType.path("name").asString()).isEqualTo("36A 청년");
        assertThat(housingType.path("houseTypeCode").asString()).isEqualTo("36A");
        assertThat(housingType.path("businessCode").asString()).isEqualTo("2011002");
        assertThat(housingType.path("recruitmentRound").asString()).isEqualTo("8");
        assertThat(housingType.path("exclusiveArea").asString()).isEqualTo("36.8");
        assertThat(housingType.path("supplyCount").asString()).isEqualTo("60");
        assertThat(housingType.path("rentalDeposit").asString()).isEqualTo("12,000,000");
        assertThat(housingType.path("monthlyRent").asString()).isEqualTo("150,000");
        assertThat(housingType.path("applicationStatus").asString()).isEqualTo("접수마감");
        assertThat(first.path("schedules").get(0).path("label").asString()).isEqualTo("온라인접수기간");
        assertThat(first.path("schedules").get(0).path("value").asString())
                .isEqualTo("2026.07.21 10:00 ~ 2026.07.24 17:00");
        var second = districts.get(1);
        assertThat(second.path("address").asString()).isEqualTo("경기도 안양시 동안구 관양동 1498-21");
        assertThat(second.path("housingTypes")).isEmpty();
        assertThat(second.path("reception").path("address").asString()).isEqualTo("관양 임대센터");
    }

    @Test
    void 공고_단위_접수처_기타사항_안내사항을_지구_구획과_섞지_않고_읽는다() throws Exception {
        var content = parser.parse(detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW), GhAnnouncementBoard.RENTAL_HOUSE,
                entry(ROW));

        var reception = JSON.readTree(content.reception());
        assertThat(reception.path("address").asString()).isEqualTo("다산 임대센터");
        assertThat(reception.path("phone").asString()).isEqualTo("031-510-7100");
        assertThat(reception.path("operatingPeriod").asString()).isEqualTo("2026.07.20~2026.07.22");
        assertThat(content.otherNotes()).isEqualTo("기타 사항은 공고문을 확인해 주시기 바랍니다.");
        assertThat(content.notice()).isEqualTo("※ 첫 줄\n※ 둘째 줄");
    }

    @Test
    void 공급정보가_없는_매입임대_공고는_빈_지구_목록과_빈_구획으로_읽는다() {
        var document = Jsoup.parse(detail(GhAnnouncementBoard.PURCHASE_RENTAL, ROW));
        document.select("h5:containsOwn(공급정보), ul.tab_box, div#tab1, div#tab2").remove();
        document.select("h5:containsOwn(기타사항), div.bg_txt_box").remove();

        var content = parser.parse(document.html(), GhAnnouncementBoard.PURCHASE_RENTAL, entry(ROW));

        assertThat(content.districts()).isEqualTo("[]");
        assertThat(content.otherNotes()).isEmpty();
        assertThat(content.unreadParts()).isEmpty();
    }

    @Test
    void 주택형_표_열이_바뀌면_공급정보만_비우고_나머지_구획은_읽는다() {
        String html = detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW).replace("금회공급 세대수", "공급 세대수");

        var content = parser.parse(html, GhAnnouncementBoard.RENTAL_HOUSE, entry(ROW));

        assertThat(content.districts()).isNull();
        assertThat(content.unreadParts()).containsExactly("공급정보");
        assertThat(content.reception()).contains("다산 임대센터");
    }

    @Test
    void 모르는_구획이_생기면_읽지_못한_부분으로_남긴다() {
        String html = detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW).replace(
                "<h5 class=\"subcont_title\">기타사항</h5>", "<h5 class=\"subcont_title\">임대조건</h5><p>표</p>"
                        + "<h5 class=\"subcont_title\">기타사항</h5>");

        var content = parser.parse(html, GhAnnouncementBoard.RENTAL_HOUSE, entry(ROW));

        assertThat(content.unreadParts()).containsExactly("알 수 없는 구획(임대조건)");
        assertThat(content.districts()).isNotNull();
    }

    @Test
    void 공고문_첨부의_파일_식별자_이름_크기와_다운로드_주소를_보존한다() throws Exception {
        var content = parser.parse(detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW), GhAnnouncementBoard.RENTAL_HOUSE,
                entry(ROW));

        var attachments = JsonMapper.builder().build().readTree(content.attachments());
        assertThat(attachments).hasSize(2);
        assertThat(attachments.get(1).path("fileName").asString()).isEqualTo("모집공고문.pdf");
        assertThat(attachments.get(1).path("fileSize").asString()).isEqualTo("1207812");
        assertThat(attachments.get(1).path("atchFileSn").asString()).isEqualTo("1604944");
        assertThat(attachments.get(1).path("atchFileDtlSn").asString()).isEqualTo("13");
        assertThat(attachments.get(1).path("downloadUrl").asString())
                .startsWith("https://apply-cdn.gh.or.kr/sr/sr7150/selectFileDown.do?pbancNo=801&");
    }

    @Test
    void 공고문_행이_없으면_첨부가_없는_공고로_본다() {
        var document = Jsoup.parse(detail(GhAnnouncementBoard.PURCHASE_RENTAL, ROW));
        document.select("th:containsOwn(공고문)").first().parent().remove();

        var content = parser.parse(document.html(), GhAnnouncementBoard.PURCHASE_RENTAL, entry(ROW));

        assertThat(content.attachments()).isEqualTo("[]");
    }

    @Test
    void 목록과_제목_유형_공고일이_다르면_거절한다() {
        String html = detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW);

        for (Entry other : new Entry[]{
                entry(new Row("801", "국민임대", "다른 공고", "2026-07-10")),
                entry(new Row("801", "행복주택", ROW.title(), "2026-07-10")),
                entry(new Row("801", "국민임대", ROW.title(), "2026-07-11"))}) {
            assertThatThrownBy(() -> parser.parse(html, GhAnnouncementBoard.RENTAL_HOUSE, other))
                    .isInstanceOf(ExternalDataRequestException.class);
        }
    }

    @Test
    void 다른_게시판의_상세는_거절한다() {
        String rental = detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW);

        assertThatThrownBy(() -> parser.parse(rental, GhAnnouncementBoard.PURCHASE_RENTAL, entry(ROW)))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void 다른_공고의_첨부_링크가_섞이면_첨부만_비우고_본문은_읽는다() {
        String html = detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW).replace(
                "pbancNo=801&atchFileSn=1604944&atchFileDtlSn=13", "pbancNo=802&atchFileSn=1604944&atchFileDtlSn=13");

        var content = parser.parse(html, GhAnnouncementBoard.RENTAL_HOUSE, entry(ROW));

        assertThat(content.attachments()).isNull();
        assertThat(content.unreadParts()).containsExactly("첨부");
        assertThat(content.districts()).contains("다산 센트럴파크6단지");
    }

    @Test
    void 모두_읽은_상세는_읽지_못한_부분이_없다() {
        var content = parser.parse(detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW), GhAnnouncementBoard.RENTAL_HOUSE,
                entry(ROW));

        assertThat(content.unreadParts()).isEmpty();
    }

    @Test
    void 상세정보_표가_없는_오류_페이지는_거절한다() {
        for (String html : new String[]{"", "<html><div class='sub_textbox'>점검 중</div></html>", "<html>오류</html>"}) {
            assertThatThrownBy(() -> parser.parse(html, GhAnnouncementBoard.RENTAL_HOUSE, entry(ROW)))
                    .isInstanceOf(ExternalDataRequestException.class);
        }
    }

    private Entry entry(Row row) {
        return new Entry(row.pbancNo(), "01", row.supplyType(), row.title(), "남양주시",
                LocalDate.parse(row.postedDate()), null, null);
    }
}
