package com.toadzip.backend.housing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionOwnershipService;
import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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
class ComplexVerificationIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private com.toadzip.backend.ingest.collection.fixture.repository.MyHomeComplexSourceFixtures sources;
    @Autowired private com.toadzip.backend.ingest.mapping.repository.MyHomeComplexLinkRepository links;
    @MockitoBean private IngestExecutionOwnershipService ownership;

    @BeforeEach
    void isolatePipelineLock() {
        org.mockito.Mockito.when(ownership.acquire()).thenAnswer(invocation -> org.mockito.Mockito.mock(
                IngestExecutionOwnershipService.Execution.class));
    }

    @Test
    void 원천이_없는_단지도_현재값과_미검토_상태를_조회한다() throws Exception {
        long id = createComplex();
        mvc.perform(get(path(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNREVIEWED"))
                .andExpect(jsonPath("$.data.currentValues.NAME").value("검증 단지"))
                .andExpect(jsonPath("$.data.currentValues.HOUSEHOLD_COUNT").value(0))
                .andExpect(jsonPath("$.data.sources").isEmpty())
                .andExpect(jsonPath("$.data.latestReview").isEmpty());
    }

    @Test
    void 같은_이름이_아닌_연결_식별자와_공급유형으로_모든_통합원천을_조회한다() throws Exception {
        long id = createComplex();
        source(901, "행복주택", 10);
        source(901, "국민임대", 99);
        source(902, "행복주택", 20);
        source(903, "행복주택", 999);
        var complex = complexes.findById(id).orElseThrow();
        links.saveAndFlush(com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink
                .connect("901:HAPPY_HOUSING", complex));
        links.saveAndFlush(com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink
                .connect("902:HAPPY_HOUSING", complex));
        JsonNode evidence = data(id).get("sources");
        assertThat(evidence.size()).isEqualTo(2);
        assertThat(evidence.get(0).get("householdCount").asInt()).isEqualTo(10);
        assertThat(evidence.get(1).get("householdCount").asInt()).isEqualTo(20);
        assertThat(evidence.get(0).get("collectedAt").asText()).isEqualTo("2026-10-08T00:00:00Z");
    }

    @Test
    void 원천이_화면_조회_후_변경되면_검토_저장을_거부한다() throws Exception {
        long id = createComplex();
        var row = source(904, "행복주택", 10);
        links.saveAndFlush(com.toadzip.backend.ingest.mapping.domain.MyHomeComplexLink
                .connect("904:HAPPY_HOUSING", complexes.findById(id).orElseThrow()));
        var outdated = request(data(id), List.of("HOUSEHOLD_COUNT"), "VERIFIED");
        entityManager.createNativeQuery("UPDATE myhome_complex_source_rows SET hshld_co = 11 WHERE id = :id")
                .setParameter("id", row.getId()).executeUpdate();
        entityManager.clear();
        save(id, outdated).andExpect(status().isConflict());
        assertThat(data(id).get("history").isEmpty()).isTrue();
    }

    @Test
    void 확인한_항목과_근거_확인자를_저장하고_다른_항목_변경은_확인범위를_유지한다() throws Exception {
        long id = createComplex();
        JsonNode before = data(id);
        save(id, request(before, List.of("NAME", "HOUSEHOLD_COUNT"), "VERIFIED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.latestReview.fields.length()").value(2))
                .andExpect(jsonPath("$.data.latestReview.actor").value("reviewer"))
                .andExpect(jsonPath("$.data.latestReview.evidenceNote").value("공급기관 단지 안내 2쪽 확인"));
        updateColumn(id, "parking_space_count", "10");
        assertThat(data(id).get("status").asText()).isEqualTo("VERIFIED");
        updateColumn(id, "name", "'변경된 단지'");
        assertThat(data(id).get("status").asText()).isEqualTo("STALE");
        assertThat(data(id).get("latestReview").get("fields").size()).isEqualTo(2);
    }

    @Test
    void 이전_화면에서_중복_검토와_변경된_값을_확인하는_요청을_거부한다() throws Exception {
        long id = createComplex();
        var request = request(data(id), List.of("NAME"), "ON_HOLD");
        save(id, request).andExpect(status().isOk());
        save(id, request).andExpect(status().isConflict());
        var outdated = request(data(id), List.of("NAME"), "VERIFIED");
        updateColumn(id, "name", "'동시 수정'");
        save(id, outdated).andExpect(status().isConflict());
        assertThat(data(id).get("history").size()).isEqualTo(1);
    }

    @Test
    void 목록에서_미검토_확인완료_보류_재검토를_구분한다() throws Exception {
        long id = createComplex();
        search(id, "UNREVIEWED", 1);
        save(id, request(data(id), List.of("NAME"), "ON_HOLD")).andExpect(status().isOk());
        search(id, "ON_HOLD", 1);
        search(id, "UNREVIEWED", 0);
        save(id, request(data(id), List.of("NAME"), "VERIFIED")).andExpect(status().isOk());
        search(id, "VERIFIED", 1);
        updateColumn(id, "name", "'변경 단지'");
        search(id, "STALE", 1);
        search(id, "VERIFIED", 0);
    }

    @Test
    void 빈_확인범위와_근거_위험한_URL과_권한없는_요청을_거부한다() throws Exception {
        long id = createComplex();
        var emptyFields = request(data(id), List.of(), "VERIFIED");
        save(id, emptyFields).andExpect(status().isBadRequest());
        var unsafe = new java.util.LinkedHashMap<>(request(data(id), List.of("NAME"), "VERIFIED"));
        unsafe.put("evidenceUrl", "javascript:alert(1)");
        save(id, unsafe).andExpect(status().isBadRequest());
        unsafe.put("evidenceUrl", "");
        unsafe.put("evidenceNote", " ");
        save(id, unsafe).andExpect(status().isBadRequest());
        mvc.perform(get(path(id))).andExpect(status().isUnauthorized());
        User member = userRepository.saveAndFlush(
                User.create("google:verification-" + UUID.randomUUID(), LocalDateTime.now()));
        mvc.perform(post(path(id) + "/reviews").with(user(member.getId().toString()).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(emptyFields)))
                .andExpect(status().isForbidden());
    }

    private long createComplex() {
        return complexes.saveAndFlush(HousingComplex.createFromMyHome("검증 단지", "admin:" + java.util.UUID.randomUUID(),
                "HAPPY_HOUSING", Address.create("서울특별시 중구 세종대로 110", "1114010100100010000",
                        "1114010100", "11", "11140", new BigDecimal("37.566500"), new BigDecimal("126.978000")),
                0, "LH", null, null, null, null, null, 0)).getId();
    }

    private com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource source(
            long identifier, String rental, int households) {
        var row = com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSource.from(
                new com.toadzip.backend.ingest.collection.myhome.complex.domain.projection.MyHomeComplexSourceSnapshot(
                        identifier, "한국토지주택공사", "11", "서울", "11140", "중구", "검증 단지",
                        "서울특별시 중구 세종대로 110", "1114010100100010000", null, households, rental, "26A",
                        new BigDecimal("26"), null, null, null, null, null, 0, null, null, null));
        row.markCollectedAt(java.time.Instant.parse("2026-10-08T00:00:00Z"));
        return sources.save(row);
    }

    private JsonNode data(long id) throws Exception {
        String response = mvc.perform(get(path(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data");
    }

    private Map<String, Object> request(JsonNode value, List<String> fields, String outcome) {
        long reviewId = 0;
        if (!value.get("latestReview").isNull()) {
            reviewId = value.get("latestReview").get("id").asLong();
        }
        return Map.of("version", value.get("version").asLong(), "reviewId", reviewId,
                "snapshotToken", value.get("snapshotToken").asText(), "fields", fields, "outcome", outcome,
                "evidenceUrl", "https://example.com/complex", "evidenceNote", "공급기관 단지 안내 2쪽 확인");
    }

    private org.springframework.test.web.servlet.ResultActions save(long id, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post(path(id) + "/reviews").with(user("reviewer").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private void search(long id, String verification, int count) throws Exception {
        String identifier = complexes.findById(id).orElseThrow().getSourceComplexIdentifier();
        mvc.perform(get("/api/admin/housing-complexes").param("keyword", identifier)
                        .param("verification", verification).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(count));
    }

    private void updateColumn(long id, String column, String value) {
        entityManager.createNativeQuery("UPDATE housing_complexes SET " + column + " = " + value + " WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        entityManager.clear();
    }

    private String path(long id) {
        return "/api/admin/housing-complexes/" + id + "/verification";
    }
}
