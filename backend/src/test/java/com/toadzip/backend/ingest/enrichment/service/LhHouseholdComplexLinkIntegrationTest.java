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
import com.toadzip.backend.ingest.enrichment.dto.LhHouseholdComplexComparison.View;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** 실제 로컬 데이터의 대전관저5: 공급유형·세대수·지역은 맞지만 LH와 마이홈 단지명이 달라 세대수 보강이 실패했다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LhHouseholdComplexLinkIntegrationTest {
    private static final String PATH = "/api/admin/ingest/lh/housing-type-households/failures/";
    private static final String ADDRESS = "대전광역시 서구 구봉산북로 212";
    private static final String PNU = "3017011600115990000";
    @Autowired private LhHousingTypeHouseholdEnrichmentService enrichment;
    @Autowired private LhLeaseCatalogSourceFixtures lhSources;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private HousingTypeRepository types;
    @Autowired private JdbcClient jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    private HousingComplex complex;

    @BeforeEach
    void setUp() {
        jdbc.sql("""
                TRUNCATE housing_complexes, lh_lease_catalog_source_bundles, lh_household_enrichment_failures,
                    lh_household_complex_links, admin_data_changes CASCADE
                """).update();
        complex = complex("대전관저5(05,국민,GB) 1단지", "complex-742");
        types.save(HousingType.createFromMyHome(complex, "complex-742:21", "21", new BigDecimal("21.9700"), null));
        types.save(HousingType.createFromMyHome(complex, "complex-742:26", "26", new BigDecimal("26.9000"), null));
        lhSources.saveAll(List.of(lh(0, "21.97", "60"), lh(1, "26.9", "180")));
    }

    @Test
    void 이름만_달라_실패하면_공급유형_세대수_지역이_맞는_유일한_후보를_비교로_보여준다() throws Exception {
        enrichment.enrichAll();

        var view = view();

        assertThat(pendingReasons()).containsExactly("COMPLEX_NOT_FOUND");
        assertThat(view.source().supplyTypeName()).isEqualTo("영구임대");
        assertThat(view.source().totalHouseholdCount()).isEqualTo(240);
        assertThat(view.source().housingTypes()).extracting(type -> type.householdCount()).containsExactly(60, 180);
        assertThat(view.linkable()).isTrue();
        assertThat(view.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.id()).isEqualTo(complex.getId());
            assertThat(candidate.name()).isEqualTo("대전관저5(05,국민,GB) 1단지");
            assertThat(candidate.exclusiveAreas()).containsExactly(new BigDecimal("21.9700"), new BigDecimal("26.9000"));
        });
    }

    @Test
    void 관리자가_확인해_연결하면_다음_세대수_보강에서_주택형_세대수를_채운다() throws Exception {
        enrichment.enrichAll();

        save(view(), "도로명 주소와 영구임대 240세대, 전용 21.97·26.9 대조").andExpect(status().isOk());
        enrichment.enrichAll();

        assertThat(types.findAllByHousingComplex(complex)).extracting(HousingType::getTotalHouseholdCount)
                .containsExactlyInAnyOrder(60, 180);
        assertThat(pendingReasons()).isEmpty();
        assertThat(jdbc.sql("SELECT actor FROM admin_data_changes WHERE action = 'SAVE_LH_HOUSEHOLD_LINK'")
                .query(String.class).list()).containsExactly("operator");
    }

    @Test
    void 연결한_뒤_단지_세대수가_바뀌면_연결을_쓰지_않고_재확인을_요구한다() throws Exception {
        enrichment.enrichAll();
        save(view(), "대조 완료").andExpect(status().isOk());
        jdbc.sql("UPDATE housing_complexes SET total_household_count = 241").update();

        enrichment.enrichAll();

        assertThat(pendingReasons()).containsExactly("MANUAL_LINK_REVIEW_REQUIRED");
        assertThat(view().savedLink().reviewRequired()).isTrue();
        assertThat(types.findAllByHousingComplex(complex)).extracting(HousingType::getTotalHouseholdCount)
                .containsOnlyNulls();
    }

    @Test
    void 후보가_둘_이상이면_연결을_저장하지_않는다() throws Exception {
        complex("대전관저5 다른단지", "complex-999");
        enrichment.enrichAll();
        var view = view();

        save(view, "대조").andExpect(status().isConflict());

        assertThat(view.linkable()).isFalse();
        assertThat(view.candidates()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM lh_household_complex_links").query(Long.class).single()).isZero();
    }

    @Test
    void 연결을_이유와_함께_해제하면_다음_보강부터_다시_이름으로_매칭한다() throws Exception {
        enrichment.enrichAll();
        var saved = read(save(view(), "대조 완료").andExpect(status().isOk()));

        mvc.perform(post(PATH + failureId() + "/complex-link/release").with(user("operator").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "token", saved.token(), "reason", "다른 단지로 잘못 연결함")))).andExpect(status().isOk());
        enrichment.enrichAll();

        assertThat(jdbc.sql("SELECT count(*) FROM lh_household_complex_links").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT after_value FROM admin_data_changes WHERE action = 'RELEASE_LH_HOUSEHOLD_LINK'")
                .query(String.class).single()).contains("다른 단지로 잘못 연결함");
        assertThat(pendingReasons()).containsExactly("COMPLEX_NOT_FOUND");
    }

    @Test
    void 조회_후_후보나_원천이_바뀌면_저장을_거절하고_근거_이유는_필수다() throws Exception {
        enrichment.enrichAll();
        var view = view();
        save(view, " ").andExpect(status().isBadRequest());
        jdbc.sql("UPDATE housing_complexes SET road_address = '대전광역시 서구 구봉산북로 213'").update();

        save(view, "대조").andExpect(status().isConflict());
    }

    @Test
    void 비교와_저장은_관리자와_CSRF를_요구한다() throws Exception {
        enrichment.enrichAll();
        String memberId = jdbc.sql("""
                        INSERT INTO users(login_identifier,created_at) VALUES (:identifier,CURRENT_TIMESTAMP)
                        RETURNING id
                        """).param("identifier", "google:household-" + java.util.UUID.randomUUID())
                .query(Long.class).single().toString();

        mvc.perform(get(PATH + failureId() + "/complex-link")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH + failureId() + "/complex-link").with(user(memberId).roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH + failureId() + "/complex-link").with(user("operator").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    private ResultActions save(View view, String reason) throws Exception {
        return mvc.perform(post(PATH + view.failureId() + "/complex-link").with(user("operator").roles("ADMIN"))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "complexId", complex.getId(), "token", view.token(), "reason", reason))));
    }

    private View view() throws Exception {
        return read(mvc.perform(get(PATH + failureId() + "/complex-link").with(user("operator").roles("ADMIN")))
                .andExpect(status().isOk()));
    }

    private View read(ResultActions result) throws Exception {
        return json.readValue(result.andReturn().getResponse().getContentAsString(), View.class);
    }

    private long failureId() {
        return jdbc.sql("SELECT id FROM lh_household_enrichment_failures ORDER BY id LIMIT 1")
                .query(Long.class).single();
    }

    private List<String> pendingReasons() {
        return jdbc.sql("SELECT reason FROM lh_household_enrichment_failures WHERE status = 'PENDING'")
                .query(String.class).list();
    }

    private HousingComplex complex(String name, String identifier) {
        return complexes.save(HousingComplex.createFromMyHome(name, identifier, "PERMANENT_RENTAL",
                Address.create(ADDRESS, PNU, PNU.substring(0, 10), "30", "30170",
                        new BigDecimal("36.3"), new BigDecimal("127.3")),
                240, "LH", null, "INDIVIDUAL", "APARTMENT", "CORRIDOR", true, 100));
    }

    private LhCatalogSource lh(int order, String area, String count) {
        var source = new LhCatalogSource(order, new LhCatalogSourceSnapshot("대전광역시 서구", "영구임대",
                "대전관저5 천년나무 1단지", "240", area, count, null, null));
        source.markCollectedAt(Instant.parse("2026-10-24T00:00:00Z"));
        return source;
    }
}
