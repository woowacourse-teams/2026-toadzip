package com.toadzip.backend.ingest.gh.collection.repository;

import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.gh.collection.dto.GhAnnouncementPage;
import com.toadzip.backend.ingest.gh.collection.dto.GhAnnouncementPage.Entry;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

@Component
public class GhAnnouncementListParser {

    private static final Pattern SUMMARY = Pattern.compile("총게시물\\s*:\\s*([0-9,]+)\\s*페이지\\s*:\\s*(\\d+)\\s*/\\s*(\\d+)");
    private static final String NONE = "-";

    public GhAnnouncementPage parseList(String html, GhAnnouncementBoard board, int requestedPage) {
        try {
            Document document = document(html, board);
            var summary = SUMMARY.matcher(required(document, ".board_list > p.total_txt").text());
            require(summary.find(), "GH 목록 페이지 정보를 확인할 수 없습니다.");
            int total = Integer.parseInt(summary.group(1).replace(",", ""));
            int page = Integer.parseInt(summary.group(2));
            int lastPage = Integer.parseInt(summary.group(3));
            require(page == requestedPage && page >= 1, "GH 목록이 요청과 다른 페이지입니다.");
            require(lastPage == (total + 9) / 10 || (total == 0 && lastPage == 1),
                    "GH 목록의 전체 건수와 페이지 수가 다릅니다.");
            List<Entry> entries = new ArrayList<>();
            for (Element row : required(document, "table.board_tbl > tbody").select("> tr")) {
                if (row.selectFirst("> td.list_none") != null) {
                    continue;
                }
                entries.add(entry(row));
            }
            int expected = Math.min(10, Math.max(0, total - (page - 1) * 10));
            require(entries.size() == expected, "GH 목록의 전체 건수와 행 수가 다릅니다.");
            require(new HashSet<>(entries.stream().map(Entry::pbancNo).toList()).size() == entries.size(),
                    "GH 목록에 중복 공고 번호가 있습니다.");
            return new GhAnnouncementPage(page, total, List.copyOf(entries));
        }
        catch (DateTimeException | IllegalArgumentException exception) {
            throw new ExternalDataRequestException("GH 목록의 숫자 또는 날짜 형식이 올바르지 않습니다.", exception);
        }
    }

    private Entry entry(Element row) {
        var cells = row.select("> td");
        require(cells.size() == 10, "GH 목록의 열 구성이 변경됐습니다.");
        Element link = required(cells.get(2), "a[data-pbancno]");
        String pbancNo = link.attr("data-pbancno");
        String kindCode = link.attr("data-pbanckndcd");
        String supplyType = cells.get(1).text();
        require(pbancNo.matches("[1-9][0-9]*") && kindCode.matches("[0-9]{2}"), "GH 공고 식별자가 올바르지 않습니다.");
        require(!link.text().isBlank() && !supplyType.isBlank() && supplyType.equals(link.attr("data-biztynm")),
                "GH 공고 제목 또는 유형이 없습니다.");
        return new Entry(pbancNo, kindCode, supplyType, link.text(), cells.get(3).text(),
                LocalDate.parse(cells.get(5).text()), optionalDate(cells.get(6).text()), optional(cells.get(7).text()));
    }

    private LocalDate optionalDate(String value) {
        String text = optional(value);
        return text == null ? null : LocalDate.parse(text);
    }

    private String optional(String value) {
        return value.isBlank() || value.equals(NONE) ? null : value;
    }

    private Document document(String html, GhAnnouncementBoard board) {
        require(html != null && !html.isBlank(), "GH HTML 응답이 비어 있습니다.");
        Document document = Jsoup.parse(html);
        // 페이지 이동 스크립트가 요청한 게시판의 목록 주소로 제출해야 그 게시판 응답이다.
        String pagingTarget = "\"/sb/sr/" + board.code() + "/selectPbancRentHouseList.do\"";
        require(document.select("script:not([src])").stream().map(Element::data)
                        .anyMatch(script -> script.contains("function fn_sel_page") && script.contains(pagingTarget)),
                "GH " + board.displayName() + " 게시판 응답이 아닙니다.");
        return document;
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
