package com.toadzip.backend.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminUserManagementControllerIntegrationTest {

    private static final String ENDPOINT = "/api/admin/users";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;

    @Test
    void 관리자는_회원의_최소_정보만_가입_최신순으로_조회한다() throws Exception {
        long id = member("google:subject-secret", "member@example.test", "2026-10-03T09:00:00");

        String response = mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(id))
                .andExpect(jsonPath("$.data.items[0].email").value("member@example.test"))
                .andExpect(jsonPath("$.data.items[0].provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-03T09:00:00"))
                .andExpect(jsonPath("$.data.items[0].loginIdentifier").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].eligibilityInfo").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].places").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("subject-secret", "loginIdentifier");
    }

    @Test
    void 이메일_검색은_앞뒤_공백과_대소문자를_무시한다() throws Exception {
        long id = member("kakao:1", "MixedCase@Example.test", "2026-10-03T09:00:00");
        member("google:2", "other@example.test", "2026-10-03T10:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("keyword", "  mixedcase@EXAMPLE  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id));
    }

    @Test
    void 숫자_검색은_회원_ID와_정확히_일치해야_한다() throws Exception {
        long id = member("google:one", "one@example.test", "2026-10-03T09:00:00");
        member("kakao:two", "two@example.test", "2026-10-03T09:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("keyword", Long.toString(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id));
    }

    @Test
    void Long_범위를_넘는_숫자_검색도_잘못된_서버_오류로_처리하지_않는다() throws Exception {
        member("google:one", "one@example.test", "2026-10-03T09:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("keyword", "9223372036854775808"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!"})
    void 이메일_검색의_SQL_와일드카드와_이스케이프_문자는_문자로_검색한다(String keyword) throws Exception {
        long id = member("google:literal", "literal" + keyword + "value@example.test", "2026-10-03T09:00:00");
        member("google:other", "literalXvalue@example.test", "2026-10-03T09:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(id));
    }

    @Test
    void 로그인_공급자는_정확한_접두사로_구분하고_검색한다() throws Exception {
        member("google:1", "one@example.test", "2026-10-03T09:00:00");
        member("kakao:2", "two@example.test", "2026-10-03T09:00:00");
        member("google-like:3", "three@example.test", "2026-10-03T09:00:00");
        member("GOOGLE:4", "four@example.test", "2026-10-03T09:00:00");
        member("legacy", "five@example.test", "2026-10-03T09:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("provider", "GOOGLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].provider").value("GOOGLE"));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("provider", "KAKAO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].provider").value("KAKAO"));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("provider", "UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[0].provider").value("UNKNOWN"));
    }

    @Test
    void 빈_공급자_필터와_공백_검색은_전체_회원을_조회한다() throws Exception {
        member("google:1", "one@example.test", "2026-10-03T09:00:00");
        member("kakao:2", "two@example.test", "2026-10-03T09:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("provider", "").param("keyword", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void 같은_이메일의_다른_공급자_계정은_서로_다른_회원으로_조회한다() throws Exception {
        member("google:one", "same@example.test", "2026-10-03T09:00:00");
        member("kakao:two", "same@example.test", "2026-10-03T09:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("keyword", "same@example.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void 같은_가입_시각에도_ID_최신순으로_안정된_페이지를_조회한다() throws Exception {
        long first = member("google:first", "one@example.test", "2026-10-03T09:00:00");
        long second = member("google:second", "two@example.test", "2026-10-03T09:00:00");
        long newest = member("kakao:newest", "three@example.test", "2026-10-03T10:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(newest))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(second));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(first))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 목록_범위를_벗어난_페이지에도_검색한_전체_건수를_보존한다() throws Exception {
        member("google:one", "one@example.test", "2026-10-03T09:00:00");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("page", "3").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(3))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 검색된_회원이_없으면_빈_목록과_0개의_페이지를_반환한다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 이메일이_없는_회원도_상세를_조회할_수_있다() throws Exception {
        long id = member("kakao:subject-secret", null, "2026-10-03T09:00:00");

        String response = mockMvc.perform(get(ENDPOINT + "/" + id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.email").isEmpty())
                .andExpect(jsonPath("$.data.provider").value("KAKAO"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-03T09:00:00"))
                .andExpect(jsonPath("$.data.loginIdentifier").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("subject-secret");
    }

    @Test
    void 존재하지_않는_회원의_상세는_404로_응답한다() throws Exception {
        mockMvc.perform(get(ENDPOINT + "/9223372036854775807").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101", "provider,google", "provider,OTHER"})
    void 잘못된_검색_조건은_검증_오류로_응답한다(String field, String value) throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param(field, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 검색어는_최대_200자이다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("keyword", "a".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/1"})
    void 비로그인_요청은_회원_목록과_상세를_조회할_수_없다(String suffix) throws Exception {
        mockMvc.perform(get(ENDPOINT + suffix)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/1"})
    void 일반_회원은_관리자의_회원_목록과_상세를_조회할_수_없다(String suffix) throws Exception {
        mockMvc.perform(get(ENDPOINT + suffix).with(user("member").roles("USER")))
                .andExpect(status().isForbidden());
    }

    private long member(String identifier, String email, String createdAt) {
        return jdbc.sql("""
                INSERT INTO users (login_identifier, email, created_at)
                VALUES (:identifier, :email, :createdAt::timestamp) RETURNING id
                """).param("identifier", identifier).param("email", email).param("createdAt", createdAt)
                .query(Long.class).single();
    }
}
