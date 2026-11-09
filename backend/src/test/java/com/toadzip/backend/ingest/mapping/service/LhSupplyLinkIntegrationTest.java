package com.toadzip.backend.ingest.mapping.service;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import java.time.LocalDate;
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
import com.toadzip.backend.ingest.lh.collection.detail.domain.LhAnnouncementDetailSourceSnapshot;
import com.toadzip.backend.ingest.lh.collection.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.lh.collection.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.lh.collection.supply.domain.LhAnnouncementSupplySourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/** 마이홈 정제가 남긴 LH 공고의 연결 조회와 공급정보 보정 작업실을 검증한다. 연결 저장·해제는 지원을 종료했다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LhSupplyLinkIntegrationTest {
    private static final String TARGET = "manual-19585";
    private static final String PATH = "/api/admin/ingest/announcement-supply-matches/" + TARGET + "/lh-links";
    private static final String PNU = "4511311100100010000";
    @Autowired private MyHomeAnnouncementSourceFixtures sources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private SupplyCorrectionSources correctionSources;
    @Autowired private SupplyRowRepository supplies;
    @Autowired private JdbcClient jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private LhAnnouncementCollectionCandidateResolver candidateResolver;
    @Autowired private LhAnnouncementCollectionProgressStore progressStore;
    @Autowired private LhStorageFixtures lhStorage;
    private String panId;
    private HousingComplex complex;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, announcements, myhome_announcement_source_bundles,
                    myhome_announcement_mapping_failures, lh_announcement_enrichment_failures,
                    announcement_supply_match_decisions, lh_supply_row_links, admin_data_changes,
                    lh_announcement_query_sources CASCADE
                """).update();
        complex = complexes.save(HousingComplex.createFromMyHome("평화주공4단지", "complex-19585", "PERMANENT_RENTAL",
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
            progressStore.save(target, TARGET, candidate.requestDescription(), panId);
        }
    }

    @Test
    @org.springframework.test.context.jdbc.Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
    @org.springframework.test.context.jdbc.Sql(statements = "DROP TABLE admin_comparison_checks, admin_comparison_sources",
            executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void LH_수집_연결을_확정할_수_없는_공고도_공급정보_보정_작업실을_연다() throws Exception {
        registerFromSources();
        // 공고 등록 뒤 LH 수집 연결이 사라지면 LH 공급 원본을 고를 수 없다. 보정 작업실은 그래도 열려야 한다.
        jdbc.sql("DELETE FROM lh_announcement_collection_links").update();
        long id = jdbc.sql("SELECT id FROM announcements").query(Long.class).single();

        mvc.perform(get(correctionPath(id)).with(user("operator").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unattachedLhSupplyRows").isEmpty());
    }

    @Test
    void 조회는_관리자를_요구한다() throws Exception {
        String memberId = jdbc.sql("""
                        INSERT INTO users(login_identifier,created_at) VALUES (:identifier,CURRENT_TIMESTAMP)
                        RETURNING id
                        """).param("identifier", "google:lh-link-" + UUID.randomUUID())
                .query(Long.class).single().toString();
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(user(memberId).roles("USER"))).andExpect(status().isForbidden());
    }

    private String correctionPath(long id) {
        return "/api/admin/announcements/" + id + "/supply-corrections";
    }

    /** 지원을 종료한 마이홈 공고 정제가 남긴 공고처럼, 지금 원천의 해석 결과대로 공고와 공급행을 등록한다. */
    private void registerFromSources() {
        var first = sources.findAll().getFirst();
        var announcement = announcements.save(Announcement.create(TARGET, null, null, first.getPblancNm(),
                AnnouncementPublicationType.ORIGINAL, RentalType.PERMANENT_RENTAL, RecruitmentType.WAITLIST,
                AgencyCode.LH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 11, 6), first.getUrl(), null, 0L, null));
        int order = 0;
        for (var data : correctionSources.supplyRows(TARGET)) {
            var row = SupplyRow.create(announcement, complex, null, data.sourceSupplyRowIdentifier(), order++,
                    data.sourceComplexName(), data.sourceHousingTypeName(), data.pnu(), null, data.supplyCategory(),
                    null, data.totalSupplyHouseholdCount());
            row.recordMyHomeSource(data.source().getSourceKey());
            supplies.save(row);
        }
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
