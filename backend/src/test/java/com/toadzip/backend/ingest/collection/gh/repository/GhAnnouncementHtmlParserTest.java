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
    private final GhAnnouncementHtmlParser parser = new GhAnnouncementHtmlParser();

    @Test
    void 상세의_공급정보와_일정을_줄_구분이_있는_본문으로_정리한다() {
        var content = parser.parse(detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW), GhAnnouncementBoard.RENTAL_HOUSE,
                entry(ROW));

        assertThat(content.title()).isEqualTo(ROW.title());
        assertThat(content.supplyType()).isEqualTo("국민임대");
        assertThat(content.postedDate()).isEqualTo(LocalDate.parse("2026-07-10"));
        assertThat(content.bodyText()).contains("공급정보", "지구명 다산 센트럴파크6단지 소재지 경기도 남양주시 다산동 6107",
                "모집호수 280", "온라인접수기간 : 2026.07.21 10:00 ~ 2026.07.24 17:00", "첫 줄\n둘째 줄");
        assertThat(content.bodyText()).doesNotContain("<", "noise", "푸터", "메뉴", "청약공고 상세정보", "공고명",
                "사업지구별 공급정보", "다운로드", "\t", "\r", "\n\n\n", "  ");
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
    void 다른_게시판이나_다른_공고의_첨부_링크는_거절한다() {
        String rental = detail(GhAnnouncementBoard.RENTAL_HOUSE, ROW);

        assertThatThrownBy(() -> parser.parse(rental, GhAnnouncementBoard.PURCHASE_RENTAL, entry(ROW)))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parser.parse(rental.replace("pbancNo=801&atchFileSn=1604944&atchFileDtlSn=13",
                        "pbancNo=802&atchFileSn=1604944&atchFileDtlSn=13"), GhAnnouncementBoard.RENTAL_HOUSE, entry(ROW)))
                .isInstanceOf(ExternalDataRequestException.class);
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
