package com.toadzip.backend.feedback.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.user.domain.User;
import com.toadzip.backend.user.repository.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class AdminFeedbackControllerIntegrationTest {

    @Autowired private UserRepository users;

    private static final String ENDPOINT = "/api/admin/feedback";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;

    @Test
    void 관리자는_의견_전체_내용과_UTC_접수시각을_최신순으로_확인한다() throws Exception {
        feedback("어제 의견", "2026-10-06T10:00:00Z");
        long second = feedback("같은 시각 먼저 작성", "2026-10-07T10:00:00Z");
        long latest = feedback("최신 의견\n두 번째 줄", "2026-10-07T10:00:00Z");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(latest))
                .andExpect(jsonPath("$.data.items[0].content").value("최신 의견\n두 번째 줄"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(second));
    }

    @Test
    void 의견_본문을_검색하고_와일드카드를_문자로_취급한다() throws Exception {
        feedback("검색에서 100%_! 표시가 안 돼요", "2026-10-07T10:00:00Z");
        feedback("100회 검색", "2026-10-07T10:00:00Z");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("keyword", "  %_!  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].content").value("검색에서 100%_! 표시가 안 돼요"));
    }

    @Test
    void 검색은_대소문자를_구분하지_않는다() throws Exception {
        feedback("CS 개선 의견", "2026-10-07T10:00:00Z");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("keyword", "cs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 없는_검색결과는_빈_목록을_반환한다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void 범위_밖_페이지에도_검색된_건수를_유지한다() throws Exception {
        feedback("개선 요청", "2026-10-07T10:00:00Z");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("page", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 매우_큰_페이지도_검색된_건수를_유지하며_빈_목록을_반환한다() throws Exception {
        feedback("검색 개선 요청", "2026-10-07T10:00:00Z");
        feedback("기타 의견", "2026-10-07T10:00:00Z");

        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))
                        .param("page", "2147483647").param("size", "100").param("keyword", "검색"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(2147483647))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 비로그인_사용자는_의견을_조회하지_못한다() throws Exception {
        mockMvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
    }

    @Test
    void 일반_회원은_의견을_조회하지_못한다() throws Exception {
        long memberId = users.saveAndFlush(User.create(
                "authorization-test:" + java.util.UUID.randomUUID(), LocalDateTime.now())).getId();
        mockMvc.perform(get(ENDPOINT).with(user(Long.toString(memberId)).roles("USER"))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101", "page,abc"})
    void 잘못된_페이지_조건을_거절한다(String name, String value) throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param(name, value))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 검색어_길이를_제한한다() throws Exception {
        mockMvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN")).param("keyword", "가".repeat(201)))
                .andExpect(status().isBadRequest());
    }

    private long feedback(String content, String createdAt) {
        return jdbc.sql("""
                INSERT INTO feedback (content, created_at) VALUES (:content, :createdAt::timestamptz) RETURNING id
                """).param("content", content).param("createdAt", createdAt).query(Long.class).single();
    }
}
