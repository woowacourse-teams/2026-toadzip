package com.toadzip.backend.notification.controller;

import static com.toadzip.backend.notification.NotificationFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.notification.NotificationFixtures;
import com.toadzip.backend.notification.repository.UserNotificationRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationApiIntegrationTest {
    private static final String ENDPOINT = "/api/v1/notifications";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private UserNotificationRepository notifications;
    @Autowired private ObjectMapper json;
    private NotificationFixtures fixtures;
    private long first;

    @BeforeEach
    void seed() {
        fixtures = new NotificationFixtures(entities, jdbc);
        fixtures.user(101);
        fixtures.user(102);
        first = notification(101);
    }

    @Test
    void 회원인증과_CSRF를_검사한다() throws Exception {
        mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ENDPOINT).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post(ENDPOINT + "/" + first + "/read").with(user("101").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 본인_알림만_조회하고_조회만으로는_읽지_않는다() throws Exception {
        notification(102);
        mvc.perform(get(ENDPOINT).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(first)))
                .andExpect(jsonPath("$.items[0].readAt").isEmpty())
                .andExpect(jsonPath("$.readThrough").value(Long.toString(first)));
        mvc.perform(get(ENDPOINT + "/unread-count").with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void 타인_읽음은_거절하고_본인_읽음은_멱등이다() throws Exception {
        mvc.perform(post(ENDPOINT + "/" + first + "/read").with(user("102").roles("USER")).with(csrf()))
                .andExpect(status().isNotFound());
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(ENDPOINT + "/" + first + "/read").with(user("101").roles("USER")).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get(ENDPOINT + "/unread-count").with(user("101").roles("USER")))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void 모두읽음은_조회뒤_새로_생긴_알림을_읽지_않는다() throws Exception {
        String body = mvc.perform(get(ENDPOINT).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String readThrough = json.readTree(body).get("readThrough").asText();
        long latest = notification(101);
        mvc.perform(post(ENDPOINT + "/read-all").with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"readThrough\":\"" + readThrough + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM user_notifications WHERE id=?", Boolean.class, latest))
                .isTrue();
        mvc.perform(get(ENDPOINT + "/unread-count").with(user("101").roles("USER")))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void 커서로_다음페이지를_조회하고_잘못된_요청은_거절한다() throws Exception {
        long second = notification(101);
        String firstPage = mvc.perform(get(ENDPOINT).param("size", "1").with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(Long.toString(second)))
                .andReturn().getResponse().getContentAsString();
        String cursor = json.readTree(firstPage).get("nextCursor").asText();
        mvc.perform(get(ENDPOINT).param("cursor", cursor).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(Long.toString(first)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        mvc.perform(get(ENDPOINT).param("cursor", "invalid").with(user("101").roles("USER")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ENDPOINT).param("size", "-1").with(user("101").roles("USER")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ENDPOINT + "/read-all").with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"readThrough\":\"9223372036854775807\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 만료항목은_제외하고_삭제된_공고는_제목을_보존한다() throws Exception {
        jdbc.update("UPDATE announcements SET admin_deleted=true WHERE id="
                + "(SELECT announcement_id FROM user_notifications WHERE id=?)", first);
        mvc.perform(get(ENDPOINT).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].targetAvailable").value(false))
                .andExpect(jsonPath("$.items[0].title").value("관심 단지 새 공고"));
        jdbc.update("UPDATE user_notifications SET expires_at=? WHERE id=?", Timestamp.from(Instant.EPOCH), first);
        mvc.perform(get(ENDPOINT).with(user("101").roles("USER")))
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.readThrough").value("0"));
    }

    private long notification(long userId) {
        var announcement = fixtures.announcement();
        fixtures.link(announcement, fixtures.complex("11140", "1114010100"));
        long eventId = fixtures.event(announcement);
        return notifications.insertIfAbsent(userId, eventId, announcement.getId(), announcement.getName(), NOW).id();
    }
}
