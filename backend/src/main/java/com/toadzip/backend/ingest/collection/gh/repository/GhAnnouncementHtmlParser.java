package com.toadzip.backend.ingest.collection.gh.repository;

import com.toadzip.backend.ingest.collection.gh.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementContent;
import com.toadzip.backend.ingest.collection.gh.dto.GhAnnouncementPage.Entry;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GhAnnouncementHtmlParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter POSTED_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final Pattern ATTACHMENT = Pattern.compile("^(.*\\S)\\s*\\(([0-9]+) Byte\\)$");
    private static final String DETAIL_CAPTION = "청약공고 상세정보";

    public GhAnnouncementContent parse(String html, GhAnnouncementBoard board, Entry entry) {
        try {
            require(html != null && !html.isBlank(), "GH HTML 응답이 비어 있습니다.");
            Document document = Jsoup.parse(html);
            Element content = required(document, ".sub_textbox");
            Element table = detailTable(content);
            String title = labelledValue(table, "공고명").text();
            String supplyType = labelledValue(table, "유형").text();
            LocalDate postedDate = LocalDate.parse(labelledValue(table, "공고일").text(), POSTED_DATE);
            require(title.equals(entry.title()) && supplyType.equals(entry.supplyType())
                    && postedDate.equals(entry.postedDate()), "GH 상세의 제목·유형·공고일이 목록과 다릅니다.");
            requireBoard(document, board);
            List<String> unread = new ArrayList<>();
            String bodyText = readPart(() -> bodyText(content), "본문", unread);
            String attachments = readPart(() -> attachments(table, board, entry.pbancNo()), "첨부", unread);
            return new GhAnnouncementContent(title, supplyType, postedDate, bodyText, attachments, List.copyOf(unread));
        }
        catch (DateTimeException | IllegalArgumentException exception) {
            throw new ExternalDataRequestException("GH 상세의 날짜 또는 원천 필드가 올바르지 않습니다.", exception);
        }
    }

    /** 정체성(게시판·제목·유형·공고일)이 맞은 뒤에는 본문·첨부를 따로 읽어, 읽지 못한 쪽만 비운다. */
    private String readPart(Supplier<String> reader, String name, List<String> unread) {
        try {
            return reader.get();
        }
        catch (ExternalDataRequestException | IllegalArgumentException exception) {
            unread.add(name);
            return null;
        }
    }

    // 목록으로 버튼이 요청한 게시판의 목록 주소로 이동해야 그 게시판의 상세다. 메뉴의 링크는 작은따옴표로 적혀 있다.
    private void requireBoard(Document document, GhAnnouncementBoard board) {
        String listTarget = "\"/sb/sr/" + board.code() + "/selectPbancRentHouseList.do\"";
        require(document.select("script:not([src])").stream().map(Element::data)
                        .anyMatch(script -> script.contains("#btn_submit") && script.contains(listTarget)),
                "GH " + board.displayName() + " 게시판 상세가 아닙니다.");
    }

    private Element detailTable(Element content) {
        var tables = content.select("table").stream()
                .filter(table -> table.selectFirst("> caption") != null
                        && table.selectFirst("> caption").text().equals(DETAIL_CAPTION))
                .toList();
        require(tables.size() == 1, "GH 상세정보 표가 없거나 변경됐습니다.");
        return tables.getFirst();
    }

    private Element labelledValue(Element table, String label) {
        Element value = optionalLabelledValue(table, label);
        require(value != null, "GH 상세 필드가 없습니다: " + label);
        return value;
    }

    private Element optionalLabelledValue(Element table, String label) {
        var headers = table.select("th[scope=row]").stream().filter(header -> header.text().equals(label)).toList();
        require(headers.size() <= 1, "GH 상세 필드가 중복됐습니다: " + label);
        if (headers.isEmpty()) {
            return null;
        }
        Element value = headers.getFirst().nextElementSibling();
        require(value != null && value.tagName().equals("td"), "GH 상세 필드 값이 없습니다: " + label);
        return value;
    }

    private String attachments(Element table, GhAnnouncementBoard board, String pbancNo) {
        var result = JSON.createArrayNode();
        Element files = optionalLabelledValue(table, "공고문");
        if (files == null) {
            return JSON.writeValueAsString(result);
        }
        var fileKeys = new HashSet<String>();
        for (Element link : files.select("a[href]")) {
            String url = link.attr("href");
            require(url.startsWith(board.fileDownloadPrefix(pbancNo)), "GH 공고문 링크의 게시판 또는 공고 번호가 다릅니다.");
            var query = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
            String fileSn = query.getFirst("atchFileSn");
            String fileDetailSn = query.getFirst("atchFileDtlSn");
            var name = ATTACHMENT.matcher(link.text());
            require(fileSn != null && fileSn.matches("[0-9]+") && fileDetailSn != null && fileDetailSn.matches("[0-9]+")
                    && fileKeys.add(fileSn + ":" + fileDetailSn) && name.matches(), "GH 공고문 필드가 올바르지 않습니다.");
            var attachment = result.addObject();
            attachment.put("atchFileSn", fileSn);
            attachment.put("atchFileDtlSn", fileDetailSn);
            attachment.put("fileName", name.group(1));
            attachment.put("fileSize", name.group(2));
            attachment.put("downloadUrl", url);
        }
        return JSON.writeValueAsString(result);
    }

    private String bodyText(Element content) {
        Element body = content.clone();
        body.select("script, style, noscript, button.down_btn").remove();
        body.select("table").stream()
                .filter(table -> table.selectFirst("> caption") != null
                        && table.selectFirst("> caption").text().equals(DETAIL_CAPTION))
                .forEach(Element::remove);
        // 표 제목(caption)은 화면에 보이지 않는 설명이라 본문에서 뺀다.
        body.select("caption").remove();
        for (Element element : body.getAllElements()) {
            if (element.closest("pre") == null) {
                for (var text : element.textNodes()) {
                    text.text(text.getWholeText().replaceAll("\\s+", " "));
                }
            }
        }
        for (Element block : body.select("p, div, section, article, h1, h2, h3, h4, h5, h6, li, tr")) {
            block.before(new TextNode("\n"));
            block.after(new TextNode("\n"));
        }
        for (Element cell : body.select("th, td")) {
            cell.after(new TextNode(" "));
        }
        return body.wholeText().replace("\r\n", "\n").replace('\r', '\n')
                .lines().map(line -> line.replaceAll("[\\p{Zs}\\t\\x0B\\f]+", " ").strip())
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n").strip();
    }

    private Element required(Element element, String selector) {
        var matches = element.select(selector);
        require(matches.size() == 1, "GH HTML 구조가 없거나 변경됐습니다: " + selector);
        return matches.getFirst();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ExternalDataRequestException(message);
        }
    }
}
