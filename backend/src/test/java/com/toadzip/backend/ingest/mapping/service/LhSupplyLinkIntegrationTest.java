package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension.owned;
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
import com.toadzip.backend.ingest.collection.fixture.LhSourceViews;
import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** 단지명 표기가 달라 자동으로 붙지 않는 LH 공급행을 관리자가 연결하는 흐름을 실제 정제·보강으로 검증한다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LhSupplyLinkIntegrationTest {
    private static final String TARGET = "manual-19585";
    private static final String PATH = "/api/admin/ingest/announcement-supply-matches/" + TARGET + "/lh-links";
    private static final String PNU = "4511311100100010000";
    private static final String RETIRED_MESSAGE =
            "마이홈 공고 정제·LH 보강과 그 보정은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다.";
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
    @Autowired private IngestExecutionOwnershipService ownership;
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
        lhStorage.replaceDetails(panId, candidate.requestDescription(), List.of(LhSourceViews.detail(0, panId,
                new LhAnnouncementDetailSourceSnapshot(
                "COMPLEX", "전주평화4 영구임대주택", "전북특별자치도 전주시 완산구 평화로 1", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null))));
        // LH 표기가 마이홈 단지명과 다르고 모집수도 달라 자동 연결 규칙으로는 이을 수 없다.
        lhStorage.replaceSupplies(panId, candidate.requestDescription(), List.of(LhSourceViews.supply(0,
                panId, new LhAnnouncementSupplySourceSnapshot("전주평화4 영구임대주택", "26", "26.37", "38.9756",
                        "300", "12", "4076000", "72820"))));
        for (var target : List.of(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            progressStore.complete(target, TARGET, candidate.requestDescription(), panId);
        }
    }

    @Test
    void 연결하지_않은_LH_공급행은_LH_보강에서_단지를_찾지_못한다() {
        owned(ownership, mapping::mapAll);
        owned(ownership, enrichment::enrichAll);

        assertThat(supplies.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getLhSourceSupplyRowIdentifier()).isNull());
        assertThat(pendingEnrichmentFailures()).containsExactly("COMPLEX_NOT_FOUND");
    }

    @Test
    @org.springframework.test.context.jdbc.Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
    @org.springframework.test.context.jdbc.Sql(statements = "DROP TABLE admin_comparison_checks, admin_comparison_sources",
            executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void LH_수집_연결을_확정할_수_없는_공고도_공급정보_보정_작업실을_연다() throws Exception {
        owned(ownership, mapping::mapAll);
        // 공고 등록 뒤 LH 수집 연결이 사라지면 LH 공급 원본을 고를 수 없다. 보정 작업실은 그래도 열려야 한다.
        jdbc.sql("DELETE FROM lh_announcement_collection_links").update();
        long id = jdbc.sql("SELECT id FROM announcements").query(Long.class).single();

        mvc.perform(get(correctionPath(id)).with(user("operator").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unattachedLhSupplyRows").isEmpty());
    }

    @Test
    void 조회와_저장과_해제는_관리자와_CSRF를_요구한다() throws Exception {
        String memberId = jdbc.sql("""
                        INSERT INTO users(login_identifier,created_at) VALUES (:identifier,CURRENT_TIMESTAMP)
                        RETURNING id
                        """).param("identifier", "google:lh-link-" + UUID.randomUUID())
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

    @ParameterizedTest
    @ValueSource(strings = {"", "/release"})
    void 지원을_종료한_LH_연결_저장과_해제는_관리자_요청도_410으로_거절하고_연결을_남기지_않는다(String action)
            throws Exception {
        owned(ownership, mapping::mapAll);
        var body = json.writeValueAsString(Map.of("token", "token", "myhomeRowIdentifier", "row",
                "lhRowIdentifiers", List.of("LH:1:SUPPLY:0"), "reason", "근거"));

        mvc.perform(post(PATH + action).with(user("operator").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"))
                .andExpect(jsonPath("$.message").value(RETIRED_MESSAGE));

        assertThat(jdbc.sql("SELECT count(*) FROM lh_supply_row_links").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM admin_data_changes").query(Long.class).single()).isZero();
    }

    private String correctionPath(long id) {
        return "/api/admin/announcements/" + id + "/supply-corrections";
    }

    private List<String> pendingEnrichmentFailures() {
        return jdbc.sql("SELECT reason FROM lh_announcement_enrichment_failures WHERE status = 'PENDING'")
                .query(String.class).list();
    }

    private MyHomeAnnouncementSourceView source() {
        return source(1, "평화주공4단지", PNU);
    }

    private MyHomeAnnouncementSourceView source(int order, String complexName, String pnu) {
        var source = MyHomeSourceViews.announcement(order, new MyHomeAnnouncementSourceSnapshot(TARGET,
                order, "모집중", "영구임대 입주자 모집공고", "LH", "26", "영구임대", null,
                "20261001", "20261106", "20261008", "20261015", null,
                "https://example.com/announcements?panId=lh-19585&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07",
                null, null, complexName, "전북특별자치도", "전주시", null, null, null, pnu, null, null,
                300, null, null, null, null));
        source = MyHomeSourceViews.collectedAt(source, Instant.parse("2026-10-08T00:00:00Z"));
        return source;
    }
}
