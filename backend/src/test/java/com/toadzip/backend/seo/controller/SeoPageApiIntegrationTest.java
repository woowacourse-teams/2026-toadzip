package com.toadzip.backend.seo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 실제 조회·지역 목록·보안 설정으로 공개 GET 페이지가 열리는지 확인한다. */
@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SeoPageApiIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 단지_페이지와_지역_페이지와_사이트맵을_로그인_없이_받는다() throws Exception {
        HousingComplex complex = HousingComplex.create("세종대로 행복주택", "seo-integration", "행복주택",
                Address.create("서울특별시 중구 세종대로 110", "1114010100100010000", "1114010100", "11", "11140",
                        new BigDecimal("37.500000"), new BigDecimal("126.900000")),
                100, "LH", LocalDate.of(2020, 1, 1), "개별난방", "아파트", "계단식", true, 80, null, 7);
        entityManager.persist(complex);
        entityManager.flush();

        mockMvc.perform(get("/api/v1/seo/pages/complexes/" + complex.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>세종대로 행복주택 (서울 중구) | 공공주택 복덕방</title>")))
                .andExpect(content().string(containsString("<a href=\"/regions/11140\">")));
        mockMvc.perform(get("/api/v1/seo/pages/regions/11140/happy-housing"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<a href=\"/complexes/" + complex.getId() + "\">")));
        mockMvc.perform(get("/api/v1/seo/pages/complexes/" + Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("noindex")));
        mockMvc.perform(get("/api/v1/seo/sitemaps/complexes.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/complexes/" + complex.getId() + "</loc>")))
                .andExpect(content().string(not(containsString("/announcements/"))));
    }
}
