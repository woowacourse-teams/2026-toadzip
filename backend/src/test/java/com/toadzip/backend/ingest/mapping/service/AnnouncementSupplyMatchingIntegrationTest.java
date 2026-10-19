package com.toadzip.backend.ingest.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.announcement.repository.AnnouncementRepository;
import com.toadzip.backend.announcement.repository.SupplyRowRepository;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.MyHomeAnnouncementSourceFixtures;
import com.toadzip.backend.ingest.collection.domain.ExternalDataSource;
import com.toadzip.backend.ingest.collection.lh.repository.LhAnnouncementCollectionProgressStore;
import com.toadzip.backend.ingest.collection.lh.service.LhAnnouncementCollectionCandidateResolver;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSource;
import com.toadzip.backend.ingest.collection.myhome.announcement.domain.projection.MyHomeAnnouncementSourceSnapshot;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Request;
import com.toadzip.backend.ingest.mapping.dto.AnnouncementSupplyMatching.Row;
import com.toadzip.backend.ingest.collection.fixture.repository.LhStorageFixtures;
import com.toadzip.backend.ingest.collection.lh.detail.domain.LhAnnouncementDetailSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnnouncementSupplyMatchingIntegrationTest {
    private static final String PATH = "/api/admin/ingest/announcement-supply-matches";
    private static final String TARGET = "manual-21395";
    private static final String PNU = "2823710500105500000";
    @Autowired private AnnouncementSupplyMatchingService matching;
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
    @Autowired private com.toadzip.backend.announcement.service.AnnouncementQueryService query;
    @Autowired private com.toadzip.backend.announcement.repository.SupplyTargetRepository targets;
    @Autowired private com.toadzip.backend.ingest.enrichment.service.LhAnnouncementEnrichmentService enrichment;
    private HousingComplex complex;
    private HousingType type;

    private Map<String, Object> splitBody(Row row) {
        var candidates = matching.housingTypes(complex.getId());
        var selections = new java.util.ArrayList<Map<String, Object>>();
        for (int index = 0; index < candidates.size(); index++) {
            var option = candidates.get(index);
            var selection = new java.util.LinkedHashMap<String, Object>();
            if (row.splitGroup() != null) { selection.put("id", row.splitGroup().rows().get(index).id()); }
            selection.put("name", List.of("26A", "26B").get(index));
            selection.put("complexId", complex.getId());
            selection.put("housingTypeId", option.id());
            selection.put("targetToken", option.token());
            selections.add(selection);
        }
        return new java.util.LinkedHashMap<>(Map.of("rowIdentifier", row.rowIdentifier(), "token", row.token(),
                "sourceUrl", "https://www.myhome.go.kr/hws/portal/sch/selectRsdtRcritNtcDetailView.do?pblancId=21395",
                "reason", "공식 API 기반 회귀 검증의 명시적 A/B 선택이며 실제 대응 확정이 아님", "rows", selections));
    }

    private ResultActions split(Map<String, Object> body) throws Exception {
        return mvc.perform(post(PATH + "/" + TARGET + "/split").with(user("operator").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void 실제_한스빌_원천에서_보정한_AB와_공통170은_LH_재보강과_공개_집계에서도_유지한다() throws Exception {
        prepareHansville();
        split(splitBody(matching.rows(TARGET).getFirst())).andExpect(status().isOk());
        var announcement = announcements.findBySourceAnnouncementIdentifier(TARGET).orElseThrow();
        var ids = supplies.findAll().stream().map(value -> value.getId()).toList();
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate)
                candidateResolver.resolve(sources.findAll().getFirst());
        lhStorage.replaceSupplies(candidate.panId(), candidate.requestDescription(), List.of(
                new com.toadzip.backend.ingest.collection.lh.supply.domain.LhAnnouncementSupplySource(0,
                        candidate.panId(), new com.toadzip.backend.ingest.collection.lh.supply.domain.projection.LhAnnouncementSupplySourceSnapshot(
                        "익산한스빌", "26A,B", "26.5195", null, "529", "170", "4076000", "72820"))));
        mapping.mapAll();
        enrichment.enrichAll();
        mapping.mapAll();
        assertThat(supplies.findAll()).extracting(value -> value.getId()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(supplies.findAll()).extracting(value -> value.getSourceHousingTypeName())
                .containsExactlyInAnyOrder("26A", "26B");
        assertThat(supplies.findAll()).allSatisfy(row -> assertThat(row.getTotalSupplyHouseholdCount()).isNull());
        var detail = query.getAnnouncement(announcement.getId());
        assertThat(detail.supplyHouseholdCount()).isEqualTo(170);
        mvc.perform(get("/api/v1/announcements/" + announcement.getId()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.supplyHouseholdCount").value(170))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.supplyRows[0].sharedRecruitment.householdCount").value(170));
        assertThat(detail.supplyRows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.totalSupplyHouseholdCount()).isNull();
            assertThat(row.sharedRecruitment().householdCount()).isEqualTo(170);
            assertThat(row.targets()).isEmpty();
        });
        assertThat(detail.supplyRows()).extracting(row -> row.sharedRecruitment().id()).containsOnly(
                detail.supplyRows().getFirst().sharedRecruitment().id());
        assertThat(complexes.findById(complex.getId()).orElseThrow().getTotalHouseholdCount()).isEqualTo(529);
        assertThat(types.findAll()).extracting(value -> value.getName()).containsOnly("26");
        assertThat(jdbc.sql("SELECT actor FROM admin_data_changes WHERE action = 'SPLIT_SUPPLY'")
                .query(String.class).list()).containsExactly("operator");
    }

    @Test
    void 원천_모집수가_바뀌면_보정과170을_보존하고_재확인_후에만_공통수를_바꾼다() throws Exception {
        prepareHansville();
        var original = splitBody(matching.rows(TARGET).getFirst());
        split(original).andExpect(status().isOk());
        split(original).andExpect(status().isConflict());
        jdbc.sql("UPDATE myhome_announcement_source_rows SET sum_suply_co = 190").update();
        mapping.mapAll();
        var changed = matching.rows(TARGET).getFirst();
        assertThat(changed.splitGroup().reviewRequired()).isTrue();
        assertThat(changed.splitGroup().sharedHouseholdCount()).isEqualTo(170);
        assertThat(changed.totalSupplyHouseholdCount()).isEqualTo(190);
        var ids = supplies.findAll().stream().map(value -> value.getId()).toList();
        split(splitBody(changed)).andExpect(status().isOk());
        assertThat(matching.rows(TARGET).getFirst().splitGroup().reviewRequired()).isFalse();
        assertThat(matching.rows(TARGET).getFirst().splitGroup().sharedHouseholdCount()).isEqualTo(190);
        assertThat(supplies.findAll()).extracting(value -> value.getId()).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void 같은_후보를_복제하거나_조회후_바뀐_주택형으로_분할하면_제품을_만들지_않는다() throws Exception {
        prepareHansville();
        var row = matching.rows(TARGET).getFirst();
        var body = splitBody(row);
        @SuppressWarnings("unchecked")
        var selections = (List<Map<String, Object>>) body.get("rows");
        selections.getLast().put("housingTypeId", selections.getFirst().get("housingTypeId"));
        selections.getLast().put("targetToken", selections.getFirst().get("targetToken"));
        split(body).andExpect(status().isBadRequest());
        var stale = splitBody(row);
        jdbc.sql("UPDATE housing_types SET exclusive_area = 40 WHERE id = ?").param(type.getId()).update();
        split(stale).andExpect(status().isConflict());
        assertThat(announcements.count()).isZero();
        assertThat(supplies.count()).isZero();
    }

    @Test
    void 복수_공급행_뒤_LH검증이_실패하면_행과_공통수와_이력을_함께_롤백한다() throws Exception {
        prepareHansville();
        jdbc.sql("DELETE FROM lh_announcement_detail_rows").update();
        split(splitBody(matching.rows(TARGET).getFirst())).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
        assertThat(supplies.count()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM supply_row_groups").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM admin_data_changes WHERE action = 'SPLIT_SUPPLY'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void 보정한_주택형이_바뀌어도_AB_행을_보존하며_단일_매칭으로_축소하지_않는다() throws Exception {
        prepareHansville();
        split(splitBody(matching.rows(TARGET).getFirst())).andExpect(status().isOk());
        jdbc.sql("UPDATE housing_types SET exclusive_area = 30 WHERE id = ?").param(type.getId()).update();
        mapping.mapAll();
        var current = matching.rows(TARGET).getFirst();
        assertThat(current.splitGroup().reviewRequired()).isTrue();
        assertThat(current.splitGroup().rows()).hasSize(2);
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rows", List.of(
                        Map.of("rowIdentifier", current.rowIdentifier(), "token", current.token(),
                                "complexId", complex.getId(), "housingTypeId", type.getId()))))))
                .andExpect(status().isConflict());
        assertThat(supplies.count()).isEqualTo(2);
        assertThat(query.getAnnouncement(announcements.findAll().getFirst().getId()).supplyHouseholdCount()).isEqualTo(170);
    }

    @Test
    void 기존_대상조건을_분배하지_않은_채_분할해서_잃어버리지_않는다() throws Exception {
        jdbc.sql("UPDATE myhome_announcement_source_rows SET sum_suply_co = 170").update();
        rememberCurrent();
        var original = supplies.findAll().getFirst();
        targets.save(com.toadzip.backend.announcement.domain.SupplyTarget.create(original, "전체", null,
                170, null, new BigDecimal("4076000"), new BigDecimal("72820"), null, "공고문 참조", 0));
        types.save(HousingType.createFromMyHome(complex, "other", "26", new BigDecimal("26.5195"), null));
        split(splitBody(matching.rows(TARGET).getFirst())).andExpect(status().isConflict());
        assertThat(supplies.findAll()).singleElement().satisfies(row -> assertThat(row.getId()).isEqualTo(original.getId()));
        assertThat(targets.count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM supply_row_groups").query(Long.class).single()).isZero();
    }

    @Test
    void 복수_공급행_보정도_관리자와_CSRF를_요구한다() throws Exception {
        mvc.perform(post(PATH + "/" + TARGET + "/split").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + TARGET + "/split").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 보정에_새_행을_추가하면_기존_AB_뒤에_배치하며_ID와_공통수를_유지한다() throws Exception {
        prepareHansville();
        split(splitBody(matching.rows(TARGET).getFirst())).andExpect(status().isOk());
        var original = matching.rows(TARGET).getFirst();
        var body = splitBody(original);
        var third = types.save(HousingType.createFromMyHome(complex, "additional-test-type", "26C",
                new BigDecimal("26.6"), null));
        var candidate = matching.housingTypes(complex.getId()).stream()
                .filter(value -> value.id() == third.getId()).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var selections = (List<Map<String, Object>>) body.get("rows");
        selections.add(Map.of("name", "26C", "complexId", complex.getId(), "housingTypeId", third.getId(),
                "targetToken", candidate.token()));
        split(body).andExpect(status().isOk());
        var updated = matching.rows(TARGET).getFirst().splitGroup();
        assertThat(updated.rows()).extracting(value -> value.name()).containsExactly("26A", "26B", "26C");
        assertThat(updated.rows().subList(0, 2)).extracting(value -> value.id())
                .containsExactlyElementsOf(original.splitGroup().rows().stream().map(value -> value.id()).toList());
        assertThat(updated.sharedHouseholdCount()).isEqualTo(170);
    }

    @Test
    void 한_원천에서_AB_공급행을_보정해도_공통_모집수는_한번만_저장하고_재정제에서도_유지한다() throws Exception {
        jdbc.sql("UPDATE myhome_announcement_source_rows SET sum_suply_co = 170").update();
        var second = types.save(HousingType.createFromMyHome(complex, "complex-21395:26B", "26",
                new BigDecimal("26.5195"), null));
        var row = matching.rows(TARGET).getFirst();
        var candidates = matching.housingTypes(complex.getId());
        mvc.perform(post(PATH + "/" + TARGET + "/split").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "rowIdentifier", row.rowIdentifier(), "token", row.token(),
                        "sourceUrl", "https://www.myhome.go.kr/official", "reason", "A/B 연결을 확인한 테스트",
                        "rows", List.of(
                                Map.of("name", "26A", "complexId", complex.getId(), "housingTypeId", type.getId(),
                                        "targetToken", candidates.getFirst().token()),
                                Map.of("name", "26B", "complexId", complex.getId(), "housingTypeId", second.getId(),
                                        "targetToken", candidates.getLast().token()))))))
                .andExpect(status().isOk());
        assertThat(supplies.findAll()).hasSize(2).allSatisfy(value -> {
            assertThat(value.getTotalSupplyHouseholdCount()).isNull();
            assertThat(value.isAdminModified()).isTrue();
        });
        assertThat(jdbc.sql("SELECT shared_household_count FROM supply_row_groups")
                .query(Integer.class).list()).containsExactly(170);
        var ids = supplies.findAll().stream().map(value -> value.getId()).toList();
        mapping.mapAll();
        mapping.mapAll();
        assertThat(supplies.findAll()).extracting(value -> value.getId()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(supplies.findAll()).extracting(value -> value.getSourceHousingTypeName())
                .containsExactlyInAnyOrder("26A", "26B");
    }

    @Test
    void 마이홈_21395의_실제_API_한_원천행과_두_주택형을_보존하고_명시적_선택을_재사용한다() throws Exception {
        var notice = prepareHansville();
        var before = matching.rows(TARGET);
        assertThat(before).singleElement().satisfies(row -> {
            assertThat(row.sourceHousingTypeName()).isEqualTo("아파트");
            assertThat(row.exclusiveArea()).isNull();
            assertThat(row.housingTypeId()).isNull();
        });
        String chosenToken = matching.housingTypes(complex.getId()).stream()
                .filter(option -> option.id() == type.getId()).findFirst().orElseThrow().token();
        remember(before.getFirst(), chosenToken, notice.get("sourceUrl").asText(),
                "재현 테스트의 명시적 후보 선택이며 실제 주택형 대응을 확정한 자료가 아님")
                .andExpect(status().isOk());
        mapping.mapAll();
        assertThat(supplies.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getSourceHousingTypeName()).isEqualTo("아파트");
            assertThat(row.getTotalSupplyHouseholdCount()).isEqualTo(170);
            assertThat(row.getHousingType().getId()).isEqualTo(type.getId());
        });
        assertThat(complexes.findById(complex.getId()).orElseThrow().getTotalHouseholdCount()).isEqualTo(529);
    }

    @Test
    void 근거와_함께_선택을_기억하면_후속_자동_정제에서도_연결을_유지한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        var response = mvc.perform(get(PATH + "/complexes/" + complex.getId() + "/housing-types")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        var option = json.readTree(response.getResponse().getContentAsString()).get(0);
        String targetToken = "unavailable";
        if (option.hasNonNull("token")) {
            targetToken = option.get("token").asText();
        }
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "rows", List.of(Map.of("rowIdentifier", row.rowIdentifier(), "token", row.token(),
                                "complexId", complex.getId(), "housingTypeId", type.getId(), "targetToken", targetToken)),
                        "remember", true, "sourceUrl", "https://example.com/official.pdf",
                        "reason", "공고문 3쪽의 주택형 면적을 확인함"))))
                .andExpect(status().isOk());
        assertThat(matching.rows(TARGET).getFirst().housingTypeId()).isEqualTo(type.getId());
        mapping.mapAll();
        assertThat(supplies.findAll()).singleElement().satisfies(value ->
                assertThat(value.getHousingType().getId()).isEqualTo(type.getId()));
    }

    private tools.jackson.databind.JsonNode prepareHansville() throws Exception {
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
        var apiNotice = (tools.jackson.databind.node.ObjectNode) fixture.get("api")
                .get("announcement").get("items").get(0).deepCopy();
        // 공고 ID만 테스트용으로 격리하고 나머지는 실제 응답값을 유지한다.
        apiNotice.put("pblancId", TARGET);
        var snapshot = json.treeToValue(apiNotice,
                com.toadzip.backend.ingest.collection.myhome.announcement.domain.MyHomeAnnouncementSourceSnapshot.class);
        var source = MyHomeAnnouncementSource.read(null, 1, Instant.parse("2026-10-18T14:56:03Z"),
                null, 0, true, snapshot);
        sources.save(source);
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        // 공식 화면의 단지명·주소만 LH 상세 형식에 담는다. LH 공급 보강은 없는 상태로 재현한다.
        lhStorage.replaceDetails(candidate.panId(), candidate.requestDescription(), List.of(new LhAnnouncementDetailSource(
                0, candidate.panId(), "COMPLEX", notice.get("complexName").asText(),
                notice.get("roadAddress").asText(), null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null)));
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

    @Test
    void 선택값을_한번에_보내면_별도_저장_없이_공고가_등록된다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        assertThat(row.complexId()).isEqualTo(complex.getId());
        assertThat(row.housingTypeId()).isNull();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isOk());
        assertThat(supplies.findAll()).singleElement().satisfies(value -> {
            assertThat(value.getHousingType().getId()).isEqualTo(type.getId());
            assertThat(value.getSourceHousingTypeName()).isEqualTo("26A,B");
        });
        assertThat(sources.findAll()).singleElement().satisfies(value ->
                assertThat(value.getHsmpNm()).isEqualTo("익산한스빌"));
        assertThat(complexes.findById(complex.getId()).orElseThrow().getName()).isEqualTo("한스빌아파트");
        // 선택 규칙은 남지 않는다. 자동 매칭 결과는 여전히 모호하지만 최종 공급행의 연결은 저장된다.
        assertThat(matching.rows(TARGET).getFirst().housingTypeId()).isNull();
    }

    @Test
    void 여러_공급행을_한번에_선택하고_다른_공고의_원천은_변경하지_않는다() throws Exception {
        sources.save(source(TARGET, 2, PNU));
        sources.save(source("other-ann", 1, PNU));
        var requests = matching.rows(TARGET).stream()
                .map(row -> request(row, complex.getId(), type.getId())).toList();
        refine(TARGET, requests).andExpect(status().isOk());
        assertThat(supplies.count()).isEqualTo(2);
        assertThat(announcements.findBySourceAnnouncementIdentifier("other-ann")).isEmpty();
        assertThat(sources.findAll()).hasSize(3);
    }

    @Test
    void 일부_행이_유효하지_않으면_어떤_공고나_공급행도_저장하지_않는다() throws Exception {
        sources.save(source(TARGET, 2, PNU));
        var rows = matching.rows(TARGET);
        var other = complex("다른 단지", "other", PNU, "NATIONAL_RENTAL");
        var foreign = types.save(HousingType.createFromMyHome(other, "other:26", "26", BigDecimal.ONE, null));
        refine(TARGET, List.of(request(rows.getFirst(), complex.getId(), type.getId()),
                request(rows.getLast(), complex.getId(), foreign.getId()))).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
        assertThat(supplies.count()).isZero();
    }

    @Test
    void 정제_후_LH_보강이_실패하면_공고와_공급행을_모두_롤백한다() throws Exception {
        var source = sources.findAll().getFirst();
        ReflectionTestUtils.setField(source, "suplyInsttNm", "LH");
        ReflectionTestUtils.setField(source, "url", "https://example.com/announcements?panId=manual-lh"
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07");
        sources.save(source);
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        for (var target : List.of(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            progressStore.complete(target, TARGET, candidate.requestDescription(), candidate.panId());
        }
        var row = matching.rows(TARGET).getFirst();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
        assertThat(supplies.count()).isZero();
        assertThat(sources.findAll()).hasSize(1);
    }

    @Test
    void 정제_실패_후_원천을_유지하고_선택을_고쳐_재시도할_수_있다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        refine(TARGET, List.of(request(row, complex.getId(), null))).andExpect(status().isBadRequest());
        assertThat(supplies.count()).isZero();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isOk());
        assertThat(supplies.count()).isEqualTo(1);
    }

    @Test
    void PNU가_달라도_관리자가_명시한_단지와_주택형으로_정제한다() throws Exception {
        jdbc.sql("UPDATE myhome_announcement_source_rows SET pnu = '1111010100100010000'").update();
        var row = matching.rows(TARGET).getFirst();
        assertThat(row.complexId()).isNull();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isOk());
        assertThat(supplies.findAll().getFirst().getHousingComplex().getId()).isEqualTo(complex.getId());
    }

    @Test
    void 조회_후_원천이_변경되면_정제를_거절한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET house_ty_nm = '새 주택형'").update();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isConflict());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 재수집_시각만_변경되면_기존_조회_값으로_정제할_수_있다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET collected_at = now()").update();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isOk());
    }

    @Test
    void 새_공급행이_추가되면_다시_조회하도록_한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        sources.save(source(TARGET, 2, PNU));
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isConflict());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 다른_공고의_행이나_중복_행은_거절한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        sources.save(source("other-ann", 1, PNU));
        refine("other-ann", List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isBadRequest());
        sources.save(source(TARGET, 2, PNU));
        var selection = request(row, complex.getId(), type.getId());
        refine(TARGET, List.of(selection, selection)).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 삭제되었거나_공급유형이_다른_단지는_거절한다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = true WHERE id = ?").param(complex.getId()).update();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isBadRequest());
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = false, supply_type = 'HAPPY_HOUSING' WHERE id = ?")
                .param(complex.getId()).update();
        refine(TARGET, List.of(request(row, complex.getId(), type.getId()))).andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 기존_공고도_정제하며_반복_요청은_중복_생성을_하지_않는다() throws Exception {
        var requests = List.of(request(matching.rows(TARGET).getFirst(), complex.getId(), type.getId()));
        refine(TARGET, requests).andExpect(status().isOk());
        refine(TARGET, requests).andExpect(status().isOk());
        assertThat(announcements.count()).isEqualTo(1);
        assertThat(supplies.count()).isEqualTo(1);
    }

    @Test
    void 관리자가_수정한_공급행은_덮어쓰지_않는다() throws Exception {
        var requests = List.of(request(matching.rows(TARGET).getFirst(), complex.getId(), type.getId()));
        refine(TARGET, requests).andExpect(status().isOk());
        jdbc.sql("UPDATE supply_rows SET admin_modified = true").update();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET pblanc_nm = '변경된 공고명'").update();
        var current = List.of(request(matching.rows(TARGET).getFirst(), complex.getId(), type.getId()));
        refine(TARGET, current).andExpect(status().isBadRequest());
        assertThat(supplies.findAll().getFirst().getHousingType().getId()).isEqualTo(type.getId());
        assertThat(announcements.findAll().getFirst().getName()).isEqualTo("국민임대 입주자 모집공고");
    }

    @Test
    void 관리자가_공고_기관을_변경했다면_선택을_무시하고_성공하지_않는다() throws Exception {
        var requests = List.of(request(matching.rows(TARGET).getFirst(), complex.getId(), type.getId()));
        refine(TARGET, requests).andExpect(status().isOk());
        jdbc.sql("UPDATE announcements SET admin_modified = true, provider = 'LH'").update();
        refine(TARGET, requests).andExpect(status().isBadRequest());
        assertThat(supplies.count()).isEqualTo(1);
        assertThat(supplies.findAll().getFirst().getHousingType().getId()).isEqualTo(type.getId());
    }

    @Test
    void 단지_검색과_주택형_조회는_해당_단지의_기존_데이터만_반환한다() {
        assertThat(matching.complexes("한스빌")).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(complex.getId()));
        assertThat(matching.housingTypes(complex.getId())).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(type.getId()));
    }

    @Test
    void 조회와_정제는_관리자_인증_CSRF_필수값을_검증한다() throws Exception {
        String memberId = jdbc.sql("""
                        INSERT INTO users(login_identifier,created_at) VALUES (:identifier,CURRENT_TIMESTAMP)
                        RETURNING id
                        """).param("identifier", "google:matching-" + java.util.UUID.randomUUID())
                .query(Long.class).single().toString();
        mvc.perform(get(PATH + "/" + TARGET)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + "/" + TARGET).with(user(memberId).roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user(memberId).roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}")).andExpect(status().isBadRequest());
    }

    private ResultActions refine(String identifier, List<Request> requests) throws Exception {
        return mvc.perform(post(PATH + "/" + identifier + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("rows", requests))));
    }

    private Request request(Row row, long complexId, Long housingTypeId) {
        return new Request(row.rowIdentifier(), row.token(), complexId, housingTypeId);
    }

    private ResultActions remember(Row row, String targetToken, String sourceUrl, String reason) throws Exception {
        return mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "rows", List.of(Map.of("rowIdentifier", row.rowIdentifier(), "token", row.token(),
                                "complexId", complex.getId(), "housingTypeId", type.getId(),
                                "targetToken", targetToken)),
                        "remember", true, "sourceUrl", sourceUrl, "reason", reason))));
    }

    private void rememberCurrent() throws Exception {
        String token = matching.housingTypes(complex.getId()).stream().filter(value -> value.id() == type.getId())
                .findFirst().orElseThrow().token();
        var selections = matching.rows(TARGET).stream().map(row -> Map.of(
                "rowIdentifier", row.rowIdentifier(), "token", row.token(), "complexId", complex.getId(),
                "housingTypeId", type.getId(), "targetToken", token)).toList();
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "rows", selections, "remember", true, "sourceUrl", "https://example.com/official.pdf",
                        "reason", "공고문 3쪽에서 면적과 주택형 대응 확인")))).andExpect(status().isOk());
    }

    @Test
    void 재확인_공급행을_보존해도_다른_행의_표시순서가_중복되지_않는다() throws Exception {
        sources.save(source(TARGET, 2, PNU));
        rememberCurrent();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET house_ty_nm = '42' WHERE house_sn = 1").update();
        mapping.mapAll();
        assertThat(supplies.findAll()).extracting(value -> value.getDisplayOrder()).doesNotHaveDuplicates();
    }

    @Test
    void 저장한_매칭의_원천이_달라지면_자동_대체하지_않고_마지막_정상_연결을_보존한다() throws Exception {
        rememberCurrent();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET house_ty_nm = '42'").update();
        assertThat(matching.rows(TARGET).getFirst().savedMatch().reviewRequired()).isTrue();
        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(1);
        assertThat(supplies.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getHousingType().getId()).isEqualTo(type.getId());
            assertThat(row.getSourceHousingTypeName()).isEqualTo("26A,B");
        });
        assertThat(jdbc.sql("SELECT reason FROM myhome_announcement_mapping_failures WHERE status = 'PENDING'")
                .query(String.class).list()).contains("MANUAL_MATCH_REVIEW_REQUIRED");
    }

    @Test
    void 연결_판단과_무관한_공고명과_수집시각_변경은_선택을_무효화하지_않는다() throws Exception {
        rememberCurrent();
        jdbc.sql("UPDATE myhome_announcement_source_rows SET pblanc_nm = '공고명 정정', collected_at = now()")
                .update();
        assertThat(matching.rows(TARGET).getFirst().savedMatch().reviewRequired()).isFalse();
        assertThat(mapping.mapAll().failedSourceRowCount()).isZero();
        assertThat(supplies.findAll().getFirst().getHousingType().getId()).isEqualTo(type.getId());
    }

    @Test
    void 저장_이후_주택형이_바뀌면_다른_이름의_후보로_자동_매칭하지_않는다() throws Exception {
        rememberCurrent();
        jdbc.sql("UPDATE housing_types SET exclusive_area = 31 WHERE id = ?").param(type.getId()).update();
        assertThat(matching.rows(TARGET).getFirst().savedMatch().reviewRequired()).isTrue();
        assertThat(mapping.mapAll().failedSourceRowCount()).isEqualTo(1);
        assertThat(supplies.findAll().getFirst().getHousingType().getId()).isEqualTo(type.getId());
    }

    @Test
    void 후보_조회_후_면적이_변경되면_오래된_후보로_저장하지_않는다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        String token = matching.housingTypes(complex.getId()).getFirst().token();
        jdbc.sql("UPDATE housing_types SET exclusive_area = 31 WHERE id = ?").param(type.getId()).update();
        remember(row, token, "https://example.com/official", "원문 확인").andExpect(status().isConflict());
        assertThat(announcements.count()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM announcement_supply_match_decisions").query(Long.class).single())
                .isZero();
    }

    @Test
    void 저장한_선택이_바뀐_뒤에는_이전_화면의_저장과_해제를_거절한다() throws Exception {
        var stale = matching.rows(TARGET).getFirst();
        String token = matching.housingTypes(complex.getId()).getFirst().token();
        rememberCurrent();
        remember(stale, token, "https://example.com/official", "이전 입력").andExpect(status().isConflict());
        mvc.perform(post(PATH + "/" + TARGET + "/release").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "rowIdentifier", stale.rowIdentifier(), "token", stale.token(), "reason", "해제"))))
                .andExpect(status().isConflict());
    }

    @Test
    void 저장한_선택을_근거와_함께_해제하면_제품은_유지하고_다음_정제부터_자동_매칭한다() throws Exception {
        rememberCurrent();
        var row = matching.rows(TARGET).getFirst();
        mvc.perform(post(PATH + "/" + TARGET + "/release").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "rowIdentifier", row.rowIdentifier(), "token", row.token(), "reason", "연결 판단 재검토"))))
                .andExpect(status().isOk());
        assertThat(matching.rows(TARGET).getFirst().savedMatch()).isNull();
        assertThat(supplies.findAll().getFirst().getHousingType().getId()).isEqualTo(type.getId());
        assertThat(matching.rows(TARGET).getFirst().housingTypeId()).isNull();
        assertThat(jdbc.sql("SELECT actor FROM admin_data_changes WHERE action = 'RELEASE_SUPPLY_MATCH'")
                .query(String.class).list()).contains("admin");
    }

    @Test
    void 근거와_유효한_공식_URL이_없으면_선택을_저장하지_않는다() throws Exception {
        var row = matching.rows(TARGET).getFirst();
        String token = matching.housingTypes(complex.getId()).getFirst().token();
        remember(row, token, "javascript:alert(1)", "원문 확인").andExpect(status().isBadRequest());
        remember(row, token, "https://example.com/official", " ").andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
    }

    @Test
    void 일부_원천행이_사라져도_저장한_연결의_공급행은_삭제하지_않는다() throws Exception {
        sources.save(source(TARGET, 2, PNU));
        String targetToken = matching.housingTypes(complex.getId()).getFirst().token();
        var selections = matching.rows(TARGET).stream().map(row -> Map.of(
                "rowIdentifier", row.rowIdentifier(), "token", row.token(), "complexId", complex.getId(),
                "housingTypeId", type.getId(), "targetToken", targetToken)).toList();
        mvc.perform(post(PATH + "/" + TARGET + "/refine").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "rows", selections, "remember", true, "sourceUrl", "https://example.com/official",
                        "reason", "공식 공급행 2개 확인")))).andExpect(status().isOk());
        jdbc.sql("DELETE FROM myhome_announcement_source_rows WHERE house_sn = 1").update();
        mapping.mapAll();
        assertThat(supplies.count()).isEqualTo(2);
        assertThat(supplies.findAll()).extracting(value -> value.getDisplayOrder()).doesNotHaveDuplicates();
    }

    @Test
    void 보정한_연결과_제외_면적_순서는_재정제와_원천소실에도_유지한다() throws Exception {
        rememberCurrent();
        var original = supplies.findAll().getFirst();
        original.correctPresentation(true, 7, true, new BigDecimal("26.5195"), null);
        original.confirmTargetCountScope(true);
        supplies.saveAndFlush(original);
        sources.save(source(TARGET, 2, PNU));
        mapping.mapAll();
        jdbc.sql("DELETE FROM myhome_announcement_source_rows WHERE house_sn = 1").update();
        mapping.mapAll();
        var preserved = supplies.findById(original.getId()).orElseThrow();
        assertThat(preserved.isAdminExcluded()).isTrue();
        assertThat(preserved.isAreaOverride()).isTrue();
        assertThat(preserved.effectiveExclusiveArea()).isEqualByComparingTo("26.5195");
        assertThat(preserved.getDisplayOrder()).isEqualTo(7);
        assertThat(preserved.getHousingType().getId()).isEqualTo(type.getId());
        assertThat(preserved.isTargetCountsComparable()).isTrue();
        assertThat(supplies.count()).isEqualTo(2);
    }

    @Test
    void 선택_해제도_관리자_권한과_CSRF를_요구한다() throws Exception {
        mvc.perform(post(PATH + "/" + TARGET + "/release").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + "/" + TARGET + "/release").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 뒤의_LH_보강이_실패하면_선택과_제품과_선택_이력을_함께_취소한다() throws Exception {
        var source = sources.findAll().getFirst();
        ReflectionTestUtils.setField(source, "suplyInsttNm", "LH");
        ReflectionTestUtils.setField(source, "url", "https://example.com/announcements?panId=manual-lh"
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07");
        sources.save(source);
        var candidate = (LhAnnouncementCollectionCandidateResolver.Candidate) candidateResolver.resolve(source);
        for (var target : List.of(ExternalDataSource.LH_ANNOUNCEMENT_SUPPLY, ExternalDataSource.LH_ANNOUNCEMENT_DETAIL)) {
            progressStore.complete(target, TARGET, candidate.requestDescription(), candidate.panId());
        }
        remember(matching.rows(TARGET).getFirst(), matching.housingTypes(complex.getId()).getFirst().token(),
                "https://example.com/official", "공식 자료 확인").andExpect(status().isBadRequest());
        assertThat(announcements.count()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM announcement_supply_match_decisions").query(Long.class).single())
                .isZero();
    }

    private HousingComplex complex(String name, String identifier, String pnu, String supplyType) {
        return complexes.save(HousingComplex.createFromMyHome(name, identifier, supplyType,
                Address.create("익산시 목천로 30-14", pnu, pnu.substring(0, 10), "28", "28237",
                        new BigDecimal("35.9"), new BigDecimal("126.9")),
                100, "SH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 80));
    }

    private MyHomeAnnouncementSource source(String identifier, int house, String pnu) {
        var source = MyHomeAnnouncementSource.from(house, new MyHomeAnnouncementSourceSnapshot(identifier,
                house, "모집중", "국민임대 입주자 모집공고", "서울주택도시공사", "26A,B", "국민임대", null,
                "20261001", "20261106", "20261008", "20261015", null, "https://example.com/manual", null,
                null, "익산한스빌", "전북특별자치도", "익산시", null, null, null, pnu, null, null,
                0, null, null, null, null));
        source.markCollectedAt(Instant.parse("2026-10-08T00:00:00Z"));
        return source;
    }
}
