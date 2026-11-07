package com.toadzip.backend.announcement.controller;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
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
    @Autowired private SupplyRowRepository rows;
    @Autowired private MyHomeAnnouncementSourceFixtures myhomeSources;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void lease() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(call ->
                org.mockito.Mockito.mock(IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 미리보기는_저장하지_않고_공고별_면적과_모집수만_원자적으로_적용한다() throws Exception {
        var row = fixture();
        long id = row.getAnnouncement().getId();
        var request = request(id);
        var input = (ObjectNode) request.get("rows").get(0);
        input.put("typeName", "26A"); input.put("areaOverride", true);
        input.put("exclusiveArea", new BigDecimal("26.5195")); input.putNull("householdCount");
        var target = (ObjectNode) input.get("targets").get(0);
        target.put("rent", "0"); target.putNull("householdCount");
        var preview = preview(id, request);
        assertThat(rows.findById(row.getId()).orElseThrow().getSourceHousingTypeName()).isEqualTo("26");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_data_changes", Long.class)).isZero();
        assertThat(supplyOwner(id)).isEqualTo("LH_SOURCE");

        apply(id, request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
        // 관리자가 저장한 공급정보는 공고문이 주인이라 정제가 공급행을 다시 만들지 않는다.
        assertThat(supplyOwner(id)).isEqualTo("ANNOUNCEMENT_DOCUMENT");
        var saved = rows.findById(row.getId()).orElseThrow();
        assertThat(saved.getSourceHousingTypeName()).isEqualTo("26");
        assertThat(saved.getAnnouncementHousingTypeName()).isEqualTo("26A");
        assertThat(saved.getTotalSupplyHouseholdCount()).isNull();
        assertThat(saved.getHousingType().getExclusiveArea()).isEqualByComparingTo("26.4673");
        mvc.perform(get("/api/v1/announcements/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplyRows[0].sourceHousingTypeName").value("26"))
                .andExpect(jsonPath("$.data.supplyRows[0].displayHousingTypeName").value("26A"))
                .andExpect(jsonPath("$.data.supplyRows[0].exclusiveArea").value(26.5195))
                .andExpect(jsonPath("$.data.supplyRows[0].totalSupplyHouseholdCount").isEmpty())
                .andExpect(jsonPath("$.data.supplyRows[0].targets[0].monthlyRent").value(0));
    }

    @Test
    void 지번을_모르는_단지의_공급행은_소재지_PNU를_비워_저장한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        jdbc.update("UPDATE housing_complexes SET pnu = NULL WHERE id = ?", row.getHousingComplex().getId());
        jdbc.update("UPDATE supply_rows SET supply_pnu = NULL WHERE id = ?", row.getId());
        entities.clear();
        var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("pnu", "").put("typeName", "26A");

        apply(id, request, preview(id, request)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT supply_pnu IS NULL FROM supply_rows WHERE id = ?", Boolean.class,
                row.getId())).isTrue();
    }

    @Test
    void 같은_작업_재전송은_추가행과_이력을_중복생성하지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        var extra = request.get("rows").get(0).deepCopy();
        // 같은 단지에서 공급행은 주택형마다 하나라 추가 행은 아직 주택형을 정하지 않은 행으로 둔다.
        ((ObjectNode) extra).putNull("id").put("key", UUID.randomUUID().toString()).put("typeName", "26B")
                .putNull("housingTypeId").putNull("exclusiveArea").remove("linkedHousingTypes");
        ((ObjectNode) extra.get("targets").get(0)).putNull("id").put("key", UUID.randomUUID().toString());
        ((ArrayNode) request.get("rows")).add(extra);
        var preview = preview(id, request);
        apply(id, request, preview).andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(false));
        apply(id, request, preview).andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true));
        assertThat(rows.findAllByAnnouncement(row.getAnnouncement())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_data_changes", Long.class)).isEqualTo(1L);
    }

    @Test
    void 제외한_공급행과_대상은_공개응답에서_빠지고_복구하면_같은_ID로_돌아온다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("excluded", true);
        apply(id, request, preview(id, request)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/announcements/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplyRows").isEmpty()).andExpect(jsonPath("$.data.supplyComplexCount").value(0));
        var restore = request(id);
        ((ObjectNode) restore.get("rows").get(0)).put("excluded", false);
        ((ObjectNode) restore.get("rows").get(0).get("targets").get(0)).put("excluded", true);
        apply(id, restore, preview(id, restore)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/announcements/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplyRows[0].supplyRowId").value(row.getId()))
                .andExpect(jsonPath("$.data.supplyRows[0].targets").isEmpty());
    }

    @Test
    void 관리자가_추가한_공급행만_지울_수_있고_원천행은_제외만_할_수_있다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        var extra = request.get("rows").get(0).deepCopy();
        // 같은 단지에서 공급행은 주택형마다 하나라 추가 행은 아직 주택형을 정하지 않은 행으로 둔다.
        ((ObjectNode) extra).putNull("id").put("key", UUID.randomUUID().toString()).put("typeName", "26B")
                .putNull("housingTypeId").putNull("exclusiveArea").remove("linkedHousingTypes");
        ((ObjectNode) extra.get("targets").get(0)).putNull("id").put("key", UUID.randomUUID().toString());
        ((ArrayNode) request.get("rows")).add(extra);
        apply(id, request, preview(id, request)).andExpect(status().isOk());
        var manual = rows.findAllByAnnouncement(row.getAnnouncement()).stream()
                .filter(item -> item.getSourceSupplyRowIdentifier().startsWith("MANUAL:")).findFirst().orElseThrow();
        String manualKey = "row:" + manual.getId();
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletableRowKeys.length()").value(1))
                .andExpect(jsonPath("$.data.deletableRowKeys[0]").value(manualKey));

        var source = withoutRow(request(id), "row:" + row.getId());
        source.putArray("deletedRowKeys").add("row:" + row.getId());
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(source)))
                .andExpect(status().isBadRequest());

        var delete = withoutRow(request(id), manualKey);
        delete.putArray("deletedRowKeys").add(manualKey);
        var preview = preview(id, delete);
        assertThat(preview.get("after")).hasSize(1);
        apply(id, delete, preview).andExpect(status().isOk());
        assertThat(rows.findById(manual.getId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_targets WHERE supply_row_id = ?", Long.class,
                manual.getId())).isZero();
        assertThat(rows.findById(row.getId())).isPresent();
    }

    private ObjectNode withoutRow(ObjectNode request, String key) {
        var rows = (ArrayNode) request.get("rows");
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).get("key").asText().equals(key)) { rows.remove(index); break; }
        }
        return request;
    }

    @Test
    void 다른_단지의_주택형과_누락한_기존행을_거절하고_일부만_저장하지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        var other = fixture();
        ((ObjectNode) request.get("rows").get(0)).put("housingTypeId", other.getHousingType().getId())
                .put("typeName", "저장되면 안 됨");
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        request.putArray("rows");
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertThat(rows.findById(row.getId()).orElseThrow().getSourceHousingTypeName()).isEqualTo("26");
    }

    @Test
    void 미리보기_후_연결후보가_바뀌면_적용을_거절한다() throws Exception {
        var row = fixture(); var other = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("housingComplexId", other.getHousingComplex().getId())
                .put("housingTypeId", other.getHousingType().getId());
        var preview = preview(id, request);
        other.getHousingType().reviseByAdmin("바뀐 공통형명", new BigDecimal("29.1234"), null);
        entities.flush();
        apply(id, request, preview).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DATA_STALE"));
        assertThat(row.getHousingComplex().getId()).isNotEqualTo(other.getHousingComplex().getId());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 화면을_연_뒤_저장값이_바뀌면_어느_확인에서_막혔는지_로그에_남긴다(CapturedOutput output) throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("typeName", "26A");
        jdbc.update("UPDATE supply_rows SET source_housing_type_name = '다른 관리자 형명' WHERE id = ?", row.getId());
        entities.clear();
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DATA_STALE"));
        assertThat(output).contains("event=announcement.supply_correction.conflict_detected announcementId=" + id
                + " check=snapshot").contains("requestToken=" + request.get("snapshotToken").asText().substring(0, 12))
                .contains("rowsHash=");
    }

    @Test
    void DB가_공급행을_돌려주는_순서가_달라도_작업실과_PDF보정_토큰은_같다() throws Exception {
        var first = fixture(); var announcement = first.getAnnouncement(); long id = announcement.getId();
        entities.persist(SupplyRow.create(announcement, first.getHousingComplex(), first.getHousingType(),
                UUID.randomUUID().toString(), 1, first.getHousingComplex().getName(), "36", "1114010100100010000", null,
                SupplyCategory.NEW_SUPPLY, null, 5));
        entities.flush(); entities.clear();
        String supplyBefore = request(id).get("snapshotToken").asText(); String pdfBefore = jsonCorrectionToken(id);
        // 값은 그대로 두고 행의 저장 위치만 옮긴다. 정렬 없이 읽으면 두 공급행이 돌아오는 순서가 바뀐다.
        jdbc.update("UPDATE supply_rows SET display_order = display_order WHERE id = ?", first.getId());
        entities.clear();
        assertThat(request(id).get("snapshotToken").asText()).isEqualTo(supplyBefore);
        assertThat(jsonCorrectionToken(id)).isEqualTo(pdfBefore);
    }

    @Test
    void 보정쓰기에는_CSRF가_필요하다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        mvc.perform(post(root(id)).with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void 검토한_범위만_변경을_감지하고_보류와_이력을_남긴다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var review = reviewRequest(row, "householdCount");
        mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.records[0].actor").value("reviewer"));
        jdbc.update("UPDATE supply_rows SET source_housing_type_name = '범위 밖 형명' WHERE id = ?", row.getId());
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"));
        jdbc.update("UPDATE supply_rows SET total_supply_household_count = 12 WHERE id = ?", row.getId());
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("STALE"))
                .andExpect(jsonPath("$.data.records[0].checkedValues.householdCount").value(10))
                .andExpect(jsonPath("$.data.records[0].currentValues.householdCount").value(12));
        entities.clear();
        review.put("snapshotToken", request(id).get("snapshotToken").asText()).put("outcome", "ON_HOLD");
        mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].status").value("ON_HOLD"))
                .andExpect(jsonPath("$.data.records[1].latest").value(false));
    }

    @Test
    void 선택한_원천_항목의_변경과_소실만_재검토로_판정한다() throws Exception {
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
        var workspace = json.readTree(mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var review = reviewRequest(row, "householdCount");
        var source = json.createObjectNode().put("category", "MYHOME_ANNOUNCEMENT")
                .put("sourceKey", workspace.at("/sources/0/sourceKey").asText());
        // 수집이 원천 행에 함께 담는 관리 열(collected_at)은 원천 값이 아니라 확인 항목으로 고를 수 없다.
        source.putArray("fields").add("collected_at");
        review.putArray("sources").add(source);
        mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("수집 메타정보를 제외한 실제 원천 항목을 선택하세요."));
        source.putArray("fields").add("sum_suply_co");
        mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"));
        jdbc.update("UPDATE myhome_announcement_source_rows SET pblanc_nm = '범위 밖 이름', collected_at = CURRENT_TIMESTAMP WHERE pblanc_id = ?", sourceId);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"));
        jdbc.update("UPDATE myhome_announcement_source_rows SET sum_suply_co = 12 WHERE pblanc_id = ?", sourceId);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("STALE"));
        jdbc.update("UPDATE myhome_announcement_source_rows SET sum_suply_co = 10 WHERE pblanc_id = ?", sourceId);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"));
        jdbc.update("DELETE FROM myhome_announcement_source_rows WHERE pblanc_id = ?", sourceId);
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("STALE"));
    }

    @Test
    void 검토_기록도_권한과_CSRF_범위_버전_근거를_검증한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var review = reviewRequest(row, "householdCount");
        mvc.perform(post(root(id) + "/reviews").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isForbidden());
        review.put("snapshotToken", "old");
        mvc.perform(post(root(id) + "/reviews").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isConflict());
        review.put("snapshotToken", request(id).get("snapshotToken").asText());
        review.putArray("fields").add("unknown");
        mvc.perform(post(root(id) + "/reviews").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_supply_reviews", Long.class)).isZero();
    }

    private ObjectNode reviewRequest(SupplyRow row, String field) throws Exception {
        var request = json.createObjectNode().put("target", "row:" + row.getId()).put("outcome", "VERIFIED")
                .put("snapshotToken", request(row.getAnnouncement().getId()).get("snapshotToken").asText())
                .put("note", "공식 원문의 동일 범위를 확인함").put("sourceUrl", "https://example.com/notice.pdf");
        request.putArray("fields").add(field); request.putArray("sources");
        return request;
    }

    private ResultActions reviews(long id) throws Exception {
        return mvc.perform(get(root(id) + "/reviews").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
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
        var correction = request(id);
        ((ObjectNode) correction.get("rows").get(0)).put("areaOverride", true);
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
    void 함께_모집하는_주택형으로만_연결된_주택형도_전용면적을_바꿀_수_없다() throws Exception {
        var row = fixture();
        var joint = HousingType.createFromMyHome(row.getHousingComplex(), UUID.randomUUID().toString(), "26B",
                new BigDecimal("26.7000"), null);
        entities.persist(joint); entities.flush();
        jdbc.update("INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id) VALUES (?, ?)",
                row.getId(), joint.getId());
        String path = "/api/admin/housing-complexes/" + row.getHousingComplex().getId() + "/housing-types/" + joint.getId();
        var update = json.createObjectNode().put("version", row.getHousingComplex().getVersion())
                .put("name", "26B").put("exclusiveArea", 27).put("impactToken", impactToken(path));
        mvc.perform(MockMvcRequestBuilders.put(path)
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(update))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("전용면적을 바꿀 수 없습니다")));
    }

    @Test
    void 연결_없이_대표로만_쓰인_주택형도_전용면적을_바꿀_수_없다() throws Exception {
        // 연결 표를 모르는 이전 애플리케이션이 만든 공급행도 대표 주택형으로 면적을 지킨다.
        var row = fixture();
        jdbc.update("DELETE FROM supply_row_housing_types WHERE supply_row_id = ?", row.getId());
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

    @Test
    void 모집수_합계는_범위를_확인한_경우에만_검증하고_큰_금액과_수동_주택형을_보존한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        long originalTypeId = row.getHousingType().getId();
        var input = (ObjectNode) request.get("rows").get(0);
        var condition = (ObjectNode) input.get("targets").get(0);
        condition.put("deposit", "999999999999999999");
        var second = condition.deepCopy().putNull("id").put("key", "additional").put("target", "중복 범위 대상");
        ((ArrayNode) input.get("targets")).add(second);
        preview(id, request);
        input.put("targetCountsComparable", true);
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
        input.put("targetCountsComparable", false).putNull("housingTypeId");
        input.putObject("newHousingType").put("name", "30").put("exclusiveArea", new BigDecimal("30.1200"));
        long events = jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class);
        var preview = preview(id, request);
        apply(id, request, preview).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace.rows[0].targets[0].deposit").value("999999999999999999"));
        entities.flush(); entities.clear();
        var saved = rows.findById(row.getId()).orElseThrow();
        assertThat(saved.getHousingType().getId()).isNotEqualTo(originalTypeId);
        assertThat(saved.getHousingType().getName()).isEqualTo("30");
        // 보정으로 만든 주택형은 공고 값이라 보호하지 않는다. 다음 공고가 갱신한다.
        assertThat(saved.getHousingType().isNameAdminModified()).isFalse();
        assertThat(saved.isAdminModified()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class)).isEqualTo(events);
    }

    @Test
    void 작업실은_마이홈_원천이_기록된_공급행에만_대응하는_원천_키를_준다() throws Exception {
        var row = fixture();
        long id = row.getAnnouncement().getId();
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rowSources").isEmpty());
        row.recordMyHomeSource("12:source-key:1");
        entities.flush();
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowSources.length()").value(1))
                .andExpect(jsonPath("$.data.rowSources[0].rowKey").value("row:" + row.getId()))
                .andExpect(jsonPath("$.data.rowSources[0].category").value("MYHOME_ANNOUNCEMENT"))
                .andExpect(jsonPath("$.data.rowSources[0].sourceKey").value("12:source-key:1"));
    }


    @Test
    void 원천이_사라진_보정행은_보존하고_작업실에_원천_경고를_표시한다() throws Exception {
        var row = fixture();
        row.recordMyHomeSource("missing-source-key");
        row.protectConditionsByAdmin();
        entities.flush();
        long id = row.getAnnouncement().getId();
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceWarnings[0]").value(
                        containsString("원천이 없거나 비활성")));
        assertThat(rows.findById(row.getId())).isPresent();
    }

    @Test
    void 확인한_일정이_참조하는_마지막_단지연결을_제외할_수_없다() throws Exception {
        var row = fixture();
        var schedule = AnnouncementApplicationSchedule.verified(
                row.getAnnouncement(), row.getHousingComplex(), null,
                ApplicationScheduleState.CONFIRMED, null,
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), null, null,
                "https://example.com/notice.pdf", 3);
        entities.persist(schedule); entities.flush();
        long id = row.getAnnouncement().getId(); var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("excluded", true);
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DATA_CONFLICT"))
                .andExpect(jsonPath("$.message").value("제외·연결 변경할 단지에 접수 일정이 있습니다. 일정 적용 단지를 먼저 수정하세요."));
        assertThat(row.isAdminExcluded()).isFalse();
    }

    @Test
    void 새_공통_모집_묶음과_LH_행_분리_요청은_더_받지_않는다() throws Exception {
        // 공급행은 주택형마다 하나이고 신청자 묶음은 대상이라, 공통 모집 묶음을 새로 만들거나 LH 행을 나누는 요청을 조용히 버리지 않고 거절한다.
        var row = fixture(); long id = row.getAnnouncement().getId();
        var request = request(id);
        request.putArray("newGroups").addObject().put("sourceRowId", row.getId()).put("sharedHouseholdCount", 170);
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("newGroups")));
        request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("saveMode", "SPLIT");
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("saveMode")));
    }

    @Test
    void 같은_단지에서_두_공급행이_같은_주택형을_가리키면_거절하고_아무것도_저장하지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var request = request(id);
        var extra = request.get("rows").get(0).deepCopy();
        ((ObjectNode) extra).putNull("id").put("key", "new-row").put("typeName", "26B").put("displayOrder", 1);
        ((ObjectNode) extra.get("targets").get(0)).putNull("id").put("key", "new-target");
        ((ArrayNode) request.get("rows")).add(extra);
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("같은 주택형을 가리킵니다")));
        assertThat(rows.findAllByAnnouncement(row.getAnnouncement())).hasSize(1);
    }

    @Test
    void 함께_모집하는_주택형을_연결하면_대표와_함께_저장하고_작업실이_돌려준다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var joint = housingType(row, "26B", "26.7000");
        var request = request(id);
        var links = ((ObjectNode) request.get("rows").get(0)).putArray("linkedHousingTypes");
        links.addObject().putObject("newHousingType").put("name", "26C").put("exclusiveArea", new BigDecimal("26.9"));
        links.addObject().put("housingTypeId", joint.getId());

        var preview = preview(id, request);
        assertThat(linkedTypeIds(row.getId())).containsExactly(row.getHousingType().getId());
        assertThat(preview.at("/after/0/linkedHousingTypes/0/housingTypeId").asLong()).isEqualTo(joint.getId());
        apply(id, request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();

        long created = jdbc.queryForObject("SELECT id FROM housing_types WHERE housing_complex_id = ? AND exclusive_area = 26.9",
                Long.class, row.getHousingComplex().getId());
        assertThat(linkedTypeIds(row.getId())).containsExactlyInAnyOrder(row.getHousingType().getId(), joint.getId(), created);
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].housingTypeId").value(row.getHousingType().getId()))
                .andExpect(jsonPath("$.data.rows[0].linkedHousingTypes.length()").value(2))
                .andExpect(jsonPath("$.data.rows[0].linkedHousingTypes[0].housingTypeId").value(Math.min(joint.getId(), created)))
                .andExpect(jsonPath("$.data.rows[0].linkedHousingTypes[1].housingTypeId").value(Math.max(joint.getId(), created)));
    }

    @Test
    void 함께_모집하는_주택형을_생략하면_저장된_연결을_유지하고_빈_목록이면_대표만_남긴다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var joint = housingType(row, "26B", "26.7000");
        jdbc.update("INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id) VALUES (?, ?)", row.getId(), joint.getId());
        entities.clear();

        var untouched = request(id);
        ((ObjectNode) untouched.get("rows").get(0)).remove("linkedHousingTypes");
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(untouched)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("변경한 공급정보가 없습니다."));

        var omitted = request(id);
        ((ObjectNode) omitted.get("rows").get(0)).put("householdCount", 12).remove("linkedHousingTypes");
        apply(id, omitted, preview(id, omitted)).andExpect(status().isOk());
        entities.flush(); entities.clear();
        assertThat(linkedTypeIds(row.getId())).containsExactlyInAnyOrder(row.getHousingType().getId(), joint.getId());

        var cleared = request(id);
        ((ObjectNode) cleared.get("rows").get(0)).putArray("linkedHousingTypes");
        apply(id, cleared, preview(id, cleared)).andExpect(status().isOk());
        entities.flush(); entities.clear();
        assertThat(linkedTypeIds(row.getId())).containsExactly(row.getHousingType().getId());
    }

    @Test
    void 함께_모집하는_주택형을_생략하고_대표를_바꾸면_새_대표만_남긴다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var joint = housingType(row, "26B", "26.7000");
        var replacement = housingType(row, "27", "27.1000");
        jdbc.update("INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id) VALUES (?, ?)", row.getId(), joint.getId());
        entities.clear();

        var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("housingTypeId", replacement.getId()).remove("linkedHousingTypes");
        apply(id, request, preview(id, request)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        assertThat(linkedTypeIds(row.getId())).containsExactly(replacement.getId());
    }

    @Test
    void 화면을_연_뒤_함께_모집하는_주택형이_바뀌면_오래된_작업실로_저장하지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var joint = housingType(row, "26B", "26.7000");
        var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("typeName", "26A");
        jdbc.update("INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id) VALUES (?, ?)", row.getId(), joint.getId());
        entities.clear();

        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DATA_STALE"));
    }

    @Test
    void 미리보기_후_함께_모집하는_주택형이_바뀌면_적용을_거절한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var joint = housingType(row, "26B", "26.7000");
        var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).putArray("linkedHousingTypes").addObject().put("housingTypeId", joint.getId());
        var preview = preview(id, request);
        joint.reviseByAdmin("바뀐 함께 모집형", new BigDecimal("26.7000"), null);
        entities.flush();

        apply(id, request, preview).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DATA_STALE"));
        assertThat(linkedTypeIds(row.getId())).containsExactly(row.getHousingType().getId());
    }

    @Test
    void 다른_단지_주택형이나_면적이_겹치는_주택형은_함께_모집하는_주택형으로_연결하지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var otherComplexType = housingType(fixture(), "27", "27.1000");
        var sameArea = housingType(row, "26B", "26.4673");
        rejectLinks(id, links -> links.addObject().put("housingTypeId", otherComplexType.getId()),
                "선택한 단지에 속한 주택형을 선택하세요.");
        rejectLinks(id, links -> links.addObject().put("housingTypeId", row.getHousingType().getId()), "전용면적마다");
        rejectLinks(id, links -> links.addObject().put("housingTypeId", sameArea.getId()), "전용면적마다");
        rejectLinks(id, links -> {
            links.addObject().putObject("newHousingType").put("name", "26C").put("exclusiveArea", new BigDecimal("26.9"));
            links.addObject().putObject("newHousingType").put("name", "26D").put("exclusiveArea", new BigDecimal("26.90"));
        }, "전용면적마다");
        housingType(row, "27", "27.5000");
        rejectLinks(id, links -> links.addObject().putObject("newHousingType").put("name", "27A")
                .put("exclusiveArea", new BigDecimal("27.5")), "이 단지에 있습니다");
        assertThat(linkedTypeIds(row.getId())).containsExactly(row.getHousingType().getId());
    }

    @Test
    void 이미_저장된_공통_모집_묶음_행에는_함께_모집하는_주택형을_연결하지_않는다() throws Exception {
        var row = fixture();
        var joint = housingType(row, "26C", "26.9000");
        // 새 묶음은 더 만들지 않지만 이전에 저장한 묶음은 남아 있다.
        long groupId = jdbc.queryForObject("""
                INSERT INTO supply_row_groups (announcement_id, source_row_identifier, shared_household_count, source_fingerprint,
                    source_url, reason, actor, updated_at, review_required, version)
                VALUES (?, 'old-group', 170, 'fingerprint', 'https://example.com', '이전 보정', 'admin', now(), false, 0) RETURNING id""",
                Long.class, row.getAnnouncement().getId());
        jdbc.update("UPDATE supply_rows SET supply_group_id = ?, total_supply_household_count = NULL WHERE id = ?", groupId,
                row.getId());
        entities.clear();
        var request = request(row.getAnnouncement().getId());
        ((ObjectNode) request.get("rows").get(0)).putArray("linkedHousingTypes").addObject().put("housingTypeId", joint.getId());
        mvc.perform(post(root(row.getAnnouncement().getId()) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("공통 모집 묶음 행에는 함께 모집하는 주택형")));
    }

    @Test
    void 연결을_검토한_공급행에_함께_모집하는_주택형을_더하면_재검토로_판정한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(reviewRequest(row, "connection"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.records[0].checkedValues.connection.typeIds").value(String.valueOf(row.getHousingType().getId())));
        entities.clear();

        var joint = housingType(row, "26B", "26.7000");
        var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).putArray("linkedHousingTypes").addObject().put("housingTypeId", joint.getId());
        apply(id, request, preview(id, request)).andExpect(status().isOk());
        entities.flush(); entities.clear();

        long low = Math.min(row.getHousingType().getId(), joint.getId()), high = Math.max(row.getHousingType().getId(), joint.getId());
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("STALE"))
                .andExpect(jsonPath("$.data.records[0].currentValues.connection.typeIds").value(low + "," + high));
    }

    @Test
    void 함께_모집하는_주택형_목록이_없던_연결_검토는_그대로_검토_완료다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(reviewRequest(row, "connection"))))
                .andExpect(status().isOk());
        jdbc.update("UPDATE admin_supply_reviews SET checked_values = (checked_values::jsonb #- '{connection,typeIds}')::text");
        jdbc.update("INSERT INTO supply_row_housing_types (supply_row_id, housing_type_id) VALUES (?, ?)",
                row.getId(), housingType(row, "26B", "26.7000").getId());

        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.records[0].checkedValues.connection.typeIds").doesNotExist());
    }

    private void rejectLinks(long id, java.util.function.Consumer<ArrayNode> links, String message) throws Exception {
        var request = request(id);
        links.accept(((ObjectNode) request.get("rows").get(0)).putArray("linkedHousingTypes"));
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString(message)));
    }

    private HousingType housingType(SupplyRow row, String name, String area) {
        var type = HousingType.createFromMyHome(row.getHousingComplex(), UUID.randomUUID().toString(), name,
                new BigDecimal(area), null);
        entities.persist(type); entities.flush();
        return type;
    }

    private java.util.List<Long> linkedTypeIds(long rowId) {
        return jdbc.queryForList("SELECT housing_type_id FROM supply_row_housing_types WHERE supply_row_id = ?", Long.class, rowId);
    }


    private SupplyRow fixture() {
        var fixtures = new NotificationFixtures(entities, jdbc);
        var announcement = fixtures.announcement(); var complex = fixtures.complex("11140", "1114010100");
        var type = HousingType.createFromMyHome(complex, UUID.randomUUID().toString(), "26", new BigDecimal("26.4673"), null);
        entities.persist(type);
        var row = SupplyRow.create(announcement, complex, type, UUID.randomUUID().toString(), 0, complex.getName(),
                "26", "1114010100100010000", null, SupplyCategory.NEW_SUPPLY, null, 10);
        entities.persist(row);
        entities.persist(SupplyTarget.create(row, "일반", null, 10, null, new BigDecimal("100000000"),
                new BigDecimal("200000"), null, "공고문 확인", 0));
        entities.flush();
        return row;
    }

    private ObjectNode request(long id) throws Exception {
        var workspace = json.readTree(mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var request = json.createObjectNode();
        request.put("operationId", UUID.randomUUID().toString());
        request.put("snapshotToken", workspace.get("snapshotToken").asText());
        request.put("reason", "공식 공고문과 공급 범위를 대조함");
        request.put("sourceUrl", "https://example.com/notice.pdf"); request.put("sourcePage", "3");
        request.set("rows", workspace.get("rows").deepCopy());
        return request;
    }

    private JsonNode preview(long id, ObjectNode request) throws Exception {
        return json.readTree(mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private ResultActions apply(long id, ObjectNode request, JsonNode preview)
            throws Exception {
        var body = json.createObjectNode().set("correction", request);
        body.put("confirmationToken", preview.get("confirmationToken").asText());
        return mvc.perform(post(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(body)));
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
