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
    void 워커가_공급대상을_맞추지_못해도_자격_본문의_대상_이름으로_공급표_대상에_잇는다() throws Exception {
        // 공고문 공급표는 '신혼부부·한부모', 자격 본문은 '신혼부부·한부모가족'이라 워커가 supplyTargets를 비워 둔 추출(인천산곡 A1).
        long announcement = notice(true);
        jdbc.update("UPDATE announcement_supply_targets SET label = '신혼부부·한부모' WHERE announcement_id = ?"
                + " AND display_order = 0", announcement);
        List<Map<String, Object>> targets = new java.util.ArrayList<>();
        for (String route : List.of("신혼부부", "예비신혼부부", "한부모가족")) {
            targets.add(Map.of("target", route, "supplyTarget", "신혼부부·한부모가족", "supplyTargets", List.of(),
                    "requirements", List.of(Map.of("kind", "OTHER", "asWritten", "입주 전까지 청약통장 가입")),
                    "qualifications", List.of(), "summary", route));
        }
        String extraction = json.writeValueAsString(Map.of("data", Map.of(
                "announcement", Map.of("name", "인천산곡 행복주택"),
                "eligibility", Map.of("common", Map.of("requirements", List.of(Map.of("kind", "NO_HOUSE",
                                "scope", "HOUSEHOLD", "asWritten", "무주택세대구성원")), "qualifications", List.of(),
                                "summary", "무주택세대구성원"), "targets", targets, "exclusions", List.of())),
                "evidence", List.of(Map.of("path", "eligibility.common.requirements[0]", "page", 5, "quote", "무주택세대구성원"))));
        jdbc.update("UPDATE ai_jobs SET result = jsonb_set(result, '{json}', to_jsonb(?::text)) WHERE announcement_id = ?",
                extraction, announcement);

        JsonNode created = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()));

        JsonNode content = created.path("ruleSet").path("content");
        assertThat(created.path("published").asBoolean()).isTrue();
        assertThat(texts(content.path("paths"), "label")).containsExactly("신혼부부", "예비신혼부부", "한부모가족");
        assertThat(texts(content.path("options"), "recruitmentStatus")).containsOnly("RECRUITING").hasSize(3);
        assertThat(texts(created.path("notices"))).noneMatch(notice -> notice.contains("잇지 못한"));
    }

    @Test
    void 같은_표기라도_신청_조건이_다른_대상은_처음_만들_때와_다시_만들_때_모두_각기_다른_경로에_잇는다() throws Exception {
        long announcement = notice(true);
        jdbc.update("UPDATE announcement_supply_targets SET application_condition = '소득 있음'"
                + " WHERE announcement_id = ? AND label = '청년'", announcement);
        Long second = jdbc.queryForObject("""
                INSERT INTO supply_rows (announcement_id, announcement_complex_id, display_order, housing_type_name,
                    supply_category, exclusive_area)
                SELECT ?, id, 1, '36A', 'NEW_SUPPLY', 36.00 FROM announcement_complexes WHERE announcement_id = ?
                RETURNING id
                """, Long.class, announcement, announcement);
        cell(announcement, second, "청년", "소득 없음", 2, 5);
        List<Map<String, Object>> routes = List.of(
                route("청년(소득 있음)", List.of("t0"), "eligibility.targets[0]"),
                route("청년(소득 없음)", List.of("t1"), "eligibility.targets[1]"));
        replaceExtraction(announcement, Map.of("targets", routes), List.of(
                Map.of("key", "t0", "label", "청년", "applicationCondition", "소득 있음"),
                Map.of("key", "t1", "label", "청년", "applicationCondition", "소득 없음")));
        long first = jdbc.queryForObject("SELECT id FROM supply_rows WHERE announcement_id = ? AND display_order = 0",
                Long.class, announcement);

        JsonNode created = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()))
                .path("ruleSet").path("content");
        JsonNode recreated = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()))
                .path("ruleSet").path("content");

        for (JsonNode content : List.of(created, recreated)) {
            assertThat(texts(content.path("paths"), "label")).containsExactly("청년(소득 있음)", "청년(소득 없음)");
            assertThat(texts(content.path("options"), "supplyUnitId")).containsExactly("unit-" + first, "unit-" + second);
            assertThat(texts(content.path("options"), "pathId")).containsExactly("path-1", "path-2");
            assertThat(content.path("options").get(0).path("supplyTargets").get(0).path("applicationCondition")
                    .asString()).isEqualTo("소득 있음");
            assertThat(content.path("options").get(1).path("supplyTargets").get(0).path("applicationCondition")
                    .asString()).isEqualTo("소득 없음");
        }
    }

    @Test
    void 정본을_확정하기_전_공고는_기관_준비_데이터의_공급행으로_신청_단위를_만든다() throws Exception {
        long announcement = notice(true);
        // 정본 없이 LH 준비 줄만 있는 확정 전 공고. 신청자격 경로가 없는 추출이라 공고 전체 경로 하나가 모든 대상에 이어진다.
        jdbc.update("DELETE FROM supply_rows WHERE announcement_id = ?", announcement);
        jdbc.update("UPDATE announcements SET supply_confirmed_at = NULL WHERE id = ?", announcement);
        Long line = jdbc.queryForObject("""
                INSERT INTO agency_supply_lines (announcement_id, agency, source_line_key, display_order, complex_name,
                    housing_type_name, household_count, supply_category)
                VALUES (?, 'LH', 'LH:1', 0, '행복단지', '26A(청년)', 5, 'NEW_SUPPLY') RETURNING id
                """, Long.class, announcement);
        jdbc.update("""
                INSERT INTO agency_supply_lines (announcement_id, agency, source_line_key, display_order, complex_name,
                    housing_type_name, household_count, supply_category)
                VALUES (?, 'LH', 'LH:2', 1, '행복단지', '26A(고령자)', 3, 'NEW_SUPPLY')
                """, announcement);
        replaceExtraction(announcement, Map.of("targets", List.of()), List.of());

        JsonNode content = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()))
                .path("ruleSet").path("content");

        assertThat(texts(content.path("supplyUnits"), "id")).containsExactly("unit-line-" + line);
        assertThat(texts(content.path("supplyUnits"), "label")).containsExactly("행복단지 26A(청년) · 26A(고령자)");
        assertThat(texts(content.path("options"), "recruitmentStatus")).containsExactly("RECRUITING");
        assertThat(texts(content.path("options").get(0).path("supplyTargets"), "label")).containsExactly("청년", "고령자");
    }

    @Test
    void 확정_전_준비_데이터에는_워커가_이은_대상_키가_없어도_대상_이름으로_경로를_잇는다() throws Exception {
        long announcement = notice(true);
        // 준비 데이터 대상의 신청 조건은 '공고문 참조'라, 공고문에서 읽은 조건이 붙은 키와는 같을 수 없다.
        jdbc.update("DELETE FROM supply_rows WHERE announcement_id = ?", announcement);
        jdbc.update("UPDATE announcements SET supply_confirmed_at = NULL WHERE id = ?", announcement);
        Long line = jdbc.queryForObject("""
                INSERT INTO agency_supply_lines (announcement_id, agency, source_line_key, display_order, complex_name,
                    housing_type_name, household_count, supply_category)
                VALUES (?, 'LH', 'LH:1', 0, '행복단지', '26A(청년)', 5, 'NEW_SUPPLY') RETURNING id
                """, Long.class, announcement);
        jdbc.update("""
                INSERT INTO agency_supply_lines (announcement_id, agency, source_line_key, display_order, complex_name,
                    housing_type_name, household_count, supply_category)
                VALUES (?, 'LH', 'LH:2', 1, '행복단지', '26A(고령자)', 3, 'NEW_SUPPLY')
                """, announcement);
        replaceExtraction(announcement, Map.of("targets", List.of(route("청년", List.of("t0"), "eligibility.targets[0]"))),
                List.of(Map.of("key", "t0", "label", "청년", "applicationCondition", "무주택 미혼 청년")));

        JsonNode content = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()))
                .path("ruleSet").path("content");

        // 고령자는 이 경로가 가리키지 않아 공통 요건 경로를 따로 받는다.
        assertThat(texts(content.path("paths"), "label")).containsExactly("청년", "고령자");
        assertThat(texts(content.path("options"), "pathId")).containsExactly("path-1", "path-2");
        assertThat(texts(content.path("options"), "supplyUnitId")).containsOnly("unit-line-" + line);
        assertThat(content.path("options").get(0).path("supplyTargets")).hasSize(1);
        assertThat(content.path("options").get(0).path("supplyTargets").get(0).path("label").asString())
                .isEqualTo("청년");
        assertThat(content.path("options").get(0).path("supplyTargets").get(0).path("applicationCondition")
                .asString()).isEqualTo("공고문 참조");
    }

    @Test
    void 그_칸만의_모집수가_0인_행은_다른_행과_함께_쓰는_모집수가_있어도_선택지에서_뺀다() throws Exception {
        long announcement = notice(true);
        long first = jdbc.queryForObject("SELECT id FROM supply_rows WHERE announcement_id = ? AND display_order = 0",
                Long.class, announcement);
        Long second = jdbc.queryForObject("""
                INSERT INTO supply_rows (announcement_id, announcement_complex_id, display_order, housing_type_name,
                    supply_category, exclusive_area)
                SELECT ?, id, 1, '36A', 'NEW_SUPPLY', 36.00 FROM announcement_complexes WHERE announcement_id = ?
                RETURNING id
                """, Long.class, announcement, announcement);
        // 두 행의 청년 칸이 함께 쓰는 수 5와, 둘째 행 청년 칸만의 수 0. 칸이 든 가장 작은 묶음(그 칸만의 0)을 따른다.
        Long youth = jdbc.queryForObject("SELECT id FROM announcement_supply_targets WHERE announcement_id = ?"
                + " AND label = '청년'", Long.class, announcement);
        Long firstCell = jdbc.queryForObject("SELECT id FROM supply_row_targets WHERE supply_row_id = ?"
                + " AND supply_target_id = ?", Long.class, first, youth);
        Long secondCell = jdbc.queryForObject("INSERT INTO supply_row_targets (supply_row_id, supply_target_id,"
                + " display_order) VALUES (?, ?, 0) RETURNING id", Long.class, second, youth);
        quota(announcement, 0, secondCell);
        quota(announcement, 5, firstCell, secondCell);

        JsonNode content = response(fromExtraction(announcement, SHA, null, 0).andExpect(status().isOk()))
                .path("ruleSet").path("content");

        assertThat(texts(content.path("options"), "supplyUnitId")).containsExactly("unit-" + first);
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

        // 공급표에 칸이 하나도 없는 공급행은 대상 구분이 없는 행이 아니다. 아무 경로도 잇지 않는다.
        long allTargetsExcluded = notice(true);
        jdbc.update("DELETE FROM supply_row_targets WHERE supply_row_id IN"
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

    /** 공급표 칸 하나와 그 칸만의 당첨 수. */
    private void cell(long announcement, long row, String label, int order, int winnerCount) {
        cell(announcement, row, label, "", order, winnerCount);
    }

    private void cell(long announcement, long row, String label, String condition, int order, int winnerCount) {
        Long target = jdbc.queryForObject("""
                INSERT INTO announcement_supply_targets (announcement_id, label, application_condition, display_order,
                    admin_modified) VALUES (?, ?, ?, ?, false) RETURNING id
                """, Long.class, announcement, label, condition, order);
        Long cell = jdbc.queryForObject("INSERT INTO supply_row_targets (supply_row_id, supply_target_id, display_order)"
                + " VALUES (?, ?, ?) RETURNING id", Long.class, row, target, order);
        Long quota = jdbc.queryForObject("""
                INSERT INTO supply_quotas (announcement_id, kind, household_count, admin_modified)
                VALUES (?, 'WINNER', ?, false) RETURNING id
                """, Long.class, announcement, winnerCount);
        jdbc.update("INSERT INTO supply_quota_members (supply_quota_id, supply_row_target_id) VALUES (?, ?)", quota, cell);
    }

    private void quota(long announcement, int winnerCount, Long... cells) {
        Long quota = jdbc.queryForObject("""
                INSERT INTO supply_quotas (announcement_id, kind, household_count, admin_modified)
                VALUES (?, 'WINNER', ?, false) RETURNING id
                """, Long.class, announcement, winnerCount);
        for (Long cell : cells) {
            jdbc.update("INSERT INTO supply_quota_members (supply_quota_id, supply_row_target_id) VALUES (?, ?)",
                    quota, cell);
        }
    }

    /** 공고 하나: 공급행 1개(청년 10명, 고령자 대상), PDF 첨부, 신청자격이 든(또는 이전 워커라 없는) 성공 추출. */
    private long notice(boolean withEligibility) throws Exception {
        long announcement = announcement();
        // 공고문 보정을 적용해 정본을 확정한 공고다. 신청 단위는 정본 공급표에서 읽는다.
        jdbc.update("UPDATE announcements SET posted_date = '2026-09-30', supply_confirmed_at = now() WHERE id = ?",
                announcement);
        jdbc.update("""
                INSERT INTO announcement_attachments (announcement_id, display_order, file_name, file_type, file_url)
                VALUES (?, 0, ?, 'ANNOUNCEMENT', 'https://apply.lh.or.kr/notice.pdf')
                """, announcement, FILE);
        Long complex = jdbc.queryForObject("""
                INSERT INTO announcement_complexes (announcement_id, source_complex_name, present_in_source, created_at)
                VALUES (?, '인천석남', true, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, announcement);
        Long row = jdbc.queryForObject("""
                INSERT INTO supply_rows (announcement_id, announcement_complex_id, display_order, housing_type_name,
                    supply_category, exclusive_area)
                VALUES (?, ?, 0, '21A', 'NEW_SUPPLY', 21.00) RETURNING id
                """, Long.class, announcement, complex);
        cell(announcement, row, "청년", 0, 10);
        cell(announcement, row, "고령자(주거약자용)", 1, 0);
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
        // SQL로 바꾼 확정 시각을 공고 엔티티가 읽게 영속성 컨텍스트를 비운다.
        entities.clear();
        return announcement;
    }

    /** 신청자격 경로 하나: 워커가 이은 공급표 대상 키와 판정을 막지 않는 요건 하나. */
    private static Map<String, Object> route(String name, List<String> keys, String path) {
        return Map.of("target", name, "supplyTarget", "청년", "supplyTargets", List.of("청년"), "supplyTargetKeys", keys,
                "requirements", List.of(Map.of("kind", "OTHER", "asWritten", "입주 전까지 청약통장 가입")),
                "qualifications", List.of(), "summary", name);
    }

    /** 공고의 성공 추출을 이 신청자격 경로(eligibility 안의 칸)와 공급표 대상으로 바꾼다. 공통 요건은 무주택세대구성원이다. */
    private void replaceExtraction(long announcement, Map<String, Object> eligibility, List<Map<String, Object>> targets)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("common", Map.of("requirements", List.of(Map.of(
                        "kind", "NO_HOUSE", "scope", "HOUSEHOLD", "asWritten", "무주택세대구성원")),
                "qualifications", List.of(), "summary", "무주택세대구성원"), "exclusions", List.of()));
        body.putAll(eligibility);
        String extraction = json.writeValueAsString(Map.of("data", Map.of(
                        "announcement", Map.of("name", "인천석남 행복주택"), "eligibility", body,
                        "supplySheet", Map.of("targets", targets)),
                "evidence", List.of(Map.of("path", "eligibility.common.requirements[0]", "page", 3,
                        "quote", "무주택세대구성원"))));
        jdbc.update("UPDATE ai_jobs SET result = jsonb_set(result, '{json}', to_jsonb(?::text)) WHERE announcement_id = ?",
                extraction, announcement);
        entities.clear();
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
