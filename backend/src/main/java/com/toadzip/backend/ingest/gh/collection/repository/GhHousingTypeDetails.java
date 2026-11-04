package com.toadzip.backend.ingest.gh.collection.repository;

import com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard;
import com.toadzip.backend.ingest.collection.repository.external.ExternalDataRequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공급정보 주택형마다 있는 상세공급정보 팝업을 다룬다. 상세에서 읽은 팝업 요청 값으로 요청 목록을 만들고,
 * 팝업의 면적·세대수·금액 원문을 그 주택형의 detail로 붙인다.
 */
@Component
public class GhHousingTypeDetails {

    /** 상세 화면의 주택형 버튼이 팝업 폼에 그대로 넣는 값. */
    public static final List<String> REQUEST_FIELDS = List.of("bizCd", "rcritNmtm", "bizTyCd", "gnrlDrwtYn",
            "priorDrwtYn", "gnrlDrwtGroupTyCd", "priorDrwtGroupTyCd", "splySlsrtCd", "houseTyCd");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> DETAIL_FIELDS = fields();

    /** 지구·주택형 위치와 팝업 폼 값. label은 실패 사유에 남기는 사람이 읽는 이름이다. */
    public record Request(int district, int housingType, String label, Map<String, String> form) {
    }

    public List<Request> requests(String districts) {
        List<Request> requests = new ArrayList<>();
        JsonNode root = read(districts);
        for (int district = 0; district < root.size(); district++) {
            JsonNode housingTypes = root.get(district).path("housingTypes");
            for (int housingType = 0; housingType < housingTypes.size(); housingType++) {
                JsonNode type = housingTypes.get(housingType);
                Map<String, String> form = new LinkedHashMap<>();
                REQUEST_FIELDS.forEach(field -> form.put(field, type.path("detailRequest").path(field).asString("")));
                requests.add(new Request(district, housingType,
                        root.get(district).path("name").asString() + " " + type.path("name").asString(), form));
            }
        }
        return requests;
    }

    /** 팝업 응답을 detail 객체로 읽는다. 다른 주택형의 팝업이면 거절한다. */
    public ObjectNode parse(String html, Request request) {
        require(html != null && !html.isBlank(), "GH 주택형 상세 응답이 비어 있습니다.");
        Document document = Jsoup.parse(html);
        Map<String, String> values = new LinkedHashMap<>();
        for (Element header : document.select(".sub_textbox table th")) {
            Element value = header.nextElementSibling();
            require(value != null && value.tagName().equals("td"), "GH 주택형 상세 필드 값이 없습니다: " + header.text());
            require(values.put(header.text(), value.text()) == null, "GH 주택형 상세 필드가 중복됐습니다: " + header.text());
        }
        require(request.form().get("houseTyCd").equals(values.get("주택형")), "GH 주택형 상세가 요청한 주택형과 다릅니다.");
        ObjectNode detail = JSON.createObjectNode();
        DETAIL_FIELDS.forEach((label, key) -> {
            require(values.containsKey(label), "GH 주택형 상세 필드가 없습니다: " + label);
            detail.put(key, values.get(label));
        });
        ArrayNode images = detail.putArray("images");
        // 팝업의 이미지 주소는 사이트 기준 상대 경로라 사이트 주소를 붙인다.
        document.select(".sub_textbox img[src]").forEach(image -> images.add(image.attr("src").startsWith("/")
                ? GhAnnouncementBoard.SITE_URL + image.attr("src") : image.attr("src")));
        return detail;
    }

    public String withDetails(String districts, Map<Request, ObjectNode> details) {
        JsonNode root = read(districts);
        details.forEach((request, detail) -> ((ObjectNode) root.get(request.district()).path("housingTypes")
                .get(request.housingType())).set("detail", detail));
        return JSON.writeValueAsString(root);
    }

    /**
     * 같은 판을 다시 받으며 팝업을 못 받은 주택형에는 저장돼 있던 detail을 옮긴다.
     * 같은 지구·주택형 위치에 팝업 요청 값이 같을 때만 같은 주택형으로 본다.
     */
    public String carryOver(String districts, String stored) {
        if (districts == null || stored == null) {
            return districts;
        }
        JsonNode current = read(districts);
        JsonNode previous = read(stored);
        for (int district = 0; district < current.size() && district < previous.size(); district++) {
            JsonNode types = current.get(district).path("housingTypes");
            JsonNode oldTypes = previous.get(district).path("housingTypes");
            for (int index = 0; index < types.size() && index < oldTypes.size(); index++) {
                JsonNode type = types.get(index);
                JsonNode old = oldTypes.get(index);
                if (!type.has("detail") && old.has("detail") && type.path("detailRequest").equals(old.path("detailRequest"))) {
                    ((ObjectNode) type).set("detail", old.get("detail"));
                }
            }
        }
        return JSON.writeValueAsString(current);
    }

    private JsonNode read(String districts) {
        try {
            return JSON.readTree(districts);
        }
        catch (JacksonException exception) {
            throw new IllegalArgumentException("GH 공급정보 JSON이 올바르지 않습니다.", exception);
        }
    }

    private static Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("공급면적(m²)", "supplyArea");
        fields.put("주거공용면적(m²)", "residentialCommonArea");
        fields.put("기타공용면적(m²)", "otherCommonArea");
        fields.put("지하주차장면적(m²)", "undergroundParkingArea");
        fields.put("계약면적(m²)", "contractArea");
        fields.put("세대수", "householdCount");
        fields.put("금회분양 세대수", "currentSupplyCount");
        fields.put("임대보증금(원)", "rentalDeposit");
        fields.put("월임대료(원)", "monthlyRent");
        return fields;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new ExternalDataRequestException(message);
        }
    }
}
