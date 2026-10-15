package com.toadzip.backend.housing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
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
class ComplexComparisonIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private EntityManager entityManager;
    @Autowired private com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures sources;
    @Autowired private com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository mappingLinks;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void pipelineLease() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(invocation -> org.mockito.Mockito.mock(
                IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 원천이_없는_단지도_현재값을_불러와_수정하고_근거와_이력을_보존한다() throws Exception {
        long id = complex();
        JsonNode before = comparison(id);
        assertThat(before.get("sources").isEmpty()).isTrue();
        var data = before.get("current").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) data).put("totalHouseholdCount", 120);
        save(id, before, data).andExpect(status().isOk());
        entityManager.clear();
        assertThat(complexes.findById(id).orElseThrow().getTotalHouseholdCount()).isEqualTo(120);
        assertThat(complexes.findById(id).orElseThrow().isAdminModified()).isTrue();
        String history = mvc.perform(get(root(id) + "/changes").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(history).contains("UPDATE_COMPARED", "현장 공식 자료 확인", "comparisonEvidence");
    }

    @Test
    void 오래된_등록값으로_수정하면_충돌로_거부하고_아무것도_반영하지_않는다() throws Exception {
        long id = complex();
        JsonNode before = comparison(id);
        entityManager.createNativeQuery("UPDATE housing_complexes SET name = '다른 관리자 수정' WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        entityManager.clear();
        save(id, before, before.get("current")).andExpect(status().isConflict());
        assertThat(complexes.findById(id).orElseThrow().getName()).isEqualTo("다른 관리자 수정");
    }

    @Test
    void 단지_외의_원천은_연결할_수_없으며_파일없이_검증을_시작한다() throws Exception {
        long id = complex();
        var before = comparison(id);
        mvc.perform(post(root(id) + "/verification/comparison/sources").with(user("admin").roles("ADMIN"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "snapshotToken", before.get("snapshotToken").asText(), "category", "SH_ANNOUNCEMENT",
                                "sourceKey", "not-a-complex", "evidenceNote", "연결 근거"))))
                .andExpect(status().isBadRequest());
        assertThat(comparison(id).get("sources").isEmpty()).isTrue();
    }

    @Test
    void 관리자_권한과_CSRF와_수정_근거를_요구한다() throws Exception {
        long id = complex();
        mvc.perform(get(root(id) + "/verification/comparison")).andExpect(status().isUnauthorized());
        var before = comparison(id);
        var body = json.writeValueAsString(Map.of("snapshotToken", before.get("snapshotToken").asText(),
                "current", before.get("current"), "evidenceNote", ""));
        mvc.perform(put(root(id) + "/verification/comparison").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(put(root(id) + "/verification/comparison").with(user("admin").roles("ADMIN"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void DB_원천을_명시적으로_연결하고_해제해도_자동정제_연결과_등록값은_변하지_않는다() throws Exception {
        long id = complex();
        String key = sourceKey(99101);
        var before = comparison(id);
        link(id, before, key).andExpect(status().isOk());
        var connected = comparison(id);
        assertThat(connected.get("sources").size()).isEqualTo(1);
        assertThat(connected.at("/sources/0/data/raw/hshld_co").asInt()).isEqualTo(120);
        assertThat(connected.at("/current/totalHouseholdCount").asInt()).isZero();
        assertThat(mappingLinks.findAllByHousingComplexId(id)).isEmpty();
        link(id, connected, key).andExpect(status().isConflict());
        mvc.perform(delete(root(id) + "/verification/comparison/sources")
                        .with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(linkBody(connected, key)))).andExpect(status().isOk());
        assertThat(comparison(id).get("sources").isEmpty()).isTrue();
        assertThat(complexes.findById(id).orElseThrow().isAdminModified()).isFalse();
    }

    @Test
    void 연결한_원천이_바뀌면_수정_저장을_거부하고_사라진_원천도_연결_기록을_보존한다() throws Exception {
        long id = complex();
        String key = sourceKey(99102);
        link(id, comparison(id), key).andExpect(status().isOk());
        var before = comparison(id);
        long rowId = before.at("/sources/0/data/id").asLong();
        entityManager.createNativeQuery("UPDATE myhome_complex_source_rows SET hshld_co = 121 WHERE id = :id")
                .setParameter("id", rowId).executeUpdate();
        entityManager.clear();
        save(id, before, before.get("current")).andExpect(status().isConflict());
        entityManager.createNativeQuery("DELETE FROM myhome_complex_source_rows WHERE id = :id")
                .setParameter("id", rowId).executeUpdate();
        entityManager.clear();
        var missing = comparison(id);
        assertThat(missing.get("sources").size()).isEqualTo(1);
        assertThat(missing.at("/sources/0/data").isNull()).isTrue();
        assertThat(missing.at("/sources/0/evidenceNote").asText()).isEqualTo("주소와 공급 범위 확인");
    }

    @Test
    void 자동_마이홈_원천의_시설_항목도_파일없이_가져온다() throws Exception {
        long id = complex();
        sourceKey(99103);
        mappingLinks.saveAndFlush(com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink
                .connect("99103:HAPPY_HOUSING", complexes.findById(id).orElseThrow()));
        var before = comparison(id);
        assertThat(before.at("/sources/0/automatic").asBoolean()).isTrue();
        assertThat(before.at("/sources/0/data/raw/heat_mthd_detail_nm").asText()).isEqualTo("지역난방");
        assertThat(before.at("/sources/0/data/raw/parkng_co").asInt()).isEqualTo(0);
    }

    @Test
    void 같은_원천키의_행이_여러개면_서로_다른_값을_모두_보존한다() throws Exception {
        long id = complex();
        String key = sourceKey(99104);
        entityManager.createNativeQuery("""
                INSERT INTO myhome_complex_source_rows
                  (source_id, source_order, hsmp_sn, pnu, suply_ty_nm, style_nm, suply_prvuse_ar,
                   suply_cmnuse_ar, hsmp_nm, hshld_co, collected_at)
                SELECT source_id, 1, hsmp_sn, pnu, suply_ty_nm, style_nm, suply_prvuse_ar,
                       suply_cmnuse_ar, hsmp_nm, 121, collected_at
                FROM myhome_complex_source_rows WHERE hsmp_sn = 99104
                """).executeUpdate();
        link(id, comparison(id), key).andExpect(status().isOk());
        var after = comparison(id).get("sources");
        assertThat(after.size()).isEqualTo(2);
        assertThat(after.get(0).at("/data/raw/hshld_co").asInt()).isEqualTo(120);
        assertThat(after.get(1).at("/data/raw/hshld_co").asInt()).isEqualTo(121);
    }

    @Test
    void 원천_연결_직후와_재조회_토큰이_같아_이어_수정할_수_있다() throws Exception {
        long id = complex();
        String key = sourceKey(99105);
        JsonNode connected = json.readTree(link(id, comparison(id), key).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        entityManager.clear();
        assertThat(comparison(id).get("snapshotToken").asText()).isEqualTo(connected.get("snapshotToken").asText());
        var current = (tools.jackson.databind.node.ObjectNode) connected.get("current").deepCopy();
        current.put("totalHouseholdCount", 120);
        save(id, connected, current).andExpect(status().isOk());
    }

    @Test
    void 주소_변경은_전체_주소정보_확인없이_저장할_수_없다() throws Exception {
        long id = complex();
        var before = comparison(id);
        var current = (tools.jackson.databind.node.ObjectNode) before.get("current").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) current.get("address")).put("roadAddress", "주소 변경");
        save(id, before, current).andExpect(status().isBadRequest());
        assertThat(comparison(id).at("/current/address/roadAddress").asText()).contains("세종대로");
    }

    @Test
    void 저장_응답의_좌표_정밀도와_다음_조회_토큰이_일치한다() throws Exception {
        long id = complex();
        var before = comparison(id);
        var current = (tools.jackson.databind.node.ObjectNode) before.get("current").deepCopy();
        current.put("name", "새 이름");
        JsonNode saved = json.readTree(save(id, before, current).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        entityManager.clear();
        assertThat(comparison(id).get("snapshotToken").asText()).isEqualTo(saved.get("snapshotToken").asText());
    }

    private String sourceKey(long identifier) throws Exception {
        var row = com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource.from(
                new com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot(
                        identifier, "한국토지주택공사", "11", "서울", "11140", "중구", "비교 원천 " + identifier,
                        "서울특별시 중구 세종대로 110", "1114010100100010000", "20200101", 120, "행복주택", "26A",
                        new BigDecimal("26"), null, "아파트", "지역난방", "계단식", "미설치", 0,
                        null, null, null));
        row.markCollectedAt(java.time.Instant.parse("2026-10-08T00:00:00Z"));
        sources.save(row);
        return json.readTree(mvc.perform(get("/api/admin/ingest/sources").param("category", "MYHOME_COMPLEX")
                        .param("keyword", "비교 원천 " + identifier).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at("/data/items/0/sourceKey").asText();
    }

    private Map<String, Object> linkBody(JsonNode before, String key) {
        return Map.of("snapshotToken", before.get("snapshotToken").asText(), "category", "MYHOME_COMPLEX",
                "sourceKey", key, "evidenceNote", "주소와 공급 범위 확인");
    }

    private org.springframework.test.web.servlet.ResultActions link(long id, JsonNode before, String key)
            throws Exception {
        return mvc.perform(post(root(id) + "/verification/comparison/sources")
                .with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(linkBody(before, key))));
    }

    private long complex() {
        return complexes.saveAndFlush(HousingComplex.createFromMyHome("비교 단지", "admin:" + UUID.randomUUID(),
                "HAPPY_HOUSING", Address.create("서울특별시 중구 세종대로 110", "1114010100100010000",
                        "1114010100", "11", "11140", new BigDecimal("37.566500"), new BigDecimal("126.978000")),
                0, "LH", null, null, null, null, null, 0)).getId();
    }

    private JsonNode comparison(long id) throws Exception {
        return json.readTree(mvc.perform(get(root(id) + "/verification/comparison")
                        .with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private org.springframework.test.web.servlet.ResultActions save(long id, JsonNode snapshot, JsonNode data)
            throws Exception {
        return mvc.perform(put(root(id) + "/verification/comparison").with(user("admin").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "snapshotToken", snapshot.get("snapshotToken").asText(), "current", data,
                        "evidenceNote", "현장 공식 자료 확인", "addressConfirmed", false))));
    }

    private String root(long id) {
        return "/api/admin/housing-complexes/" + id;
    }
}
