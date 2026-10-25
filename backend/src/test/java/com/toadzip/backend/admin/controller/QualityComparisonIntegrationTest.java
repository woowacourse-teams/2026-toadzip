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
    @Autowired private com.toadzip.backend.admin.quality.repository.QualityComparisonRepository comparisons;
    @Autowired private com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures sourceRows;
    @Autowired private com.toadzip.backend.ingest.collection.fixture.repository.LhLeaseCatalogSourceFixtures catalogRows;
    @Autowired private com.toadzip.backend.ingest.source.repository.IngestSourceQueryRepository rawSources;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
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
                                "target", "type:" + first.getId(),
                                "snapshotToken", before.get("snapshotToken").asText(),
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
    void 확인한_뒤_원천_값이_바뀌면_오래된_화면의_수정을_거절한다() throws Exception {
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
        entities.createNativeQuery("UPDATE myhome_complex_source_rows SET hshld_co = 321").executeUpdate();
        mvc.perform(put(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("target", target,
                        "snapshotToken", linked.get("snapshotToken").asText(), "values", Map.of("name", "덮어쓰기"),
                        "evidenceNote", "오래된 원천으로 수정 시도")))).andExpect(status().isConflict());
        assertThat(types.findById(type.getId()).orElseThrow().getName()).isEqualTo("26A");
    }

    @Test
    void LH_카탈로그를_같은_내용으로_재수집하면_단지와_주택형의_연결과_확인_기록이_유지된다() throws Exception {
        var type = type();
        long id = type.getHousingComplex().getId();
        String target = "type:" + type.getId();
        catalogRow("카탈로그 연결 단지", "10000000");
        catalogRow("다른 카탈로그 단지", "20000000");
        String key = catalogKey();
        var before = detail(id, target);
        mvc.perform(post(root(id) + "/sources").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "target", target, "snapshotToken", before.get("snapshotToken").asText(),
                        "category", "LH_LEASE_CATALOG", "sourceKey", key, "evidenceNote", "주택형 원천 확인"))))
                .andExpect(status().isOk());
        String basicPath = "/api/admin/housing-complexes/" + id + "/verification/comparison";
        var basic = json.readTree(mvc.perform(get(basicPath).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        mvc.perform(post(basicPath + "/sources").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "snapshotToken", basic.get("snapshotToken").asText(), "category", "LH_LEASE_CATALOG",
                        "sourceKey", key, "evidenceNote", "단지 원천 확인")))).andExpect(status().isOk());
        var linked = detail(id, target);
        mvc.perform(post(root(id) + "/checks").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "target", target, "snapshotToken", linked.get("snapshotToken").asText(),
                        "evidenceNote", "재수집 전 원천 확인")))).andExpect(status().isOk());
        long previousId = linked.at("/sources/0/rows/0/id").asLong();

        // 실제 저장기처럼 전부 삭제·삽입하되 응답 순서와 수집 시각도 바꾼다.
        catalogRows.deleteAll();
        catalogRow("다른 카탈로그 단지", "20000000");
        catalogRow("카탈로그 연결 단지", "10000000");
        entities.createNativeQuery("UPDATE lh_lease_catalog_source_rows SET collected_at = CURRENT_TIMESTAMP")
                .executeUpdate();

        var recollected = detail(id, target);
        assertThat(catalogKey()).isEqualTo(key);
        assertThat(recollected.at("/sources/0/rows").size()).isEqualTo(1);
        assertThat(recollected.at("/sources/0/rows/0/id").asLong()).isNotEqualTo(previousId);
        var base = json.readTree(mvc.perform(get(basicPath).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(base.at("/sources/0/data/raw/deposit_text").asText()).isEqualTo("10000000");

        entities.createNativeQuery("UPDATE lh_lease_catalog_source_rows SET deposit_text = '11000000' "
                + "WHERE complex_label = '카탈로그 연결 단지'").executeUpdate();
        assertThat(catalogKey()).isEqualTo(key);
        catalogRows.deleteAll();
        assertThat(detail(id, target).at("/sources/0/rows").isEmpty()).isTrue();
    }

    @Test
    void 기존_숫자키와_중복행의_연결을_이관하고_확인_근거와_다른_분류는_보존한다() throws Exception {
        var type = type();
        long id = type.getHousingComplex().getId();
        String target = "type:" + type.getId();
        var category = com.toadzip.backend.ingest.source.dto.IngestSourceCategory.LH_LEASE_CATALOG;
        var resource = com.toadzip.backend.admin.quality.domain.QualityResource.COMPLEX;
        long first = catalogRow("카탈로그 연결 단지", "10000000");
        long second = catalogRow("카탈로그 연결 단지", "20000000");
        var expected = new java.util.ArrayList<Map<String, Object>>();
        for (long rowId : java.util.List.of(first, second)) {
            String key = rawSources.findById(category, rowId).orElseThrow().sourceKey();
            assertThat(key).startsWith("lh-lease:");
            entities.createNativeQuery("""
                    INSERT INTO complex_comparison_links
                    (housing_complex_id, category, source_key, evidence_note, actor, linked_at)
                    VALUES (:id, 'LH_LEASE_CATALOG', :key, '기존 단지 근거', 'original-admin', CURRENT_TIMESTAMP)
                    """).setParameter("id", id).setParameter("key", Long.toString(rowId)).executeUpdate();
            comparisons.link(resource, id, target, category, Long.toString(rowId), "기존 주택형 근거", "original-admin");
            expected.add(Map.of("category", category.name(), "sourceKey", key,
                    "rows", json.readTree(comparisons.fingerprint(category, key))));
        }
        var legacy = json.valueToTree(expected).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) legacy.get(0)).put("sourceKey", Long.toString(first));
        ((tools.jackson.databind.node.ObjectNode) legacy.get(1)).put("sourceKey", Long.toString(second));
        comparisons.check(resource, id, target, json.writeValueAsString(legacy), "{}", "기존 확인 근거", "original-admin");
        comparisons.link(resource, id, target,
                com.toadzip.backend.ingest.source.dto.IngestSourceCategory.MYHOME_COMPLEX,
                Long.toString(first), "다른 분류", "original-admin");
        comparisons.link(resource, id, target, category, "9223372036854775807", "이미 소실된 원천", "original-admin");

        for (int run = 0; run < 2; run++) {
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource(
                                "db/migration/V20261019_01__stable_lh_lease_comparison_keys.sql"));
                return null;
            });
        }

        var keys = expected.stream().map(item -> (String) item.get("sourceKey")).toList();
        assertThat(keys).doesNotHaveDuplicates();
        assertThat(jdbc.queryForList("SELECT source_key FROM complex_comparison_links WHERE housing_complex_id = ?",
                String.class, id)).containsExactlyInAnyOrderElementsOf(keys);
        var links = comparisons.links(resource, id, target);
        assertThat(links.stream().filter(link -> link.category() == category).map(link -> link.sourceKey()).toList())
                .containsExactlyInAnyOrder(keys.get(0), keys.get(1), "9223372036854775807");
        assertThat(links.stream().filter(link -> link.category()
                == com.toadzip.backend.ingest.source.dto.IngestSourceCategory.MYHOME_COMPLEX).findFirst().orElseThrow()
                .sourceKey()).isEqualTo(Long.toString(first));
        assertThat(json.readTree(jdbc.queryForObject("SELECT evidence::text FROM admin_comparison_checks "
                + "WHERE resource_id = ? AND target = ?", String.class, id, target))).isEqualTo(json.valueToTree(expected));
        assertThat(jdbc.queryForObject("SELECT evidence_note || ':' || actor FROM admin_comparison_checks "
                + "WHERE resource_id = ? AND target = ?", String.class, id, target))
                .isEqualTo("기존 확인 근거:original-admin");
        catalogRows.deleteAll();
        catalogRow("카탈로그 연결 단지", "10000000");
        catalogRow("카탈로그 연결 단지", "20000000");
    }

    private long catalogRow(String name, String deposit) {
        var row = new com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource(0,
                new com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.projection.LhCatalogSourceSnapshot(
                        "서울 중구", "행복주택", name, "120", "26.12", "10", deposit, "100000"));
        row.markCollectedAt(java.time.Instant.parse("2026-10-15T00:00:00Z"));
        return catalogRows.save(row).getId();
    }

    private String catalogKey() throws Exception {
        var response = mvc.perform(get("/api/admin/ingest/sources").param("category", "LH_LEASE_CATALOG")
                .param("keyword", "카탈로그 연결 단지").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data/items/0/sourceKey").asText();
    }

    @Test
    void 공고의_수집_실행과_누락_횟수만_바뀌면_원천값_변경으로_보지_않는다() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID record : java.util.List.of(first, second)) {
            entities.createNativeQuery("""
                    INSERT INTO source_collection_records
                    (id, version, source, started_at, finished_at, status, stored_row_count)
                    VALUES (:id, 0, 'MYHOME_ANNOUNCEMENT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SUCCESS', 1)
                    """).setParameter("id", record).executeUpdate();
        }
        entities.createNativeQuery("""
                INSERT INTO myhome_announcement_source_bundles(version, pblanc_id, last_collection_record_id)
                VALUES (0, 'quality-metadata-test', :record)
                """).setParameter("record", first).executeUpdate();
        entities.createNativeQuery("""
                INSERT INTO myhome_announcement_source_rows
                (source_id, collection_record_id, source_order, pblanc_id, house_sn, pblanc_nm,
                active, consecutive_miss_count)
                SELECT id, :record, 0, pblanc_id, 1, '동일 공고', true, 0
                FROM myhome_announcement_source_bundles WHERE pblanc_id = 'quality-metadata-test'
                """).setParameter("record", first).executeUpdate();
        String query = "SELECT raw::text FROM ("
                + com.toadzip.backend.admin.quality.repository.QualityComparisonRepository.SOURCE_ROWS
                + ") sources WHERE category = 'MYHOME_ANNOUNCEMENT' AND raw ->> 'pblanc_id' = 'quality-metadata-test'";
        Object before = entities.createNativeQuery(query).getSingleResult();
        String key = "21:quality-metadata-test1:1";
        var category = com.toadzip.backend.ingest.source.dto.IngestSourceCategory.MYHOME_ANNOUNCEMENT;
        var checked = json.readTree(comparisons.fingerprint(category, key));
        assertThat(checked.size()).isEqualTo(1);
        assertThat(checked.get(0)).isEqualTo(json.readTree((String) before));
        entities.createNativeQuery("""
                UPDATE myhome_announcement_source_rows SET collection_record_id = :record,
                collected_at = CURRENT_TIMESTAMP, last_seen_run_id = 'next-run', consecutive_miss_count = 1
                WHERE pblanc_id = 'quality-metadata-test'
                """).setParameter("record", second).executeUpdate();
        assertThat(entities.createNativeQuery(query).getSingleResult()).isEqualTo(before);
        assertThat(json.readTree(comparisons.fingerprint(category, key))).isEqualTo(checked);
        entities.createNativeQuery("""
                UPDATE myhome_announcement_source_rows SET active = false WHERE pblanc_id = 'quality-metadata-test'
                """).executeUpdate();
        assertThat(entities.createNativeQuery(query).getSingleResult()).isNotEqualTo(before);
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
                .content(json.writeValueAsString(Map.of("target", "basic",
                        "snapshotToken", first.get("snapshotToken").asText(),
                        "values", Map.of("name", "원문 확인 공고"), "evidenceNote", "공식 공고명 확인"))))
                .andExpect(status().isOk());
        var row = supplyRows.findAll().stream().filter(item -> item.getAnnouncement().getId() == id)
                .findFirst().orElseThrow();
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
