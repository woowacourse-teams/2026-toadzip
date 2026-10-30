package com.toadzip.backend.ingest.location.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAddressLookupIntegrationTest {

    @Autowired private MockMvc mvc;

    @Test
    void 주소_API_키가_없으면_관리자에게_사용할_수_없음을_알린다() throws Exception {
        mvc.perform(get("/api/admin/addresses").param("keyword", "부평동 65-17").with(user("admin").roles("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ADDRESS_LOOKUP_UNAVAILABLE"));
    }

    @Test
    void 관리자가_아니면_주소를_검색할_수_없고_빈_검색어는_거절한다() throws Exception {
        // 관리자 보안 설정은 일반 회원 인증을 관리자 세션으로 인정하지 않는다(401 또는 403).
        mvc.perform(get("/api/admin/addresses").param("keyword", "부평동").with(user("member").roles("USER")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mvc.perform(get("/api/admin/addresses").param("keyword", " ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ADDRESS_KEYWORD"));
    }
}
