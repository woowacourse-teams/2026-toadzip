package com.toadzip.backend.ingest.gh.collection.repository;

import static com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class GhAnnouncementListParserTest {

    private static final Row ROW = new Row("801", "국민임대", "다산 국민임대주택 예비입주자 모집공고", "2026-07-10");
    private final GhAnnouncementListParser parser = new GhAnnouncementListParser();

    @Test
    void 목록_행의_공고_식별자와_목록_값을_읽는다() {
        var page = parser.parseList(list(GhAnnouncementBoard.RENTAL_HOUSE, 1, 1, ROW),
                GhAnnouncementBoard.RENTAL_HOUSE, 1);

        assertThat(page.totalCount()).isOne();
        assertThat(page.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.pbancNo()).isEqualTo("801");
            assertThat(entry.kindCode()).isEqualTo("01");
            assertThat(entry.supplyType()).isEqualTo("국민임대");
            assertThat(entry.title()).isEqualTo(ROW.title());
            assertThat(entry.region()).isEqualTo("남양주시");
            assertThat(entry.postedDate()).isEqualTo(LocalDate.parse("2026-07-10"));
            assertThat(entry.closingDate()).isEqualTo(LocalDate.parse("2026-10-20"));
            assertThat(entry.status()).isEqualTo("접수마감");
        });
    }

    @Test
    void 마감일과_상태가_없다고_표시된_공고는_빈_값으로_읽는다() {
        String html = list(GhAnnouncementBoard.RENTAL_HOUSE, 1, 1, ROW)
                .replace("<td>2026-10-20", "<td> - ").replace("<td>접수마감", "<td> - ");

        var entry = parser.parseList(html, GhAnnouncementBoard.RENTAL_HOUSE, 1).entries().getFirst();

        assertThat(entry.closingDate()).isNull();
        assertThat(entry.status()).isNull();
    }

    @Test
    void 빈_목록은_안내_행을_공고로_읽지_않는다() {
        var page = parser.parseList(list(GhAnnouncementBoard.PURCHASE_RENTAL, 1, 0),
                GhAnnouncementBoard.PURCHASE_RENTAL, 1);

        assertThat(page.totalCount()).isZero();
        assertThat(page.entries()).isEmpty();
    }

    @Test
    void 다른_게시판이나_다른_페이지_응답은_거절한다() {
        String rental = list(GhAnnouncementBoard.RENTAL_HOUSE, 1, 1, ROW);

        assertThatThrownBy(() -> parser.parseList(rental, GhAnnouncementBoard.PURCHASE_RENTAL, 1))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parser.parseList(rental, GhAnnouncementBoard.RENTAL_HOUSE, 2))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void 전체_건수와_행_수가_다르거나_식별자가_없으면_거절한다() {
        String missingRow = list(GhAnnouncementBoard.RENTAL_HOUSE, 1, 2, ROW);
        String noIdentifier = list(GhAnnouncementBoard.RENTAL_HOUSE, 1, 1, ROW).replace("data-pbancNo=\"801\"", "");
        String brokenDate = list(GhAnnouncementBoard.RENTAL_HOUSE, 1, 1, ROW).replace("2026-07-10", "2026/07/10");

        for (String html : new String[]{missingRow, noIdentifier, brokenDate, "", "<html>점검 중</html>"}) {
            assertThatThrownBy(() -> parser.parseList(html, GhAnnouncementBoard.RENTAL_HOUSE, 1))
                    .isInstanceOf(ExternalDataRequestException.class);
        }
    }
}
