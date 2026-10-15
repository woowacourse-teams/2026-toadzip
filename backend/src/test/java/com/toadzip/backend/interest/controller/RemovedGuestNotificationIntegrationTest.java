package com.toadzip.backend.interest.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RemovedGuestNotificationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;

    @Test
    void 비회원_설정_조회_경로는_제거된다() throws Exception {
        long member = jdbc.sql("""
                INSERT INTO users(login_identifier,created_at) VALUES ('removed-guest-route',now()) RETURNING id
                """).query(Long.class).single();
        mockMvc.perform(get("/api/v1/notification-subscriptions/guest")
                        .header("X-Notification-Client-Id", "00000000-0000-4000-8000-000000000001")
                        .with(user(Long.toString(member)).roles("USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 비회원_취소_접수_경로는_제거된다() throws Exception {
        mockMvc.perform(post("/api/v1/notification-guest-cancellations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"guest@example.test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 관리자_비회원_취소_요청_경로는_제거된다() throws Exception {
        mockMvc.perform(get("/api/admin/notification-guest-cancellations")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }
}
