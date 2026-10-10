package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class ShAnnouncementListParserTest {

    private final ShAnnouncementListParser parser = new ShAnnouncementListParser();

    @Test
    void 목록의_표시번호가_아닌_게시글_ID와_수집_대상_정보를_읽고_원문을_보존한다() throws IOException {
        String html = fixture();

        var page = parser.parseList(html, 1);

        assertThat(page.entries()).hasSize(10);
        assertThat(page.entries()).extracting(entry -> entry.seq()).containsExactly(
                "311005", "310933", "311015", "310950", "310937", "310653", "310650",
                "310874", "310845", "310757");
        assertThat(page.entries().getFirst().title()).isEqualTo("사회주택 입주자 모집");
        assertThat(page.entries().getFirst().department()).isEqualTo("공급부");
        assertThat(page.entries().getFirst().registeredDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(page.rawHtml()).isEqualTo(html);
        assertThat(page.totalCount()).isEqualTo(1705);
    }

    @Test
    void NEW_배지만_제외하고_제목에_포함된_NEW는_보존한다() throws IOException {
        var document = Jsoup.parse(fixture());
        document.select("td.txtL > a").getFirst().html("<span class='icoNew'>NEW</span> NEW 주택 입주자 모집");

        assertThat(parser.parseList(document.html(), 1).entries().getFirst().title())
                .isEqualTo("NEW 주택 입주자 모집");
    }

    @Test
    void 요청한_페이지와_전체_건수를_검증한다() throws IOException {
        String secondPage = fixture().replace("[1/171페이지]", "[2/171페이지]");

        assertThat(parser.parseList(secondPage, 2).page()).isEqualTo(2);
        assertThatThrownBy(() -> parser.parseList(secondPage, 1))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("다른 페이지");
        assertThatThrownBy(() -> parser.parseList(secondPage.replace("[2/171페이지]", "[2/170페이지]"), 2))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("전체 건수");
    }

    @Test
    void name만_있는_게시판구분값을_읽고_다른_게시판과_누락과_중복은_거절한다() throws IOException {
        var document = Jsoup.parse(fixture());
        var input = document.selectFirst("input[name=multi_itm_seq]");
        input.attr("value", "1");
        assertThatThrownBy(() -> parser.parseList(document.html(), 1)).isInstanceOf(ExternalDataRequestException.class);
        input.attr("value", "2");
        input.after(input.clone());
        assertThatThrownBy(() -> parser.parseList(document.html(), 1)).isInstanceOf(ExternalDataRequestException.class);
        document.select("input[name=multi_itm_seq]").remove();
        assertThatThrownBy(() -> parser.parseList(document.html(), 1)).isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void 목록_행이_누락되거나_게시글_ID가_중복되면_거절한다() throws IOException {
        var document = Jsoup.parse(fixture());
        document.select("#listTb tbody > tr").getFirst().remove();
        assertThatThrownBy(() -> parser.parseList(document.html(), 1))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("행 수");
        String duplicated = fixture().replace("getDetailView('310933')", "getDetailView('311005')");
        assertThatThrownBy(() -> parser.parseList(duplicated, 1))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("중복");
    }

    @Test
    void 명시적인_빈_목록은_허용하고_오류_페이지와_잘못된_날짜는_거절한다() throws IOException {
        String empty = """
                <input name="multi_itm_seq" value="2">
                <div class="topTxt"><p>총 <strong>0</strong> 건 [1/1페이지]</p></div>
                <div id="listTb"><table><tbody><tr><td colspan="5">게시글 없음</td></tr></tbody></table></div>
                """;
        assertThat(parser.parseList(empty, 1).entries()).isEmpty();
        assertThatThrownBy(() -> parser.parseList("<html>접근 오류</html>", 1))
                .isInstanceOf(ExternalDataRequestException.class);
        String badDate = fixture().replace("2026-10-02", "잘못된 날짜");
        assertThatThrownBy(() -> parser.parseList(badDate, 1))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    private String fixture() throws IOException {
        try (var input = getClass().getResourceAsStream("/ingest/sh/list.html")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
