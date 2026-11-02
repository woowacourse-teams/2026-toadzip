package com.toadzip.backend.ingest.mapping.service;

import static com.toadzip.backend.ingest.pipeline.service.IngestExecutionExtension.owned;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
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
import com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySourceView;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceView;
import com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementSupplyMatchingIntegrationTest {
    private static final String PATH = "/api/admin/ingest/announcement-supply-matches";
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
    @Autowired private LhAnnouncementCollectionCandidateResolver candidateResolver;
    @Autowired private LhAnnouncementCollectionProgressStore progressStore;
    @Autowired private MyHomeAnnouncementMappingService mapping;
    @Autowired private LhStorageFixtures lhStorage;
    @Autowired private LhAnnouncementEnrichmentService enrichment;
    @Autowired private IngestExecutionOwnershipService ownership;
    private HousingComplex complex;
    private HousingType type;

    @Test
    void 공급_보정의_새_공통모집도_LH_행_하나만_나누고_재정제와_LH_재보강에서_유지한다() throws Exception {
        prepareHansville();
        var first = types.findAll().stream().filter(value -> value.getExclusiveArea().compareTo(new BigDecimal("26.4673")) == 0)
                .findFirst().orElseThrow();
        var second = types.findAll().stream().filter(value -> value.getExclusiveArea().compareTo(new BigDecimal("26.5195")) == 0)
                .findFirst().orElseThrow();
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate)
                candidateResolver.resolve(sources.findAll().getFirst());
        lhStorage.replaceSupplies(candidate.panId(), candidate.requestDescription(), List.of(
                lhSupplyRow(0, candidate.panId(), "26(청년)", "26.4673", "100"),
                lhSupplyRow(1, candidate.panId(), "26A,B(고령자)", "26.5195", "50"),
                lhSupplyRow(2, candidate.panId(), "26(신혼부부)", "26.5195", "20")));
        owned(ownership, mapping::mapAll);
        // 공급 보정 작업실은 비교 검토 테이블을 읽는다. 이 테스트 스키마에는 없어 이 테스트 동안만 만든다.
        boolean comparisonTables = jdbc.sql("SELECT to_regclass('admin_comparison_sources') IS NULL").query(Boolean.class).single();
        if (comparisonTables) {
            jdbc.sql(new String(new ClassPathResource("db/migration/V20261016_01__quality_comparison_workflow.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8)).update();
        }
        try {
        long id = announcements.findBySourceAnnouncementIdentifier(TARGET).orElseThrow().getId();
        String root = "/api/admin/announcements/" + id + "/supply-corrections";
        var workspace = json.readTree(mvc.perform(get(root).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var rows = (ArrayNode) workspace.get("rows").deepCopy();
        var original = (ObjectNode) StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> row.get("id").asLong() == supplies.findAll().stream()
                        .filter(stored -> stored.getSourceHousingTypeName().equals("26A,B(고령자)")).findFirst().orElseThrow().getId())
                .findFirst().orElseThrow();
        original.put("housingTypeId", first.getId()).put("typeName", "26A").putNull("householdCount");
        var added = original.deepCopy().putNull("id").put("key", UUID.randomUUID().toString())
                .put("housingTypeId", second.getId()).put("typeName", "26B").put("displayOrder", 9);
        added.putArray("targets");
        rows.add(added);
        var correction = json.createObjectNode().put("operationId", UUID.randomUUID().toString())
                .put("snapshotToken", workspace.get("snapshotToken").asText()).put("reason", "공고문 4쪽 26A·26B 고령자 50세대")
                .put("sourceUrl", "https://example.com/official.pdf").put("sourcePage", "4");
        correction.set("rows", rows);
        correction.putArray("newGroups").addObject().put("sourceRowId", original.get("id").asLong())
                .put("sharedHouseholdCount", 50).putArray("rowKeys").add(original.get("key").asText()).add(added.get("key").asText());
        var preview = json.readTree(mvc.perform(post(root + "/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(correction)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var body = json.createObjectNode().put("confirmationToken", preview.get("confirmationToken").asText());
        body.set("correction", correction);
        mvc.perform(post(root).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk());

        owned(ownership, mapping::mapAll);
        owned(ownership, enrichment::enrichAll);
        owned(ownership, mapping::mapAll);

        assertThat(supplies.findAll()).extracting(row -> row.getAnnouncementHousingTypeName() == null
                        ? row.getSourceHousingTypeName() : row.getAnnouncementHousingTypeName())
                .containsExactlyInAnyOrder("26(청년)", "26A", "26B", "26(신혼부부)");
        String key = rowQuery.rows(TARGET).get(1).rowIdentifier();
        assertThat(jdbc.sql("SELECT source_row_identifier || '=' || shared_household_count || ':' || review_required"
                + " FROM supply_row_groups").query(String.class).list()).containsExactly(key + "=50:false");
        } finally {
            if (comparisonTables) { jdbc.sql("DROP TABLE admin_comparison_checks, admin_comparison_sources").update(); }
        }
    }

    private LhAnnouncementSupplySourceView lhSupplyRow(int order,
            String panId, String typeName, String area, String supplied) {
        return LhSourceViews.supply(order, panId,
                new LhAnnouncementSupplySourceSnapshot(
                        "익산한스빌", typeName, area, null, "529", supplied, "4076000", "72820"));
    }

    @Test
    void 복수_공급행_보정도_관리자와_CSRF를_요구한다() throws Exception {
        mvc.perform(post(PATH + "/" + TARGET + "/split").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + TARGET + "/split").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    private JsonNode prepareHansville() throws Exception {
        var fixture = json.readTree(new ClassPathResource("ingest/hansville-21395.json").getInputStream());
        var notice = fixture.get("announcement");
        var building = fixture.get("complex");
        var apiTypes = fixture.get("api").get("complex").get("items");
        var apiComplex = apiTypes.get(0);
        sources.deleteAll();
        types.deleteAll();
        complexes.deleteAll();
        complex = complexes.save(HousingComplex.createFromMyHome(building.get("name").asText(),
                building.get("sourceIdentifier").asText(), "NATIONAL_RENTAL",
                Address.create(apiComplex.get("rnAdres").asText(), apiComplex.get("pnu").asText(),
                        apiComplex.get("pnu").asText().substring(0, 10), apiComplex.get("brtcCode").asText(),
                        apiComplex.get("pnu").asText().substring(0, 5),
                        fixture.get("reproduction").get("testOnlyCoordinates").get("latitude").decimalValue(),
                        fixture.get("reproduction").get("testOnlyCoordinates").get("longitude").decimalValue()),
                building.get("totalHouseholdCount").asInt(), "LH", null,
                "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, building.get("parkingCount").asInt()));
        for (int index = 0; index < apiTypes.size(); index++) {
            var row = apiTypes.get(index);
            type = types.save(HousingType.createFromMyHome(complex, "30700120:fixture:" + index,
                    row.get("styleNm").asText(), row.get("suplyPrvuseAr").decimalValue(), null));
        }
        var apiNotice = (ObjectNode) fixture.get("api")
                .get("announcement").get("items").get(0).deepCopy();
        // 공고 ID만 테스트용으로 격리하고 나머지는 실제 응답값을 유지한다.
        apiNotice.put("pblancId", TARGET);
        var snapshot = json.treeToValue(apiNotice,
                MyHomeAnnouncementSourceSnapshot.class);
        var source = MyHomeAnnouncementSourceView.read(null, 1, Instant.parse("2026-10-18T14:56:03Z"),
                null, 0, true, snapshot);
        sources.save(source);
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        // 공식 화면의 단지명·주소만 LH 상세 형식에 담는다. LH 공급 보강은 없는 상태로 재현한다.
        lhStorage.replaceDetails(candidate.panId(), candidate.requestDescription(), List.of(LhSourceViews.detail(0, candidate.panId(),
                new LhAnnouncementDetailSourceSnapshot(
                "COMPLEX", notice.get("complexName").asText(), notice.get("roadAddress").asText(), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null))));
        lhStorage.replaceSupplies(candidate.panId(), candidate.requestDescription(), List.of());
        for (var target : List.of(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            progressStore.complete(target, TARGET, candidate.requestDescription(), candidate.panId());
        }
        return notice;
    }

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

    @ParameterizedTest
    @ValueSource(strings = {"refine", "rerun", "release", "exclusion", "split"})
    void 지원을_종료한_공급행_매칭_보정은_관리자_요청도_410으로_거절하고_아무것도_저장하지_않는다(String action)
            throws Exception {
        mvc.perform(post(PATH + "/" + TARGET + "/" + action).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("INGEST_OPERATION_RETIRED"))
                .andExpect(jsonPath("$.message").value(
                        "마이홈 공고 정제·LH 보강과 그 보정은 지원을 종료했습니다. 공고는 LH 공고 정제와 PDF 보정으로만 바뀝니다."));

        assertThat(announcements.count()).isZero();
        assertThat(supplies.count()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM announcement_supply_match_decisions").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM admin_data_changes").query(Long.class).single()).isZero();
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
    void PDF_보정으로_연결한_공급행은_재정제에서_매칭_실패로_다시_남지_않는다() throws Exception {
        owned(ownership, mapping::mapAll);
        assertThat(pendingFailureReasons()).containsExactly("AMBIGUOUS_HOUSING_TYPE");
        long announcementId = announcements.findBySourceAnnouncementIdentifier(TARGET).orElseThrow().getId();

        correctFromPdf(announcementId, type);
        // PDF 보정이 정한 대표 주택형이 함께 모집하는 주택형에도 들어간다.
        assertThat(jdbc.sql("SELECT housing_type_id FROM supply_row_housing_types").query(Long.class).list())
                .containsExactly(type.getId());
        assertLinksFollowRepresentative();
        assertThat(jdbc.sql("SELECT housing_type_id, reason FROM announcement_supply_match_decisions")
                .query((rs, rowNum) -> List.of(rs.getLong(1), rs.getString(2))).list())
                .containsExactly(List.of(type.getId(), "공고문 PDF 4쪽에서 주택형 확인"));
        owned(ownership, mapping::mapAll);

        assertThat(supplies.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getHousingType().getId()).isEqualTo(type.getId()));
        assertThat(pendingFailureReasons()).isEmpty();
    }

    /** 정제와 매칭 보정이 쓴 공급행은 함께 모집하는 주택형이 대표 주택형 하나뿐이다. */
    private void assertLinksFollowRepresentative() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM supply_rows row
                FULL JOIN supply_row_housing_types link ON link.supply_row_id = row.id
                WHERE link.housing_type_id IS DISTINCT FROM row.housing_type_id
                """).query(Long.class).single()).isZero();
    }

    @Test
    void 다시_정제는_관리자와_CSRF를_요구한다() throws Exception {
        mvc.perform(post(PATH + "/" + TARGET + "/rerun").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + TARGET + "/rerun").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        assertThat(announcements.count()).isZero();
    }

    private List<String> pendingFailureReasons() {
        return jdbc.sql("""
                SELECT reason FROM myhome_announcement_mapping_failures
                WHERE source_announcement_identifier = ? AND status = 'PENDING'
                """).param(TARGET).query(String.class).list();
    }

    @Test
    @Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
    @Sql(statements = "DROP TABLE admin_comparison_checks, admin_comparison_sources",
            executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void PDF_보정에서_주택형_연결을_지우면_저장한_매칭도_해제한다() throws Exception {
        owned(ownership, mapping::mapAll);
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

    @Test
    void 선택_해제도_관리자_권한과_CSRF를_요구한다() throws Exception {
        mvc.perform(post(PATH + "/" + TARGET + "/release").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + TARGET + "/release").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
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
