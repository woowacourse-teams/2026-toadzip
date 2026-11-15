package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.service.QualityComparisonService;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplySheetSeed;
import com.toadzip.backend.announcement.fixture.SupplySheetInputs;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 공급 보정 작업대(/supply-corrections). 작업실은 공고 공급표 보기를 주고, 보정 요청은 공급표 전체를 보내며 요청에 없는 개체는 지운다.
 * 픽스처는 옛 공급행·공급대상을 만들고 DB 동기화가 공급표를 만든다.
 */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
class SupplyCorrectionIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private QualityComparisonService comparisons;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void lease() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(call ->
                org.mockito.Mockito.mock(IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 작업실은_저장된_개체를_보기_key로_가리키는_공급표를_준다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();

        var sheet = workspace(id).get("sheet");

        var type = sheet.get("housingTypes").get(0);
        assertThat(type.get("key").asText()).isEqualTo("row:" + row.getId());
        assertThat(type.get("name").asText()).isEqualTo("26");
        assertThat(type.get("housingTypeId").asLong()).isEqualTo(row.getHousingType().getId());
        assertThat(type.has("newHousingType")).isFalse();
        var target = sheet.get("targets").get(0);
        assertThat(target.get("key").asText()).isEqualTo("target:" + target.get("id").asLong());
        assertThat(target.get("label").asText()).isEqualTo("일반");
        var cell = sheet.get("cells").get(0);
        String cellKey = cell.get("key").asText();
        assertThat(cellKey).isEqualTo("cell:" + cell.get("id").asLong());
        assertThat(cell.get("housingType").asText()).isEqualTo(type.get("key").asText());
        assertThat(cell.get("target").asText()).isEqualTo(target.get("key").asText());
        assertThat(sheet.get("quotas")).singleElement().satisfies(quota -> {
            assertThat(quota.get("kind").asText()).isEqualTo("WINNER");
            assertThat(quota.get("count").asInt()).isEqualTo(10);
            assertThat(quota.get("members").toString()).isEqualTo("[\"" + cellKey + "\"]");
        });
        // 금액은 원 단위 정수 문자열이다.
        assertThat(sheet.get("leaseTerms").get(0).get("rentalDeposit").asText()).isEqualTo("100000000");
        assertThat(sheet.get("leaseTerms").get(0).get("monthlyRent").asText()).isEqualTo("200000");
    }

    @Test
    void 미리보기는_저장하지_않고_적용하면_같은_개체를_고쳐_관리자_수정으로_표시하고_공개_응답에_보인다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var before = workspace(id).get("sheet");
        var request = request(id);
        type(request, 0).put("name", "26A").put("exclusiveArea", new BigDecimal("26.5195"));
        ((ObjectNode) sheet(request).get("quotas").get(0)).put("count", 9);
        ((ObjectNode) sheet(request).get("leaseTerms").get(0)).put("monthlyRent", "0");

        var preview = preview(id, request);

        assertThat(preview.at("/after/housingTypes/0/key").asText()).isEqualTo("row:" + row.getId());
        assertThat(preview.at("/after/housingTypes/0/adminModified").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_data_changes", Long.class)).isZero();
        assertThat(supplyOwner(id)).isEqualTo("LH_SOURCE");
        assertThat(workspace(id).get("sheet")).isEqualTo(before);

        apply(id, request, preview).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyApplied").value(false))
                .andExpect(jsonPath("$.data.workspace.sheet.housingTypes[0].name").value("26A"));
        entities.flush(); entities.clear();

        // 관리자가 저장한 공급정보는 공고문이 주인이라 정제가 공급표를 다시 만들지 않는다.
        assertThat(supplyOwner(id)).isEqualTo("ANNOUNCEMENT_DOCUMENT");
        var after = workspace(id).get("sheet");
        // 주택형·공급대상·칸은 같은 ID로 남는다. 신청자격 단위(unit-{공급행 ID})와 공개 응답의 행 ID가 그대로다.
        for (var list : List.of("housingTypes", "targets", "cells")) {
            assertThat(keys(after.get(list))).isEqualTo(keys(before.get(list)));
        }
        assertThat(after.at("/housingTypes/0/adminModified").asBoolean()).isTrue();
        assertThat(after.at("/housingTypes/0/exclusiveArea").decimalValue()).isEqualByComparingTo("26.5195");
        assertThat(after.at("/quotas/0/count").asInt()).isEqualTo(9);
        assertThat(after.at("/quotas/0/adminModified").asBoolean()).isTrue();
        // 공통 주택형 면적은 그대로 두고 공고 면적만 바꾼다.
        assertThat(entities.find(HousingType.class, row.getHousingType().getId()).getExclusiveArea()).isEqualByComparingTo("26.4673");
        mvc.perform(get("/api/v1/announcements/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplyRows[0].supplyRowId").value(row.getId()))
                .andExpect(jsonPath("$.data.supplyRows[0].displayHousingTypeName").value("26A"))
                .andExpect(jsonPath("$.data.supplyRows[0].exclusiveArea").value(26.5195))
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].supplyHouseholdCount").value(9))
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].monthlyRent").value(0));
        String history = jdbc.queryForObject("SELECT after_value FROM admin_data_changes WHERE action = 'CORRECT_SUPPLY'",
                String.class);
        assertThat(json.readTree(history).propertyNames()).containsExactlyInAnyOrder("sheet", "reason", "sourceUrl",
                "sourcePage", "operationId");
    }

    @Test
    void 공통_모집수는_두_칸을_멤버로_가진_묶음_하나로_저장하고_읽는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        second(row);
        var request = request(id);
        var cells = sheet(request).get("cells");
        var quotas = sheet(request).withArray("quotas");
        quotas.removeAll();
        quotas.addObject().put("kind", "WINNER").put("count", 15).putArray("members")
                .add(cells.get(0).get("key").asText()).add(cells.get(1).get("key").asText());

        apply(id, request, preview(id, request)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var saved = workspace(id).get("sheet").get("quotas");
        assertThat(saved).singleElement().satisfies(quota -> {
            assertThat(quota.get("kind").asText()).isEqualTo("WINNER");
            assertThat(quota.get("count").asInt()).isEqualTo(15);
            assertThat(keys(quota.get("members"), null)).containsExactly(cells.get(0).get("key").asText(),
                    cells.get(1).get("key").asText());
        });
    }

    @Test
    void 작업대에서_고친_주택형만_관리자_수정으로_표시하고_요청에서_뺀_주택형은_칸과_함께_지운다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var other = second(row);
        var request = request(id);
        type(request, 0).put("name", "26A");

        apply(id, request, preview(id, request)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        var types = workspace(id).get("sheet").get("housingTypes");
        assertThat(types.get(0).get("adminModified").asBoolean()).isTrue();
        assertThat(types.get(1).get("key").asText()).isEqualTo("row:" + other.getId());
        assertThat(types.get(1).get("adminModified").asBoolean()).isFalse();

        var remove = request(id);
        removeType(remove, "row:" + other.getId());
        apply(id, remove, preview(id, remove)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        assertThat(workspace(id).get("sheet").get("housingTypes")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_rows WHERE id = ?", Long.class, other.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_row_targets WHERE supply_row_id = ?", Long.class,
                other.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_rows WHERE id = ?", Long.class, row.getId())).isOne();
    }

    @Test
    void 관리자가_제외한_옛_공급행은_작업실에_보이지_않고_다음_저장에서_지운다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var excluded = second(row);
        jdbc.update("UPDATE supply_rows SET admin_excluded = true WHERE id = ?", excluded.getId());
        entities.clear();
        assertThat(keys(workspace(id).get("sheet").get("housingTypes"))).containsExactly("row:" + row.getId());

        var request = request(id);
        type(request, 0).put("name", "26A");
        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_rows WHERE id = ?", Long.class, excluded.getId())).isZero();
    }

    @Test
    void 새_주택형을_만들어_연결하고_만든_주택형은_보호하지_않으며_큰_금액을_보존한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        long originalTypeId = row.getHousingType().getId();
        var request = request(id);
        type(request, 0).putNull("housingTypeId").putObject("newHousingType").put("name", "30")
                .put("exclusiveArea", new BigDecimal("30.1200"));
        ((ObjectNode) sheet(request).get("leaseTerms").get(0)).put("rentalDeposit", "999999999999999999");
        long events = jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class);

        var preview = preview(id, request);
        assertThat(preview.at("/after/housingTypes/0/newHousingType/name").asText()).isEqualTo("30");
        apply(id, request, preview).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace.sheet.leaseTerms[0].rentalDeposit").value("999999999999999999"));
        entities.flush(); entities.clear();

        var saved = entities.find(SupplyRow.class, row.getId()).getHousingType();
        assertThat(saved.getId()).isNotEqualTo(originalTypeId);
        assertThat(saved.getName()).isEqualTo("30");
        assertThat(saved.getExclusiveArea()).isEqualByComparingTo("30.12");
        // 보정으로 만든 주택형은 공고 값이라 보호하지 않는다. 다음 공고가 갱신한다.
        assertThat(saved.isNameAdminModified()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class)).isEqualTo(events);
    }

    @Test
    void 지번을_모르는_단지의_주택형은_소재지_PNU를_비워_저장한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        jdbc.update("UPDATE housing_complexes SET pnu = NULL WHERE id = ?", row.getHousingComplex().getId());
        jdbc.update("UPDATE supply_rows SET supply_pnu = NULL WHERE id = ?", row.getId());
        entities.clear();
        var request = request(id);
        type(request, 0).put("supplyPnu", "").put("name", "26A");

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT supply_pnu IS NULL FROM supply_rows WHERE id = ?", Boolean.class,
                row.getId())).isTrue();
    }

    @Test
    void 같은_작업_재전송은_새_주택형과_이력을_중복생성하지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var request = request(id);
        // 아직 단지 주택형을 정하지 않은 새 주택형.
        sheet(request).withArray("housingTypes").addObject().put("key", "new:26b").put("complexName",
                type(request, 0).get("complexName").asText()).put("name", "26B").put("category", "NEW_SUPPLY")
                .put("displayOrder", 1);
        var preview = preview(id, request);

        apply(id, request, preview).andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(false));
        var again = apply(id, request, preview).andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_rows WHERE announcement_id = ?", Long.class, id)).isEqualTo(2L);
        // 첫 적용이 공고 단지 없던 행을 같은 이름의 공고 단지에 넣으며 그 단지 연결을 한 번 기록한다. 재전송은 아무것도 더 쓰지 않는다.
        assertThat(jdbc.queryForList("SELECT resource_type || ':' || action FROM admin_data_changes ORDER BY id",
                String.class))
                .containsExactlyInAnyOrder("ANNOUNCEMENT_COMPLEX:LINK_BY_ADMIN", "ANNOUNCEMENT:CORRECT_SUPPLY");
        again.andExpect(jsonPath("$.data.workspace.sheet.housingTypes.length()").value(2));
    }

    @Test
    void 바꾼_것이_없는_요청과_다른_단지의_주택형과_같은_신청형_두_줄은_거절하고_아무것도_저장하지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var other = fixture();
        rejects(id, request(id), "변경한 공급정보가 없습니다");

        var foreign = request(id);
        type(foreign, 0).put("housingTypeId", other.getHousingType().getId()).put("name", "저장되면 안 됨");
        rejects(id, foreign, "선택한 단지에 속한 주택형");

        var duplicated = request(id);
        sheet(duplicated).withArray("housingTypes").add(type(duplicated, 0).deepCopy().putNull("id").put("key", "new:dup")
                .put("displayOrder", 1));
        rejects(id, duplicated, "한 단지에서 주택형(신청형)은 하나입니다");

        var dangling = request(id);
        sheet(dangling).withArray("housingTypes").removeAll();
        rejects(id, dangling, "칸이 가리키는 주택형이나 공급대상이 공급표에 없습니다");

        assertThat(jdbc.queryForObject("SELECT source_housing_type_name FROM supply_rows WHERE id = ?", String.class,
                row.getId())).isEqualTo("26");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_rows WHERE announcement_id = ?", Long.class, id)).isOne();
    }

    @Test
    void 옛_공급행_모양의_요청과_서버가_정하는_필드는_받지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var legacy = request(id);
        legacy.putArray("rows");
        rejects(id, legacy, "rows");

        var flagged = request(id);
        type(flagged, 0).put("adminModified", true);
        rejects(id, flagged, "adminModified");
    }

    @Test
    void 미리보기_후_연결후보가_바뀌면_적용을_거절한다() throws Exception {
        var row = fixture(); var other = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        type(request, 0).put("housingComplexId", other.getHousingComplex().getId())
                .put("housingTypeId", other.getHousingType().getId());
        var preview = preview(id, request);
        other.getHousingType().reviseByAdmin("바뀐 공통형명", new BigDecimal("29.1234"), null);
        entities.flush();

        apply(id, request, preview).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DATA_STALE"));
        assertThat(jdbc.queryForObject("SELECT housing_complex_id FROM supply_rows WHERE id = ?", Long.class, row.getId()))
                .isEqualTo(row.getHousingComplex().getId());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 화면을_연_뒤_저장값이_바뀌면_409로_거절하고_어느_확인에서_막혔는지_로그에_남긴다(CapturedOutput output) throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        type(request, 0).put("name", "26A");
        jdbc.update("UPDATE supply_rows SET source_housing_type_name = '다른 관리자 형명' WHERE id = ?", row.getId());
        entities.clear();

        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DATA_STALE"));
        assertThat(output).contains("event=announcement.supply_correction.conflict_detected announcementId=" + id
                + " check=snapshot").contains("requestToken=" + request.get("snapshotToken").asText().substring(0, 12));
    }

    @Test
    void DB가_공급행을_돌려주는_순서가_달라도_작업실과_PDF보정_토큰은_같다() throws Exception {
        var first = fixture(); long id = first.getAnnouncement().getId();
        second(first);
        String supplyBefore = workspace(id).get("snapshotToken").asText(); String pdfBefore = jsonCorrectionToken(id);
        // 값은 그대로 두고 행의 저장 위치만 옮긴다. 정렬 없이 읽으면 두 공급행이 돌아오는 순서가 바뀐다.
        jdbc.update("UPDATE supply_rows SET display_order = display_order WHERE id = ?", first.getId());
        entities.clear();
        assertThat(workspace(id).get("snapshotToken").asText()).isEqualTo(supplyBefore);
        assertThat(jsonCorrectionToken(id)).isEqualTo(pdfBefore);
    }

    @Test
    void 보정쓰기에는_CSRF가_필요하다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        mvc.perform(post(root(id)).with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void 확인한_일정이_참조하는_마지막_단지연결을_지울_수_없다() throws Exception {
        var row = fixture();
        entities.persist(AnnouncementApplicationSchedule.verified(row.getAnnouncement(), row.getHousingComplex(), null,
                ApplicationScheduleState.CONFIRMED, null, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), null, null,
                "https://example.com/notice.pdf", 3));
        entities.flush();
        long id = row.getAnnouncement().getId(); var request = request(id);
        removeType(request, "row:" + row.getId());

        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DATA_CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("접수 일정이 있습니다")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_rows WHERE id = ?", Long.class, row.getId())).isOne();
    }

    @Test
    void 주택형_검토는_고른_항목만_변경을_감지하고_보류와_이력을_남긴다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var review = reviewRequest(id, "row:" + row.getId(), "householdCount");
        record(id, review).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.records[0].actor").value("reviewer"))
                .andExpect(jsonPath("$.data.records[0].checkedValues.householdCount").value(10));
        jdbc.update("UPDATE supply_rows SET supply_pnu = '1114010100100020000' WHERE id = ?", row.getId());
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"));
        jdbc.update("UPDATE supply_quotas SET household_count = 12 WHERE announcement_id = ?", id);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("STALE"))
                .andExpect(jsonPath("$.data.records[0].currentValues.householdCount").value(12));
        entities.clear();
        review.put("snapshotToken", workspace(id).get("snapshotToken").asText()).put("outcome", "ON_HOLD");
        record(id, review).andExpect(jsonPath("$.data.records[0].status").value("ON_HOLD"))
                .andExpect(jsonPath("$.data.records[1].latest").value(false));
    }

    @Test
    void 칸_검토는_공급대상과_모집수와_임대조건_값을_검토한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var cell = workspace(id).at("/sheet/cells/0");
        var review = reviewRequest(id, cell.get("key").asText(), "label");
        review.withArray("fields").add("winnerCount").add("monthlyRent");
        record(id, review).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.records[0].checkedValues.label").value("일반"))
                .andExpect(jsonPath("$.data.records[0].checkedValues.winnerCount").value(10));
        jdbc.update("UPDATE lease_terms SET monthly_rent = 210000 WHERE announcement_id = ?", id);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("STALE"));
    }

    @Test
    void 검토에_고른_원천_항목의_변경과_소실만_재검토로_판정한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        String sourceId = row.getAnnouncement().getSourceAnnouncementIdentifier();
        var collectionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO source_collection_records(id, version, source, started_at, finished_at, status, stored_row_count)
                VALUES (?, 0, 'MYHOME_ANNOUNCEMENT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SUCCESS', 1)
                """, collectionId);
        jdbc.update("INSERT INTO myhome_announcement_source_bundles(version, pblanc_id, last_collection_record_id) VALUES (0, ?, ?)", sourceId, collectionId);
        jdbc.update("""
                INSERT INTO myhome_announcement_source_rows(source_id, collection_record_id, source_order,
                  pblanc_id, house_sn, pblanc_nm, sum_suply_co, active, consecutive_miss_count)
                SELECT id, ?, 0, pblanc_id, 1, '원문 공고명', 10, true, 0
                FROM myhome_announcement_source_bundles WHERE pblanc_id = ?
                """, collectionId, sourceId);
        String sourceKey = comparisons.detail(QualityResource.ANNOUNCEMENT, id, "basic").sources().getFirst().sourceKey();
        var review = reviewRequest(id, "row:" + row.getId(), "householdCount");
        var source = review.withArray("sources").addObject().put("category", "MYHOME_ANNOUNCEMENT").put("sourceKey", sourceKey);
        // 수집이 원천 행에 함께 담는 관리 열(collected_at)은 원천 값이 아니라 확인 항목으로 고를 수 없다.
        source.putArray("fields").add("collected_at");
        mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("수집 메타정보를 제외한 실제 원천 항목을 선택하세요."));
        source.putArray("fields").add("sum_suply_co");
        record(id, review).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"));
        jdbc.update("UPDATE myhome_announcement_source_rows SET pblanc_nm = '범위 밖 이름', collected_at = CURRENT_TIMESTAMP WHERE pblanc_id = ?", sourceId);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"));
        jdbc.update("UPDATE myhome_announcement_source_rows SET sum_suply_co = 12 WHERE pblanc_id = ?", sourceId);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("STALE"));
        jdbc.update("DELETE FROM myhome_announcement_source_rows WHERE pblanc_id = ?", sourceId);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("STALE"));
    }

    @Test
    void 검토_기록도_권한과_CSRF_대상_버전_항목을_검증한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var review = reviewRequest(id, "row:" + row.getId(), "householdCount");
        mvc.perform(post(root(id) + "/reviews").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isForbidden());
        review.put("snapshotToken", "old");
        mvc.perform(post(root(id) + "/reviews").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isConflict());
        review.put("snapshotToken", workspace(id).get("snapshotToken").asText());
        review.putArray("fields").add("unknown");
        mvc.perform(post(root(id) + "/reviews").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isBadRequest());
        // 옛 공급대상 key(target:{id})는 검토 대상이 아니다.
        var cell = workspace(id).at("/sheet/cells/0");
        var legacy = reviewRequest(id, cell.get("target").asText(), "label");
        mvc.perform(post(root(id) + "/reviews").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(legacy))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_supply_reviews", Long.class)).isZero();
    }

    @Test
    void 공급행이_연결된_주택형은_공고별_면적을_보호해도_공통_면적을_바꿀_수_없고_이름은_바꿀_수_있다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        String path = "/api/admin/housing-complexes/" + row.getHousingComplex().getId()
                + "/housing-types/" + row.getHousingType().getId();
        var update = json.createObjectNode().put("version", row.getHousingComplex().getVersion())
                .put("name", "26 공통").put("exclusiveArea", 27).put("impactToken", impactToken(path));
        mvc.perform(MockMvcRequestBuilders.put(path)
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(update))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("전용면적을 바꿀 수 없습니다")));
        // 공고가 면적을 따로 정해도(공고별 면적) 공통 주택형 면적은 바꿀 수 없다.
        var correction = request(id);
        type(correction, 0).put("exclusiveArea", new BigDecimal("26.4673"));
        apply(id, correction, preview(id, correction)).andExpect(status().isOk());
        update.put("impactToken", impactToken(path));
        mvc.perform(MockMvcRequestBuilders.put(path)
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(update))).andExpect(status().isConflict());
        update.put("exclusiveArea", row.getHousingType().getExclusiveArea());
        mvc.perform(MockMvcRequestBuilders.put(path)
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(update))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT name FROM housing_types WHERE id = ?", String.class,
                row.getHousingType().getId())).isEqualTo("26 공통");
        assertThat(jdbc.queryForObject("SELECT exclusive_area FROM housing_types WHERE id = ?", BigDecimal.class,
                row.getHousingType().getId())).isEqualByComparingTo(row.getHousingType().getExclusiveArea());
    }

    @Test
    void 공급행이_가리키는_주택형은_전용면적을_바꿀_수_없다() throws Exception {
        var row = fixture();
        String path = "/api/admin/housing-complexes/" + row.getHousingComplex().getId()
                + "/housing-types/" + row.getHousingType().getId();
        var update = json.createObjectNode().put("version", row.getHousingComplex().getVersion())
                .put("name", "26").put("exclusiveArea", 27).put("impactToken", impactToken(path));
        mvc.perform(MockMvcRequestBuilders.put(path)
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(update))).andExpect(status().isConflict());
    }

    private String impactToken(String path) throws Exception {
        return json.readTree(mvc.perform(get(path + "/impact").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].supplyRowId").isNumber())
                .andReturn().getResponse().getContentAsString()).get("data").get("token").asText();
    }

    private SupplyRow fixture() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement(); var complex = fixtures.complex("11140", "1114010100");
        var type = HousingType.createFromMyHome(complex, UUID.randomUUID().toString(), "26", new BigDecimal("26.4673"), null);
        entities.persist(type);
        var row = SupplyRow.create(announcement, complex, type, UUID.randomUUID().toString(), 0, complex.getName(),
                "26", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null);
        entities.persist(row);
        new SupplySheetSeed(entities).target(row, "일반", "공고문 확인", 10, null, new BigDecimal("100000000"),
                new BigDecimal("200000"), null, 0);
        entities.flush();
        return row;
    }

    /** 같은 공고·단지의 둘째 주택형(36). */
    private SupplyRow second(SupplyRow first) {
        var type = HousingType.createFromMyHome(first.getHousingComplex(), UUID.randomUUID().toString(), "36",
                new BigDecimal("36.9100"), null);
        entities.persist(type);
        var row = SupplyRow.create(first.getAnnouncement(), first.getHousingComplex(), type, UUID.randomUUID().toString(), 1,
                first.getSourceComplexName(), "36", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null);
        entities.persist(row);
        new SupplySheetSeed(entities).target(row, "일반", "공고문 확인", 5, null, new BigDecimal("150000000"),
                new BigDecimal("250000"), null, 0);
        entities.flush(); entities.clear();
        return row;
    }

    private JsonNode workspace(long id) throws Exception {
        return json.readTree(mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    /** 작업실 공급표를 그대로 보내는 요청. 테스트가 필요한 곳만 고친다. */
    private ObjectNode request(long id) throws Exception {
        var workspace = workspace(id);
        var request = json.createObjectNode();
        request.put("operationId", UUID.randomUUID().toString());
        request.put("snapshotToken", workspace.get("snapshotToken").asText());
        request.put("reason", "공식 공고문과 공급 범위를 대조함");
        request.put("sourceUrl", "https://example.com/notice.pdf"); request.put("sourcePage", "3");
        request.set("sheet", SupplySheetInputs.json(workspace.get("sheet")));
        return request;
    }

    private static ObjectNode sheet(ObjectNode request) {
        return (ObjectNode) request.get("sheet");
    }

    private static ObjectNode type(ObjectNode request, int index) {
        return (ObjectNode) sheet(request).get("housingTypes").get(index);
    }

    /** 주택형과 그 칸, 그 칸만 가진 수량 묶음·임대조건을 요청에서 뺀다. */
    private static void removeType(ObjectNode request, String key) {
        var sheet = sheet(request);
        List<String> cells = new ArrayList<>();
        sheet.get("cells").forEach(cell -> { if (cell.get("housingType").asText().equals(key)) { cells.add(cell.get("key").asText()); } });
        removeIf(sheet.withArray("housingTypes"), node -> node.get("key").asText().equals(key));
        removeIf(sheet.withArray("cells"), node -> cells.contains(node.get("key").asText()));
        for (var name : List.of("quotas", "leaseTerms")) {
            removeIf(sheet.withArray(name), node -> cells.containsAll(keys(node.get("members"), null)));
        }
    }

    private static void removeIf(ArrayNode array, java.util.function.Predicate<JsonNode> remove) {
        for (int index = array.size() - 1; index >= 0; index--) {
            if (remove.test(array.get(index))) { array.remove(index); }
        }
    }

    private static List<String> keys(JsonNode items) {
        return keys(items, "key");
    }

    private static List<String> keys(JsonNode items, String field) {
        List<String> result = new ArrayList<>();
        items.forEach(item -> result.add(field == null ? item.asText() : item.get(field).asText()));
        return result;
    }

    private JsonNode preview(long id, ObjectNode request) throws Exception {
        return json.readTree(mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private void rejects(long id, ObjectNode request, String message) throws Exception {
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString(message)));
    }

    private ResultActions apply(long id, ObjectNode request, JsonNode preview) throws Exception {
        var body = json.createObjectNode().set("correction", request);
        body.put("confirmationToken", preview.get("confirmationToken").asText());
        return mvc.perform(post(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    /** 화면처럼 원천 근거 없이(sources: []) 보내는 검토 기록 요청. */
    private ObjectNode reviewRequest(long id, String target, String field) throws Exception {
        var request = json.createObjectNode().put("target", target).put("outcome", "VERIFIED")
                .put("snapshotToken", workspace(id).get("snapshotToken").asText())
                .put("note", "공식 원문의 동일 범위를 확인함").put("sourceUrl", "https://example.com/notice.pdf");
        request.putArray("fields").add(field); request.putArray("sources");
        return request;
    }

    private ResultActions record(long id, ObjectNode review) throws Exception {
        return mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isOk());
    }

    private ResultActions reviews(long id) throws Exception {
        return mvc.perform(get(root(id) + "/reviews").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    private String jsonCorrectionToken(long id) throws Exception {
        return json.readTree(mvc.perform(get("/api/admin/announcements/" + id + "/json-corrections")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("data").get("snapshotToken").asText();
    }

    private String root(long id) { return "/api/admin/announcements/" + id + "/supply-corrections"; }

    private String supplyOwner(long id) {
        return jdbc.queryForObject("SELECT supply_owner FROM announcements WHERE id = ?", String.class, id);
    }
}
