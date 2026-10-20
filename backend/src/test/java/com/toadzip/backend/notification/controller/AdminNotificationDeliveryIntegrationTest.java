package com.toadzip.backend.notification.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.notification.NotificationFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminNotificationDeliveryIntegrationTest {
    private static final String ENDPOINT = "/api/admin/notification-deliveries";
    @Autowired private MockMvc mvc;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 회원과_상태로_발송을_찾고_실패원인을_표시하되_기기인증값은_노출하지_않는다() throws Exception {
        long notification = notification(45401);
        var device = new NotificationFixtures(entities, jdbc).device(45401);
        jdbc.update("""
                INSERT INTO notification_deliveries(notification_id, device_id, device_binding_version,
                    status, attempts, next_attempt_at, last_error_code, provider_message_id)
                VALUES (?, ?, 1, 'RETRY', 2, '2026-10-19T00:00:00Z', 'PROVIDER_UNAVAILABLE', 'private-message')
                """, notification, device);
        notification(45402);

        mvc.perform(get(ENDPOINT).with(user("operator").roles("ADMIN"))
                        .param("userId", "45401").param("status", "RETRY"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].attempts").value(2))
                .andExpect(jsonPath("$.data.items[0].errorCode").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.items[0].readAt").isEmpty())
                .andExpect(content().string(not(containsString(device.toString()))))
                .andExpect(content().string(not(containsString("private-message"))));
    }

    @Test
    void 발송기록이_없는_앱내_알림도_누락없이_조회하고_검색_와일드카드를_문자로_취급한다() throws Exception {
        long id = notification(45403);
        jdbc.update("UPDATE user_notifications SET title = '100% 모집 공고' WHERE id = ?", id);
        mvc.perform(get(ENDPOINT).with(user("operator").roles("ADMIN"))
                        .param("keyword", "%").param("status", "NO_DELIVERY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].deliveryStatus").value("NO_DELIVERY"));
    }

    @Test
    void 관리자가_아니면_발송내역을_조회할_수_없다() throws Exception {
        mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        new NotificationFixtures(entities, jdbc).user(45404);
        mvc.perform(get(ENDPOINT).with(user("45404").roles("USER"))).andExpect(status().isForbidden());
    }

    @Test
    void 알수없는_상태와_잘못된_회원번호를_거절한다() throws Exception {
        mvc.perform(get(ENDPOINT).with(user("operator").roles("ADMIN")).param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ENDPOINT).with(user("operator").roles("ADMIN")).param("userId", "0"))
                .andExpect(status().isBadRequest());
    }

    private long notification(long userId) {
        var fixtures = new NotificationFixtures(entities, jdbc);
        fixtures.user(userId);
        var announcement = fixtures.announcement();
        long event = fixtures.event(announcement);
        return jdbc.queryForObject("""
                INSERT INTO user_notifications(user_id, event_id, announcement_id, title, content, created_at, expires_at)
                VALUES (?, ?, ?, '관심 단지 새 공고', '새 공고 확인', '2026-10-13T01:00:00Z',
                    '2026-11-13T01:00:00Z') RETURNING id
                """, Long.class, userId, event, announcement.getId());
    }
}
