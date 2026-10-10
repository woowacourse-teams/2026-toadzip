package com.toadzip.backend.ingest.collection.repository.external;

import com.toadzip.backend.ingest.collection.dto.ShAnnouncementPage.Entry;
import com.toadzip.backend.ingest.collection.dto.ShAnnouncementPage;
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
public class ShAnnouncementListParser {

    private static final Pattern PAGE = Pattern.compile("\\[(\\d+)/(\\d+)페이지\\]");
    private static final Pattern SEQ = Pattern.compile("getDetailView\\('([1-9][0-9]*)'\\)");

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
