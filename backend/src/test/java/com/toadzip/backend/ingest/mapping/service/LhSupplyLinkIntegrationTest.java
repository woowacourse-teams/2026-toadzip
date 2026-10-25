package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource;
import com.toadzip.backend.ingest.collection.lh.supply.domain.projection.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.mapping.dto.LhSupplyLinking.View;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 단지명 표기가 달라 자동으로 붙지 않는 LH 공급행을 관리자가 연결하는 흐름을 실제 정제·보강으로 검증한다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LhSupplyLinkIntegrationTest {
    private static final String TARGET = "manual-19585";
    private static final String PATH = "/api/admin/ingest/announcement-supply-matches/" + TARGET + "/lh-links";
    private static final String PNU = "4511311100100010000";
    private static final String OFFICIAL_URL = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do";
    @Autowired private MyHomeAnnouncementSourceFixtures sources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private SupplyRowRepository supplies;
    @Autowired private JdbcClient jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private LhAnnouncementCollectionCandidateResolver candidateResolver;
    @Autowired private LhAnnouncementCollectionProgressStore progressStore;
    @Autowired private MyHomeAnnouncementMappingService mapping;
    @Autowired private LhAnnouncementEnrichmentService enrichment;
    @Autowired private LhStorageFixtures lhStorage;
    private String panId;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, announcements, myhome_announcement_source_bundles,
                    myhome_announcement_mapping_failures, lh_announcement_enrichment_failures,
                    announcement_supply_match_decisions, lh_supply_row_links, admin_data_changes,
                    lh_announcement_query_sources CASCADE
                """).update();
        var complex = complexes.save(HousingComplex.createFromMyHome("평화주공4단지", "complex-19585", "PERMANENT_RENTAL",
                Address.create("전북특별자치도 전주시 완산구 평화로 1", PNU, PNU.substring(0, 10), "52", "52111",
                        new BigDecimal("35.8"), new BigDecimal("127.1")),
                300, "LH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 80));
        types.save(HousingType.createFromMyHome(complex, "complex-19585:26", "26",
                new BigDecimal("26.3700"), new BigDecimal("38.9756")));
        var source = sources.save(source());
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        panId = candidate.panId();
        lhStorage.replaceDetails(panId, candidate.requestDescription(), List.of(new LhAnnouncementDetailSource(
                0, panId, "COMPLEX", "전주평화4 영구임대주택", "전북특별자치도 전주시 완산구 평화로 1",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)));
        // LH 표기가 마이홈 단지명과 다르고 모집수도 달라 자동 연결 규칙으로는 이을 수 없다.
        lhStorage.replaceSupplies(panId, candidate.requestDescription(), List.of(new LhAnnouncementSupplySource(0,
                panId, new LhAnnouncementSupplySourceSnapshot("전주평화4 영구임대주택", "26", "26.37", "38.9756",
                        "300", "12", "4076000", "72820"))));
        for (var target : List.of(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            progressStore.complete(target, TARGET, candidate.requestDescription(), panId);
        }
    }

    @Test
    void 연결하지_않은_LH_공급행은_LH_보강에서_단지를_찾지_못한다() {
        mapping.mapAll();
        enrichment.enrichAll();

        assertThat(supplies.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getLhSourceSupplyRowIdentifier()).isNull());
        assertThat(pendingEnrichmentFailures()).containsExactly("COMPLEX_NOT_FOUND");
    }

    @Test
    void 조회하면_실패한_LH_행과_비교할_공급행_후보를_현재_데이터로_알려준다() throws Exception {
        mapping.mapAll();

        var view = view();

        assertThat(view.announcement().name()).isEqualTo("영구임대 입주자 모집공고");
        assertThat(view.announcement().panId()).isEqualTo(panId);
        assertThat(view.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.lhRowIdentifier()).isEqualTo("LH:" + panId + ":SUPPLY:0");
            assertThat(failure.reason()).isEqualTo("COMPLEX_NOT_FOUND");
            assertThat(failure.candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.sourceComplexName()).isEqualTo("평화주공4단지");
                assertThat(candidate.sourceHousingTypeName()).isEqualTo("26");
                assertThat(candidate.complexName()).isEqualTo("평화주공4단지");
                assertThat(candidate.myhomeRowIdentifier()).isEqualTo(view.myhomeRows().getFirst().rowIdentifier());
            });
        });
        save(view, "공고문 단지 목록 대조").andExpect(status().isOk());
        mapping.mapAll();
        assertThat(view().failures()).isEmpty();
    }

    @Test
    void 관리자가_연결하면_다음_정제와_LH_보강이_그_원천행으로_공급행을_잇는다() throws Exception {
        var before = view();
        assertThat(before.lhRows()).singleElement().satisfies(row -> {
            assertThat(row.linkedRowIdentifier()).isNull();
            assertThat(row.savedLink()).isNull();
        });

        var after = read(save(before, "공고문 단지 목록의 전주평화4가 평화주공4단지임을 확인함")
                .andExpect(status().isOk()));
        mapping.mapAll();
        enrichment.enrichAll();

        String myhomeRow = before.myhomeRows().getFirst().rowIdentifier();
        assertThat(after.lhRows().getFirst().linkedRowIdentifier()).isEqualTo(myhomeRow);
        assertThat(after.lhRows().getFirst().savedLink().reviewRequired()).isFalse();
        assertThat(supplies.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getLhSourceSupplyRowIdentifier()).isEqualTo("LH:" + panId + ":SUPPLY:0");
            assertThat(row.getSourceHousingTypeName()).isEqualTo("26");
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(12);
        });
        assertThat(pendingMappingFailures()).isEmpty();
        assertThat(pendingEnrichmentFailures()).isEmpty();
        assertThat(jdbc.sql("SELECT actor FROM admin_data_changes WHERE action = 'SAVE_LH_SUPPLY_LINK'")
                .query(String.class).list()).containsExactly("operator");
    }

    @Test
    void 정제를_다시_하지_않아도_LH_보강은_저장한_연결의_원천행에서_공급행을_찾는다() throws Exception {
        mapping.mapAll();
        save(view(), "공고문 단지 목록 대조").andExpect(status().isOk());

        enrichment.enrichAll();

        assertThat(pendingEnrichmentFailures()).isEmpty();
        assertThat(supplies.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(12));
    }

    @Test
    void LH_연결로_원천행의_LH_행_구성이_바뀌면_그_행의_저장_매칭을_해제하고_구성이_같으면_유지한다() throws Exception {
        mapping.mapAll();
        var view = view();
        String myhomeRow = view.myhomeRows().getFirst().rowIdentifier();
        saveDecision(myhomeRow);

        var linked = read(save(view, "공고문 단지 목록 대조").andExpect(status().isOk()));

        assertThat(linked.releasedSupplyMatches()).containsExactly(myhomeRow);
        assertThat(decisionCount()).isZero();
        assertThat(jdbc.sql("SELECT after_value FROM admin_data_changes WHERE action = 'RELEASE_SUPPLY_MATCH'")
                .query(String.class).single()).contains(myhomeRow, "LH 행 구성이 바뀌어");
        mapping.mapAll();
        assertThat(pendingMappingFailures()).isEmpty();

        saveDecision(myhomeRow);
        var unchanged = read(save(linked, "같은 연결을 다시 확인").andExpect(status().isOk()));

        assertThat(unchanged.releasedSupplyMatches()).isEmpty();
        assertThat(decisionCount()).isOne();
    }

    @Test
    @org.springframework.test.context.jdbc.Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
    @org.springframework.test.context.jdbc.Sql(statements = "DROP TABLE admin_comparison_checks, admin_comparison_sources",
            executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void LH_원본행이_공급행에_붙지_않았으면_PDF_보정은_새_공급행을_거절한다() throws Exception {
        mapping.mapAll();
        long id = jdbc.sql("SELECT id FROM announcements").query(Long.class).single();
        var workspace = json.readTree(mvc.perform(get(correctionPath(id)).with(user("operator").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(workspace.get("unattachedLhSupplyRows")).singleElement().satisfies(row -> {
            assertThat(row.get("rowIdentifier").asText()).isEqualTo("LH:" + panId + ":SUPPLY:0");
            assertThat(row.get("complexLabel").asText()).isEqualTo("전주평화4 영구임대주택");
        });
        var request = json.createObjectNode();
        request.put("operationId", UUID.randomUUID().toString());
        request.put("snapshotToken", workspace.get("snapshotToken").asText());
        request.put("reason", "공고문 공급 표 대조");
        request.put("sourceUrl", OFFICIAL_URL);
        var rows = workspace.get("rows").deepCopy();
        var extra = (ObjectNode) rows.get(0).deepCopy();
        extra.putNull("id").put("key", UUID.randomUUID().toString()).put("typeName", "26B");
        extra.putArray("targets");
        ((ArrayNode) rows).add(extra);
        request.set("rows", rows);

        mvc.perform(post(correctionPath(id) + "/preview").with(user("operator").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(
                        "LH 공급 원본 1행이 아직 공급행에 붙지 않았습니다.")));
    }

    @Test
    void 연결한_LH_원천이_바뀌면_재확인을_요구하고_다시_저장하면_재적용한다() throws Exception {
        save(view(), "공고문 단지 목록 대조").andExpect(status().isOk());
        mapping.mapAll();
        var stale = view();
        jdbc.sql("UPDATE lh_announcement_supply_rows SET supplied_unit_count = '13'").update();

        enrichment.enrichAll();
        var changed = view();
        save(stale, "오래된 화면에서 저장").andExpect(status().isConflict());

        assertThat(changed.lhRows().getFirst().savedLink().reviewRequired()).isTrue();
        assertThat(changed.lhRows().getFirst().linkedRowIdentifier()).isNull();
        assertThat(pendingEnrichmentFailures()).containsExactly("LH_SUPPLY_LINK_REVIEW_REQUIRED");
        save(changed, "변경된 모집수를 공고문 정정본으로 다시 확인함").andExpect(status().isOk());
        mapping.mapAll();
        enrichment.enrichAll();
        assertThat(pendingEnrichmentFailures()).isEmpty();
        assertThat(supplies.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(13));
    }

    @Test
    void 연결을_이유와_함께_해제하면_다음_정제부터_자동_연결로_돌아간다() throws Exception {
        var saved = read(save(view(), "공고문 단지 목록 대조").andExpect(status().isOk()));

        var released = read(release(saved, "다른 단지로 잘못 연결함").andExpect(status().isOk()));
        mapping.mapAll();
        enrichment.enrichAll();

        assertThat(released.lhRows().getFirst().savedLink()).isNull();
        assertThat(released.lhRows().getFirst().linkedRowIdentifier()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM lh_supply_row_links").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT after_value FROM admin_data_changes WHERE action = 'RELEASE_LH_SUPPLY_LINK'")
                .query(String.class).single()).contains("다른 단지로 잘못 연결함");
        assertThat(pendingEnrichmentFailures()).containsExactly("COMPLEX_NOT_FOUND");
    }

    @Test
    void 근거가_없거나_다른_공고의_LH_행이면_저장하지_않는다() throws Exception {
        var view = view();
        String myhomeRow = view.myhomeRows().getFirst().rowIdentifier();

        saveBody(Map.of("token", view.token(), "myhomeRowIdentifier", myhomeRow,
                "lhRowIdentifiers", List.of(view.lhRows().getFirst().rowIdentifier()),
                "sourceUrl", "ftp://example.com", "reason", "근거")).andExpect(status().isBadRequest());
        saveBody(Map.of("token", view.token(), "myhomeRowIdentifier", myhomeRow,
                "lhRowIdentifiers", List.of("LH:other:SUPPLY:0"), "sourceUrl", OFFICIAL_URL, "reason", "근거"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.sql("SELECT count(*) FROM lh_supply_row_links").query(Long.class).single()).isZero();
    }

    @Test
    void 조회와_저장과_해제는_관리자와_CSRF를_요구한다() throws Exception {
        String memberId = jdbc.sql("""
                        INSERT INTO users(login_identifier,created_at) VALUES (:identifier,CURRENT_TIMESTAMP)
                        RETURNING id
                        """).param("identifier", "google:lh-link-" + java.util.UUID.randomUUID())
                .query(Long.class).single().toString();
        var body = json.writeValueAsString(Map.of("token", "token", "lhRowIdentifier", "LH:1:SUPPLY:0",
                "reason", "근거"));

        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(user(memberId).roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/release").with(user("operator").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(PATH).with(user(memberId).roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    private ResultActions save(View view, String reason) throws Exception {
        return saveBody(Map.of("token", view.token(), "myhomeRowIdentifier", view.myhomeRows().getFirst().rowIdentifier(),
                "lhRowIdentifiers", List.of(view.lhRows().getFirst().rowIdentifier()),
                "sourceUrl", OFFICIAL_URL, "reason", reason));
    }

    private ResultActions release(View view, String reason) throws Exception {
        return mvc.perform(post(PATH + "/release").with(user("operator").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "token", view.token(), "lhRowIdentifier", view.lhRows().getFirst().rowIdentifier(),
                        "reason", reason))));
    }

    private ResultActions saveBody(Map<String, Object> body) throws Exception {
        return mvc.perform(post(PATH)
                .with(user("operator").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private void saveDecision(String rowIdentifier) {
        jdbc.sql("""
                INSERT INTO announcement_supply_match_decisions (version, row_identifier, complex_id, housing_type_id,
                    source_fingerprint, target_fingerprint, source_url, reason, actor, updated_at)
                SELECT 0, :row, complex.id, type.id, 'source', 'target', :url, 'PDF 보정', 'operator', now()
                FROM housing_complexes complex JOIN housing_types type ON type.housing_complex_id = complex.id
                """).param("row", rowIdentifier).param("url", OFFICIAL_URL).update();
    }

    private long decisionCount() {
        return jdbc.sql("SELECT count(*) FROM announcement_supply_match_decisions").query(Long.class).single();
    }

    private String correctionPath(long id) {
        return "/api/admin/announcements/" + id + "/supply-corrections";
    }

    private View view() throws Exception {
        return read(mvc.perform(get(PATH).with(user("operator").roles("ADMIN"))).andExpect(status().isOk()));
    }

    private View read(ResultActions result) throws Exception {
        return json.readValue(result.andReturn().getResponse().getContentAsString(), View.class);
    }

    private List<String> pendingMappingFailures() {
        return jdbc.sql("SELECT reason || ' ' || detail FROM myhome_announcement_mapping_failures "
                + "WHERE status = 'PENDING'").query(String.class).list();
    }

    private List<String> pendingEnrichmentFailures() {
        return jdbc.sql("SELECT reason FROM lh_announcement_enrichment_failures WHERE status = 'PENDING'")
                .query(String.class).list();
    }

    private MyHomeAnnouncementSource source() {
        var source = MyHomeAnnouncementSource.from(1, new MyHomeAnnouncementSourceSnapshot(TARGET,
                1, "모집중", "영구임대 입주자 모집공고", "LH", "26", "영구임대", null,
                "20261001", "20261106", "20261008", "20261015", null,
                "https://example.com/announcements?panId=lh-19585&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07",
                null, null, "평화주공4단지", "전북특별자치도", "전주시", null, null, null, PNU, null, null,
                300, null, null, null, null));
        source.markCollectedAt(Instant.parse("2026-10-08T00:00:00Z"));
        return source;
    }
}
