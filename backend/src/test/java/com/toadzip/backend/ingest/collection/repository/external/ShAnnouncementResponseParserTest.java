package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import com.toadzip.backend.ingest.collection.dto.ShAnnouncementPage;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ShAnnouncementResponseParserTest {

    private final ShAnnouncementResponseParser parser = new ShAnnouncementResponseParser();

    @ParameterizedTest
    @ValueSource(strings = {"311167", "311004", "311188", "311169", "310976"})
    void 실제_신규게시글의_NEW배지는_제목에서_제외하고_상세와_대조한다(String seq) throws IOException {
        var page = parser.parseList(fixture("list-page1-20261007.html"), 1);
        var entry = page.entries().stream().filter(row -> row.seq().equals(seq)).findFirst().orElseThrow();
        var snapshot = parser.parseDetail(fixture("detail-" + seq + "-20261007.html"), entry, page);
        assertThat(snapshot.title()).isEqualTo(entry.title()).doesNotStartWith("NEW ");
        assertThat(snapshot.registeredDate()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    void 제목_문자열의_NEW는_배지와_구분해_보존한다() throws IOException {
        var html = Jsoup.parse(fixture("list-page1-20261007.html"));
        var link = html.select("td.txtL > a[onclick*=getDetailView]").getFirst();
        link.html("<span class='icoNew'>NEW</span> NEW 주택 입주자 모집");
        assertThat(parser.parseList(html.html(), 1).entries().getFirst().title()).isEqualTo("NEW 주택 입주자 모집");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "310080|DBGS01|2026-09-09|[도봉서원], [도봉서광] 입주안내문 게시|2",
            "309975|GWJGS01|2026-09-08|리버센sk뷰롯데캐슬 입주안내문|1",
            "307426|DBGS01|2026-07-22|[도봉서원], [도봉서광] 입주안내문 게시|2",
            "307046|GNGS01|2026-07-15|디에이치 대치 에델루이 준공인가증(준공필증)을 게시 합니다|1"
    }, delimiter = '|')
    void 실제_지역센터_첨부의_게시판코드를_보존한다(String seq, String board, String date, String title, int count)
            throws IOException {
        var page = parser.parseList(fixture("list-page1-20261007.html"), 1);
        var entry = new ShAnnouncementPage.Entry(seq, title, "SH", LocalDate.parse(date));
        String html = fixture("detail-" + seq + "-20261007.html");
        var snapshot = parser.parseDetail(html, entry, page);
        var files = JsonMapper.builder().build().readTree(snapshot.attachments());
        assertThat(files).hasSize(count);
        for (var file : files) {
            assertThat(file.path("brdId").asString()).isEqualTo(board);
            assertThat(file.path("seq").asString()).isEqualTo(seq);
            assertThat(file.path("previewUrl").asString()).contains("brd_id=" + board + "&seq=" + seq);
        }
        assertThatThrownBy(() -> parser.parseDetail(html.replace(board, "UNKNOWN"), entry, page))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("식별자");
    }

    @Test
    void 실제_전체_목록의_name만_있는_게시판구분값을_읽고_누락과_중복은_거절한다() throws IOException {
        String html = fixture("list-page1-20261007.html");
        var result = parser.parseList(html, 1);
        assertThat(result.totalCount()).isEqualTo(1709);
        assertThat(result.entries()).extracting(entry -> entry.seq()).containsExactly(
                "311167", "311004", "311188", "311169", "310976", "311005", "310933", "311015", "310950", "310937");
        var document = Jsoup.parse(html);
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
    void readsStableSeqInsteadOfDisplayNumberAndPreservesOriginalHtml() {
        String html = """
                <input type="hidden" name="multi_itm_seq" value="2">
                <div class="topTxt"><p>총 <strong>1</strong> 건 [1/1페이지]</p></div>
                <div id="listTb"><table><tbody><tr>
                <td>1705</td><td class="txtL"><a onclick="javascript:getDetailView('311005');return false;">
                민간 모집 공고</a></td><td>공급부</td><td>2026-10-02</td><td>123</td>
                </tr></tbody></table></div><div class="pagingWrap"><div class="page"><strong>1</strong></div></div>
                """;

        var page = parser.parseList(html, 1);

        assertThat(page.entries()).hasSize(1);
        assertThat(page.entries().getFirst().seq()).isEqualTo("311005");
        assertThat(page.entries().getFirst().registeredDate()).hasToString("2026-10-02");
        assertThat(page.rawHtml()).isEqualTo(html);
    }

    @Test
    void rejectsHttp200ErrorPage() {
        assertThatThrownBy(() -> parser.parseList("<html>접근 오류</html>", 1))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void parsesActualListPagesAndRejectsWrongPage() throws IOException {
        var first = parser.parseList(fixture("list-page1.html"), 1);
        var second = parser.parseList(fixture("list-page2.html"), 2);
        assertThat(first.totalCount()).isEqualTo(1705);
        assertThat(first.entries()).extracting(entry -> entry.seq())
                .containsExactly("311005", "310933", "311015", "310950", "310937", "310653", "310650",
                        "310874", "310845", "310757");
        assertThat(second.entries()).hasSize(10);
        assertThatThrownBy(() -> parser.parseList(fixture("list-page2.html"), 1))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("다른 페이지");
    }

    @Test
    void keepsNestedBodyTablesAndOriginalDetailHtml() throws IOException {
        var page = parser.parseList(fixture("list-page1.html"), 1);
        String html = fixture("detail-311005.html");
        var snapshot = parser.parseDetail(html, page.entries().getFirst(), page);

        assertThat(Jsoup.parse(snapshot.bodyHtml()).select("table")).hasSize(3);
        assertThat(snapshot.bodyText()).contains("민간사업자", "soco.seoul.go.kr");
        assertThat(snapshot.rawDetailHtml()).isEqualTo(html);
        assertThat(snapshot.sourceKey()).isEqualTo("SH:m_247:311005");
    }

    @Test
    void readsActualFileSeqInsteadOfButtonIndex() throws IOException {
        var page = parser.parseList(fixture("list-page1.html"), 1);
        var entry = page.entries().stream().filter(row -> row.seq().equals("310653")).findFirst().orElseThrow();
        var snapshot = parser.parseDetail(fixture("detail-310653.html"), entry, page);
        var files = JsonMapper.builder().build().readTree(snapshot.attachments());

        assertThat(files).hasSize(6);
        assertThat(files.get(4).path("fileSeq").asString()).isEqualTo("7");
        assertThat(files.get(4).path("previewUrl").asString()).endsWith("&file_seq=7");
        assertThat(snapshot.department()).isEqualTo("매입주택공급부");
    }

    @Test
    void rejectsDetailForAnotherPostAndMissingAttachmentMetadata() throws IOException {
        var page = parser.parseList(fixture("list-page1.html"), 1);
        String detail = fixture("detail-311005.html");
        assertThatThrownBy(() -> parser.parseDetail(detail, page.entries().get(5), page))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("목록과 다릅니다");
        assertThatThrownBy(() -> parser.parseDetail(detail.replace("initParam.downList", "removed"),
                page.entries().getFirst(), page)).isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parser.parseDetail(detail.replace("\"seq\":\"311005\"", "\"seq\":\"999\""),
                page.entries().getFirst(), page)).isInstanceOf(ExternalDataRequestException.class)
                .hasMessageContaining("식별자");
    }

    @Test
    void acceptsExplicitEmptyListButRejectsIncompleteNonemptyList() throws IOException {
        String empty = """
                <input type="hidden" name="multi_itm_seq" value="2"><div class="topTxt"><p>총 <strong>0</strong> 건 [1/1페이지]</p>
                </div><div id="listTb"><table><tbody><tr><td colspan="5">게시글 없음</td></tr></tbody></table></div>
                """;
        assertThat(parser.parseList(empty, 1).entries()).isEmpty();
        assertThatThrownBy(() -> parser.parseList(empty.replace("<strong>0", "<strong>1"), 1))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    private String fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/ingest/sh/" + name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void invalidDatesBecomeRecordedCollectionFailuresInsteadOfUnhandledErrors() throws IOException {
        String list = fixture("list-page1.html");
        var page = parser.parseList(list, 1);
        assertThatThrownBy(() -> parser.parseList(list.replace("2026-10-02", "잘못된 날짜"), 1))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parser.parseDetail(fixture("detail-311005.html")
                .replace("2026-10-02", "2026-99-99"), page.entries().getFirst(), page))
                .isInstanceOf(ExternalDataRequestException.class);
    }
}
