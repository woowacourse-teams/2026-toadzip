package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.domain.ShAnnouncementSnapshot;
import com.toadzip.backend.ingest.collection.dto.ShAnnouncementPage;
import com.toadzip.backend.ingest.collection.dto.ShAnnouncementPage.Entry;
import com.toadzip.backend.ingest.collection.repository.ShAnnouncementExternalRepository;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ShAnnouncementResponseParser {

    private static final Pattern PAGE = Pattern.compile("\\[(\\d+)/(\\d+)페이지\\]");
    private static final Pattern SEQ = Pattern.compile("getDetailView\\('([1-9][0-9]*)'\\)");
    private static final Pattern ATTACHMENTS = Pattern.compile("initParam\\.downList\\s*=\\s*");
    private static final Pattern ATTACHMENT_INDEX = Pattern.compile("existFile\\('([0-9]+)'\\)");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DETAIL_TABLE = ".detailTable.gs0401Table.firgs0401Table > table";
    private static final Set<String> ATTACHMENT_BOARDS = Set.of("GS0401", "DBGS01", "GWJGS01", "GNGS01");

    public ShAnnouncementPage parseList(String html, int requestedPage) {
        try {
            Document document = document(html);
            String summary = required(document, ".topTxt > p").text();
            var pageMatch = PAGE.matcher(summary);
            require(pageMatch.find(), "SH 목록 페이지 정보를 확인할 수 없습니다.");
            int page = Integer.parseInt(pageMatch.group(1));
            int lastPage = Integer.parseInt(pageMatch.group(2));
            int total = Integer.parseInt(required(document, ".topTxt > p > strong").text().replace(",", ""));
            require(page == requestedPage && page >= 1 && total >= 0, "SH 목록이 요청과 다른 페이지입니다.");
            require(lastPage == Math.max(1, (total + 9) / 10), "SH 목록의 전체 건수와 페이지 수가 다릅니다.");
            var rows = required(document, "#listTb > table > tbody").select("> tr");
            List<Entry> entries = new ArrayList<>();
            for (Element row : rows) {
                if (total == 0) {
                    continue;
                }
                entries.add(entry(row));
            }
            int expected = Math.min(10, Math.max(0, total - (page - 1) * 10));
            require(entries.size() == expected, "SH 목록의 전체 건수와 행 수가 다릅니다.");
            require(new HashSet<>(entries.stream().map(Entry::seq).toList()).size() == entries.size(),
                    "SH 목록에 중복 게시글 식별자가 있습니다.");
            return new ShAnnouncementPage(page, total, List.copyOf(entries), html);
        }
        catch (DateTimeException | IllegalArgumentException exception) {
            throw new ExternalDataRequestException("SH 목록의 숫자 또는 날짜 형식이 올바르지 않습니다.", exception);
        }
    }

    public ShAnnouncementSnapshot parseDetail(String html, Entry entry, ShAnnouncementPage page) {
        try {
            Document document = document(html);
            Element table = required(document, DETAIL_TABLE);
            String title = required(table, "> thead > tr > th").text();
            String date = labelledValue(table.select("> tbody > tr:first-child > td > ul > li"),
                    "strong", "등록일");
            LocalDate registeredDate = LocalDate.parse(date);
            require(title.equals(entry.title()) && registeredDate.equals(entry.registeredDate()),
                    "SH 상세의 제목 또는 등록일이 목록과 다릅니다.");
            String department = labelledValue(document.select("ul.personInfo > li"), "span", "담당부서");
            Element body = required(table, "> tbody > tr > td.cont");
            require(!body.html().isBlank(), "SH 상세 본문이 비어 있습니다.");
            String attachments = attachments(document, table, entry.seq());
            return new ShAnnouncementSnapshot(entry.seq(), title, department, registeredDate,
                    body.html(), body.wholeText(), attachments, ShAnnouncementExternalRepository.detailUrl(entry.seq()),
                    ShAnnouncementExternalRepository.LIST_URL + "&page=" + page.page(), page.rawHtml(), html);
        }
        catch (DateTimeException | JacksonException | IllegalArgumentException exception) {
            throw new ExternalDataRequestException("SH 상세의 날짜 또는 원천 필드가 올바르지 않습니다.", exception);
        }
    }

    private Entry entry(Element row) {
        var cells = row.select("> td");
        require(cells.size() == 5, "SH 목록의 열 구성이 변경됐습니다.");
        Element link = required(row, "td.txtL > a[onclick*=getDetailView]");
        var match = SEQ.matcher(link.attr("onclick"));
        Element title = link.clone();
        title.select(".icoNew").remove();
        require(match.find() && !title.text().isBlank(), "SH 게시글 식별자 또는 제목이 없습니다.");
        return new Entry(match.group(1), title.text(), cells.get(2).text(), LocalDate.parse(cells.get(3).text()));
    }

    private Document document(String html) {
        require(html != null && !html.isBlank(), "SH HTML 응답이 비어 있습니다.");
        Document document = Jsoup.parse(html);
        require(required(document, "input[name=multi_itm_seq]").attr("value").equals("2"),
                "SH 주택임대 게시판 응답이 아닙니다.");
        return document;
    }

    private String labelledValue(Iterable<Element> items, String selector, String label) {
        for (Element item : items) {
            Element name = item.selectFirst(selector);
            if (name != null && name.text().replace(":", "").strip().equals(label)) {
                Element copy = item.clone();
                copy.select(selector).remove();
                return copy.text().replaceFirst("^\\s*:\\s*", "").strip();
            }
        }
        throw new ExternalDataRequestException("SH 상세 필드가 없습니다: " + label);
    }

    private String attachments(Document document, Element table, String seq) {
        List<JsonNode> assignments = new ArrayList<>();
        for (Element script : document.select("script:not([src])")) {
            var match = ATTACHMENTS.matcher(script.data());
            if (match.find()) {
                assignments.add(JSON.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(script.data().substring(match.end())));
            }
        }
        require(assignments.size() == 1 && assignments.getFirst().isArray(), "SH 첨부 메타데이터가 없거나 잘못됐습니다.");
        JsonNode files = assignments.getFirst();
        var links = table.select("a.btnAttach[onclick]");
        require(files.size() == links.size(), "SH 첨부 목록과 메타데이터 개수가 다릅니다.");
        var result = JSON.createArrayNode();
        var fileKeys = new HashSet<String>();
        for (int index = 0; index < files.size(); index++) {
            JsonNode file = files.get(index);
            var match = ATTACHMENT_INDEX.matcher(links.get(index).attr("onclick"));
            require(match.find() && Integer.parseInt(match.group(1)) == index, "SH 첨부 버튼 인덱스가 다릅니다.");
            String board = file.path("brdId").asString("");
            require(ATTACHMENT_BOARDS.contains(board) && file.path("seq").asString("").equals(seq)
                    && file.path("fileTp").asString("").equals("A"), "SH 첨부의 게시글 식별자가 다릅니다.");
            String fileSeq = file.path("fileSeq").asString("");
            require(fileSeq.matches("[1-9][0-9]*") && fileKeys.add(fileSeq)
                    && !file.path("oriFileNm").asString("").isBlank()
                    && file.path("fileSize").asString("").matches("[0-9]+"), "SH 첨부 필드가 올바르지 않습니다.");
            var attachment = result.addObject();
            for (String key : List.of("brdId", "seq", "fileSeq", "fileSize", "oriFileNm", "fileTp")) {
                attachment.put(key, file.path(key).asString(""));
            }
            attachment.put("previewUrl", "https://www.i-sh.co.kr/app/com/util/htmlConverter.do?brd_id=" + board + "&seq="
                    + seq + "&data_tp=A&file_seq=" + fileSeq);
        }
        return JSON.writeValueAsString(result);
    }

    private Element required(Element element, String selector) {
        var matches = element.select(selector);
        require(matches.size() == 1, "SH HTML 구조가 없거나 변경됐습니다: " + selector);
        return matches.getFirst();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ExternalDataRequestException(message);
        }
    }
}
