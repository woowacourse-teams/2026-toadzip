package com.toadzip.backend.ingest.collection.gh.repository;

import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GH 청약공고 상세의 구획(공급정보 지구·주택형·공급일정·접수처·안내사항·기타사항)을 의미 단위로 읽는다.
 * 값은 화면에 보이는 원문 그대로 두고, 숫자·날짜 해석은 읽는 쪽에 맡긴다.
 */
final class GhAnnouncementSectionReader {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DISTRICT_CAPTION = "사업지구별 공급정보";
    private static final List<String> HOUSING_TYPE_HEADERS = List.of(
            "주택형", "전용면적(m²)", "금회공급 세대수", "임대보증금(원)", "월임대료(원)", "인터넷청약");
    /** 읽는 구획과 읽지 않아도 되는 안내 구획. 모르는 구획이 생기면 사이트 구조가 바뀐 것으로 본다. */
    private static final Set<String> KNOWN_SECTIONS = Set.of(
            "상세정보", "공급정보", "단지 관련 이미지 정보", "주택형 안내", "안내사항", "공급일정", "접수처 정보", "기타사항");

    private final Element content;

    GhAnnouncementSectionReader(Element content) {
        this.content = content;
    }

    /** 사이트가 새로 추가한 구획 제목. 비어 있지 않으면 저장값만으로는 공고를 다 담지 못한다. */
    List<String> unknownSections() {
        return content.select("h5.subcont_title").stream().map(Element::text)
                .filter(title -> !KNOWN_SECTIONS.contains(title)).distinct().toList();
    }

    String districts() {
        var tabs = content.select("ul.tab_box > li.pbancTab");
        var panels = content.select("div[id~=^tab[0-9]+$]");
        require(tabs.size() == panels.size(), "GH 공급정보 탭과 지구 수가 다릅니다.");
        ArrayNode districts = JSON.createArrayNode();
        for (int index = 0; index < panels.size(); index++) {
            Element panel = panels.get(index);
            require(panel.id().equals("tab" + (index + 1)), "GH 공급정보 지구 순서가 탭과 다릅니다.");
            districts.add(district(panel, tabs.get(index).text()));
        }
        return JSON.writeValueAsString(districts);
    }

    /** 공고 단위 접수처. 행복주택은 지구마다 접수처가 있어 공고 단위 접수처가 없으면 빈 객체다. */
    String reception() {
        Element table = sectionContent(content, "접수처 정보", "table.view_tbl");
        return JSON.writeValueAsString(table == null ? JSON.createObjectNode() : reception(table));
    }

    String notice() {
        return text(sectionContent(content, "안내사항", "div.bg_box"));
    }

    String otherNotes() {
        return text(sectionContent(content, "기타사항", "div"));
    }

    private ObjectNode district(Element panel, String tabName) {
        Element table = captioned(panel, DISTRICT_CAPTION);
        ObjectNode district = JSON.createObjectNode();
        district.put("name", value(table, "지구명"));
        require(district.get("name").asString().equals(tabName), "GH 지구명이 탭 이름과 다릅니다.");
        district.put("address", value(table, "소재지"));
        district.put("exclusiveAreaRange", value(table, "전용면적(m²)"));
        district.put("recruitmentCount", value(table, "모집호수"));
        district.put("heatingType", value(table, "난방방식"));
        district.put("expectedMoveIn", value(table, "입주예정월"));
        district.set("housingTypes", housingTypes(panel));
        district.set("schedules", schedules(panel));
        district.put("notice", text(sectionContent(panel, "안내사항", "div.bg_box")));
        Element reception = sectionContent(panel, "접수처 정보", "table.view_tbl");
        if (reception != null) {
            district.set("reception", reception(reception));
        }
        return district;
    }

    private ArrayNode housingTypes(Element panel) {
        ArrayNode housingTypes = JSON.createArrayNode();
        var bodies = panel.select("tbody.tb_house");
        require(bodies.size() <= 1, "GH 주택형 표가 여러 개입니다.");
        if (bodies.isEmpty()) {
            return housingTypes;
        }
        List<String> headers = bodies.getFirst().select("> tr > th").stream().map(Element::text).toList();
        require(headers.equals(HOUSING_TYPE_HEADERS), "GH 주택형 표의 열 구성이 변경됐습니다.");
        for (Element row : bodies.getFirst().select("> tr.tb_house_con")) {
            var cells = row.select("> td");
            require(cells.size() == HOUSING_TYPE_HEADERS.size(), "GH 주택형 행의 열 수가 다릅니다.");
            Element button = cells.getFirst().selectFirst("button[data-housetycd]");
            require(button != null && !cells.getFirst().text().isBlank(), "GH 주택형 이름이 없습니다.");
            ObjectNode housingType = housingTypes.addObject();
            housingType.put("houseTypeCode", button.attr("data-housetycd"));
            housingType.put("businessCode", button.attr("data-bizcd"));
            housingType.put("recruitmentRound", button.attr("data-rcritnmtm"));
            housingType.put("name", cells.get(0).text());
            housingType.put("exclusiveArea", cells.get(1).text());
            housingType.put("supplyCount", cells.get(2).text());
            housingType.put("rentalDeposit", cells.get(3).text());
            housingType.put("monthlyRent", cells.get(4).text());
            housingType.put("applicationStatus", cells.get(5).text());
        }
        return housingTypes;
    }

    private ArrayNode schedules(Element panel) {
        ArrayNode schedules = JSON.createArrayNode();
        Element list = sectionContent(panel, "공급일정", "ul");
        if (list == null) {
            return schedules;
        }
        for (Element item : list.select("> li")) {
            Element label = item.selectFirst("b");
            require(label != null, "GH 공급일정 항목 이름이 없습니다.");
            Element copy = item.clone();
            copy.select("b").remove();
            ObjectNode schedule = schedules.addObject();
            schedule.put("label", label.text().replaceFirst("\\s*:\\s*$", ""));
            schedule.put("value", copy.text());
        }
        return schedules;
    }

    private ObjectNode reception(Element table) {
        ObjectNode reception = JSON.createObjectNode();
        reception.put("address", value(table, "소재지"));
        reception.put("phone", value(table, "전화번호"));
        reception.put("operatingPeriod", value(table, "접수처 운영기간"));
        return reception;
    }

    /**
     * 구획 제목 바로 뒤의 내용 요소. 지구 탭 안의 구획은 공고 단위 구획으로 읽지 않는다.
     * 같은 제목의 구획이 범위 안에 여러 번 나오면 구조가 바뀐 것으로 본다.
     */
    private Element sectionContent(Element scope, String title, String selector) {
        var headings = scope.select("h5.subcont_title").stream()
                .filter(heading -> heading.text().equals(title))
                .filter(heading -> scope != content || heading.closest("div[id~=^tab[0-9]+$]") == null)
                .toList();
        require(headings.size() <= 1, "GH 구획이 중복됐습니다: " + title);
        if (headings.isEmpty()) {
            return null;
        }
        Element next = headings.getFirst().nextElementSibling();
        require(next != null && next.is(selector), "GH 구획 내용이 없습니다: " + title);
        return next;
    }

    private Element captioned(Element scope, String caption) {
        var tables = scope.select("table").stream()
                .filter(table -> table.selectFirst("> caption") != null
                        && table.selectFirst("> caption").text().equals(caption))
                .toList();
        require(tables.size() == 1, "GH 표가 없거나 변경됐습니다: " + caption);
        return tables.getFirst();
    }

    /** 표의 이름 칸 다음 값 칸. 지도보기 같은 버튼 글자는 값이 아니라서 뺀다. */
    private String value(Element table, String label) {
        var headers = table.select("th[scope=row]").stream().filter(header -> header.text().equals(label)).toList();
        require(headers.size() == 1, "GH 표 필드가 없거나 중복됐습니다: " + label);
        Element cell = headers.getFirst().nextElementSibling();
        require(cell != null && cell.tagName().equals("td"), "GH 표 필드 값이 없습니다: " + label);
        Element copy = cell.clone();
        copy.select("button").remove();
        return copy.text();
    }

    /** 줄바꿈(br·문단)을 살린 텍스트. 구획이 없으면 빈 문자열이다. */
    private String text(Element element) {
        if (element == null) {
            return "";
        }
        Element copy = element.clone();
        for (Element text : copy.getAllElements()) {
            for (var node : text.textNodes()) {
                node.text(node.getWholeText().replaceAll("\\s+", " "));
            }
        }
        // br은 wholeText가 줄바꿈으로 옮긴다.
        for (Element block : copy.select("p, div, li")) {
            block.before(new TextNode("\n"));
            block.after(new TextNode("\n"));
        }
        return copy.wholeText().lines().map(String::strip)
                .collect(Collectors.joining("\n")).replaceAll("\n{3,}", "\n\n").strip();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ExternalDataRequestException(message);
        }
    }
}
