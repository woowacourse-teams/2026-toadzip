package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementPublicationType;
import com.toadzip.backend.announcement.domain.RecruitmentType;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.housing.domain.AgencyCode;
import com.toadzip.backend.housing.domain.RentalType;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.MyHomeSourceViews;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementSupplyMatchingIntegrationTest {
    private static final String TARGET = "manual-21395";
    private static final String PNU = "2823710500105500000";
    @Autowired private AnnouncementSupplyRowQueryService rowQuery;
    @Autowired private AnnouncementSupplyCandidateService candidateService;
    @Autowired private MyHomeAnnouncementSourceFixtures sources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private SupplyRowRepository supplies;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JdbcClient jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private SupplyCorrectionSources correctionSources;
    private HousingComplex complex;
    private HousingType type;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, announcements, myhome_announcement_source_bundles,
                    myhome_announcement_mapping_failures, announcement_supply_match_decisions,
                    admin_data_changes, lh_announcement_query_sources CASCADE
                """).update();
        complex = complex("한스빌아파트", "complex-21395", PNU, "NATIONAL_RENTAL");
        type = types.save(HousingType.createFromMyHome(complex, "complex-21395:26", "26",
                new BigDecimal("26.0000"), new BigDecimal("40.0000")));
        sources.save(source(TARGET, 1, PNU));
    }

    @Test
    void 단지_검색과_주택형_조회는_해당_단지의_기존_데이터만_반환한다() {
        assertThat(candidateService.complexes("한스빌")).singleElement()
                .satisfies(value -> {
                    assertThat(value.id()).isEqualTo(complex.getId());
                    assertThat(value.pnu()).isEqualTo(complex.getAddress().getPnu());
                    assertThat(value.totalHouseholdCount()).isEqualTo(complex.getTotalHouseholdCount());
                    assertThat(value.housingTypes()).singleElement().satisfies(area -> {
                        assertThat(area.id()).isEqualTo(type.getId());
                        assertThat(area.exclusiveArea()).isEqualByComparingTo(type.getExclusiveArea());
                    });
                });
        assertThat(candidateService.housingTypes(complex.getId())).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(type.getId()));
    }

    @Test
    void 주택형_조회는_PDF_보정이_정한_기본_임대조건의_출처를_함께_준다() {
        type.observeAnnouncement(new HousingType.AnnouncementValues(null, null, null, 8_100_000L, 156_230L),
                Instant.parse("2026-10-01T00:00:00Z"));
        types.save(type);

        assertThat(candidateService.housingTypes(complex.getId())).singleElement().satisfies(value -> {
            assertThat(value.basicDeposit()).isEqualTo(8_100_000L);
            assertThat(value.rentalConditionSource()).isEqualTo("ANNOUNCEMENT");
        });
    }

    @Test
    void 단지_검색에_원천_PNU를_주면_이름이_다른_같은_필지_단지를_먼저_후보로_준다() {
        var main = complex("중촌주공2단지", "complex-main", PNU, "NATIONAL_RENTAL");
        complex("한스빌 다른필지", "complex-other", "1111111111111111111", "NATIONAL_RENTAL");

        assertThat(candidateService.complexes("한스빌", PNU)).extracting(value -> value.id())
                .containsExactly(complex.getId(), main.getId(), complexes.findBySourceComplexIdentifier("complex-other")
                        .orElseThrow().getId());
        assertThat(candidateService.complexes("한스빌")).extracting(value -> value.id()).doesNotContain(main.getId());
        assertThatThrownBy(() -> candidateService.complexes("한스빌", "12a")).hasMessageContaining("PNU");
    }

    @Test
    @org.springframework.test.context.jdbc.Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
    @org.springframework.test.context.jdbc.Sql(statements = "DROP TABLE admin_comparison_checks, admin_comparison_sources",
            executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void PDF_보정으로_연결한_공급행은_대표_주택형과_저장한_매칭을_함께_남긴다() throws Exception {
        registerFromSources();
        long announcementId = announcements.findBySourceAnnouncementIdentifier(TARGET).orElseThrow().getId();

        correctFromPdf(announcementId, type);
        // PDF 보정이 정한 대표 주택형이 함께 모집하는 주택형에도 들어간다.
        assertThat(jdbc.sql("SELECT housing_type_id FROM supply_row_housing_types").query(Long.class).list())
                .containsExactly(type.getId());
        assertLinksFollowRepresentative();
        assertThat(jdbc.sql("SELECT housing_type_id, reason FROM announcement_supply_match_decisions")
                .query((rs, rowNum) -> List.of(rs.getLong(1), rs.getString(2))).list())
                .containsExactly(List.of(type.getId(), "공고문 PDF 4쪽에서 주택형 확인"));
        assertThat(supplies.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getHousingType().getId()).isEqualTo(type.getId()));
    }

    /** PDF 보정이 쓴 공급행은 함께 모집하는 주택형이 대표 주택형 하나뿐이다. */
    private void assertLinksFollowRepresentative() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM supply_rows row
                FULL JOIN supply_row_housing_types link ON link.supply_row_id = row.id
                WHERE link.housing_type_id IS DISTINCT FROM row.housing_type_id
                """).query(Long.class).single()).isZero();
    }

    @Test
    @Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
    @Sql(statements = "DROP TABLE admin_comparison_checks, admin_comparison_sources",
            executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void PDF_보정에서_주택형_연결을_지우면_저장한_매칭도_해제한다() throws Exception {
        registerFromSources();
        long announcementId = announcements.findBySourceAnnouncementIdentifier(TARGET).orElseThrow().getId();
        correctFromPdf(announcementId, type);
        assertThat(jdbc.sql("SELECT count(*) FROM announcement_supply_match_decisions").query(Long.class).single())
                .isOne();

        correctFromPdf(announcementId, null);

        assertThat(jdbc.sql("SELECT count(*) FROM announcement_supply_match_decisions").query(Long.class).single())
                .isZero();
    }

    /** 관리자 화면의 PDF 보정과 같은 경로로 공급행의 주택형 연결만 바꾼다. */
    private void correctFromPdf(long announcementId, HousingType housingType) throws Exception {
        var state = json.readTree(mvc.perform(get("/api/admin/announcements/" + announcementId + "/json-corrections")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        var request = json.createObjectNode();
        request.put("snapshotToken", state.path("snapshotToken").asText());
        request.set("document", json.readTree(new ClassPathResource("announcement/import-v1-valid.json").getInputStream()));
        request.putArray("fields");
        request.putArray("schedules");
        request.putArray("attachments");
        var correction = request.putObject("correction");
        correction.put("operationId", UUID.randomUUID().toString());
        correction.put("snapshotToken", state.path("supply").path("snapshotToken").asText());
        correction.put("reason", "공고문 PDF 4쪽에서 주택형 확인");
        correction.put("sourceUrl", "https://example.com/official.pdf");
        correction.putNull("sourcePage");
        correction.set("rows", state.path("supply").path("rows").deepCopy());
        var row = ((ObjectNode) correction.get("rows").get(0)).put("housingComplexId", complex.getId());
        if (housingType == null) {
            row.putNull("housingTypeId");
        }
        else {
            row.put("housingTypeId", housingType.getId());
        }
        var preview = json.readTree(mvc.perform(post("/api/admin/announcements/" + announcementId
                        + "/json-corrections/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var body = json.createObjectNode();
        body.set("request", request);
        body.put("confirmationToken", preview.path("confirmationToken").asText());
        mvc.perform(post("/api/admin/announcements/" + announcementId + "/json-corrections")
                        .with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk());
    }

    /** 지원을 종료한 마이홈 공고 정제가 남긴 공고처럼, 지금 원천의 해석 결과대로 공고와 공급행을 등록한다. */
    private void registerFromSources() {
        var first = sources.findAll().getFirst();
        var announcement = announcements.save(Announcement.create(TARGET, null, null, first.getPblancNm(),
                AnnouncementPublicationType.ORIGINAL, RentalType.NATIONAL_RENTAL, RecruitmentType.WAITLIST,
                AgencyCode.LH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 11, 6), first.getUrl(), null, 0L, null));
        int order = 0;
        for (var data : correctionSources.supplyRows(TARGET)) {
            var housingType = data.exclusiveArea() == null ? null : types.findAll().stream()
                    .filter(value -> value.getExclusiveArea().compareTo(data.exclusiveArea()) == 0)
                    .findFirst().orElse(null);
            var row = SupplyRow.create(announcement, complex, housingType, data.sourceSupplyRowIdentifier(), order++,
                    data.sourceComplexName(), data.sourceHousingTypeName(), data.pnu(), null, data.supplyCategory(),
                    null, data.totalSupplyHouseholdCount());
            row.recordMyHomeSource(data.source().getSourceKey());
            supplies.save(row);
        }
    }

    private HousingComplex complex(String name, String identifier, String pnu, String supplyType) {
        return complexes.save(HousingComplex.createFromMyHome(name, identifier, supplyType,
                Address.create("익산시 목천로 30-14", pnu, pnu.substring(0, 10), "28", "28237",
                        new BigDecimal("35.9"), new BigDecimal("126.9")),
                100, "SH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 80));
    }

    private MyHomeAnnouncementSourceView source(String identifier, int house, String pnu) {
        var source = MyHomeSourceViews.announcement(house, new MyHomeAnnouncementSourceSnapshot(identifier,
                house, "모집중", "국민임대 입주자 모집공고", "서울주택도시공사", "26A,B", "국민임대", null,
                "20261001", "20261106", "20261008", "20261015", null, "https://example.com/manual", null,
                null, "익산한스빌", "전북특별자치도", "익산시", null, null, null, pnu, null, null,
                0, null, null, null, null));
        source = MyHomeSourceViews.collectedAt(source, Instant.parse("2026-10-08T00:00:00Z"));
        return source;
    }
}
