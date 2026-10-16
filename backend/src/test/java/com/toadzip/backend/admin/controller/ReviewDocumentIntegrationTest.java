package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/db/migration/V20261016_02__admin_review_document_archive.sql")
class ReviewDocumentIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private ObjectMapper json;

    @Test
    void PDF_원본을_해시로_중복_없이_보관하고_관리자만_대상별로_다운로드한다() throws Exception {
        long id = complex();
        byte[] bytes = "%PDF-1.7\n원본 파일\n%%EOF".getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "공식 공고.pdf", "application/pdf", bytes);
        String response = mvc.perform(multipart(root(id)).file(file).param("sourceUrl", "https://example.com/notice")
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sha = json.readTree(response).at("/data/sha256").asText();
        mvc.perform(multipart(root(id)).file(file).param("sourceUrl", "https://example.com/notice")
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        String list = mvc.perform(get(root(id)).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(list).get("data").size()).isEqualTo(1);
        var downloaded = mvc.perform(get(root(id) + "/" + sha).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(downloaded.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(downloaded.getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(downloaded.getHeader("Cache-Control")).isEqualTo("no-store");
        mvc.perform(get(root(id) + "/" + sha)).andExpect(status().isUnauthorized());
        mvc.perform(get(root(complex()) + "/" + sha).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void CSRF와_PDF_헤더와_공식_출처_URL을_검증한다() throws Exception {
        long id = complex();
        var fake = new MockMultipartFile("file", "위장.pdf", "application/pdf", "not-pdf".getBytes());
        mvc.perform(multipart(root(id)).file(fake).param("sourceUrl", "https://example.com")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(multipart(root(id)).file(fake).param("sourceUrl", "https://example.com")
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        var pdf = new MockMultipartFile("file", "원본.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes());
        mvc.perform(multipart(root(id)).file(pdf).param("sourceUrl", "https://user:password@example.com")
                .with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
    }

    private String root(long id) {
        return "/api/admin/quality/COMPLEX/" + id + "/documents";
    }

    private long complex() {
        return complexes.saveAndFlush(HousingComplex.createFromMyHome("원본 보관", "manual:" + UUID.randomUUID(),
                "HAPPY_HOUSING", Address.create("서울특별시 중구 세종대로 110", "1114010300100010000",
                        "1114010300", "11", "11140", new BigDecimal("37.5665"), new BigDecimal("126.978")),
                120, "LH", null, null, null, null, null, 0)).getId();
    }
}
