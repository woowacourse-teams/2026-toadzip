package com.toadzip.backend.ingest.gh.refinement.service;

import static com.toadzip.backend.ingest.gh.collection.domain.GhAnnouncementBoard.RENTAL_HOUSE;
import static com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension.owned;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.fixture.SupplySheetInputs;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures;
import com.toadzip.backend.ingest.collection.fixture.GhAnnouncementFixtures.Row;
import com.toadzip.backend.ingest.failure.repository.ExternalDataCollectionFailureRepository;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementExternalRepository;
import com.toadzip.backend.ingest.gh.collection.repository.GhAnnouncementSourceRepository;
import com.toadzip.backend.ingest.gh.collection.service.GhAnnouncementCollectionService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** GH 원천이 정제·관리자 단지 확인·PDF 보정 공개를 거쳐 사용자에게 나가는 흐름과, 그 뒤 재정제가 관리자 작업을 덮지 않는지 본다. */
@SpringBootTest(properties = {"spring.main.web-application-type=servlet", "ingest.gh.request-interval=0s",
        "ingest.gh.lookback-days=0", "app.notification.publisher-enabled=true",
        "app.notification.activation-at=2026-01-01T00:00:00Z"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 트랜잭션 없이 커밋하는 테스트라, 보정 화면이 쓰는 품질 비교 표를 만든 뒤 지워 다른 테스트의 같은 스크립트와 겹치지 않게 한다.
@Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
@Sql(statements = {"DROP TABLE IF EXISTS admin_comparison_checks", "DROP TABLE IF EXISTS admin_comparison_sources"},
        executionPhase = ExecutionPhase.AFTER_TEST_METHOD)
class GhAnnouncementPublishFlowIntegrationTest {

    private static final Row ROW = new Row("801", "국민임대", "다산 국민임대주택 예비입주자 모집공고", "2026-07-10");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IngestExecutionOwnershipService ownership;
    @Autowired private GhAnnouncementRefinementService refinement;
    @Autowired private GhAnnouncementCollectionService collection;
    @Autowired private GhAnnouncementSourceRepository sources;
    @Autowired private ExternalDataCollectionFailureRepository failures;
    @Autowired private HousingComplexRepository complexes;
    @MockitoBean private GhAnnouncementExternalRepository externalRepository;

    @BeforeEach
    void housingTypeDetails() {
        when(externalRepository.fetchHousingTypeDetail(any(), any())).thenAnswer(invocation ->
                GhAnnouncementFixtures.housingTypeDetail(invocation.<Map<String, String>>getArgument(1).get("houseTyCd")));
    }

    @AfterEach
    void cleanUp() {
        String announcement = "(SELECT id FROM announcements WHERE source_announcement_identifier = 'GH:801')";
        jdbc.update("DELETE FROM notification_outbox_events WHERE source_identifier = 'GH:801'");
        jdbc.update("DELETE FROM admin_supply_corrections WHERE announcement_id IN " + announcement);
        jdbc.update("DELETE FROM admin_data_changes WHERE resource_type = 'ANNOUNCEMENT' AND resource_id IN "
                + announcement);
        jdbc.update("DELETE FROM supply_rows WHERE announcement_id IN " + announcement);
        jdbc.update("DELETE FROM announcement_complexes WHERE announcement_id IN " + announcement);
        jdbc.update("DELETE FROM announcement_attachments WHERE announcement_id IN " + announcement);
        jdbc.update("DELETE FROM announcements WHERE source_announcement_identifier = 'GH:801'");
        jdbc.update("DELETE FROM housing_complexes WHERE name IN ('잘못 고른 다산 단지', '관리자가 고른 다산 단지')");
        sources.deleteAll();
        failures.deleteAll();
    }

    @Test
    void 관리자가_고쳐_연결하고_정본을_시작해_공개를_요청한_GH_공고만_공개되고_그_뒤_재정제는_정본을_덮지_않는다() throws Exception {
        long wrong = complex("잘못 고른 다산 단지");
        collect(ROW);
        owned(ownership, () -> GhRefinementTestSupport.refineAll(refinement, sources));
        long id = announcementId();
        assertThat(lines(id)).extracting(row -> row.get("housing_complex_id")).containsOnly(wrong);
        assertThat(lines(id)).extracting(row -> row.get("announcement_complex_id")).containsOnly(dasanComplexId(id));
        assertThat(lines(id)).extracting(row -> row.get("supply_category")).containsOnly("RESUPPLY");
        assertThat(canonical(id)).isEmpty();

        long right = complex("관리자가 고른 다산 단지");
        var workspace = data("/api/admin/announcements/" + id + "/supply-corrections");
        var link = json.createObjectNode().put("snapshotToken", workspace.path("snapshotToken").asText())
                .put("housingComplexId", right);
        mvc.perform(post("/api/admin/announcements/" + id + "/supply-corrections/announcement-complexes/"
                        + dasanComplexId(id) + "/link").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(link.toString()))
                .andExpect(status().isOk());
        assertThat(lines(id)).extracting(row -> row.get("housing_complex_id")).containsOnly(right);

        // 확정 전에는 원천이 바뀌어도 준비 줄이 원천을 따르고 재확인 대상이 아니다.
        changeSourceSupplyCount("120", "90");
        assertThat(owned(ownership, () -> GhRefinementTestSupport.refineAll(refinement, sources)).supplyRecheckRequired()).isZero();
        assertThat(lines(id)).extracting(row -> row.get("household_count")).containsExactly(90, 60);
        assertThat(lines(id)).extracting(row -> row.get("housing_complex_id")).containsOnly(right);
        assertThat(supplyRecheckRequired(id)).isFalse();

        var started = data("/api/admin/announcements/" + id + "/supply-corrections");
        mvc.perform(post("/api/admin/announcements/" + id + "/supply-corrections/start-from-staging")
                        .with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("snapshotToken", started.path("snapshotToken").asText())
                                .put("stagingToken", started.path("stagingToken").asText()).toString()))
                .andExpect(status().isOk());
        assertThat(canonical(id)).extracting(row -> row.get("winner_count")).containsExactly(90, 60);
        assertThat(canonical(id)).extracting(row -> row.get("announcement_complex_id")).containsOnly(dasanComplexId(id));

        var correction = request(id);
        correction.withArray("fields").add("rentalType").add("recruitmentType").add("applicationStartDate")
                .add("applicationEndDate").add("winnerAnnouncementDate");
        correction.put("publish", true);
        apply(id, correction);
        assertThat(releaseStatus(id)).isEqualTo("PUBLISHED");
        assertThat(supplyRecheckRequired(id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox_events WHERE source_identifier = 'GH:801'",
                Long.class)).isOne();

        collect(new Row("801", ROW.supplyType(), "[정정] " + ROW.title(), ROW.postedDate()));
        // 다시 수집하면 지구 원천이 수집 응답(120)으로 돌아온다. 그 값을 바꿔 확정한 정본과 다르게 한다.
        changeSourceSupplyCount("120", "80");
        owned(ownership, () -> GhRefinementTestSupport.refineAll(refinement, sources));
        assertThat(jdbc.queryForObject("SELECT name FROM announcements WHERE id = ?", String.class, id))
                .isEqualTo(ROW.title());
        assertThat(jdbc.queryForObject("SELECT source_review_required FROM announcements WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(canonical(id)).extracting(row -> row.get("winner_count")).containsExactly(90, 60);
        assertThat(lines(id)).extracting(row -> row.get("household_count")).containsExactly(80, 60);
        assertThat(supplyRecheckRequired(id)).isTrue();
    }

    @Test
    void GH_공고_작업실은_수집한_단지별_주택형_줄을_검산_참고로_내보낸다() throws Exception {
        collect(ROW);
        owned(ownership, () -> GhRefinementTestSupport.refineAll(refinement, sources));
        long id = announcementId();

        var workspace = data("/api/admin/announcements/" + id + "/supply-corrections");

        // 작업실은 정본에서 시작한다. 확정 전 공고는 정본이 비어 있고 준비 줄은 검산 참고로만 보인다.
        assertThat(workspace.path("supplyConfirmed").asBoolean()).isFalse();
        assertThat(workspace.path("sheet").path("housingTypes")).isEmpty();
        var references = new ArrayList<List<String>>();
        workspace.path("sourceReference").forEach(reference -> references.add(List.of(
                reference.path("complexLabel").asText(), reference.path("typeName").asText(),
                reference.path("exclusiveArea").asText(), reference.path("householdCount").asText())));
        assertThat(references).hasSize(2);
        assertThat(references.get(0)).containsExactly("다산 센트럴파크6단지", references.get(0).get(1), "33.71", "120");
        assertThat(references.get(0).get(1)).contains("33A");
        assertThat(references.get(1)).containsExactly("다산 센트럴파크6단지", references.get(1).get(1), "36.8", "60");
        assertThat(references.get(1).get(1)).contains("36A");
    }

    private long complex(String name) {
        return complexes.saveAndFlush(HousingComplex.create(name, UUID.randomUUID().toString(), "NATIONAL_RENTAL",
                Address.create("경기도 남양주시 다산순환로 20", "4136010100161070000", "4136010100", "41", "41360",
                        new BigDecimal("37.600000"), new BigDecimal("127.150000")),
                100, "GH", LocalDate.of(2020, 6, 30), "INDIVIDUAL", "APARTMENT", "STAIR", true, 80, null, 0)).getId();
    }

    private void collect(Row row) {
        when(externalRepository.fetchList(RENTAL_HOUSE, 1)).thenReturn(GhAnnouncementFixtures.list(RENTAL_HOUSE, 1, 1, row));
        when(externalRepository.fetchDetail(RENTAL_HOUSE, row.pbancNo(), "01"))
                .thenReturn(GhAnnouncementFixtures.detail(RENTAL_HOUSE, row));
        owned(ownership, collection::collect);
    }

    private void changeSourceSupplyCount(String from, String to) {
        int changed = jdbc.update("""
                UPDATE gh_announcement_source SET districts = replace(districts, ?, ?)
                WHERE pbanc_no = '801' AND strpos(districts, ?) > 0
                """, "\"supplyCount\":\"" + from + "\"", "\"supplyCount\":\"" + to + "\"",
                "\"supplyCount\":\"" + from + "\"");
        assertThat(changed).isOne();
    }

    private long announcementId() {
        return jdbc.queryForObject("SELECT id FROM announcements WHERE source_announcement_identifier = 'GH:801'",
                Long.class);
    }

    private long dasanComplexId(long id) {
        return jdbc.queryForObject("""
                SELECT id FROM announcement_complexes WHERE announcement_id = ? AND source_complex_name = '다산 센트럴파크6단지'
                """, Long.class, id);
    }

    /** 공고 공급표(정본) 공급행. */
    private List<Map<String, Object>> canonical(long id) {
        return jdbc.queryForList("""
                SELECT r.announcement_complex_id,
                    (SELECT sum(q.household_count) FROM supply_quotas q WHERE q.kind = 'WINNER' AND EXISTS (
                        SELECT 1 FROM supply_quota_members m JOIN supply_row_targets c ON c.id = m.supply_row_target_id
                        WHERE m.supply_quota_id = q.id AND c.supply_row_id = r.id))::int AS winner_count
                FROM supply_rows r WHERE r.announcement_id = ? ORDER BY r.display_order
                """, id);
    }

    /** 기관 준비 줄. 단지는 줄의 공고 단지가 고른 제품 단지다. */
    private List<Map<String, Object>> lines(long id) {
        return jdbc.queryForList("""
                SELECT c.housing_complex_id, l.announcement_complex_id, l.household_count, l.supply_category
                FROM agency_supply_lines l LEFT JOIN announcement_complexes c ON c.id = l.announcement_complex_id
                WHERE l.announcement_id = ? ORDER BY l.display_order
                """, id);
    }

    private boolean supplyRecheckRequired(long id) {
        return jdbc.queryForObject("SELECT supply_recheck_required FROM announcements WHERE id = ?", Boolean.class, id);
    }

    private String releaseStatus(long id) {
        return jdbc.queryForObject("SELECT release_status FROM announcements WHERE id = ?", String.class, id);
    }

    private ObjectNode request(long id) throws Exception {
        var state = data("/api/admin/announcements/" + id + "/json-corrections");
        var document = (ObjectNode) json.readTree(new ClassPathResource("announcement/import-v1-valid.json")
                .getInputStream());
        var request = json.createObjectNode();
        request.put("snapshotToken", state.path("snapshotToken").asText());
        request.set("document", document);
        request.putArray("fields");
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

    private void apply(long id, ObjectNode request) throws Exception {
        String token = json.readTree(mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections/preview")
                        .with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(request.toString())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).path("data").path("confirmationToken").asText();
        var body = json.createObjectNode();
        body.set("request", request);
        body.put("confirmationToken", token);
        mvc.perform(post("/api/admin/announcements/" + id + "/json-corrections").with(user("admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk());
    }

    private JsonNode data(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }
}
