package com.toadzip.backend.ingest.collection.sh.repository;

import static com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures.detail;
import static com.toadzip.backend.ingest.collection.fixture.ShAnnouncementFixtures.mainList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.sh.dto.ShAnnouncementContent;
import com.toadzip.backend.ingest.collection.sh.dto.ShAnnouncementPage;
import java.time.LocalDate;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ShAnnouncementHtmlParserTest {

    private final ShAnnouncementListParser lists = new ShAnnouncementListParser();
    private final ShAnnouncementHtmlParser parser = new ShAnnouncementHtmlParser();

    @Test
    void producesReadableParagraphsAndTableRowsWithoutFormattingNoise() {
        var entry = listEntry("100");
        var document = Jsoup.parse(detail());
        document.select("td.cont").first().html("""
                <p>  첫째&nbsp; 문단 </p><p>둘째<br>줄바꿈</p>
                <table><tr><th>단지</th><th>공급호수</th></tr><tr><td>가람</td><td>37호</td></tr></table>
                <script>noise()</script><style>.noise { color: red; }</style>
                """);
        String text = parser.parse(document.html(), entry.seq(), entry.title(), entry.registeredDate()).bodyText();
        assertThat(text).contains("첫째 문단", "둘째\n줄바꿈", "단지 공급호수", "가람 37호");
        assertThat(text).doesNotContain("<", "noise", "\t", "\r", "\n\n\n", "  ");
        assertThat(text).isEqualTo(text.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2"})
    void parsesDetailAndRejectsDifferentSequence(String boardType) {
        String html = detail().replace("name=\"multi_itm_seq\" value=\"0\"",
                "name=\"multi_itm_seq\" value=\"" + boardType + "\"");
        var result = parseDetail(html, listEntry("100"));
        assertThat(result.bodyText()).isEqualTo("입주자 모집 안내\n\n신청 안내\n서류를 제출하세요.");
        assertThat(result.attachments()).contains("공고문.pdf", "신청서.zip");
        assertThatThrownBy(() -> parser.parse(html, "123", result.title(), result.registeredDate()))
                .isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void imageOnlyBodyAndExplicitlyEmptyAttachmentsAreValidWithoutInventingStructuredFields() {
        var entry = listEntry("100");
        var html = Jsoup.parse(detail());
        html.select("td.cont").first().html("<img src='/notice.png' alt=''>");
        html.select("a.btnAttach").remove();
        html.select("script:not([src])").first().html("initParam.downList = [];");

        var parsed = parser.parse(html.html(), entry.seq(), entry.title(), entry.registeredDate());

        assertThat(parsed.title()).isEqualTo(entry.title());
        assertThat(parsed.bodyText()).isEmpty();
        assertThat(parsed.attachments()).isEqualTo("[]");
    }

    @Test
    void mismatchedAttachmentButtonsAndMalformedMetadataAreFailures() {
        var entry = listEntry("100");
        String html = detail();
        var document = Jsoup.parse(html);
        document.select("a.btnAttach").first().remove();
        assertThatThrownBy(() -> parser.parse(document.html(), entry.seq(), entry.title(), entry.registeredDate()))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("개수");
        assertThatThrownBy(() -> parser.parse(html.replace("initParam.downList = [", "initParam.downList = [INVALID"),
                entry.seq(), entry.title(), entry.registeredDate())).isInstanceOf(ExternalDataRequestException.class);
    }

    @Test
    void 신규게시글의_NEW배지는_제목에서_제외하고_상세와_대조한다() {
        var document = Jsoup.parse(mainList());
        document.select("td.txtL > a").getFirst().prepend("<span class='icoNew'>NEW</span> ");
        var entry = lists.parseList(document.html(), 1).entries().getFirst();
        var snapshot = parseDetail(detail(), entry);
        assertThat(snapshot.title()).isEqualTo(entry.title()).doesNotStartWith("NEW ");
        assertThat(snapshot.registeredDate()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GS0401", "DBGS01", "GWJGS01", "GNGS01"})
    void 첨부의_게시판코드를_보존한다(String board) {
        var entry = listEntry("100");
        String html = detail().replace("GS0401", board);
        var snapshot = parseDetail(html, entry);
        var files = JsonMapper.builder().build().readTree(snapshot.attachments());
        assertThat(files).hasSize(2);
        for (var file : files) {
            assertThat(file.path("brdId").asString()).isEqualTo(board);
            assertThat(file.path("seq").asString()).isEqualTo(entry.seq());
            assertThat(file.path("previewUrl").asString()).contains("brd_id=" + board + "&seq=" + entry.seq());
        }
        assertThatThrownBy(() -> parseDetail(html.replace(board, "UNKNOWN"), entry))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("식별자");
    }

    @Test
    void extractsTextFromNestedBodyTables() {
        var document = Jsoup.parse(detail());
        document.selectFirst("td.cont").html("""
                <p>본문 시작</p>
                <table><tr><td>바깥 표
                    <table><tr><td>중간 표
                        <table><tr><th>안쪽 표</th></tr><tr><td>공급 안내</td></tr></table>
                    </td></tr></table>
                </td></tr></table>
                <p>본문 끝</p>
                """);
        var snapshot = parseDetail(document.html(), listEntry("100"));

        assertThat(snapshot.bodyText()).contains("본문 시작", "바깥 표", "중간 표", "안쪽 표", "공급 안내", "본문 끝")
                .doesNotContain("<", ">");
    }

    @Test
    void readsActualFileSeqInsteadOfButtonIndex() {
        var entry = listEntry("100");
        var snapshot = parseDetail(detail(), entry);
        var files = JsonMapper.builder().build().readTree(snapshot.attachments());

        assertThat(files).hasSize(2);
        assertThat(files.get(0).path("fileSeq").asString()).isEqualTo("2");
        assertThat(files.get(1).path("fileSeq").asString()).isEqualTo("7");
        assertThat(files.get(1).path("previewUrl").asString()).endsWith("&file_seq=7");
        assertThat(snapshot.department()).isEqualTo("공급부");
    }

    @Test
    void rejectsDetailForAnotherPostAndMissingAttachmentMetadata() {
        var entry = listEntry("100");
        var otherEntry = listEntry("200");
        String detail = detail();
        assertThatThrownBy(() -> parseDetail(detail, otherEntry))
                .isInstanceOf(ExternalDataRequestException.class).hasMessageContaining("목록과 다릅니다");
        assertThatThrownBy(() -> parseDetail(detail.replace("initParam.downList", "removed"), entry))
                .isInstanceOf(ExternalDataRequestException.class);
        assertThatThrownBy(() -> parseDetail(detail.replace("\"seq\":\"100\"", "\"seq\":\"999\""), entry))
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessageContaining("식별자");
    }

    @Test
    void invalidDetailDatesAreRefinementFailures() {
        var entry = listEntry("100");
        String detail = detail().replace("2026-10-08", "2026-99-99");
        assertThatThrownBy(() -> parseDetail(detail, entry)).isInstanceOf(ExternalDataRequestException.class);
    }

    private ShAnnouncementPage.Entry listEntry(String seq) {
        return lists.parseList(mainList(), 1).entries().stream()
                .filter(entry -> entry.seq().equals(seq)).findFirst().orElseThrow();
    }

    private ShAnnouncementContent parseDetail(
            String html, ShAnnouncementPage.Entry entry) {
        return parser.parse(html, entry.seq(), entry.title(), entry.registeredDate());
    }
}
