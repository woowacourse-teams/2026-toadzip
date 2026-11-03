package com.toadzip.backend.ingest.collection.sh.repository;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import com.toadzip.backend.ingest.collection.sh.dto.ShAnnouncementContent;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ShAnnouncementHtmlParser {

    private static final Pattern ATTACHMENTS = Pattern.compile("initParam\\.downList\\s*=\\s*");
    private static final Pattern ATTACHMENT_INDEX = Pattern.compile("existFile\\('([0-9]+)'\\)");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DETAIL_TABLE = ".detailTable.gs0401Table.firgs0401Table > table";
    private static final Set<String> ATTACHMENT_BOARDS = Set.of("GS0401", "DBGS01", "GWJGS01", "GNGS01");

    /** 제목·등록일이 목록과 같아야 저장할 수 있고, 담당부서·본문·첨부는 읽지 못하면 null로 두고 이유를 함께 돌려준다. */
    public ShAnnouncementContent parse(String html, String seq, String expectedTitle, LocalDate expectedDate) {
        try {
            Document document = document(html);
            Element table = required(document, DETAIL_TABLE);
            String title = required(table, "> thead > tr > th").text();
            String date = labelledValue(table.select("> tbody > tr:first-child > td > ul > li"),
                    "strong", "등록일");
            LocalDate registeredDate = LocalDate.parse(date);
            require(title.equals(expectedTitle) && registeredDate.equals(expectedDate),
                    "SH 상세의 제목 또는 등록일이 목록과 다릅니다.");
            List<String> unreadParts = new ArrayList<>();
            String department = readable(unreadParts, null,
                    () -> labelledValue(document.select("ul.personInfo > li"), "span", "담당부서"));
            String bodyText = readable(unreadParts, "본문", () -> bodyText(required(table, "> tbody > tr > td.cont")));
            String attachments = readable(unreadParts, "첨부", () -> attachments(document, table, seq));
            return new ShAnnouncementContent(title, department, registeredDate, bodyText, attachments,
                    List.copyOf(unreadParts));
        }
        catch (DateTimeException | JacksonException | IllegalArgumentException exception) {
            throw new ExternalDataRequestException("SH 상세의 날짜 또는 원천 필드가 올바르지 않습니다.", exception);
        }
    }

    private String readable(List<String> unreadParts, String part, Supplier<String> reader) {
        try {
            return reader.get();
        }
        catch (ExternalDataRequestException | JacksonException | IllegalArgumentException exception) {
            if (part != null) {
                unreadParts.add(part + "(" + exception.getMessage() + ")");
            }
            return null;
        }
    }

    private String bodyText(Element body) {
        Element content = body.clone();
        content.select("script, style, noscript").remove();
        for (Element element : content.getAllElements()) {
            if (element.closest("pre") == null) {
                for (var text : element.textNodes()) {
                    text.text(text.getWholeText().replaceAll("\\s+", " "));
                }
            }
        }
        for (Element block : content.select("p, div, section, article, h1, h2, h3, h4, h5, h6, li, tr")) {
            block.before(new TextNode("\n"));
            block.after(new TextNode("\n"));
        }
        for (Element cell : content.select("th, td")) {
            cell.after(new TextNode(" "));
        }
        return content.wholeText().replace("\r\n", "\n").replace('\r', '\n')
                .lines().map(line -> line.replaceAll("[\\p{Zs}\\t\\x0B\\f]+", " ").strip())
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n").strip();
    }

    private Document document(String html) {
        require(html != null && !html.isBlank(), "SH HTML 응답이 비어 있습니다.");
        Document document = Jsoup.parse(html);
        String boardType = required(document, "input[name=multi_itm_seq]").attr("value");
        if (boardType.equals("0")) {
            require(required(document, "input[name=multi_itm_seqs]").attr("value")
                            .equals(ShAnnouncementExternalRepository.BOARD_TYPES),
                    "SH 상세 게시글 또는 게시판 범위가 다릅니다.");
        }
        else {
            require(boardType.equals("2"), "SH 주택임대 게시판 응답이 아닙니다.");
        }
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
            attachment.put("downloadUrl", ShAnnouncementExternalRepository.attachmentUrl(board, seq, fileSeq));
            attachment.put("previewUrl", "https://www.i-sh.co.kr/app/com/util/htmlConverter.do?brd_id="
                    + board + "&seq="
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
