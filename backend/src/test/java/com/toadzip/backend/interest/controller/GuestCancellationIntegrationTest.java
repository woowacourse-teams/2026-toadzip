package com.toadzip.backend.interest.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
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
class GuestCancellationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 이메일_확인_후_같은_주소의_비로그인_신청만_모두_취소한다() throws Exception {
        String email = "cancel@example.com";
        UUID first = subscribeGuest(email);
        UUID second = subscribeGuest(email);
        long memberId = 90000235L;
        jdbcTemplate.update("INSERT INTO users (id, login_identifier, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                memberId, "notification-cancel-member");
        jdbcTemplate.update("""
                INSERT INTO notification_email_preferences (user_id, email, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                """, memberId, email);

        mockMvc.perform(post("/api/v1/notification-guest-cancellations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"CANCEL@example.com\"}"))
                .andExpect(status().isAccepted());
        UUID requestId = jdbcTemplate.queryForObject(
                "SELECT id FROM notification_guest_cancellation_requests WHERE email = ?", UUID.class, email);
        mockMvc.perform(get("/api/admin/notification-guest-cancellations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/notification-guest-cancellations")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value(email));
        String issueBody = mockMvc.perform(post("/api/admin/notification-guest-cancellations/" + requestId + "/code")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(issueBody).get("code").asText();
        mockMvc.perform(post("/api/v1/notification-guest-cancellations/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cancel@example.com\",\"code\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM notification_guest_cancellation_requests WHERE id = ?",
                Integer.class, requestId));

        mockMvc.perform(post("/api/v1/notification-guest-cancellations/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cancel@example.com\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isNoContent());
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification_guest_email_preferences WHERE client_id IN (?, ?)
                """, Integer.class, first, second));
        assertEquals(0, jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notification_guest_subscriptions WHERE client_id IN (?, ?)
                """, Integer.class, first, second));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_email_preferences WHERE user_id = ?", Integer.class, memberId));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_guest_cancellation_requests WHERE id = ?",
                Integer.class, requestId));
        mockMvc.perform(post("/api/v1/notification-guest-cancellations/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cancel@example.com\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 신청하지_않은_주소도_같은_응답을_주며_요청은_남기지_않는다() throws Exception {
        String email = "unknown@example.com";
        mockMvc.perform(post("/api/v1/notification-guest-cancellations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_guest_cancellation_requests WHERE email = ?",
                Integer.class, email));
    }

    @Test
    void 관리자는_코드를_발급한_뒤에만_수동_발송을_기록할_수_있다() throws Exception {
        String email = "delivery@example.com";
        subscribeGuest(email);
        mockMvc.perform(post("/api/v1/notification-guest-cancellations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        UUID id = jdbcTemplate.queryForObject(
                "SELECT id FROM notification_guest_cancellation_requests WHERE email = ?", UUID.class, email);
        String path = "/api/admin/notification-guest-cancellations/" + id + "/sent";

        mockMvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"not-issued\"}"))
                .andExpect(status().isConflict());
        String issued = mockMvc.perform(post("/api/admin/notification-guest-cancellations/" + id + "/code")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String code = objectMapper.readTree(issued).get("code").asText();
        mockMvc.perform(get("/api/admin/notification-guest-cancellations")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$[0].codeSentAt").isEmpty());
        mockMvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/admin/notification-guest-cancellations")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$[0].codeSentAt").isNotEmpty())
                .andExpect(jsonPath("$[0].codeSentBy").value("admin"));
    }

    private UUID subscribeGuest(String email) {
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO notification_guest_email_preferences (client_id, email, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                """, clientId, email);
        jdbcTemplate.update("""
                INSERT INTO notification_guest_subscriptions
                    (client_id, target_type, target_id, active, updated_at, expires_at)
                VALUES (?, 'REGION', '11', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '12 months')
                """, clientId);
        return clientId;
    }

    @Test
    void 코드를_즉시_재발급하면_이전_코드는_무효이며_발송_기록도_초기화된다() throws Exception {
        String email = "reissue@example.com";
        subscribeGuest(email);
        mockMvc.perform(post("/api/v1/notification-guest-cancellations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        UUID id = jdbcTemplate.queryForObject(
                "SELECT id FROM notification_guest_cancellation_requests WHERE email = ?", UUID.class, email);
        String path = "/api/admin/notification-guest-cancellations/" + id;
        String first = mockMvc.perform(post(path + "/code").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String oldCode = objectMapper.readTree(first).get("code").asText();
        mockMvc.perform(post(path + "/sent").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + oldCode + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(path + "/code/reissue").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(path + "/code/reissue").with(user("member").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path + "/code/reissue").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        String reissued = mockMvc.perform(post(path + "/code/reissue")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post(path + "/sent").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + oldCode + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/admin/notification-guest-cancellations").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$[0].codeSentAt").isEmpty())
                .andExpect(jsonPath("$[0].codeSentBy").isEmpty());
        mockMvc.perform(post("/api/v1/notification-guest-cancellations/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + oldCode + "\"}"))
                .andExpect(status().isBadRequest());
        String newCode = objectMapper.readTree(reissued).get("code").asText();
        mockMvc.perform(post("/api/v1/notification-guest-cancellations/verify").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + newCode + "\"}"))
                .andExpect(status().isNoContent());
    }
}
