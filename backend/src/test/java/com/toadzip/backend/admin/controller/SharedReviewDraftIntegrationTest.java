package com.toadzip.backend.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.admin.quality.domain.QualityResource;
import com.toadzip.backend.admin.quality.service.ReviewDocumentService;
import com.toadzip.backend.housing.domain.Address;
import com.toadzip.backend.housing.domain.HousingComplex;
import com.toadzip.backend.housing.repository.HousingComplexRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql({"/db/migration/V20261016_02__admin_review_document_archive.sql",
        "/db/migration/V20261016_03__shared_review_drafts.sql"})
class SharedReviewDraftIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private HousingComplexRepository complexes;
    @Autowired private ReviewDocumentService documents;
    @Autowired private ObjectMapper json;

    @Test
    void 초안을_공유하고_다른_관리자가_수정하면_이전_revision의_덮어쓰기를_거부한다() throws Exception {
        long id = complex();
        String sha = archive(id);
        var body = body(sha, 0, "최초 메모");
        mvc.perform(put(root(id)).with(user("first").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
        String saved = mvc.perform(get(root(id)).param("target", "basic").with(user("second").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(saved).at("/data/revision").asLong()).isEqualTo(1);
        mvc.perform(put(root(id)).with(user("second").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(sha, 1, "다음 메모"))))
                .andExpect(status().isOk());
        mvc.perform(put(root(id)).with(user("first").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(sha, 1, "오래된 메모"))))
                .andExpect(status().isConflict());
        String latest = mvc.perform(get(root(id)).param("target", "basic").with(user("first").roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(latest).at("/data/note").asText()).isEqualTo("다음 메모");
        assertThat(json.readTree(latest).at("/data/actor").asText()).isEqualTo("second");
    }

    @Test
    void 관리자_CSRF와_소속_대상_원본을_검증한다() throws Exception {
        long id = complex();
        String content = json.writeValueAsString(body(archive(id), 0, "메모"));
        mvc.perform(get(root(id))).andExpect(status().isUnauthorized());
        mvc.perform(put(root(id)).with(user("viewer").roles("VIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
        mvc.perform(put(root(id)).with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
        mvc.perform(put(root(complex())).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isBadRequest());
    }

    @Test
    void 없는_revision_생성_및_다른_대상의_수정안_JSON을_거부한다() throws Exception {
        long id = complex();
        String sha = archive(id);
        mvc.perform(put(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(sha, 7, "메모"))))
                .andExpect(status().isConflict());
        var invalid = new java.util.LinkedHashMap<>(body(sha, 0, "메모"));
        invalid.put("resultJson", "{\"schemaVersion\":\"document-review/v1\",\"targetKey\":\"complexes:999:basic\"}");
        mvc.perform(put(root(id)).with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    private Map<String, Object> body(String sha, long revision, String note) {
        return Map.of("target", "basic", "expectedRevision", revision, "documentVersion", 0,
                "sha256", sha, "sourceUrl", "https://example.com/official", "pages", "1", "resultJson", "", "note", note);
    }

    private String archive(long id) {
        return documents.archive(QualityResource.COMPLEX, id, "공식.pdf", "https://example.com/official",
                "%PDF-1.7\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8), "admin");
    }

    private String root(long id) {
        return "/api/admin/quality/COMPLEX/" + id + "/draft";
    }

    private long complex() {
        return complexes.saveAndFlush(HousingComplex.createFromMyHome("공유 초안", "manual:" + UUID.randomUUID(),
                "HAPPY_HOUSING", Address.create("서울 중구 세종대로 110", "1114010300100010000",
                        "1114010300", "11", "11140", new BigDecimal("37.5665"), new BigDecimal("126.978")),
                120, "LH", null, null, null, null, null, 0)).getId();
    }
}
