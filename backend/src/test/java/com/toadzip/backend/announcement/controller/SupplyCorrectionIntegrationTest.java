package com.toadzip.backend.announcement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.domain.SupplyTarget;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
    @Autowired private com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures myhomeSources;
    @Autowired private com.toadzip.backend.ingest.collection.fixture.repository.LhAnnouncementSupplySourceFixtures lhSupplies;
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

        apply(id, request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
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
    void 같은_작업_재전송은_추가행과_이력을_중복생성하지_않는다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        var extra = request.get("rows").get(0).deepCopy();
        ((ObjectNode) extra).putNull("id").put("key", UUID.randomUUID().toString()).put("typeName", "26B");
        ((ObjectNode) extra.get("targets").get(0)).putNull("id").put("key", UUID.randomUUID().toString());
        ((tools.jackson.databind.node.ArrayNode) request.get("rows")).add(extra);
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
        apply(id, request, preview).andExpect(status().isConflict());
        assertThat(row.getHousingComplex().getId()).isNotEqualTo(other.getHousingComplex().getId());
    }

    @Test
    void 보정쓰기에는_CSRF가_필요하다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        mvc.perform(post(root(id)).with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void 검토한_범위만_변경을_감지하고_보류와_이력을_목록에_연결한다() throws Exception {
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
        mvc.perform(get("/api/admin/quality/queue").param("resource", "ANNOUNCEMENT").param("reason", "STALE")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(id));
        entities.clear();
        review.put("snapshotToken", request(id).get("snapshotToken").asText()).put("outcome", "ON_HOLD");
        mvc.perform(post(root(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].status").value("ON_HOLD"))
                .andExpect(jsonPath("$.data.records[1].latest").value(false));
        mvc.perform(get("/api/admin/quality/queue").param("resource", "ANNOUNCEMENT").param("reason", "STALE")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
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
        source.putArray("fields").add("sum_suply_co");
        review.putArray("sources").add(source);
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

    private org.springframework.test.web.servlet.ResultActions reviews(long id) throws Exception {
        return mvc.perform(get(root(id) + "/reviews").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void 공통_면적은_영향을_확인하고_검토한_공고_면적을_보호한_뒤에만_수정한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId();
        var review = reviewRequest(row, "areas");
        mvc.perform(post(root(id) + "/reviews").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(review))).andExpect(status().isOk());
        String path = "/api/admin/housing-complexes/" + row.getHousingComplex().getId()
                + "/housing-types/" + row.getHousingType().getId();
        var impact = json.readTree(mvc.perform(get(path + "/impact").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].reviewedAreas").value(true))
                .andReturn().getResponse().getContentAsString()).get("data");
        var update = json.createObjectNode().put("version", row.getHousingComplex().getVersion())
                .put("name", "26 공통").put("exclusiveArea", 27).put("impactToken", impact.get("token").asText());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(update))).andExpect(status().isConflict());
        var correction = request(id);
        ((ObjectNode) correction.get("rows").get(0)).put("areaOverride", true);
        apply(id, correction, preview(id, correction)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(update))).andExpect(status().isConflict());
        var latest = json.readTree(mvc.perform(get(path + "/impact").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        update.put("impactToken", latest.get("token").asText());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(update))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/announcements/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supplyRows[0].exclusiveArea").value(26.4673));
        reviews(id).andExpect(jsonPath("$.data.records[0].status").value("VERIFIED"));
    }

    @Test
    void 모집수_합계는_범위를_확인한_경우에만_검증하고_큰_금액과_수동_주택형을_보존한다() throws Exception {
        var row = fixture(); long id = row.getAnnouncement().getId(); var request = request(id);
        long originalTypeId = row.getHousingType().getId();
        var input = (ObjectNode) request.get("rows").get(0);
        var condition = (ObjectNode) input.get("targets").get(0);
        condition.put("deposit", "999999999999999999");
        var second = condition.deepCopy().putNull("id").put("key", "additional").put("target", "중복 범위 대상");
        ((tools.jackson.databind.node.ArrayNode) input.get("targets")).add(second);
        preview(id, request);
        input.put("targetCountsComparable", true);
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
        input.put("targetCountsComparable", false).putNull("housingTypeId");
        input.putObject("newHousingType").put("name", "26").put("exclusiveArea", new BigDecimal("26.4673"));
        long events = jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events", Long.class);
        var preview = preview(id, request);
        apply(id, request, preview).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace.rows[0].targets[0].deposit").value("999999999999999999"));
        entities.flush(); entities.clear();
        var saved = rows.findById(row.getId()).orElseThrow();
        assertThat(saved.getHousingType().getId()).isNotEqualTo(originalTypeId);
        assertThat(saved.getHousingType().isAdminModified()).isTrue();
        assertThat(saved.updateDisplayOrder(999)).isFalse();
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
    void 작업실은_LH_공급행을_공고번호와_순번이_유일하게_맞는_LH_공급_원천에만_대응한다() throws Exception {
        var row = fixture();
        row.recordMyHomeSource("12:myhome-key:1");
        entities.flush();
        long id = row.getAnnouncement().getId();
        jdbc.update("UPDATE announcements SET lh_pan_id = 'PAN-77' WHERE id = ?", id);
        jdbc.update("UPDATE supply_rows SET lh_source_supply_row_identifier = 'LH:PAN-77:SUPPLY:1' WHERE id = ?", row.getId());
        entities.clear();
        lhSupplies.save(lhSupply("hash-a", 0));
        lhSupplies.save(lhSupply("hash-a", 1));
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowSources.length()").value(2))
                .andExpect(jsonPath("$.data.rowSources[0].category").value("MYHOME_ANNOUNCEMENT"))
                .andExpect(jsonPath("$.data.rowSources[1].rowKey").value("row:" + row.getId()))
                .andExpect(jsonPath("$.data.rowSources[1].category").value("LH_ANNOUNCEMENT_SUPPLY"))
                .andExpect(jsonPath("$.data.rowSources[1].sourceKey").value("PAN-77:hash-a:1"));

        lhSupplies.save(lhSupply("hash-b", 0));
        lhSupplies.save(lhSupply("hash-b", 1));
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowSources.length()").value(1))
                .andExpect(jsonPath("$.data.rowSources[0].category").value("MYHOME_ANNOUNCEMENT"));

        jdbc.update("UPDATE supply_rows SET lh_source_supply_row_identifier = 'LH:PAN-77:SUPPLY:9' WHERE id = ?", row.getId());
        entities.clear();
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowSources[1].category").value("LH_ANNOUNCEMENT_SUPPLY"))
                .andExpect(jsonPath("$.data.rowSources[1].sourceKey").value("LH:PAN-77:SUPPLY:9"));
    }

    private com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource lhSupply(String hash, int order) {
        return com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource.read(null, order, "PAN-77", hash,
                java.time.Instant.parse("2026-10-18T00:00:00Z"),
                new com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot(
                        "한스빌", "26", "26.4673", null, "300", "10", "1000000", "200000"));
    }

    @Test
    void 원천이_사라진_보정행은_보존하고_작업실과_검토목록에_재확인을_표시한다() throws Exception {
        var row = fixture();
        row.recordMyHomeSource("missing-source-key");
        row.protectConditionsByAdmin();
        entities.flush();
        long id = row.getAnnouncement().getId();
        mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceWarnings[0]").value(
                        org.hamcrest.Matchers.containsString("원천이 없거나 비활성")));
        mvc.perform(get("/api/admin/quality/queue").param("resource", "ANNOUNCEMENT")
                .param("reason", "SOURCE_CHANGED").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(id))
                .andExpect(jsonPath("$.data.items[0].sourceChanges").value(org.hamcrest.Matchers.contains("SUPPLY_SOURCE")));
        assertThat(rows.findById(row.getId())).isPresent();
    }

    @Test
    void 확인한_일정이_참조하는_마지막_단지연결을_제외할_수_없다() throws Exception {
        var row = fixture();
        var schedule = com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule.verified(
                row.getAnnouncement(), row.getHousingComplex(), null,
                com.toadzip.backend.announcement.domain.ApplicationScheduleState.CONFIRMED, null,
                java.time.LocalDate.of(2026, 10, 19), java.time.LocalDate.of(2026, 10, 20), null, null,
                "https://example.com/notice.pdf", 3);
        entities.persist(schedule); entities.flush();
        long id = row.getAnnouncement().getId(); var request = request(id);
        ((ObjectNode) request.get("rows").get(0)).put("excluded", true);
        mvc.perform(post(root(id) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isConflict());
        assertThat(row.isAdminExcluded()).isFalse();
    }

    @Test
    void PDF의_공통모집_분할과_임대조건을_함께_저장하고_미리보기는_원본을_유지한다() throws Exception {
        var row = groupedFixture();
        long id = row.getAnnouncement().getId();
        var request = groupRequest(row);
        var preview = preview(id, request);
        assertThat(rows.findAllByAnnouncement(row.getAnnouncement())).hasSize(1);
        assertThat(row.getSupplyGroup()).isNull();
        assertThat(preview.has("newGroups")).isTrue();
        assertThat(preview.get("newGroups").get(0).get("sharedHouseholdCount").asInt()).isEqualTo(170);

        apply(id, request, preview).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var original = rows.findById(row.getId()).orElseThrow();
        var saved = rows.findAllByAnnouncement(original.getAnnouncement());
        assertThat(saved).hasSize(2).allSatisfy(member -> {
            assertThat(member.getSupplyGroup().getId()).isEqualTo(original.getSupplyGroup().getId());
            assertThat(member.getSupplyGroup().getSharedHouseholdCount()).isEqualTo(170);
            assertThat(member.getTotalSupplyHouseholdCount()).isNull();
            assertThat(member.isAdminModified()).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM supply_targets WHERE supply_row_id = ?",
                Long.class, row.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rental_deposit FROM supply_targets WHERE supply_row_id = ?",
                BigDecimal.class, row.getId())).isEqualByComparingTo("4076000");
        apply(id, request, preview).andExpect(status().isOk()).andExpect(jsonPath("$.data.alreadyApplied").value(true));
        assertThat(rows.findAllByAnnouncement(original.getAnnouncement())).hasSize(2);
    }

    @Test
    void 같은_주택형으로_분할하거나_다른_공고의_원천행을_지정하면_아무것도_저장하지_않는다() throws Exception {
        var row = groupedFixture();
        var request = groupRequest(row);
        ((ObjectNode) request.get("rows").get(1)).put("housingTypeId", row.getHousingType().getId());
        mvc.perform(post(root(row.getAnnouncement().getId()) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        request = groupRequest(row);
        ((ObjectNode) request.get("newGroups").get(0)).put("sourceRowId", fixture().getId());
        mvc.perform(post(root(row.getAnnouncement().getId()) + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertThat(rows.findAllByAnnouncement(row.getAnnouncement())).hasSize(1);
        assertThat(row.getSupplyGroup()).isNull();
    }

    @Test
    void 공고_JSON_갱신에서_새_공통모집과_기본정보를_함께_저장한다() throws Exception {
        var row = groupedFixture();
        String root = "/api/admin/announcements/" + row.getAnnouncement().getId() + "/json-corrections";
        var state = json.readTree(mvc.perform(get(root).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var request = json.createObjectNode().put("snapshotToken", state.get("snapshotToken").asText());
        var document = (ObjectNode) json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../docs/fixtures/announcement-import-v1-valid.json")));
        ((ObjectNode) document.get("announcement")).put("name", "PDF 보정 공고");
        request.set("document", document);
        request.putArray("fields").add("name"); request.putArray("schedules"); request.putArray("attachments");
        request.set("correction", groupRequest(row));
        var preview = json.readTree(mvc.perform(post(root + "/preview").with(user("admin").roles("ADMIN"))
                .with(csrf()).contentType("application/json").content(request.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var apply = json.createObjectNode().put("confirmationToken", preview.get("confirmationToken").asText());
        apply.set("request", request);
        mvc.perform(post(root).with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json")
                .content(apply.toString())).andExpect(status().isOk());
        entities.flush(); entities.clear();
        var saved = rows.findById(row.getId()).orElseThrow();
        assertThat(saved.getAnnouncement().getName()).isEqualTo("PDF 보정 공고");
        assertThat(saved.getSupplyGroup().getSharedHouseholdCount()).isEqualTo(170);
        assertThat(rows.findAllByAnnouncement(saved.getAnnouncement())).hasSize(2);
    }

    @Test
    void 분할_미리보기_후_주택형이_바뀌면_409로_거절하고_저장하지_않는다() throws Exception {
        var row = groupedFixture();
        var request = groupRequest(row);
        var preview = preview(row.getAnnouncement().getId(), request);
        row.getHousingType().reviseByAdmin("변경된 주택형", new BigDecimal("29.1234"), null);
        entities.flush();
        apply(row.getAnnouncement().getId(), request, preview).andExpect(status().isConflict());
        assertThat(rows.findAllByAnnouncement(row.getAnnouncement())).hasSize(1);
        assertThat(row.getSupplyGroup()).isNull();
    }

    private SupplyRow groupedFixture() {
        var row = fixture();
        var source = com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource.from(1,
                new com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot(
                        row.getAnnouncement().getSourceAnnouncementIdentifier(), 1, "모집중", "국민임대 입주자 모집공고",
                        "한국토지주택공사", "26A,B", "국민임대", null, "20261001", "20261106", "20261008", "20261015",
                        null, "https://example.com/manual", null, null, row.getHousingComplex().getName(), "서울특별시", "중구",
                        null, null, null, row.getSupplyPnu(), null, null, 170, null, null, null, null));
        source.markCollectedAt(java.time.Instant.parse("2026-10-08T00:00:00Z"));
        myhomeSources.save(source);
        row.recordMyHomeSource(source.getSourceKey());
        row = rows.save(row);
        entities.flush();
        return row;
    }

    private ObjectNode groupRequest(SupplyRow row) throws Exception {
        var type = HousingType.createFromMyHome(row.getHousingComplex(), UUID.randomUUID().toString(),
                "26B", new BigDecimal("26.5195"), new BigDecimal("40.6486"));
        entities.persist(type); entities.flush();
        var request = request(row.getAnnouncement().getId());
        var first = (ObjectNode) request.get("rows").get(0);
        first.putNull("householdCount").put("typeName", "26A");
        ((ObjectNode) first.get("targets").get(0)).put("deposit", "4076000").putNull("householdCount");
        var second = first.deepCopy().putNull("id").put("key", "new-b").put("typeName", "26B")
                .put("housingTypeId", type.getId()).put("displayOrder", 1);
        ((ObjectNode) second.get("targets").get(0)).putNull("id").put("key", "new-target-b");
        ((tools.jackson.databind.node.ArrayNode) request.get("rows")).add(second);
        var group = request.putArray("newGroups").addObject().put("sourceRowId", row.getId())
                .put("sharedHouseholdCount", 170);
        group.putArray("rowKeys").add(first.get("key").asText()).add("new-b");
        return request;
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

    private org.springframework.test.web.servlet.ResultActions apply(long id, ObjectNode request, JsonNode preview)
            throws Exception {
        var body = json.createObjectNode().set("correction", request);
        body.put("confirmationToken", preview.get("confirmationToken").asText());
        return mvc.perform(post(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private String root(long id) { return "/api/admin/announcements/" + id + "/supply-corrections"; }
}
