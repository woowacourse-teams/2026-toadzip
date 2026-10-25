package com.toadzip.backend.ingest.enrichment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.domain.HousingType;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import com.toadzip.backend.housing.repository.HousingTypeRepository;
import com.toadzip.backend.ingest.collection.fixture.repository.LhLeaseCatalogSourceFixtures;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.LhCatalogSource;
import com.toadzip.backend.ingest.collection.lh.leasecatalog.domain.projection.LhCatalogSourceSnapshot;
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdTargetDecisionView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** 실제 로컬 데이터의 남해평리: LH 목록에 같은 단지가 '남해평리'와 '남해평리(국민)' 두 묶음으로 들어 있다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LhHouseholdTargetDecisionIntegrationTest {
    private static final String PATH = "/api/admin/ingest/lh/housing-type-households/failures/";
    private static final String PNU = "4884025021100010000";
    private static final List<String> AREAS = List.of("33.61", "33.72", "46.8", "51.93");
    private static final List<String> COUNTS = List.of("116", "90", "57", "90");
    @Autowired private LhHousingTypeHouseholdEnrichmentService enrichment;
    @Autowired private LhLeaseCatalogSourceFixtures lhSources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private JdbcClient jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    private HousingComplex complex;
    private int order;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, lh_lease_catalog_source_bundles, lh_household_enrichment_failures,
                    lh_household_complex_links, lh_household_target_decisions, admin_data_changes CASCADE
                """).update();
        order = 0;
        complex = complexes.save(HousingComplex.createFromMyHome("남해평리 휴먼시아", "complex-2918", "NATIONAL_RENTAL",
                Address.create("경상남도 남해군 남해읍 평리로 30", PNU, PNU.substring(0, 10), "48", "48840",
                        new BigDecimal("34.8"), new BigDecimal("127.9")),
                353, "LH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 100));
        for (String area : AREAS) {
            types.save(HousingType.createFromMyHome(complex, "complex-2918:" + area, area.split("\\.")[0],
                    new BigDecimal(area), null));
        }
    }

    @Test
    void 같은_단지에_매칭된_묶음의_세대수가_모두_같으면_실패없이_한번만_반영한다() {
        group("남해평리", COUNTS);
        group("남해평리(국민)", COUNTS);

        enrichment.enrichAll();

        assertThat(pendingReasons()).isEmpty();
        assertThat(householdCounts()).containsExactly(116, 90, 57, 90);
    }

    @Test
    void 세대수가_다르면_두_묶음과_단지_주택형을_나란히_보여주고_고른_묶음을_다음_보강부터_쓴다() throws Exception {
        group("남해평리", COUNTS);
        group("남해평리(국민)", List.of("100", "90", "57", "106"));
        enrichment.enrichAll();
        assertThat(pendingReasons()).containsExactly("DUPLICATE_TARGET_COMPLEX", "DUPLICATE_TARGET_COMPLEX");

        var view = view();
        assertThat(view.decidable()).isTrue();
        assertThat(view.complex().housingTypes()).extracting(type -> type.exclusiveArea().stripTrailingZeros()
                .toPlainString()).containsExactly("33.61", "33.72", "46.8", "51.93");
        assertThat(view.groups()).extracting(LhHouseholdTargetDecisionView.SourceGroup::complexName)
                .containsExactlyInAnyOrder("남해평리", "남해평리(국민)");
        String chosen = view.groups().stream().filter(group -> group.complexName().equals("남해평리")).findFirst()
                .orElseThrow().sourceKey();
        save(view, "USE_SOURCE", chosen, "공고문 주택형별 세대수와 일치하는 묶음").andExpect(status().isOk());
        enrichment.enrichAll();

        assertThat(pendingReasons()).isEmpty();
        assertThat(householdCounts()).containsExactly(116, 90, 57, 90);
        assertThat(jdbc.sql("SELECT actor FROM admin_data_changes WHERE action = 'SAVE_LH_HOUSEHOLD_DECISION'")
                .query(String.class).list()).containsExactly("operator");
    }

    @Test
    void 보강하지_않음으로_정하면_세대수를_쓰지_않고_실패도_해결한다() throws Exception {
        group("남해평리", COUNTS);
        group("남해평리(국민)", List.of("100", "90", "57", "106"));
        enrichment.enrichAll();

        save(view(), "SKIP", null, "어느 묶음이 맞는지 공식 자료로 확인할 수 없음").andExpect(status().isOk());
        enrichment.enrichAll();

        assertThat(pendingReasons()).isEmpty();
        assertThat(householdCounts()).containsOnlyNulls();
    }

    @Test
    void 결정_이후_경쟁_묶음의_세대수가_바뀌면_결정을_쓰지_않고_재확인을_요구한다() throws Exception {
        group("남해평리", COUNTS);
        group("남해평리(국민)", List.of("100", "90", "57", "106"));
        enrichment.enrichAll();
        var view = view();
        save(view, "USE_SOURCE", view.groups().getFirst().sourceKey(), "공고문 대조").andExpect(status().isOk());
        jdbc.sql("UPDATE lh_lease_catalog_source_rows SET total_unit_count = '105' WHERE total_unit_count = '106'")
                .update();

        enrichment.enrichAll();

        assertThat(pendingReasons()).containsExactly("TARGET_DECISION_REVIEW_REQUIRED", "TARGET_DECISION_REVIEW_REQUIRED");
        assertThat(view().decision().reviewRequired()).isTrue();
        assertThat(householdCounts()).containsOnlyNulls();
    }

    @Test
    void 결정을_이유와_함께_해제하면_다음_보강부터_다시_중복_실패로_남긴다() throws Exception {
        group("남해평리", COUNTS);
        group("남해평리(국민)", List.of("100", "90", "57", "106"));
        enrichment.enrichAll();
        var saved = read(save(view(), "SKIP", null, "확인 불가").andExpect(status().isOk()));

        mvc.perform(post(PATH + failureId() + "/target-decision/release").with(user("operator").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "token", saved.token(), "reason", "공식 자료를 찾아 다시 판단")))).andExpect(status().isOk());
        enrichment.enrichAll();

        assertThat(jdbc.sql("SELECT count(*) FROM lh_household_target_decisions").query(Long.class).single()).isZero();
        assertThat(pendingReasons()).containsExactly("DUPLICATE_TARGET_COMPLEX", "DUPLICATE_TARGET_COMPLEX");
    }

    @Test
    void 경쟁_묶음에_없는_묶음이나_조회_후_바뀐_상태로는_저장하지_않는다() throws Exception {
        group("남해평리", COUNTS);
        group("남해평리(국민)", List.of("100", "90", "57", "106"));
        enrichment.enrichAll();
        var view = view();

        save(view, "USE_SOURCE", "다른 묶음", "대조").andExpect(status().isBadRequest());
        jdbc.sql("UPDATE lh_lease_catalog_source_rows SET total_unit_count = '105' WHERE total_unit_count = '106'")
                .update();
        save(view, "SKIP", null, "대조").andExpect(status().isConflict());
        assertThat(jdbc.sql("SELECT count(*) FROM lh_household_target_decisions").query(Long.class).single()).isZero();
    }

    @Test
    void 결정_조회와_저장은_관리자와_CSRF를_요구한다() throws Exception {
        group("남해평리", COUNTS);
        group("남해평리(국민)", List.of("100", "90", "57", "106"));
        enrichment.enrichAll();

        mvc.perform(get(PATH + failureId() + "/target-decision")).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH + failureId() + "/target-decision").with(user("operator").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    private void group(String label, List<String> counts) {
        List<LhCatalogSource> rows = new ArrayList<>();
        for (int index = 0; index < AREAS.size(); index++) {
            var source = new LhCatalogSource(order++, new LhCatalogSourceSnapshot("경상남도 남해군", "국민임대", label,
                    "353", AREAS.get(index), counts.get(index), null, null));
            source.markCollectedAt(Instant.parse("2026-10-24T00:00:00Z"));
            rows.add(source);
        }
        lhSources.saveAll(rows);
    }

    private ResultActions save(LhHouseholdTargetDecisionView view, String decision, String chosen, String reason)
            throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("decision", decision, "token", view.token(), "reason", reason));
        if (chosen != null) {
            body.put("chosenSourceKey", chosen);
        }
        return mvc.perform(post(PATH + view.failureId() + "/target-decision").with(user("operator").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private LhHouseholdTargetDecisionView view() throws Exception {
        return read(mvc.perform(get(PATH + failureId() + "/target-decision").with(user("operator").roles("ADMIN")))
                .andExpect(status().isOk()));
    }

    private LhHouseholdTargetDecisionView read(ResultActions result) throws Exception {
        return json.readValue(result.andReturn().getResponse().getContentAsString(), LhHouseholdTargetDecisionView.class);
    }

    private long failureId() {
        return jdbc.sql("SELECT id FROM lh_household_enrichment_failures ORDER BY id LIMIT 1").query(Long.class).single();
    }

    private List<String> pendingReasons() {
        return jdbc.sql("SELECT reason FROM lh_household_enrichment_failures WHERE status = 'PENDING' ORDER BY id")
                .query(String.class).list();
    }

    private List<Integer> householdCounts() {
        return types.findAllByHousingComplex(complex).stream()
                .sorted(java.util.Comparator.comparing(HousingType::getExclusiveArea))
                .map(HousingType::getTotalHouseholdCount).toList();
    }
}
