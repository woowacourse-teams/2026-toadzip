package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.AgencySupplyLine;
import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementAttachment;
import com.toadzip.backend.announcement.domain.AnnouncementComplex;
import com.toadzip.backend.announcement.domain.AnnouncementSchedule;
import com.toadzip.backend.announcement.domain.AttachmentType;
import com.toadzip.backend.announcement.domain.ScheduleType;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyRowSeed;
import com.toadzip.backend.announcement.domain.SupplySheetSeed;
import com.toadzip.backend.announcement.fixture.SupplySheetInputs;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
class AnnouncementJsonCorrectionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void lease() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(call ->
                org.mockito.Mockito.mock(IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 기존_공고의_JSON_갱신은_미리보기_후_선택한_기본정보와_공급정보만_함께_저장한다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        String identifier = announcement.getSourceAnnouncementIdentifier();
        var request = request(id);
        ((ObjectNode) sheet(request).get("quotas").get(0)).put("count", 3);
        ((ObjectNode) sheet(request).get("housingTypes").get(0)).put("exclusiveArea", 36.5).put("supplyArea", 49.8);
        var preview = preview(id, request);
        assertThat(announcement.getName()).isNotEqualTo("JSON으로 확인한 공고");
        assertThat(winnerCounts(id)).containsExactly(10);
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.data.name").value("JSON으로 확인한 공고"));
        entities.clear();
        var stored = entities.find(Announcement.class, id);
        assertThat(stored.getSourceAnnouncementIdentifier()).isEqualTo(identifier);
        assertThat(stored.getOriginalUrl()).isEqualTo(announcement.getOriginalUrl());
        assertThat(winnerCounts(id)).containsExactly(3);
        assertThat(jdbc.queryForObject("SELECT exclusive_area FROM supply_rows WHERE announcement_id=?",
                BigDecimal.class, id)).isEqualByComparingTo("36.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcements", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class)).isZero();
        // 공급표·기본정보 변경을 함께 저장해도 이력은 한 건이고, 작업 번호는 그 이력에 연결된다.
        assertThat(jdbc.queryForList("SELECT action FROM admin_data_changes WHERE resource_id=?", String.class, id))
                .containsExactly("CORRECT_FROM_PDF");
        assertThat(jdbc.queryForObject("SELECT change_id FROM admin_supply_corrections WHERE announcement_id=?", Long.class, id))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM admin_data_changes WHERE resource_id=?", Long.class, id));
        var history = json.readTree(jdbc.queryForObject("SELECT after_value FROM admin_data_changes WHERE resource_id=?",
                String.class, id));
        assertThat(history.path("sheet").path("quotas").get(0).path("count").asInt()).isEqualTo(3);
        assertThat(history.has("rows")).isFalse();
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isConflict());
    }

    @Test
    void PDF_보정을_저장하면_이_공고에_남은_정제와_보강_실패를_모두_해결로_바꾼다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        String identifier = announcement.getSourceAnnouncementIdentifier();
        for (var row : new String[][] {
                {"myhome_announcement_mapping_failures", identifier + ":1", "HOUSING_TYPE_NOT_FOUND"},
                {"myhome_announcement_mapping_failures", identifier + ":2", "AMBIGUOUS_HOUSING_TYPE"},
                {"lh_announcement_enrichment_failures", identifier + ":lh", "HOUSING_TYPE_NOT_FOUND"},
                {"myhome_announcement_mapping_failures", "other:1", "HOUSING_TYPE_NOT_FOUND"},
                {"lh_announcement_enrichment_failures", "other:lh", "HOUSING_TYPE_NOT_FOUND"}}) {
            String owner = row[1].startsWith("other") ? "other" : identifier;
            jdbc.update(("INSERT INTO %s (source_key, source_announcement_identifier, reason, detail, status, "
                    + "occurred_at, last_occurred_at, occurrence_count, recurrence_count) "
                    + "VALUES (?, ?, ?, '실패', 'PENDING', now(), now(), 1, 0)").formatted(row[0]),
                    row[1], owner, row[2]);
        }
        var request = request(id);
        ((ObjectNode) sheet(request).get("quotas").get(0)).put("count", 3);
        apply(id, request, preview(id, request).path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush();

        assertThat(jdbc.queryForList("""
                SELECT status FROM myhome_announcement_mapping_failures WHERE source_announcement_identifier = ?
                UNION ALL
                SELECT status FROM lh_announcement_enrichment_failures WHERE source_announcement_identifier = ?
                """, String.class, identifier, identifier)).containsOnly("RESOLVED").hasSize(3);
        assertThat(jdbc.queryForList("""
                SELECT last_resolved_by FROM myhome_announcement_mapping_failures
                WHERE source_announcement_identifier = ?
                UNION ALL
                SELECT last_resolved_by FROM lh_announcement_enrichment_failures
                WHERE source_announcement_identifier = ?
                """, String.class, identifier, identifier)).containsOnly("admin").hasSize(3);
        assertThat(jdbc.queryForList("""
                SELECT status FROM myhome_announcement_mapping_failures WHERE source_announcement_identifier = 'other'
                UNION ALL
                SELECT status FROM lh_announcement_enrichment_failures WHERE source_announcement_identifier = 'other'
                """, String.class)).containsOnly("PENDING").hasSize(2);
    }

    @Test
    void 바뀐_것이_없는_PDF_보정은_거절하지_않고_값은_그대로_둔_채_공고문으로_확인한_것으로_남긴다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        jdbc.update("UPDATE announcements SET supply_recheck_required = true WHERE id = ?", id);
        var request = request(id);
        ((ArrayNode) request.path("fields")).removeAll();

        var preview = preview(id, request);
        assertThat(preview.path("unchanged").asBoolean()).isTrue();
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.data.name").value(announcement.getName()));

        // 정본을 확정한 공고를 공고문으로 확인했으니 공급 재확인 표시를 끈다.
        assertThat(jdbc.queryForMap("SELECT name, supply_confirmed_at IS NOT NULL AS confirmed, supply_recheck_required"
                + " FROM announcements WHERE id = ?", id))
                .containsEntry("name", announcement.getName()).containsEntry("confirmed", true)
                .containsEntry("supply_recheck_required", false);
        assertThat(jdbc.queryForList("SELECT action FROM admin_data_changes WHERE resource_id = ?", String.class, id))
                .containsExactly("CONFIRM_FROM_PDF");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_supply_corrections WHERE announcement_id = ?", Long.class, id))
                .isZero();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            기본                    | 세종 국민임대
            관리자 수정             | 알림 테스트 단지
            관리자 등록             | 알림 테스트 단지
            주소 없음               | 알림 테스트 단지
            다른 공고 다른 이름     | 알림 테스트 단지
            다른 공고 같은 이름     | 세종 국민임대
            다른 공고 원천에서 빠짐 | 세종 국민임대
            한 공고 두 이름         | 알림 테스트 단지
            """)
    void PDF_보정은_연결한_단지_이름을_기관이_알려_준_공고_단지명이_하나일_때만_바꾸고_관리자가_정한_이름은_두다(
            String scenario, String expected) throws Exception {
        long id = linkedRow();
        long complexId = jdbc.queryForObject("SELECT housing_complex_id FROM announcement_complexes WHERE announcement_id = ?",
                Long.class, id);
        String address = "서울특별시 중구 세종대로 110";
        if (scenario.equals("한 공고 두 이름")) {
            // 같은 공고의 다른 공고 단지도 이 단지를 가리키고 기관 원천 단지명이 다르다.
            var complex = entities.find(HousingComplex.class, complexId);
            var row = SupplyRowSeed.create(entities, entities.find(Announcement.class, id), complex, type(complex, "36A", "36.0000"),
                    1, "세종 국민임대 2단지", "36A", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY);
            entities.persist(row);
            new SupplySheetSeed(entities).target(row, "일반", "공고문 확인", 5, null, new BigDecimal("100000000"),
                    new BigDecimal("200000"), null, 0);
            entities.flush();
            jdbc.update("UPDATE announcement_complexes SET road_address = ? WHERE announcement_id = ?", address, id);
        }
        // 기관 원천이 알려 준 공고 단지는 주소가 있다. 주소가 없으면 공급표 단지명으로 만든 공고 단지다.
        jdbc.update("UPDATE announcement_complexes SET source_complex_name = '세종 국민임대', road_address = ?"
                + " WHERE announcement_id = ? AND source_complex_name = '알림 테스트 단지'", scenario.equals("주소 없음") ? null : address, id);
        switch (scenario) {
            case "관리자 수정" -> jdbc.update("UPDATE housing_complexes SET admin_modified = true WHERE id = ?", complexId);
            case "관리자 등록" -> jdbc.update("UPDATE housing_complexes SET source_complex_identifier = ? WHERE id = ?",
                    HousingComplex.ADMIN_SOURCE_PREFIX + UUID.randomUUID(), complexId);
            case "다른 공고 다른 이름", "다른 공고 같은 이름", "다른 공고 원천에서 빠짐" -> {
                long other = new NotificationFixtures(entities, jdbc).announcement().getId();
                jdbc.update("""
                        INSERT INTO announcement_complexes (announcement_id, source_complex_name, road_address, present_in_source,
                            housing_complex_id, link_method) VALUES (?, ?, ?, ?, ?, 'AUTO')
                        """, other, scenario.equals("다른 공고 같은 이름") ? "세종 국민임대" : "세종 행복주택", address,
                        !scenario.equals("다른 공고 원천에서 빠짐"), complexId);
            }
            default -> { }
        }
        entities.clear();
        var request = request(id);
        ((ArrayNode) request.path("fields")).removeAll();

        var preview = preview(id, request);
        boolean renames = expected.equals("세종 국민임대");
        assertThat(keys(preview.path("warnings"), null)).filteredOn(warning -> warning.startsWith("단지 이름"))
                .isEqualTo(renames ? List.of("단지 이름 '알림 테스트 단지'을(를) 공고의 단지명 '세종 국민임대'(으)로 바꿉니다.") : List.of());
        if (!scenario.equals("한 공고 두 이름")) {
            assertThat(preview.path("unchanged").asBoolean()).isEqualTo(!renames);
        }
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT name FROM housing_complexes WHERE id = ?", String.class, complexId))
                .isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT admin_modified FROM housing_complexes WHERE id = ?", Boolean.class, complexId))
                .isEqualTo(scenario.equals("관리자 수정"));
        assertThat(jdbc.queryForList("SELECT actor FROM admin_data_changes WHERE resource_type = 'COMPLEX' AND resource_id = ?"
                + " AND action = 'RENAME_FROM_ANNOUNCEMENT'", String.class, complexId))
                .isEqualTo(renames ? List.of("admin") : List.of());
    }

    @Test
    void 확정_전_공고는_공급표_없이_기본정보만_적용하거나_변경_없음을_확인해도_확정하지_않고_준비_데이터를_보인다() throws Exception {
        var announcement = new NotificationFixtures(entities, jdbc).announcement();
        var complex = AnnouncementComplex.observe(announcement, "행복단지", null, null);
        entities.persist(complex);
        entities.persist(AgencySupplyLine.create(announcement, AgencyCode.LH, new AgencySupplyLine.Values("LH:1", 0,
                complex, "행복단지", "26A", null, null, null, 7, null, null, null, SupplyCategory.NEW_SUPPLY)));
        entities.flush();
        long id = announcement.getId();

        var named = request(id);
        assertThat(named.at("/correction/sheet/housingTypes")).isEmpty();
        apply(id, named, preview(id, named).path("confirmationToken").asText()).andExpect(status().isOk());
        var unchanged = request(id);
        ((ArrayNode) unchanged.path("fields")).removeAll();
        apply(id, unchanged, preview(id, unchanged).path("confirmationToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.summary.announcement.supplyConfirmed").value(false));
        entities.flush(); entities.clear();

        // 확정하지 않았으니 공개 조회(AnnouncementSupplies)는 계속 준비 줄을 보인다.
        assertThat(jdbc.queryForMap("SELECT name, supply_confirmed_at FROM announcements WHERE id = ?", id))
                .containsEntry("name", "JSON으로 확인한 공고").containsEntry("supply_confirmed_at", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agency_supply_lines WHERE announcement_id = ?", Long.class, id))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_rows WHERE announcement_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void 공급표_항목을_순서만_바꿔_보내도_공급정보_변경_없음으로_보고_다른_항목은_저장한다() throws Exception {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement();
        var complex = fixtures.complex("11140", "1114010100");
        int order = 0;
        for (var name : new String[] {"26A", "36A"}) {
            var row = SupplyRowSeed.create(entities, announcement, complex, type(complex, name, name.substring(0, 2) + ".5000"), order++, complex.getName(), name, "1114010100100010000", null,
                    SupplyCategory.NEW_SUPPLY);
            entities.persist(row);
            new SupplySheetSeed(entities).row(row, 10);
        }
        entities.flush(); entities.clear();
        long id = announcement.getId();
        var request = request(id);
        for (var name : List.of("housingTypes", "cells", "quotas")) {
            var items = (ArrayNode) sheet(request).get(name);
            assertThat(items).hasSize(2);
            sheet(request).set(name, json.createArrayNode().add(items.get(1)).add(items.get(0)));
        }

        var preview = preview(id, request);
        assertThat(preview.path("supply").isNull() || preview.path("supply").isMissingNode()).isTrue();
        assertThat(preview.path("unchanged").asBoolean()).isFalse();
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.data.name").value("JSON으로 확인한 공고"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_supply_corrections WHERE announcement_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void PDF_보정은_관리자가_고친_값과_PDF_값이_같을_때만_관리자_수정_표시를_남긴다() throws Exception {
        long id = linkedRow();
        // 공급정보 편집표에서 모집수와 월 임대료를 고치면 둘 다 관리자 수정이다.
        var workbench = workbenchRequest(id);
        ((ObjectNode) workbench.get("sheet").get("quotas").get(0)).put("count", 9);
        ((ObjectNode) workbench.get("sheet").get("leaseTerms").get(0)).put("monthlyRent", "0");
        var workbenchPreview = json.readTree(mvc.perform(post(workbenchRoot(id) + "/preview")
                        .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                        .content(workbench.toString())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("data");
        var body = json.createObjectNode().set("correction", workbench);
        body.put("confirmationToken", workbenchPreview.get("confirmationToken").asText());
        mvc.perform(post(workbenchRoot(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(body.toString())).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var edited = data("/api/admin/announcements/" + id + "/json-corrections").path("supply").path("sheet");
        assertThat(edited.at("/quotas/0/adminModified").asBoolean()).isTrue();
        assertThat(edited.at("/leaseTerms/0/adminModified").asBoolean()).isTrue();

        // PDF가 같은 모집수(9)를 주면 표시를 남기고, 다른 월 임대료를 주면 PDF 값으로 덮고 표시를 지운다.
        var request = request(id);
        ((ObjectNode) sheet(request).get("leaseTerms").get(0)).put("monthlyRent", "150000");
        apply(id, request, preview(id, request).path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var after = data("/api/admin/announcements/" + id + "/json-corrections").path("supply").path("sheet");
        assertThat(after.at("/quotas/0/count").asInt()).isEqualTo(9);
        assertThat(after.at("/quotas/0/adminModified").asBoolean()).isTrue();
        assertThat(after.at("/leaseTerms/0/monthlyRent").asText()).isEqualTo("150000");
        assertThat(after.at("/leaseTerms/0/adminModified").asBoolean()).isFalse();
        assertThat(after.at("/housingTypes/0/adminModified").asBoolean()).isFalse();
        // 저장된 개체에 맞춘 주택형·공급대상·칸은 ID가 그대로다.
        for (var name : List.of("housingTypes", "targets", "cells")) {
            assertThat(keys(after.get(name))).isEqualTo(keys(edited.get(name)));
        }
    }

    @Test
    void 대상끼리_함께_쓰는_예비자_수를_묶음_하나로_저장하고_작업_화면과_공개_상세에_함께_보인다() throws Exception {
        long id = incomeSplitRow();
        var request = request(id);
        shared(request, "RESERVE", 20);

        var preview = preview(id, request);
        assertThat(preview.path("unchanged").asBoolean()).isFalse();
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush(); entities.clear();

        assertThat(sheet(id)).contains("Q:RESERVE:20:청년(소득 없음),청년(소득 있음)");
        var stored = data("/api/admin/announcements/" + id + "/json-corrections").path("supply").path("sheet");
        var cells = keys(stored.get("cells"));
        assertThat(stored.get("quotas")).filteredOn(quota -> quota.get("kind").asText().equals("RESERVE"))
                .singleElement().satisfies(quota -> {
                    assertThat(quota.get("count").asInt()).isEqualTo(20);
                    assertThat(keys(quota.get("members"), null)).isEqualTo(cells);
                });
        mvc.perform(get("/api/v1/announcements/{announcementId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].waitlistCount").doesNotExist())
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].sharedWaitlistCount.count").value(20))
                .andExpect(jsonPath("$.data.supplyRows[0].targets[1].sharedWaitlistCount.targets[0]").value("청년(소득 없음)"))
                .andExpect(jsonPath("$.data.supplyRows[0].targets[1].sharedWaitlistCount.targets[1]").value("청년(소득 있음)"))
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].sharedSupplyHouseholdCount").doesNotExist());

        // 보기 그대로 다시 보내면(묶음 key 없이) 바뀐 것이 없어 공급표를 다시 쓰지 않는다. 묶음 id도 그대로다.
        long quotaId = jdbc.queryForObject("SELECT id FROM supply_quotas WHERE kind = 'RESERVE'", Long.class);
        var again = request(id);
        ((ArrayNode) again.path("fields")).removeAll();
        var same = preview(id, again);
        assertThat(same.path("unchanged").asBoolean()).isTrue();
        apply(id, again, same.path("confirmationToken").asText()).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT id FROM supply_quotas WHERE kind = 'RESERVE'", Long.class)).containsExactly(quotaId);
        // 묶음을 풀고 칸마다 수를 주면 칸마다 묶음이 하나씩 남는다.
        var split = request(id);
        ((ArrayNode) split.path("fields")).removeAll();
        each(split, "RESERVE", 10);
        apply(id, split, preview(id, split).path("confirmationToken").asText()).andExpect(status().isOk());
        assertThat(sheet(id)).contains("Q:RESERVE:10:청년(소득 없음)", "Q:RESERVE:10:청년(소득 있음)")
                .doesNotContain("Q:RESERVE:20:청년(소득 없음),청년(소득 있음)");
    }

    @Test
    void PDF_보정을_다시_적용해도_표기와_신청_조건이_같은_공급대상은_ID가_그대로다() throws Exception {
        long id = incomeSplitRow();
        var first = request(id);
        each(first, "RESERVE", 10);
        apply(id, first, preview(id, first).path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush();
        var ids = targetIds(id);
        assertThat(ids).hasSize(2);

        var second = request(id);
        ((ArrayNode) second.path("fields")).removeAll();
        each(second, "RESERVE", 7);
        ((ObjectNode) sheet(second).get("targets").get(1)).put("label", "신혼부부");
        apply(id, second, preview(id, second).path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush();

        var after = targetIds(id);
        assertThat(after).containsEntry("청년(소득 없음)", ids.get("청년(소득 없음)"))
                .doesNotContainKey("청년(소득 있음)").containsKey("신혼부부");
        assertThat(sheet(id)).contains("Q:RESERVE:7:청년(소득 없음)", "Q:RESERVE:7:신혼부부");
        mvc.perform(get("/api/v1/announcements/{announcementId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplyRows[0].targets[1].target").value("신혼부부"))
                .andExpect(jsonPath("$.data.supplyRows[0].targets[1].waitlistCount").value(7));
    }

    /**
     * PDF 보정 화면은 PDF가 표기를 바꾼 대상을 새 대상(새 키)으로 보내고, 그대로 둘 다른 PDF 단지 칸이 쓰는 지금 대상은 저장된 키·표기
     * 그대로 함께 보낸다. 서버는 두 대상을 따로 저장하고 지금 대상과 그 칸의 ID를 지킨다.
     */
    @Test
    void 표기를_바꾼_새_대상과_같은_뜻의_지금_대상을_함께_보내면_둘_다_저장하고_지금_대상과_칸의_ID는_그대로다() throws Exception {
        long id = incomeSplitRow();
        var request = request(id);
        var sheet = sheet(request);
        var saved = sheet.get("targets").get(1);
        var savedCell = sheet.get("cells").get(1);
        assertThat(saved.path("label").asText()).isEqualTo("청년(소득 있음)");
        long savedCellId = savedCell.path("id").asLong();
        ((ArrayNode) sheet.get("targets")).addObject().put("key", "new:g9").putNull("id").put("label", "청년 (소득 있음)")
                .put("applicationCondition", "").put("displayOrder", 2);
        ((ArrayNode) sheet.get("cells")).addObject().put("key", "new:c9").putNull("id")
                .put("housingType", savedCell.path("housingType").asText()).put("target", "new:g9").put("displayOrder", 2);
        apply(id, request, preview(id, request).path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush();

        assertThat(targetIds(id)).containsEntry("청년(소득 있음)", saved.path("id").asLong()).containsKey("청년 (소득 있음)");
        assertThat(jdbc.queryForObject("SELECT c.id FROM supply_row_targets c JOIN announcement_supply_targets st"
                + " ON st.id = c.supply_target_id WHERE st.announcement_id = ? AND st.label = '청년(소득 있음)'", Long.class, id))
                .isEqualTo(savedCellId);
    }

    @Test
    void 모집수와_예비자_수를_함께_쓰는_대상에서_예비자_묶음만_풀면_모집수_묶음은_그대로_남는다() throws Exception {
        long id = incomeSplitRow();
        var request = request(id);
        shared(request, "WINNER", 10);
        shared(request, "RESERVE", 20);
        apply(id, request, preview(id, request).path("confirmationToken").asText()).andExpect(status().isOk());

        var split = request(id);
        ((ArrayNode) split.path("fields")).removeAll();
        each(split, "RESERVE", 10);
        apply(id, split, preview(id, split).path("confirmationToken").asText()).andExpect(status().isOk());

        // 수량 묶음은 ID 없이 종류와 멤버 칸으로 같다고 본다(계약). 모집수 묶음은 두 칸이 함께 쓰는 10 그대로다.
        assertThat(sheet(id)).filteredOn(line -> line.startsWith("Q:")).containsExactlyInAnyOrder(
                "Q:WINNER:10:청년(소득 없음),청년(소득 있음)", "Q:RESERVE:10:청년(소득 없음)", "Q:RESERVE:10:청년(소득 있음)");
        mvc.perform(get("/api/v1/announcements/{announcementId}", id))
                .andExpect(status().isOk())
                // 행의 모든 대상이 함께 쓰는 모집수는 행 모집수로 보인다.
                .andExpect(jsonPath("$.data.supplyRows[0].totalSupplyHouseholdCount").value(10))
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].sharedSupplyHouseholdCount").doesNotExist())
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].sharedWaitlistCount").doesNotExist())
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].waitlistCount").value(10));
    }

    @Test
    void 공고문_JSON에_공급행이_없어도_PDF_보정은_공급표로_저장한다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        var request = request(id);
        ((ObjectNode) request.get("document")).putArray("supplyRows");
        ((ObjectNode) sheet(request).get("quotas").get(0)).put("count", 4);

        apply(id, request, preview(id, request).path("confirmationToken").asText()).andExpect(status().isOk());

        assertThat(winnerCounts(id)).containsExactly(4);
    }

    @Test
    void 옛_공급행_모양으로_보낸_PDF_보정은_거절한다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        var request = request(id);
        ((ObjectNode) request.get("correction")).putArray("rows");

        mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections/preview")
                        .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                        .content(request.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("rows")));
    }

    /** 칸마다 kind 수를 따로 둔다. 그 kind의 옛 묶음은 뺀다. */
    private static void each(ObjectNode request, String kind, int count) {
        var quotas = withoutKind(request, kind);
        sheet(request).get("cells").forEach(cell -> quotas.addObject().put("kind", kind).put("count", count)
                .putArray("members").add(cell.get("key").asText()));
    }

    /** 모든 칸이 kind 수 하나를 함께 쓴다. 그 kind의 옛 묶음은 뺀다. */
    private static void shared(ObjectNode request, String kind, int count) {
        var members = withoutKind(request, kind).addObject().put("kind", kind).put("count", count).putArray("members");
        sheet(request).get("cells").forEach(cell -> members.add(cell.get("key").asText()));
    }

    private static ArrayNode withoutKind(ObjectNode request, String kind) {
        var quotas = sheet(request).withArray("quotas");
        for (int index = quotas.size() - 1; index >= 0; index--) {
            if (quotas.get(index).get("kind").asText().equals(kind)) { quotas.remove(index); }
        }
        return quotas;
    }

    private static ObjectNode sheet(ObjectNode request) {
        return (ObjectNode) request.path("correction").get("sheet");
    }

    private static List<String> keys(JsonNode items) {
        return keys(items, "key");
    }

    private static List<String> keys(JsonNode items, String field) {
        var result = new java.util.ArrayList<String>();
        items.forEach(item -> result.add(field == null ? item.asText() : item.get(field).asText()));
        return result;
    }

    private List<Integer> winnerCounts(long id) {
        return jdbc.queryForList("SELECT household_count FROM supply_quotas WHERE announcement_id = ? AND kind = 'WINNER'",
                Integer.class, id);
    }

    private java.util.Map<String, Long> targetIds(long id) {
        var ids = new java.util.LinkedHashMap<String, Long>();
        jdbc.query("SELECT label, id FROM announcement_supply_targets WHERE announcement_id = ? ORDER BY display_order",
                (java.sql.ResultSet result) -> { ids.put(result.getString(1), result.getLong(2)); }, id);
        return ids;
    }

    /** 공고 공급표 전체를 ID 없이 읽는다: 공급대상, 칸, 수량 묶음과 임대조건의 멤버(대상 표기). */
    private List<String> sheet(long id) {
        return jdbc.queryForList("""
                SELECT 'T:' || st.label || '/' || st.application_condition || '/' || st.id FROM announcement_supply_targets st
                WHERE st.announcement_id = ?
                UNION ALL
                SELECT 'C:' || c.supply_row_id || ':' || st.label FROM supply_row_targets c
                JOIN announcement_supply_targets st ON st.id = c.supply_target_id WHERE st.announcement_id = ?
                UNION ALL
                SELECT 'Q:' || q.kind || ':' || q.household_count || ':'
                       || string_agg(st.label, ',' ORDER BY st.label)
                FROM supply_quotas q JOIN supply_quota_members m ON m.supply_quota_id = q.id
                JOIN supply_row_targets c ON c.id = m.supply_row_target_id
                JOIN announcement_supply_targets st ON st.id = c.supply_target_id
                WHERE q.announcement_id = ? GROUP BY q.id, q.kind, q.household_count
                UNION ALL
                SELECT 'L:' || l.rental_deposit || '/' || l.monthly_rent || ':' || string_agg(st.label, ',' ORDER BY st.label)
                FROM lease_terms l JOIN lease_term_members m ON m.lease_terms_id = l.id
                JOIN supply_row_targets c ON c.id = m.supply_row_target_id
                JOIN announcement_supply_targets st ON st.id = c.supply_target_id
                WHERE l.announcement_id = ? GROUP BY l.id, l.rental_deposit, l.monthly_rent
                ORDER BY 1""", String.class, id, id, id, id);
    }

    /** 공고문이 소득 유무로 나눈 청년 두 대상이 임대조건을 함께 쓰는 공급행(공고 기본정보는 그대로 둔다). */
    private long incomeSplitRow() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement();
        var complex = fixtures.complex("11140", "1114010100");
        var row = SupplyRowSeed.create(entities, announcement, complex, type(complex, "36A", "36.5000"), 0,
                complex.getName(), "36A", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY);
        entities.persist(row);
        int order = 0;
        for (var target : new String[] {"청년(소득 없음)", "청년(소득 있음)"}) {
            new SupplySheetSeed(entities).target(row, target, null, null, null, new BigDecimal("13780000"),
                    new BigDecimal("166640"), null, order++);
        }
        entities.flush(); entities.clear();
        return announcement.getId();
    }

    /** 단지 주택형에 연결된 공급행 하나와 일반 공급대상 하나(모집 10, 임대조건 1억/20만). */
    private long linkedRow() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement();
        var complex = fixtures.complex("11140", "1114010100");
        var row = SupplyRowSeed.create(entities, announcement, complex, type(complex, "26", "26.4673"), 0,
                complex.getName(), "26", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY);
        entities.persist(row);
        new SupplySheetSeed(entities).target(row, "일반", "공고문 확인", 10, null, new BigDecimal("100000000"),
                new BigDecimal("200000"), null, 0);
        entities.flush(); entities.clear();
        return announcement.getId();
    }

    private HousingType type(HousingComplex complex, String name, String area) {
        var type = HousingType.createFromMyHome(complex, UUID.randomUUID().toString(), name, new BigDecimal(area), null);
        entities.persist(type); entities.flush();
        return type;
    }

    private Announcement fixture() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement();
        fixtures.link(announcement, null);
        entities.flush();
        return announcement;
    }

    @Test
    void 선택한_일정과_첨부만_갱신하고_기존_다른_항목과_재정제_보호를_유지한다() throws Exception {
        var announcement = fixture();
        var event = AnnouncementSchedule.createFromSource(
                announcement, "LH:old:event", ScheduleType.APPLICATION,
                "기존 접수", LocalDateTime.of(2026, 10, 20, 9, 0),
                LocalDateTime.of(2026, 10, 21, 18, 0), 0);
        var attachment = AnnouncementAttachment.createFromSource(
                announcement, "LH:old:file", "기존 공고문.pdf",
                AttachmentType.ANNOUNCEMENT,
                "https://example.com/old.pdf", 0);
        var kept = AnnouncementAttachment.create(announcement,
                "기존 참고.pdf", AttachmentType.REFERENCE,
                "https://example.com/reference.pdf", 1);
        entities.persist(event); entities.persist(attachment); entities.persist(kept); entities.flush();
        var request = request(announcement.getId());
        request.putArray("fields");
        request.withArray("schedules").addObject().put("inputIndex", 0).put("existingId", event.getId());
        request.withArray("attachments").addObject().put("inputIndex", 0).put("existingId", attachment.getId());
        var preview = preview(announcement.getId(), request);
        apply(announcement.getId(), request, preview.path("confirmationToken").asText()).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var storedEvent = entities.find(AnnouncementSchedule.class, event.getId());
        var storedAttachment = entities.find(AnnouncementAttachment.class,
                attachment.getId());
        assertThat(storedEvent.getName()).isEqualTo("인터넷 접수");
        assertThat(storedEvent.isAdminModified()).isTrue();
        assertThat(storedAttachment.getFileName()).isEqualTo("공고문.pdf");
        assertThat(storedAttachment.isAdminModified()).isTrue();
        assertThat(storedAttachment.updateFromSource("덮어쓸 공고.pdf", storedAttachment.getFileType(),
                "https://example.com/overwrite.pdf", 0)).isFalse();
        assertThat(storedEvent.updateFromSource(storedEvent.getScheduleType(), "덮어쓸 일정",
                storedEvent.getStartAt(), storedEvent.getEndAt(), 0, null)).isFalse();
        assertThat(entities.find(AnnouncementAttachment.class,
                kept.getId()).getFileName()).isEqualTo("기존 참고.pdf");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcement_attachments WHERE announcement_id=?",
                Long.class, announcement.getId())).isEqualTo(2);
    }

    @Test
    void 미리보기_뒤_첨부가_바뀌면_기본정보와_공급정보도_저장하지_않는다() throws Exception {
        var announcement = fixture();
        var request = request(announcement.getId());
        ((ObjectNode) sheet(request).get("quotas").get(0)).put("count", 3);
        var preview = preview(announcement.getId(), request);
        entities.persist(AnnouncementAttachment.create(announcement,
                "늦게 수집한 자료.pdf", AttachmentType.REFERENCE,
                "https://example.com/changed.pdf", 0));
        entities.flush();
        apply(announcement.getId(), request, preview.path("confirmationToken").asText())
                .andExpect(status().isConflict());
        assertThat(announcement.getName()).isNotEqualTo("JSON으로 확인한 공고");
        assertThat(winnerCounts(announcement.getId())).containsExactly(10);
    }

    @Test
    void 다른_공고의_첨부나_추출_미확인_항목은_선택_적용하지_못한다() throws Exception {
        var announcement = fixture();
        var request = request(announcement.getId());
        request.withArray("attachments").addObject().put("inputIndex", 0).put("existingId", 999999);
        mvc.perform(post("/api/admin/announcements/" + announcement.getId() + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isBadRequest());
        request.putArray("attachments");
        ((ObjectNode) request.get("document")).withArray("unresolvedFields").addObject()
                .put("path", "announcement.name").put("reason", "추출 미확인");
        mvc.perform(post("/api/admin/announcements/" + announcement.getId() + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isBadRequest());
        assertThat(announcement.getName()).isNotEqualTo("JSON으로 확인한 공고");
    }

    @Test
    void 확인된_단지별_접수일정은_JSON_기본기간으로_덮어쓰지_않는다() throws Exception {
        var announcement = fixture();
        announcement.confirmApplicationPeriod(announcement.getApplicationStartDate(), announcement.getApplicationEndDate());
        entities.flush();
        var request = request(announcement.getId());
        request.withArray("fields").add("applicationStartDate");
        mvc.perform(post("/api/admin/announcements/" + announcement.getId() + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isBadRequest());
        request.putArray("fields").add("name");
        var preview = preview(announcement.getId(), request);
        apply(announcement.getId(), request, preview.path("confirmationToken").asText()).andExpect(status().isOk());
        assertThat(announcement.isApplicationScheduleReviewed()).isTrue();
        assertThat(announcement.getApplicationStartDate()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void 접수처를_고르면_화면이_보낸_접수처_목록으로_통째로_바꾸고_예전_미확인_경로가_있으면_거절한다() throws Exception {
        var announcement = fixture();
        long id = announcement.getId();
        var request = request(id);
        var document = (ObjectNode) request.get("document");
        document.remove("receptionPlace");
        var places = document.putArray("receptionPlaces");
        places.addObject().put("name", "LH 청약플러스").put("method", "ONLINE").putNull("address").putNull("contact")
                .put("url", "https://apply.lh.or.kr").put("guidance", "모바일 앱으로도 신청할 수 있습니다");
        places.addObject().put("name", "행복주택 견본주택").put("method", "VISIT").put("address", "서울시 강남구 1")
                .put("contact", "02-000-0000").putNull("url").put("guidance", "인터넷 취약계층만 현장 접수");
        request.putArray("fields").add("receptionPlaces");
        var preview = preview(id, request);
        apply(id, request, preview.path("confirmationToken").asText()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail.data.receptionPlaces.length()").value(2))
                .andExpect(jsonPath("$.data.detail.data.receptionPlaces[1].guidance").value("인터넷 취약계층만 현장 접수"));
        assertThat(jdbc.queryForList("SELECT method FROM announcement_reception_places WHERE announcement_id=? ORDER BY display_order",
                String.class, id)).containsExactly("ONLINE", "VISIT");

        // 예전 모양 document의 미확인 경로(receptionPlace.contact)도 첫 접수처 경로로 읽어 접수처 적용을 막는다.
        var legacy = request(id);
        ((ObjectNode) legacy.get("document")).withArray("unresolvedFields").addObject()
                .put("path", "receptionPlace.contact").put("reason", "연락처를 확인하지 못함");
        legacy.putArray("fields").add("receptionPlaces");
        mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(legacy.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("receptionPlaces")));
    }

    @Test
    void 인증과_CSRF없이_기존_공고를_JSON으로_갱신하지_못한다() throws Exception {
        var announcement = fixture();
        mvc.perform(get("/api/admin/announcements/" + announcement.getId() + "/json-corrections"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/announcements/" + announcement.getId() + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).contentType("application/json")
                .content(request(announcement.getId()).toString())).andExpect(status().isForbidden());
    }

    private ObjectNode request(long id) throws Exception {
        var state = data("/api/admin/announcements/" + id + "/json-corrections");
        var document = (ObjectNode) json.readTree(new ClassPathResource("announcement/import-v1-valid.json").getInputStream());
        ((ObjectNode) document.get("announcement")).put("name", "JSON으로 확인한 공고");
        var request = json.createObjectNode();
        request.put("snapshotToken", state.path("snapshotToken").asText());
        request.set("document", document);
        request.putArray("fields").add("name");
        request.putArray("schedules");
        request.putArray("attachments");
        var correction = request.putObject("correction");
        correction.put("operationId", UUID.randomUUID().toString());
        correction.put("snapshotToken", state.path("supply").path("snapshotToken").asText());
        correction.put("reason", "공식 공고문에서 확인");
        correction.put("sourceUrl", "https://example.com/notice");
        correction.putNull("sourcePage");
        correction.set("sheet", SupplySheetInputs.json(state.path("supply").path("sheet")));
        return request;
    }

    /** 공급정보 편집표(/supply-corrections)에 저장된 공급표를 그대로 보내는 요청. */
    private ObjectNode workbenchRequest(long id) throws Exception {
        var workspace = data(workbenchRoot(id));
        var request = json.createObjectNode();
        request.put("operationId", UUID.randomUUID().toString());
        request.put("snapshotToken", workspace.get("snapshotToken").asText());
        request.put("reason", "공식 공고문과 대조함");
        request.put("sourceUrl", "https://example.com/notice.pdf"); request.putNull("sourcePage");
        request.set("sheet", SupplySheetInputs.json(workspace.get("sheet")));
        return request;
    }

    private String workbenchRoot(long id) { return "/api/admin/announcements/" + id + "/supply-corrections"; }

    private JsonNode data(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private JsonNode preview(long id, ObjectNode request) throws Exception {
        return json.readTree(mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections/preview")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(request.toString())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("data");
    }

    private ResultActions apply(long id, ObjectNode request, String token)
            throws Exception {
        var body = json.createObjectNode(); body.set("request", request); body.put("confirmationToken", token);
        return mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(body.toString()));
    }

}
