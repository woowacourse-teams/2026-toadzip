package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.domain.Announcement;
import com.toadzip.backend.announcement.domain.AnnouncementApplicationSchedule;
import com.toadzip.backend.announcement.domain.ApplicationScheduleState;
import com.toadzip.backend.announcement.domain.ReceptionPlace;
import com.toadzip.backend.announcement.domain.SupplyCategory;
import com.toadzip.backend.announcement.domain.SupplyRow;
import com.toadzip.backend.announcement.repository.AnnouncementApplicationScheduleRepository;
import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.domain.RentalPriceRange;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.LhLeaseCatalogSourceFixtures;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.projection.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource;
import com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot;
import com.toadzip.backend.ingest.enrichment.service.LhHousingTypeHouseholdEnrichmentService;
import com.toadzip.backend.ingest.failure.domain.IngestFailureStatus;
import com.toadzip.backend.ingest.location.domain.GeocodedRoadAddress;
import com.toadzip.backend.ingest.location.domain.RoadAddressGeocodingFailureReason;
import com.toadzip.backend.ingest.location.exception.RoadAddressGeocodingException;
import com.toadzip.backend.ingest.location.service.RoadAddressGeocodingService;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyHomeComplexMergeIntegrationTest {

    private static final String ENDPOINT = "/api/admin/ingest/myhome/complex-merges";
    private static final String NAME = "삼산부영 재건축 소형주택(삼산신원아침도시(행복)";
    private static final String ADDRESS = "인천광역시 부평구 후정로 33";
    private static final Instant COLLECTED_AT = Instant.parse("2026-09-03T00:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcClient jdbc;
    @Autowired private MyHomeComplexSourceFixtures sources;
    @Autowired private LhLeaseCatalogSourceFixtures lhSources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private MyHomeComplexMappingService mapping;
    @Autowired private HousingTypeRepository housingTypes;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private SupplyRowRepository supplies;
    @Autowired private AnnouncementApplicationScheduleRepository schedules;
    @Autowired private UserRepository users;
    @Autowired private LhHousingTypeHouseholdEnrichmentService enrichment;
    @Autowired private IngestExecutionOwnershipService ownership;
    @MockitoBean private RoadAddressGeocodingService geocoding;

    private List<Long> ids;
    private long lhId;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, myhome_complex_source_regions, lh_lease_catalog_source_bundles,
                    myhome_complex_mapping_failures,
                    announcements, users CASCADE
                """).update();
        when(geocoding.geocode(anyString())).thenReturn(new GeocodedRoadAddress(
                ADDRESS, new BigDecimal("37.518000"), new BigDecimal("126.737000")));
        sources.saveAll(List.of(
                source(31713155L, 6, "51", "51.5017", "18.8617"),
                source(31713155L, 6, "51", "51.5017", "18.8799"),
                source(31713154L, 1, "51", "51.5017", "18.8617"),
                source(31713153L, 4, "59", "59.0000", "20.0000")
        ));
        LhCatalogSource lh = new LhCatalogSource(1, new LhCatalogSourceSnapshot(
                "인천", "행복주택", NAME, "11", "51.5017", "7", null, null));
        lh.markCollectedAt(COLLECTED_AT);
        lhId = lhSources.save(lh).getId();
        mapping.mapAll();
        ids = complexes.findAll().stream().map(HousingComplex::getId).sorted().toList();
    }

    @Test
    void 삼산부영은_확인한_연결을_저장한_뒤_재정제해도_한_단지와_네_원천_주택형을_유지한다() throws Exception {
        assertThat(ids).hasSize(3);
        JsonNode preview = postJson(ENDPOINT + "/preview", Map.of("complexIds", ids, "lhSourceId", lhId));
        postJson(ENDPOINT, Map.of(
                "operationId", UUID.randomUUID(), "complexIds", ids, "lhSourceId", lhId,
                "expectedHash", preview.get("previewHash").asText(),
                "confirmedSameComplex", true, "reason", "동일 실제 단지와 공급 범위를 확인하고 LH 11세대를 채택"));

        mapping.mapAll();

        assertThat(complexes.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getId()).isEqualTo(ids.getFirst());
            assertThat(complex.getTotalHouseholdCount()).isEqualTo(11);
        });
        assertThat(jdbc.sql("SELECT count(*) FROM housing_types").query(Long.class).single()).isEqualTo(4);
        assertThat(sources.count()).isEqualTo(4);
        enrichment.enrichAll();
        assertThat(housingTypes.findAll()).allSatisfy(type -> assertThat(type.getTotalHouseholdCount()).isNull());
    }

    @Test
    void 통합한_단지는_모든_원천의_보증금과_월임대료_범위를_표시한다() throws Exception {
        jdbc.sql("""
                UPDATE myhome_complex_source_rows
                SET bass_rent_gtn = CASE hsmp_sn WHEN 31713153 THEN 30000000 ELSE 10000000 END,
                    bass_mt_rntchrg = CASE hsmp_sn WHEN 31713153 THEN 100000 ELSE 200000 END
                """).update();
        mapping.mapAll();

        merge();
        mapping.mapAll();

        assertThat(complexes.findAll()).singleElement().satisfies(complex ->
                assertThat(complex.getRentalPriceRange()).isEqualTo(
                        new RentalPriceRange(10_000_000L, 30_000_000L, 100_000L, 200_000L)));
    }

    @Test
    void 확인된_통합_단지는_좌표가_없어도_금액을_갱신한다() throws Exception {
        merge();
        jdbc.sql("UPDATE myhome_complex_source_rows SET bass_rent_gtn = 5000000 WHERE hsmp_sn = 31713153")
                .update();
        when(geocoding.geocode(anyString())).thenThrow(new RoadAddressGeocodingException(
                RoadAddressGeocodingFailureReason.ADDRESS_NOT_FOUND, "선별 적재된 좌표가 없습니다."
        ));

        var report = mapping.mapAll();

        assertThat(report.failedSourceRowCount()).isZero();
        assertThat(complexes.findAll()).singleElement().satisfies(complex ->
                assertThat(complex.getRentalPriceRange())
                        .isEqualTo(new RentalPriceRange(5_000_000L, 5_000_000L, null, null)));
    }

    @Test
    void 같은_이름과_PNU여도_확인하기_전에는_각_단지를_유지하고_후보만_보여준다() throws Exception {
        mapping.mapAll();
        mvc.perform(get(ENDPOINT + "/candidates").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].representativeId").value(ids.getFirst()))
                .andExpect(jsonPath("$[0].lhEvidence[0].sourceId").value(lhId));
        assertThat(complexes.count()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM myhome_complex_links").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void 통합은_모든_참조와_수동값을_보존하고_즉시_복구하면_원래_ID와_참조를_되돌린다() throws Exception {
        addReferences();
        jdbc.sql("UPDATE housing_complexes SET image_url = 'manual-image',"
                + " recent_one_year_move_out_count = 2 WHERE id = ?")
                .param(ids.getLast()).update();
        jdbc.sql("UPDATE housing_types SET total_household_count = 1, floor_plan_url = 'manual-plan',"
                + " duplex = true, maintenance_fee = 12000").update();
        List<String> before = productState();
        JsonNode merged = merge();
        assertThat(complexes.findAll()).singleElement().satisfies(complex -> {
            assertThat(complex.getImageUrl()).isEqualTo("manual-image");
            assertThat(complex.getRecentOneYearMoveOutCount()).isEqualTo(2);
        });
        for (String table : List.of("housing_types", "supply_rows", "favorite_housing_complexes",
                "announcement_application_schedules")) {
            assertThat(jdbc.sql("SELECT DISTINCT housing_complex_id FROM " + table)
                    .query(Long.class).list()).containsExactly(ids.getFirst());
        }
        assertThat(housingTypes.findAll()).allSatisfy(type -> {
            assertThat(type.getFloorPlanUrl()).isEqualTo("manual-plan");
            assertThat(type.getTotalHouseholdCount()).isOne();
        });
        JsonNode reverted = postJson(ENDPOINT + "/" + merged.get("operationId").asText() + "/revert", Map.of());
        assertThat(reverted.get("revertedBy").asText()).isEqualTo("admin");
        assertThat(productState()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM housing_complex_aliases").query(Long.class).single()).isZero();
        mapping.mapAll();
        assertThat(complexes.count()).isEqualTo(3);
    }

    @Test
    void 금액_컬럼_추가_전_통합_이력도_복구할_수_있다() throws Exception {
        jdbc.sql("UPDATE myhome_complex_source_rows SET bass_rent_gtn = 10000000, bass_mt_rntchrg = 200000")
                .update();
        mapping.mapAll();
        JsonNode merged = merge();
        jdbc.sql("""
                UPDATE myhome_complex_merges
                SET before_state = jsonb_set(before_state, '{complexes}', (
                        SELECT jsonb_agg(complex - 'deposit_min' - 'deposit_max'
                            - 'monthly_rent_min' - 'monthly_rent_max')
                        FROM jsonb_array_elements(before_state->'complexes') complex)),
                    after_state = jsonb_set(after_state, '{complexes}', (
                        SELECT jsonb_agg(complex - 'deposit_min' - 'deposit_max'
                            - 'monthly_rent_min' - 'monthly_rent_max')
                        FROM jsonb_array_elements(after_state->'complexes') complex))
                """).update();

        postJson(ENDPOINT + "/" + merged.get("operationId").asText() + "/revert", Map.of());

        assertThat(complexes.count()).isEqualTo(3);
        assertThat(complexes.findAll()).allSatisfy(complex ->
                assertThat(complex.getRentalPriceRange()).isEqualTo(
                        new RentalPriceRange(
                                10_000_000L, 10_000_000L, 200_000L, 200_000L)));
    }

    @Test
    void 이전_단지_ID로_조회해도_대표_단지와_모든_주택형이_표시된다() throws Exception {
        merge();
        mvc.perform(get("/api/v1/complexes/" + ids.getLast()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complexId").value(ids.getFirst()))
                .andExpect(jsonPath("$.data.housingTypes.length()").value(4));
        mvc.perform(get("/api/v1/complexes")
                        .param("southWestLat", "37").param("southWestLng", "126")
                        .param("northEastLat", "38").param("northEastLng", "128"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v2/complexes/map").param("zoom", "18")
                        .param("southWestLat", "37").param("southWestLng", "126")
                        .param("northEastLat", "38").param("northEastLng", "128"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nodes.length()").value(1));
    }

    @Test
    void 같은_작업_ID의_재요청은_참조를_다시_이전하지_않는다() throws Exception {
        Map<String, Object> request = mergeRequest();
        JsonNode first = postJson(ENDPOINT, request);
        List<String> after = productState();
        JsonNode retry = postJson(ENDPOINT, request);
        assertThat(retry).isEqualTo(first);
        assertThat(productState()).isEqualTo(after);
    }

    @Test
    void 미리보기_후_원천이_변하면_통합하지_않는다() throws Exception {
        Map<String, Object> request = mergeRequest();
        jdbc.sql("UPDATE myhome_complex_source_rows SET bass_mt_rntchrg = 12345").update();
        expectConflict(ENDPOINT, request);
        assertThat(complexes.count()).isEqualTo(3);
    }

    @Test
    void LH_11과_원천별_범위가_일치하지_않으면_합산하지_않는다() throws Exception {
        jdbc.sql("UPDATE lh_lease_catalog_source_rows SET complex_total_unit_count = '12'").update();
        expectConflict(ENDPOINT + "/preview", Map.of("complexIds", ids, "lhSourceId", lhId));
        assertThat(complexes.count()).isEqualTo(3);
    }

    @Test
    void 단지의_수동값이_충돌하면_어느_쪽도_덮어쓰지_않는다() throws Exception {
        jdbc.sql("UPDATE housing_complexes SET image_url = 'first' WHERE id = ?").param(ids.getFirst()).update();
        jdbc.sql("UPDATE housing_complexes SET image_url = 'second' WHERE id = ?").param(ids.getLast()).update();
        List<String> before = productState();
        expectConflict(ENDPOINT + "/preview", Map.of("complexIds", ids, "lhSourceId", lhId));
        assertThat(productState()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE housing_types SET floor_plan_url = 'new-manual-plan'",
            "DELETE FROM housing_types WHERE id = (SELECT max(id) FROM housing_types)",
            "INSERT INTO housing_types (housing_complex_id, name, exclusive_area) "
                    + "SELECT id, '관리자 추가', 70 FROM housing_complexes"
    })
    void 통합_후_추가_수정_삭제가_있으면_복구가_후속_변경을_덮어쓰지_않는다(String change) throws Exception {
        JsonNode merged = merge();
        jdbc.sql(change).update();
        List<String> changed = productState();
        expectConflict(ENDPOINT + "/" + merged.get("operationId").asText() + "/revert", Map.of());
        assertThat(productState()).isEqualTo(changed);
    }

    @Test
    void 연결_원천이_누락되면_재정제가_다른_원천의_주택형을_삭제하지_않는다() throws Exception {
        merge();
        sources.deleteAll(sources.findAllByHsmpSnIn(List.of(31713154L)));
        List<String> before = productState();
        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(4);
        assertThat(mapping.findFailures()).anySatisfy(failure -> {
            assertThat(failure.sourceComplexIdentifier()).isEqualTo("31713154:HAPPY_HOUSING");
            assertThat(failure.sourceKey()).isEqualTo("linked-complex:31713154:HAPPY_HOUSING");
        });
        assertThat(productState()).isEqualTo(before);
    }

    @Test
    void 연결_원천이_전부_사라져도_누락_실패를_유지하고_복구되면_해결한다() throws Exception {
        merge();
        sources.deleteAll();
        List<String> before = productState();

        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(3);
        assertThat(mapping.findFailures()).extracting(failure -> failure.sourceComplexIdentifier())
                .containsExactlyInAnyOrder(
                        "31713155:HAPPY_HOUSING", "31713154:HAPPY_HOUSING", "31713153:HAPPY_HOUSING");
        assertThat(productState()).isEqualTo(before);

        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(3);
        assertThat(mapping.findFailures()).hasSize(3).allSatisfy(failure -> {
            assertThat(failure.occurrenceCount()).isEqualTo(2);
            assertThat(failure.recurrenceCount()).isZero();
        });

        sources.saveAll(List.of(
                source(31713155L, 6, "51", "51.5017", "18.8617"),
                source(31713155L, 6, "51", "51.5017", "18.8799"),
                source(31713154L, 1, "51", "51.5017", "18.8617"),
                source(31713153L, 4, "59", "59.0000", "20.0000")
        ));
        assertThat(mapping.mapAll().failedSourceRowCount()).isZero();
        assertThat(mapping.findFailures()).isEmpty();
        assertThat(mapping.findFailureHistory(0, 10)).hasSize(3).allSatisfy(failure -> {
            assertThat(failure.status()).isEqualTo(IngestFailureStatus.RESOLVED);
            assertThat(failure.lastResolvedAt()).isNotNull();
        });
        assertThat(productState()).isEqualTo(before);
    }

    @Test
    void 원천의_세대수가_바뀌면_11을_다시_덮어쓰지_않고_재확인으로_남긴다() throws Exception {
        merge();
        jdbc.sql("UPDATE myhome_complex_source_rows SET hshld_co = 7 WHERE hsmp_sn = 31713155").update();
        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(4);
        assertThat(complexes.findAll().getFirst().getTotalHouseholdCount()).isEqualTo(11);
    }

    @Test
    void 확인된_원천의_공급유형이_정정되면_다른_단지로_다시_생성하지_않는다() throws Exception {
        merge();
        List<String> before = productState();
        sources.deleteAll(sources.findAllByHsmpSnIn(List.of(31713154L)));
        sources.save(source(31713154L, 1, "51", "51.5017", "18.8617", "국민임대"));
        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(5);
        assertThat(productState()).isEqualTo(before);
    }

    @Test
    void 승인된_공급유형이_남아있으면_같은_hsmpSn의_별도_공급유형은_유지한다() throws Exception {
        merge();
        sources.save(source(31713154L, 1, "51", "51.5017", "18.8617", "국민임대"));
        assertThat(mapping.mapAll().failedSourceRowCount()).isZero();
        assertThat(complexes.findAll()).extracting(HousingComplex::getSupplyType)
                .containsExactlyInAnyOrder("HAPPY_HOUSING", "NATIONAL_RENTAL");
    }

    @Test
    void 별도_공급유형이_이미_연결되어도_승인된_유형이_사라지면_갱신을_보류한다() throws Exception {
        merge();
        sources.save(source(31713154L, 1, "51", "51.5017", "18.8617", "국민임대"));
        assertThat(mapping.mapAll().failedSourceRowCount()).isZero();
        List<String> before = productState();

        sources.deleteAll(sources.findAllByHsmpSnIn(List.of(31713154L)));
        sources.save(source(31713154L, 2, "51", "51.5017", "18.8617", "국민임대"));

        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(5);
        assertThat(mapping.findFailures()).anySatisfy(failure -> {
            assertThat(failure.sourceComplexIdentifier()).isEqualTo("31713154:NATIONAL_RENTAL");
            assertThat(failure.detail()).contains("공급유형");
        });
        assertThat(productState()).isEqualTo(before);
    }

    @Test
    void 참조_이전_뒤_저장이_실패해도_단지와_참조와_연결은_전부_원래대로_돌아간다() throws Exception {
        addReferences();
        Map<String, Object> request = mergeRequest();
        List<String> before = productState();
        jdbc.sql("""
                CREATE FUNCTION reject_complex_merge_test() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected merge failure'; END; $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER reject_complex_merge_test BEFORE DELETE ON housing_complexes
                FOR EACH ROW EXECUTE FUNCTION reject_complex_merge_test()
                """).update();
        try {
            mvc.perform(post(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                    .andExpect(status().isInternalServerError());
            assertThat(productState()).isEqualTo(before);
            assertThat(jdbc.sql("SELECT housing_complex_id FROM myhome_complex_links ORDER BY housing_complex_id")
                    .query(Long.class).list()).isEqualTo(ids);
            assertThat(jdbc.sql("SELECT count(*) FROM housing_complex_aliases").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM myhome_complex_merges WHERE id = ?")
                    .param(request.get("operationId")).query(Long.class).single()).isZero();
        }
        finally {
            jdbc.sql("DROP TRIGGER reject_complex_merge_test ON housing_complexes").update();
            jdbc.sql("DROP FUNCTION reject_complex_merge_test()").update();
        }
    }

    @Test
    void 지오코딩으로_정규화된_주소도_같은_단지로_검증한다() throws Exception {
        jdbc.sql("UPDATE myhome_complex_source_rows SET rn_adres = ?")
                .param(ADDRESS + " (삼산동)").update();
        merge();
        assertThat(mapping.mapAll().failedSourceRowCount()).isZero();
    }

    @Test
    void 통합_후_원천_공통값이_바뀌면_성공으로_숨기지_않고_재확인으로_남긴다() throws Exception {
        merge();
        jdbc.sql("UPDATE myhome_complex_source_rows SET parkng_co = 10").update();
        List<String> before = productState();
        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(4);
        assertThat(productState()).isEqualTo(before);
    }

    @Test
    void 같은_이름의_주택형을_보정할_때_다른_원천의_ID를_가져오지_않는다() throws Exception {
        sources.deleteAll(sources.findAllByHsmpSnIn(List.of(31713154L)));
        sources.save(source(31713154L, 1, "60", "60.0000", "20.0000"));
        mapping.mapAll();
        merge();
        MyHomeComplexSource old = sources.findAllByHsmpSnIn(List.of(31713155L)).getFirst();
        long originalId = jdbc.sql("SELECT id FROM housing_types WHERE source_housing_type_identifier = ?")
                .param(old.getSourceKey()).query(Long.class).single();
        jdbc.sql("UPDATE housing_types SET floor_plan_url = 'manual-plan' WHERE id = ?").param(originalId).update();
        sources.delete(old);
        sources.deleteAll(sources.findAllByHsmpSnIn(List.of(31713154L)));
        sources.save(source(31713154L, 1, "51", "51.6000", "18.8617"));
        assertThat(mapping.mapAll().failedSourceRowCount()).isZero();
        assertThat(jdbc.sql("SELECT id FROM housing_types WHERE source_housing_type_identifier = ?")
                .param(old.getSourceKey()).query(Long.class).single()).isEqualTo(originalId);
        assertThat(housingTypes.count()).isEqualTo(4);
    }

    @Test
    void 확인하지_않은_요청은_조건이_일치해도_통합할_수_없다() throws Exception {
        Map<String, Object> request = new java.util.HashMap<>(mergeRequest());
        request.put("confirmedSameComplex", false);
        mvc.perform(post(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
        assertThat(complexes.count()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"supply_type = 'NATIONAL_RENTAL'", "pnu = '2823710500105500001'",
            "name = '삼산부영 별도 건물'", "provider = 'SH'"})
    void 공급유형이나_건물이_다른_제품은_통합하지_않는다(String changedField) throws Exception {
        jdbc.sql("UPDATE housing_complexes SET " + changedField + " WHERE id = ?").param(ids.getLast()).update();
        expectConflict(ENDPOINT + "/preview", Map.of("complexIds", ids, "lhSourceId", lhId));
        assertThat(complexes.count()).isEqualTo(3);
    }

    @Test
    void 실제_재수집으로_수집시각이_바뀌어도_연결과_통합_당시_근거를_보존한다() throws Exception {
        JsonNode merged = merge();
        jdbc.sql("UPDATE myhome_complex_source_rows SET collected_at = TIMESTAMPTZ '2026-09-27 00:00:00Z'").update();
        assertThat(mapping.mapAll().failedSourceRowCount()).isZero();
        JsonNode history = json.readTree(mvc.perform(get(ENDPOINT + "/" + merged.get("operationId").asText())
                        .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(history.get("evidence").asText()).contains("2026-09-03").doesNotContain("2026-09-27");
        assertThat(complexes.count()).isOne();
    }

    @Test
    void 통합_후_원천_주택형이_사라져도_수동_보강값이_있는_행은_보존한다() throws Exception {
        merge();
        MyHomeComplexSource old = sources.findAllByHsmpSnIn(List.of(31713155L)).getFirst();
        jdbc.sql("UPDATE housing_types SET floor_plan_url = 'manual-plan' WHERE source_housing_type_identifier = ?")
                .param(old.getSourceKey()).update();
        sources.delete(old);
        assertThat(mapping.mapAll().failedSourceRowCount()).isZero();
        assertThat(housingTypes.count()).isEqualTo(4);
    }

    @Test
    void 수집이_진행_중이면_통합은_409로_거절된다() throws Exception {
        Map<String, Object> request = mergeRequest();
        try (var ignored = ownership.acquire()) {
            mvc.perform(post(ENDPOINT).with(user("admin").roles("ADMIN")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INGEST_ALREADY_RUNNING"));
        }
        assertThat(complexes.count()).isEqualTo(3);
    }

    @Test
    void 관리자가_아니면_근거_조회와_통합을_할_수_없다() throws Exception {
        mvc.perform(get(ENDPOINT + "/candidates")).andExpect(status().isUnauthorized());
        mvc.perform(post(ENDPOINT + "/preview").with(user("member").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("complexIds", ids, "lhSourceId", lhId))))
                .andExpect(status().isForbidden());
    }

    private JsonNode merge() throws Exception {
        return postJson(ENDPOINT, mergeRequest());
    }

    private Map<String, Object> mergeRequest() throws Exception {
        JsonNode preview = postJson(ENDPOINT + "/preview", Map.of("complexIds", ids, "lhSourceId", lhId));
        return Map.of("operationId", UUID.randomUUID(), "complexIds", ids, "lhSourceId", lhId,
                "expectedHash", preview.get("previewHash").asText(), "confirmedSameComplex", true,
                "reason", "동일 실제 단지와 공급 범위를 확인하고 LH 11세대를 채택");
    }

    private void expectConflict(String path, Object body) throws Exception {
        mvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMPLEX_MERGE_CONFLICT"));
    }

    private List<String> productState() {
        List<String> snapshots = new ArrayList<>();
        for (String table : List.of("housing_complexes", "housing_types", "supply_rows",
                "favorite_housing_complexes", "announcement_application_schedules")) {
            snapshots.add(jdbc.sql(
                    "SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id), '[]')::text FROM " + table + " t")
                    .query(String.class).single());
        }
        return snapshots;
    }

    private void addReferences() {
        Announcement announcement = announcements.save(Announcement.create(
                "merge-announcement", null, null, "통합 확인 공고", "원공고", "행복주택", "신규모집", "LH",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 10, 1), "https://apply.lh.or.kr/announcement", null, 0L,
                ReceptionPlace.create("마이홈", "인터넷", null, "1600-1004", "https://apply.lh.or.kr")));
        User user = users.save(User.create("merge-test-user", LocalDateTime.of(2026, 9, 1, 0, 0)));
        for (long id : ids) {
            HousingComplex complex = complexes.findById(id).orElseThrow();
            HousingType type = housingTypes.findAllByHousingComplex(complex).getFirst();
            supplies.save(SupplyRow.create(announcement, complex, type, "supply-" + id, 1,
                    complex.getName(), type.getName(), complex.getAddress().getPnu(), YearMonth.of(2027, 3),
                    SupplyCategory.NEW_SUPPLY, null, 10));
            schedules.save(AnnouncementApplicationSchedule.verified(
                    announcement, complex, null, ApplicationScheduleState.CONFIRMED, null,
                    LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 14), null, null,
                    "https://apply.lh.or.kr/notice.pdf", 1));
            jdbc.sql("INSERT INTO favorite_housing_complexes (user_id, housing_complex_id, created_at)"
                    + " VALUES (?, ?, TIMESTAMP '2026-09-01 00:00:00')").params(user.getId(), id).update();
        }
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        String response = mvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private MyHomeComplexSource source(long id, int count, String name, String area, String common) {
        return source(id, count, name, area, common, "행복주택");
    }

    private MyHomeComplexSource source(
            long id, int count, String name, String area, String common, String supplyType
    ) {
        MyHomeComplexSource source = MyHomeComplexSource.from(new MyHomeComplexSourceSnapshot(
                id, "한국토지주택공사", "28", "인천광역시", "237", "부평구", NAME, ADDRESS,
                "2823710500105500000", null, count, supplyType, name, new BigDecimal(area),
                new BigDecimal(common), "아파트", null, null, null, 0, null, null, null));
        source.markCollectedAt(COLLECTED_AT);
        return source;
    }
}
