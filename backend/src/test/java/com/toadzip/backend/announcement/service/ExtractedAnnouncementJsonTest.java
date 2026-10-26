package com.toadzip.backend.announcement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toadzip.backend.announcement.exception.PdfExtractionFailedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ExtractedAnnouncementJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void 근거와_공통_모집수의_쪽_번호를_원본_PDF_기준으로_바꾼다() {
        String json = response("""
                "sharedCounts":[{"paths":["supplyRows[0].totalSupplyHouseholdCount"],"count":20,"page":2,"quote":"20"}],
                "evidence":[{"path":"announcement.name","page":1,"quote":"모집"},
                {"path":"schedules[0]","page":3,"quote":"‘26.11.10"}]
                """, row("\"26A\"", "13.5", "null", "null", "가군(생계·의료급여수급자 등)"));

        ExtractedAnnouncementJson result = ExtractedAnnouncementJson.from(mapper, json, List.of(1, 2, 10));

        JsonNode tree = mapper.readTree(result.json());
        assertThat(tree.path("evidence").path(1).path("page").intValue()).isEqualTo(10);
        assertThat(tree.path("sharedCounts").path(0).path("page").intValue()).isEqualTo(2);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void 첨부_범위를_벗어난_쪽_번호는_그대로_두고_경고한다() {
        String json = response(
                "\"sharedCounts\":[],\"evidence\":[{\"path\":\"receptionPlace\",\"page\":7,\"quote\":\"x\"}]",
                row("\"26A\"", "13.5", "null", "null", "일반"));

        ExtractedAnnouncementJson result = ExtractedAnnouncementJson.from(mapper, json, List.of(1, 2, 10));

        assertThat(mapper.readTree(result.json()).path("evidence").path(0).path("page").intValue()).isEqualTo(7);
        assertThat(result.warnings()).containsExactly("receptionPlace: 첨부한 쪽 범위를 벗어난 근거 쪽 번호(7)");
    }

    @Test
    void 모델이_자주_어기는_규칙을_경고로_알린다() {
        String json = """
                {"data":{"announcement":{"name":"사천벌리1 영구임대주택 예비입주자 모집","postedDate":"2026-10-16",
                "applicationStartDate":"2026-10-28T09:00:00","applicationEndDate":"2026-10-30",
                "winnerAnnouncementDate":"2027-01-22"},
                "complexes":[{"sourceComplexName":"사천벌리1 영구임대주택 예비입주자 모집"}],
                "supplyRows":[%s]},"sharedCounts":[],"notApplicable":[],"evidence":[]}
                """.formatted(row("\"26.37\"", "13.5", "39.87", "\"2025-06\"", "「가」군"));

        ExtractedAnnouncementJson result = ExtractedAnnouncementJson.from(mapper, json, List.of(1));

        assertThat(result.warnings()).containsExactly(
                "announcement.applicationStartDate: 날짜 형식이 아님(2026-10-28T09:00:00)",
                "complexes[0].sourceComplexName: 단지명이 공고 제목 전체와 같음",
                "supplyRows[0].sourceHousingTypeName: 면적 숫자만 있음(26.37)",
                "supplyRows[0].supplyArea: 공용면적이 있는데 공급면적도 채워짐",
                "supplyRows[0].expectedMoveInMonth: 공고일보다 이른 연월(2025-06)",
                "supplyRows[0].targets[0].target: 표기 규칙 위반(「가」군)");
    }

    @Test
    void JSON이_아닌_응답은_추출_실패로_알린다() {
        assertThatThrownBy(() -> ExtractedAnnouncementJson.from(mapper, "```json {}", List.of(1)))
                .isInstanceOf(PdfExtractionFailedException.class);
    }

    private static String response(String tail, String row) {
        return """
                {"data":{"announcement":{"name":"여수미평1 영구임대주택 예비입주자 모집","postedDate":"2026-10-20",
                "applicationStartDate":"2026-11-10","applicationEndDate":"2026-11-12","winnerAnnouncementDate":null},
                "complexes":[{"sourceComplexName":"여수미평1"}],"supplyRows":[%s]},"notApplicable":[],%s}
                """.formatted(row, tail);
    }

    private static String row(String housingType, String commonArea, String supplyArea, String moveIn, String target) {
        return """
                {"sourceHousingTypeName":%s,"expectedMoveInMonth":%s,"commonArea":%s,"supplyArea":%s,
                "targets":[{"target":"%s"}]}
                """.formatted(housingType, moveIn, commonArea, supplyArea, target);
    }
}
