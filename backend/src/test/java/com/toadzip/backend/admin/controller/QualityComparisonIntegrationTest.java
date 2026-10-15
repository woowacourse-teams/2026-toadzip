package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@org.springframework.test.context.jdbc.Sql("/db/migration/V20261016_01__quality_comparison_workflow.sql")
class QualityComparisonIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private com.toadzip.backend.announcement.repository.SupplyRowRepository supplyRows;
    @Autowired private com.toadzip.backend.announcement.repository.SupplyTargetRepository supplyTargets;
    @Autowired private EntityManager entities;
    @Autowired private com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures sourceRows;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void lease() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(invocation -> org.mockito.Mockito.mock(
                IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 주택형만_선택_수정하고_이후_오래된_요청은_거부한다() throws Exception {
        var type = type();
        long id = type.getHousingComplex().getId();
        String target = "type:" + type.getId();
        var before = detail(id, target);
        mvc.perform(put(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "target", target, "snapshotToken", before.get("snapshotToken").asText(),
                                "values", Map.of("householdCount", 0), "evidenceNote", "주택형 전체 세대수 확인"))))
                .andExpect(status().isOk());
        entities.clear();
        assertThat(types.findById(type.getId()).orElseThrow().getTotalHouseholdCount()).isZero();
        assertThat(complexes.findById(id).orElseThrow().getTotalHouseholdCount()).isEqualTo(120);
        mvc.perform(put(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "target", target, "snapshotToken", before.get("snapshotToken").asText(),
                                "values", Map.of("name", "오래된 수정"), "evidenceNote", "오래된 화면"))))
                .andExpect(status().isConflict());
        var history = mvc.perform(get("/api/admin/housing-complexes/" + id + "/changes")
                .with(user("admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("comparisonEvidence", "주택형 전체 세대수 확인", "type:");
    }

    @Test
    void 다른_단지의_주택형과_잘못된_값을_거부한다() throws Exception {
        var first = type();
        var other = type();
        long id = first.getHousingComplex().getId();
        mvc.perform(get(root(id)).param("target", "type:" + other.getId())
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        var before = detail(id, "type:" + first.getId());
        mvc.perform(put(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "target", "type:" + first.getId(), "snapshotToken", before.get("snapshotToken").asText(),
                                "values", Map.of("householdCount", -1), "evidenceNote", "음수 거부"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 권한_CSRF_근거와_원천의_분류를_검증한다() throws Exception {
        var type = type();
        long id = type.getHousingComplex().getId();
        mvc.perform(get(root(id))).andExpect(status().isUnauthorized());
        var snapshot = detail(id, "type:" + type.getId());
        var body = json.writeValueAsString(Map.of("target", "type:" + type.getId(),
                "snapshotToken", snapshot.get("snapshotToken").asText(), "category", "LH_ANNOUNCEMENT_SUPPLY",
                "sourceKey", "wrong", "evidenceNote", "분류 검증"));
        mvc.perform(post(root(id) + "/sources").with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(root(id) + "/sources").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void 확인한_원천은_수집시각만_변경되면_유지하고_실제_값_변경과_소실은_재검토로_표시한다() throws Exception {
        var type = type();
        long id = type.getHousingComplex().getId();
        String target = "type:" + type.getId();
        String key = source();
        var before = detail(id, target);
        var linked = json.readTree(mvc.perform(post(root(id) + "/sources").with(user("admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "target", target, "snapshotToken", before.get("snapshotToken").asText(),
                        "category", "MYHOME_COMPLEX", "sourceKey", key, "evidenceNote", "주택형과 주소 확인"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        mvc.perform(post(root(id) + "/checks").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("target", target,
                        "snapshotToken", linked.get("snapshotToken").asText(), "evidenceNote", "원천 확인 기록"))))
                .andExpect(status().isOk());
        entities.createNativeQuery("UPDATE myhome_complex_source_rows SET collected_at = CURRENT_TIMESTAMP")
                .executeUpdate();
        assertThat(sourceChanges(id)).isZero();
        entities.createNativeQuery("UPDATE myhome_complex_source_rows SET hshld_co = 321").executeUpdate();
        assertThat(sourceChanges(id)).isEqualTo(1);
        mvc.perform(put(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("target", target,
                        "snapshotToken", linked.get("snapshotToken").asText(), "values", Map.of("name", "덮어쓰기"),
                        "evidenceNote", "오래된 원천으로 수정 시도")))).andExpect(status().isConflict());
        entities.createNativeQuery("DELETE FROM myhome_complex_source_rows").executeUpdate();
        assertThat(sourceChanges(id)).isEqualTo(1);
        assertThat(types.findById(type.getId()).orElseThrow().getName()).isEqualTo("26A");
    }

    private long sourceChanges(long id) throws Exception {
        String response = mvc.perform(get("/api/admin/quality/queue").param("resource", "COMPLEX")
                .param("reason", "SOURCE_CHANGED").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long count = 0;
        for (var item : json.readTree(response).at("/data/items")) {
            if (item.get("id").asLong() == id) {
                count++;
            }
        }
        return count;
    }

    @Test
    void 공고와_공급행_임대조건을_분리해_수정하고_큰_금액_문자열을_보존한다() throws Exception {
        long complexId = type().getHousingComplex().getId();
        String request = """
                {"housingComplexId":%d,"name":"비교 공고","rentalType":"HAPPY_HOUSING",
                "recruitmentType":"NEW","agencyCode":"LH","postedDate":"2026-09-01",
                "applicationStartDate":"2026-09-10","applicationEndDate":"2026-09-20",
                "winnerAnnouncementDate":"2026-10-01","originalUrl":"https://example.com/notice",
                "receptionPlace":{"name":"LH","method":"ONLINE","contact":"1600-1004"},
                "supplyRow":{"sourceComplexName":"비교 단지","sourceHousingTypeName":"26A",
                "supplyPnu":"1114010300100010000","supplyCategory":"NEW_SUPPLY","totalSupplyHouseholdCount":5}}
                """.formatted(complexId);
        long id = json.readTree(mvc.perform(post("/api/admin/announcements").with(user("admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .at("/data/announcementId").asLong();
        String path = "/api/admin/quality/ANNOUNCEMENT/" + id + "/comparison";
        var first = json.readTree(mvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        mvc.perform(put(path).with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("target", "basic", "snapshotToken", first.get("snapshotToken").asText(),
                        "values", Map.of("name", "원문 확인 공고"), "evidenceNote", "공식 공고명 확인"))))
                .andExpect(status().isOk());
        var row = supplyRows.findAll().stream().filter(item -> item.getAnnouncement().getId() == id).findFirst().orElseThrow();
        var condition = supplyTargets.saveAndFlush(com.toadzip.backend.announcement.domain.SupplyTarget.create(
                row, "일반", "1순위", 5, null, new BigDecimal("10000000"), new BigDecimal("100000"), null, null, 0));
        String target = "target:" + condition.getId();
        var before = json.readTree(mvc.perform(get(path).param("target", target).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        String saved = mvc.perform(put(path).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("target", target,
                        "snapshotToken", before.get("snapshotToken").asText(), "values",
                        Map.of("rentalDeposit", "999999999999999999", "monthlyRent", "0"),
                        "evidenceNote", "해당 공급대상 원 단위 확인"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(saved).contains("999999999999999999");
        entities.clear();
        var stored = supplyTargets.findById(condition.getId()).orElseThrow();
        assertThat(stored.getRentalDeposit()).isEqualByComparingTo("999999999999999999");
        assertThat(stored.getMonthlyRent()).isEqualByComparingTo("0");
        assertThat(complexes.findById(complexId).orElseThrow().getTotalHouseholdCount()).isEqualTo(120);
    }

    private String source() throws Exception {
        var source = com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource.from(
                new com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot(
                        991421L, "한국토지주택공사", "11", "서울", "11140", "중구", "검토 원천 421",
                        "서울특별시 중구 세종대로 110", "1114010300100010000", "20200101", 120, "행복주택", "26A",
                        new BigDecimal("26.1234"), null, "아파트", "지역난방", "계단식", "미설치", 0,
                        null, null, null));
        source.markCollectedAt(java.time.Instant.parse("2026-10-15T00:00:00Z"));
        sourceRows.save(source);
        String response = mvc.perform(get("/api/admin/ingest/sources").param("category", "MYHOME_COMPLEX")
                .param("keyword", "검토 원천 421").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/items/0/sourceKey").asText();
    }

    private JsonNode detail(long id, String target) throws Exception {
        return json.readTree(mvc.perform(get(root(id)).param("target", target)
                .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private String root(long id) {
        return "/api/admin/quality/COMPLEX/" + id + "/comparison";
    }

    private HousingType type() {
        var complex = complexes.saveAndFlush(HousingComplex.createFromMyHome("주택형 비교", "manual:" + UUID.randomUUID(),
                "HAPPY_HOUSING", Address.create("서울특별시 중구 세종대로 110", "1114010300100010000",
                        "1114010300", "11", "11140", new BigDecimal("37.5665"), new BigDecimal("126.978")),
                120, "LH", null, null, null, null, null, 0));
        return types.saveAndFlush(HousingType.createFromMyHome(complex, "manual:" + UUID.randomUUID(),
                "26A", new BigDecimal("26.1234"), null));
    }
}
