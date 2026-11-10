package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** PDF 보정 화면의 최신 추출(AI 신청자격)과 적용한 공급행으로 신청자격 초안을 만드는 관리자 API. 실제 Flyway 스키마에서 확인한다. */
class EligibilityExtractionDraftIntegrationTest extends EligibilityIntegrationSupport {
    private static final String SHA = "a".repeat(64);
    private static final String FILE = "인천석남 공고문.pdf";

    @Test
    void 최신_추출과_공급행으로_검토_전_초안을_만들고_공개는_검토_완료_뒤에만_한다() throws Exception {
        long announcement = notice(true);

        JsonNode draft = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()));

        JsonNode content = draft.path("content");
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(content.path("metadata").path("reviewStatus").asString()).isEqualTo("REVIEWING");
        assertThat(content.path("metadata").path("referenceDate").asString()).isEqualTo("2026-09-30");
        assertThat(content.path("documents").get(0).path("downloadUrl").asString())
                .isEqualTo("https://apply.lh.or.kr/notice.pdf");
        assertThat(content.path("documents").get(0).path("pageCount").asInt()).isEqualTo(23);
        assertThat(texts(content.path("paths"), "label")).containsExactly("청년");
        assertThat(texts(content.path("options"), "recruitmentStatus")).containsExactly("RECRUITING");
        assertThat(content.path("supplyUnits").get(0).path("label").asString()).isEqualTo("인천석남 21A");
        assertThat(texts(content.path("commonChecks"), "label")).containsExactly("무주택세대구성원");
        assertThat(content.path("guidances").get(0).path("kind").asString()).isEqualTo("REQUIRED_QUALIFICATION");
        assertThat(content.path("metadata").path("scopeNote").asString()).contains("고령자");
        publish(announcement, draft).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("검토 완료한 내용만 공개할 수 있습니다."));
    }

    @Test
    void 초안이_있으면_그_초안의_버전으로만_다시_만든다() throws Exception {
        long announcement = notice(true);
        JsonNode first = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()));

        fromExtraction(announcement, SHA, null, 0).andExpect(status().isConflict());
        JsonNode second = response(fromExtraction(announcement, SHA, first.path("id").asLong(),
                first.path("revision").asLong()).andExpect(status().isOk()));

        assertThat(second.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(second.path("revision").asLong()).isEqualTo(first.path("revision").asLong() + 1);
    }

    @Test
    void 초안을_만들_수_없는_상태는_이유와_함께_거절하고_초안을_남기지_않는다() throws Exception {
        long withoutExtraction = announcement();
        rejected(withoutExtraction, SHA, "PDF 추출 결과가 없습니다");

        long otherPdf = notice(true);
        rejected(otherPdf, "b".repeat(64), "화면의 PDF와 최신 추출 결과가 다릅니다");

        long withoutAttachment = notice(true);
        jdbc.update("DELETE FROM announcement_attachments WHERE announcement_id = ?", withoutAttachment);
        rejected(withoutAttachment, SHA, "공고 첨부에 없어");

        long olderWorker = notice(false);
        rejected(olderWorker, SHA, "이 추출 결과에는 신청자격이 없습니다");

        mvc.perform(post(admin(otherPdf) + "/from-extraction").with(user(Long.toString(USER)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(SHA, null, 0)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM basic_comparison_rule_sets WHERE announcement_id IN (?, ?, ?, ?)",
                Long.class, withoutExtraction, otherPdf, withoutAttachment, olderWorker)).isZero();
    }

    private void rejected(long announcement, String sha, String message) throws Exception {
        fromExtraction(announcement, sha, null, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BASIC_COMPARISON_CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString(message)));
    }

    /** 공고 하나: 공급행 1개(청년 10명, 고령자 대상), PDF 첨부, 신청자격이 든(또는 이전 워커라 없는) 성공 추출. */
    private long notice(boolean withEligibility) throws Exception {
        long announcement = announcement();
        jdbc.update("UPDATE announcements SET posted_date = '2026-09-30' WHERE id = ?", announcement);
        jdbc.update("""
                INSERT INTO announcement_attachments (announcement_id, display_order, file_name, file_type, file_url)
                VALUES (?, 0, ?, 'ANNOUNCEMENT', 'https://apply.lh.or.kr/notice.pdf')
                """, announcement, FILE);
        Long row = jdbc.queryForObject("""
                INSERT INTO supply_rows (announcement_id, display_order, source_supply_row_identifier, source_complex_name,
                    source_housing_type_name, supply_category, announcement_exclusive_area)
                VALUES (?, 0, ?, '인천석남', '21A', 'NEW_SUPPLY', 21.00) RETURNING id
                """, Long.class, announcement, "row-" + announcement);
        jdbc.update("INSERT INTO supply_targets (supply_row_id, display_order, target, supply_household_count) VALUES"
                + " (?, 0, '청년', 10), (?, 1, '고령자(주거약자용)', 0)", row, row);
        Map<String, Object> data = new LinkedHashMap<>(Map.of("announcement", Map.of("name", "인천석남 행복주택")));
        if (withEligibility) {
            data.put("eligibility", Map.of(
                    "common", Map.of("requirements", List.of(Map.of("kind", "NO_HOUSE", "scope", "HOUSEHOLD",
                            "asWritten", "무주택세대구성원")), "qualifications", List.of(), "summary", "무주택세대구성원"),
                    "targets", List.of(
                            Map.of("target", "청년", "supplyTarget", "청년", "supplyTargets", List.of("청년"),
                                    "requirements", List.of(Map.of("kind", "OTHER", "asWritten", "입주 전까지 청약통장 가입")),
                                    "qualifications", List.of(), "summary", "청년"),
                            Map.of("target", "고령자", "supplyTarget", "고령자", "supplyTargets", List.of(),
                                    "requirements", List.of(), "qualifications", List.of(), "summary", "고령자")),
                    "exclusions", List.of()));
        }
        String extraction = json.writeValueAsString(Map.of("data", data, "evidence", List.of(
                Map.of("path", "eligibility.common.requirements[0]", "page", 3, "quote", "무주택세대구성원"),
                Map.of("path", "eligibility.targets[0].requirements[0]", "page", 5, "quote", "청약통장"))));
        String result = json.writeValueAsString(Map.of("json", extraction, "pages", List.of(1, 3, 5), "totalPages", 23,
                "warnings", List.of(), "usage", Map.of("promptTokens", 1, "outputTokens", 1, "thinkingTokens", 0)));
        jdbc.update("""
                INSERT INTO ai_jobs (kind, announcement_id, request_source, status, next_attempt_at, file_name, pdf_sha256,
                    result, created_at, completed_at)
                VALUES ('ANNOUNCEMENT_PDF_EXTRACTION', ?, 'ADMIN', 'SUCCEEDED', now(), ?, ?, ?::jsonb, now(), now())
                """, announcement, FILE, SHA, result);
        return announcement;
    }

    private ResultActions fromExtraction(long announcement, String sha, Long draftId, long revision) throws Exception {
        return mvc.perform(post(admin(announcement) + "/from-extraction").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(sha, draftId, revision)));
    }

    private String body(String sha, Long draftId, long revision) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("pdfSha256", sha, "expectedRevision", revision));
        if (draftId != null) {
            body.put("expectedDraftId", draftId);
        }
        return json.writeValueAsString(body);
    }

    private static List<String> texts(JsonNode items, String field) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.path(field).asString()));
        return values;
    }
}
