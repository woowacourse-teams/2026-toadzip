package com.toadzip.backend.announcement.service;

import com.toadzip.backend.announcement.exception.PdfExtractionFailedException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 모델이 돌려준 추출 JSON을 관리 화면에 넘기기 전에 다듬는다.
 * 모델은 첨부한 PDF 기준 쪽 번호를 쓰므로 원본 쪽 번호로 바꾸고, 자주 어기는 규칙은 값을 고치지 않고 경고만 남긴다.
 */
record ExtractedAnnouncementJson(String json, List<String> warnings) {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern AREA_ONLY = Pattern.compile("[\\d.]+");
    private static final Pattern TARGET_NOTATION = Pattern.compile("「|\\s\\(");
    private static final List<String> DATE_FIELDS = List.of(
            "postedDate", "applicationStartDate", "applicationEndDate", "winnerAnnouncementDate");

    static ExtractedAnnouncementJson from(ObjectMapper mapper, String json, List<Integer> pageNumbers) {
        JsonNode tree = parse(mapper, json);
        List<String> warnings = new ArrayList<>();
        remapPages(tree.path("evidence"), pageNumbers, warnings);
        remapPages(tree.path("sharedCounts"), pageNumbers, warnings);
        JsonNode data = tree.path("data");
        checkAnnouncement(data.path("announcement"), warnings);
        checkComplexes(data, warnings);
        checkSupplyRows(data, warnings);
        return new ExtractedAnnouncementJson(mapper.writeValueAsString(tree), List.copyOf(warnings));
    }

    private static JsonNode parse(ObjectMapper mapper, String json) {
        try {
            JsonNode tree = mapper.readTree(json);
            if (!tree.isObject()) {
                throw new PdfExtractionFailedException("AI 모델 응답이 JSON 객체가 아닙니다.");
            }
            return tree;
        }
        catch (JacksonException exception) {
            throw new PdfExtractionFailedException("AI 모델 응답을 JSON으로 읽지 못했습니다.", exception);
        }
    }

    private static void remapPages(JsonNode items, List<Integer> pageNumbers, List<String> warnings) {
        for (JsonNode item : items.values()) {
            int page = item.path("page").intValue(0);
            if (page < 1 || page > pageNumbers.size()) {
                warnings.add("%s: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(%d)".formatted(label(item), page));
                continue;
            }
            ((ObjectNode) item).put("page", pageNumbers.get(page - 1));
        }
    }

    private static String label(JsonNode item) {
        if (item.has("path")) {
            return item.path("path").asString("");
        }
        return item.path("paths").toString();
    }

    private static void checkAnnouncement(JsonNode announcement, List<String> warnings) {
        for (String field : DATE_FIELDS) {
            String value = announcement.path(field).asString("");
            if (!value.isEmpty() && !DATE.matcher(value).matches()) {
                warnings.add("announcement.%s: 날짜 형식이 아님(%s)".formatted(field, value));
            }
        }
    }

    private static void checkComplexes(JsonNode data, List<String> warnings) {
        String title = data.path("announcement").path("name").asString("");
        List<JsonNode> complexes = List.copyOf(data.path("complexes").values());
        for (int index = 0; index < complexes.size(); index++) {
            if (!title.isEmpty() && title.equals(complexes.get(index).path("sourceComplexName").asString(""))) {
                warnings.add("complexes[%d].sourceComplexName: 단지명이 공고 제목 전체와 같음".formatted(index));
            }
        }
    }

    private static void checkSupplyRows(JsonNode data, List<String> warnings) {
        String postedMonth = data.path("announcement").path("postedDate").asString("");
        List<JsonNode> rows = List.copyOf(data.path("supplyRows").values());
        for (int index = 0; index < rows.size(); index++) {
            checkSupplyRow(rows.get(index), "supplyRows[%d]".formatted(index), postedMonth, warnings);
        }
    }

    private static void checkSupplyRow(JsonNode row, String path, String postedDate, List<String> warnings) {
        String housingType = row.path("sourceHousingTypeName").asString("");
        if (!housingType.isEmpty() && AREA_ONLY.matcher(housingType).matches()) {
            warnings.add("%s.sourceHousingTypeName: 면적 숫자만 있음(%s)".formatted(path, housingType));
        }
        if (isPresent(row.path("commonArea")) && isPresent(row.path("supplyArea"))) {
            warnings.add("%s.supplyArea: 공용면적이 있는데 공급면적도 채워짐".formatted(path));
        }
        String moveIn = row.path("expectedMoveInMonth").asString("");
        if (!moveIn.isEmpty() && postedDate.length() >= 7 && moveIn.compareTo(postedDate.substring(0, 7)) < 0) {
            warnings.add("%s.expectedMoveInMonth: 공고일보다 이른 연월(%s)".formatted(path, moveIn));
        }
        checkTargets(row, path, warnings);
    }

    private static void checkTargets(JsonNode row, String path, List<String> warnings) {
        List<JsonNode> targets = List.copyOf(row.path("targets").values());
        for (int index = 0; index < targets.size(); index++) {
            String target = targets.get(index).path("target").asString("");
            if (TARGET_NOTATION.matcher(target).find()) {
                warnings.add("%s.targets[%d].target: 표기 규칙 위반(%s)".formatted(path, index, target));
            }
        }
    }

    private static boolean isPresent(JsonNode value) {
        return !value.isMissingNode() && !value.isNull();
    }
}
