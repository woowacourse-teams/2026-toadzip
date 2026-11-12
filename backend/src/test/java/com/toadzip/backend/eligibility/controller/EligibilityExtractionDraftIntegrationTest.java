package com.toadzip.backend.eligibility.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void 최신_추출과_공급행으로_신청자격을_만들어_곧바로_사용자에게_공개한다() throws Exception {
        long announcement = notice(true);

        JsonNode created = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()));

        JsonNode draft = created.path("ruleSet");
        JsonNode content = draft.path("content");
        assertThat(created.path("published").asBoolean()).isTrue();
        assertThat(draft.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(content.path("metadata").path("reviewStatus").asString()).isEqualTo("REVIEWED");
        assertThat(content.path("metadata").path("referenceDate").asString()).isEqualTo("2026-09-30");
        assertThat(content.path("documents").get(0).path("downloadUrl").asString())
                .isEqualTo("https://apply.lh.or.kr/notice.pdf");
        assertThat(content.path("documents").get(0).path("pageCount").asInt()).isEqualTo(23);
        assertThat(texts(content.path("paths"), "label")).containsExactly("청년");
        // 공통 모집수 묶음에 든 청년(소득有) 대상은 묶음 모집수로 모집 중이고, 관리자가 뺀 대상은 잇지 않는다.
        assertThat(texts(content.path("options"), "recruitmentStatus")).containsExactly("RECRUITING");
        assertThat(content.path("supplyUnits").get(0).path("label").asString()).isEqualTo("인천석남 21A");
        assertThat(texts(content.path("commonChecks"), "label")).containsExactly("무주택세대구성원");
        assertThat(content.path("guidances").get(0).path("kind").asString()).isEqualTo("REQUIRED_QUALIFICATION");
        assertThat(content.path("metadata").path("scopeNote").asString()).doesNotContain("고령자");
        assertThat(texts(created.path("notices"))).containsExactly("공급대상과 잇지 못한 신청자격 경로: 고령자");
        // 공개본만 남고 초안은 남지 않는다. 관리자 화면은 공개본을 그대로 다시 읽는다.
        String base = "/api/admin/announcements/" + announcement + "/basic-comparison-rule-set";
        assertThat(response(mvc.perform(get(base + "/draft").with(user("admin").roles("ADMIN")))).isNull()).isTrue();
        JsonNode published = response(mvc.perform(get(base + "/published").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()));
        assertThat(published.path("id").asLong()).isEqualTo(draft.path("id").asLong());
        assertThat(published.path("version").asLong()).isEqualTo(1);
        mvc.perform(get(base + "/published").with(user(Long.toString(USER)).roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 다시_적용하면_공개본을_다음_버전으로_바꾼다() throws Exception {
        long announcement = notice(true);
        JsonNode first = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk())).path("ruleSet");

        JsonNode second = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk())).path("ruleSet");

        assertThat(second.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(second.path("version").asLong()).isEqualTo(first.path("version").asLong() + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM basic_comparison_rule_sets WHERE announcement_id = ?",
                Long.class, announcement)).isOne();
    }

    @Test
    void 공개_전_공고는_저장만_하고_그_이유를_알린다() throws Exception {
        long announcement = notice(true);
        jdbc.update("UPDATE announcements SET release_status = 'UNCONFIRMED' WHERE id = ?", announcement);

        JsonNode created = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()));

        assertThat(created.path("published").asBoolean()).isFalse();
        assertThat(created.path("ruleSet").path("status").asString()).isEqualTo("DRAFT");
        assertThat(texts(created.path("notices"))).contains(
                "공고가 아직 공개 전이라 신청자격은 저장만 했습니다. 공고를 공개한 뒤 보정을 다시 적용하면 사용자에게 반영됩니다.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM basic_comparison_rule_sets WHERE announcement_id = ?"
                + " AND status = 'PUBLISHED'", Long.class, announcement)).isZero();
    }

    @Test
    void 초안이_있으면_그_초안의_버전으로만_다시_만든다() throws Exception {
        // 공개 전 공고는 초안으로 남으므로, 다시 적용할 때 그 초안의 버전을 맞춰야 한다.
        long announcement = notice(true);
        jdbc.update("UPDATE announcements SET release_status = 'UNCONFIRMED' WHERE id = ?", announcement);
        JsonNode first = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk())).path("ruleSet");

        fromExtraction(announcement, SHA, null, 0).andExpect(status().isConflict());
        JsonNode second = response(fromExtraction(announcement, SHA, first.path("id").asLong(),
                first.path("revision").asLong()).andExpect(status().isOk())).path("ruleSet");

        assertThat(second.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(second.path("revision").asLong()).isEqualTo(first.path("revision").asLong() + 1);
    }

    @Test
    void 자동_판정할_조건이_하나도_없어도_직접_확인으로_초안을_남긴다() throws Exception {
        // 공통 요건을 하나도 자동 판정할 수 없으면 모두 직접 확인 안내가 되고, 경로는 신청 가능으로 확정되지 않는다.
        long nothingJudgeable = notice(true);
        jdbc.update("UPDATE ai_jobs SET result = jsonb_set(result, '{json}', to_jsonb(replace(result->>'json',"
                + " '\"kind\":\"NO_HOUSE\"', '\"kind\":\"OTHER\"'))) WHERE announcement_id = ?", nothingJudgeable);

        JsonNode content = response(fromExtraction(nothingJudgeable, SHA, null, 0).andExpect(status().isOk()))
                .path("ruleSet").path("content");

        assertThat(content.path("commonChecks").isEmpty()).isTrue();
        // 경로마다 그 경로 전체에 걸리는 직접 확인이 있어야 판정이 판단 보류로 남는다.
        for (JsonNode path : content.path("paths")) {
            String id = path.path("id").asString();
            boolean direct = false;
            for (JsonNode guidance : content.path("guidances")) {
                for (JsonNode target : guidance.path("targets")) {
                    direct |= guidance.path("kind").asString().equals("REQUIRED_QUALIFICATION")
                            && (target.path("scope").asString().equals("ANNOUNCEMENT")
                            || target.path("scope").asString().equals("PATH") && target.path("pathId").asString().equals(id));
                }
            }
            assertThat(direct).as("경로 %s의 직접 확인", id).isTrue();
        }
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

        // 관리자가 공급대상을 모두 뺀 공급행은 대상 구분이 없는 행이 아니다. 아무 경로도 잇지 않는다.
        long allTargetsExcluded = notice(true);
        jdbc.update("UPDATE supply_targets SET admin_excluded = true WHERE supply_row_id IN"
                + " (SELECT id FROM supply_rows WHERE announcement_id = ?)", allTargetsExcluded);
        rejected(allTargetsExcluded, SHA, "공급대상과 이어지는 신청자격 경로가 없어");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM basic_comparison_rule_sets WHERE announcement_id = ?",
                Long.class, allTargetsExcluded)).isZero();

        mvc.perform(post(admin(otherPdf) + "/from-extraction").with(user(Long.toString(USER)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(SHA, null, 0)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM basic_comparison_rule_sets WHERE announcement_id IN (?, ?, ?, ?)",
                Long.class, withoutExtraction, otherPdf, withoutAttachment, olderWorker)).isZero();
    }

    @Test
    void 적용_전_미리보기는_공급행_없이_변환_결과를_보이고_저장하지_않는다() throws Exception {
        long announcement = notice(true);
        String rows = "(SELECT id FROM supply_rows WHERE announcement_id = ?)";
        jdbc.update("DELETE FROM supply_targets WHERE supply_row_id IN " + rows, announcement);
        jdbc.update("DELETE FROM supply_target_count_groups WHERE supply_row_id IN " + rows, announcement);
        jdbc.update("DELETE FROM supply_rows WHERE announcement_id = ?", announcement);
        jdbc.update("DELETE FROM announcement_attachments WHERE announcement_id = ?", announcement);

        JsonNode content = response(mvc.perform(get(admin(announcement) + "/from-extraction/preview")
                .param("pdfSha256", SHA).with(user("admin").roles("ADMIN"))).andExpect(status().isOk()));

        assertThat(texts(content.path("paths"), "label")).containsExactly("청년", "고령자");
        assertThat(texts(content.path("commonChecks"), "label")).containsExactly("무주택세대구성원");
        // 입력으로 판정할 수 없는 청약통장 요건은 판정을 막지 않는 목록으로 근거 쪽과 함께 보인다.
        JsonNode listed = null;
        for (JsonNode guidance : content.path("guidances")) {
            if (guidance.path("title").asString().equals("신청 전에 확인할 자격")) {
                listed = guidance;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("kind").asString()).isEqualTo("REQUIRED_CHECK");
        assertThat(texts(listed.path("items"))).containsExactly("입주 전까지 청약통장 가입");
        String evidence = listed.path("evidenceIds").get(0).asString();
        List<Integer> pages = new ArrayList<>();
        content.path("evidences").forEach(item -> {
            if (item.path("id").asString().equals(evidence)) {
                pages.add(item.path("firstPage").asInt());
            }
        });
        assertThat(pages).containsExactly(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM basic_comparison_rule_sets WHERE announcement_id = ?",
                Long.class, announcement)).isZero();

        mvc.perform(get(admin(announcement) + "/from-extraction/preview").param("pdfSha256", "b".repeat(64))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("화면의 PDF와 최신 추출 결과가 다릅니다")));
        mvc.perform(get(admin(announcement) + "/from-extraction/preview").param("pdfSha256", SHA)
                .with(user(Long.toString(USER)).roles("USER"))).andExpect(status().isForbidden());
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
        Long group = jdbc.queryForObject("INSERT INTO supply_target_count_groups (supply_row_id, kind, household_count)"
                + " VALUES (?, 'SUPPLY', 10) RETURNING id", Long.class, row);
        jdbc.update("INSERT INTO supply_targets (supply_row_id, display_order, target, supply_household_count,"
                + " supply_count_group_id, admin_excluded) VALUES (?, 0, '청년', NULL, ?, false),"
                + " (?, 1, '고령자(주거약자용)', 0, NULL, false), (?, 2, '청년', 5, NULL, true)", row, group, row, row);
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

    private static List<String> texts(JsonNode items) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.asString()));
        return values;
    }
}
