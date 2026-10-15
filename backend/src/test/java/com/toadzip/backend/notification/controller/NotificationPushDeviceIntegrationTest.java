package com.toadzip.backend.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NotificationPushDeviceIntegrationTest {
    private static final String ENDPOINT = "/api/v1/notification-push-devices";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void members() {
        jdbc.update("INSERT INTO users(id,login_identifier,created_at) VALUES(101,'push-101',now()),(102,'push-102',now())");
    }

    @Test
    void 회원인증과_CSRF없이_등록할_수_없다() throws Exception {
        mvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request(101, null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ENDPOINT).with(user("101").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(request(101, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 같은_기기의_갱신은_멱등이고_다른_쿠키의_FID_탈취는_거절한다() throws Exception {
        Cookie cookie = register(101, new MockHttpSession(), null, null);
        mvc.perform(post(ENDPOINT).cookie(cookie).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request(101, "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bindingVersion").value("1"));
        mvc.perform(post(ENDPOINT).with(user("102").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request(102, null)))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_push_devices", Long.class)).isOne();
    }

    @Test
    void 현재_기기만_끄고_오래된_해제요청은_새_바인딩을_끄지_않는다() throws Exception {
        Cookie cookie = register(101, new MockHttpSession(), null, null);
        register(102, new MockHttpSession(), cookie, "1");
        register(101, new MockHttpSession(), cookie, "2");
        mvc.perform(delete(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedUserId\":\"101\",\"bindingVersion\":\"1\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices", Boolean.class)).isTrue();
        mvc.perform(delete(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedUserId\":\"101\",\"bindingVersion\":\"3\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices", Boolean.class)).isFalse();
    }

    @Test
    void 같은_해제의_재시도는_멱등이지만_새_등록은_해제하지_않는다() throws Exception {
        Cookie cookie = register(101, new MockHttpSession(), null, null);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedUserId\":\"101\",\"bindingVersion\":\"1\"}"))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForObject("SELECT binding_version FROM notification_push_devices", Long.class))
                .isEqualTo(2);
    }

    @Test
    void 로그아웃은_서버에서_현재_세션의_기기를_끊는다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie cookie = register(101, session, null, null);
        var otherDevice = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification_push_devices(id,user_id,fid,binding_version,enabled,
                    consented_at,registered_at,last_seen_at) VALUES(?,101,'other-installation',1,true,now(),now(),now())
                """, otherDevice);
        mvc.perform(post("/api/auth/logout").session(session).cookie(cookie)
                        .with(user("101").roles("USER")).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices WHERE id=?",
                Boolean.class, java.util.UUID.fromString(cookie.getValue()))).isFalse();
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices WHERE id=?",
                Boolean.class, otherDevice)).isTrue();
    }

    @Test
    void 다른_세션의_늦은_로그아웃은_A_B_A_재등록을_해제하지_않는다() throws Exception {
        MockHttpSession oldSession = new MockHttpSession();
        Cookie cookie = register(101, oldSession, null, null);
        register(102, new MockHttpSession(), cookie, "1");
        register(101, new MockHttpSession(), cookie, "2");
        mvc.perform(post("/api/auth/logout").session(oldSession).cookie(cookie)
                        .with(user("101").roles("USER")).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices", Boolean.class)).isTrue();
    }

    @Test
    void 계정변경_후_이전_화면의_등록_요청을_거절한다() throws Exception {
        mvc.perform(post(ENDPOINT).with(user("102").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request(101, null)))
                .andExpect(status().isConflict());
        mvc.perform(get(ENDPOINT + "/current").with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void 로그아웃으로_중지한_기기는_같은_회원에게만_복원할_수_있다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie cookie = register(101, session, null, null);
        mvc.perform(post("/api/auth/logout").session(session).cookie(cookie)
                        .with(user("101").roles("USER")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(ENDPOINT + "/current").cookie(cookie).with(user("102").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resumeAfterLogin").value(false));
        mvc.perform(get(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.resumeAfterLogin").value(true))
                .andExpect(jsonPath("$.bindingVersion").value("2"));
        register(101, new MockHttpSession(), cookie, "2");
        mvc.perform(get(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.resumeAfterLogin").value(false))
                .andExpect(jsonPath("$.bindingVersion").value("3"));
    }

    @Test
    void 직접_알림을_끄면_재로그인해도_복원하지_않는다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie cookie = register(101, session, null, null);
        mvc.perform(delete(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedUserId\":\"101\",\"bindingVersion\":\"1\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").session(session).cookie(cookie)
                        .with(user("101").roles("USER")).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.resumeAfterLogin").value(false));
    }

    @Test
    void 세션만_만료되어도_기기_알림은_활성_상태를_유지한다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie cookie = register(101, session, null, null);
        session.invalidate();
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices", Boolean.class)).isTrue();
        mvc.perform(get(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
    }


    @Test
    void 내_기기만_조회하고_현재기기와_마지막사용을_표시하며_FID는_노출하지_않는다() throws Exception {
        Cookie cookie = register(101, new MockHttpSession(), null, null);
        mvc.perform(post(ENDPOINT).cookie(cookie).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(101, "1").replace("}", ",\"platform\":\"MAC\",\"browser\":\"CHROME\"}")))
                .andExpect(status().isOk());
        jdbc.update("""
                INSERT INTO notification_push_devices(id,user_id,fid,binding_version,enabled,
                    consented_at,registered_at,last_seen_at)
                VALUES(?,102,'private-other-fid',1,true,now(),now(),now())
                """, java.util.UUID.randomUUID());
        mvc.perform(get(ENDPOINT).cookie(cookie).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].platform").value("MAC"))
                .andExpect(jsonPath("$[0].browser").value("CHROME"))
                .andExpect(jsonPath("$[0].lastSeenAt").isNotEmpty())
                .andExpect(jsonPath("$[0].fid").doesNotExist());
        mvc.perform(get(ENDPOINT)).andExpect(status().isUnauthorized());
    }

    @Test
    void 다른_기기에서_해제하면_복원되지_않고_마지막사용을_유지한다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie cookie = register(101, session, null, null);
        mvc.perform(post("/api/auth/logout").session(session).cookie(cookie)
                        .with(user("101").roles("USER")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get(ENDPOINT).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].current").value(false))
                .andExpect(jsonPath("$[0].resumeAfterLogin").value(true));
        var lastSeen = jdbc.queryForObject("SELECT last_seen_at FROM notification_push_devices", java.sql.Timestamp.class);
        mvc.perform(delete(ENDPOINT + "/" + cookie.getValue()).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedUserId\":\"101\",\"bindingVersion\":\"2\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get(ENDPOINT + "/current").cookie(cookie).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.knownForUser").value(true))
                .andExpect(jsonPath("$.resumeAfterLogin").value(false));
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM notification_push_devices", java.sql.Timestamp.class))
                .isEqualTo(lastSeen);
        mvc.perform(post(ENDPOINT).cookie(cookie).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request(101, "2")))
                .andExpect(status().isConflict());
        mvc.perform(get(ENDPOINT).with(user("101").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(ENDPOINT + "/current").cookie(cookie).with(user("102").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.knownForUser").value(false));
    }

    @Test
    void 원격해제는_본인기기에만_가능하고_CSRF와_현재버전이_필요하다() throws Exception {
        Cookie cookie = register(101, new MockHttpSession(), null, null);
        String path = ENDPOINT + "/" + cookie.getValue();
        mvc.perform(delete(path).with(user("102").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedUserId\":\"102\",\"bindingVersion\":\"1\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete(path).with(user("101").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedUserId\":\"101\",\"bindingVersion\":\"1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedUserId\":\"101\",\"bindingVersion\":\"9\"}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT enabled FROM notification_push_devices", Boolean.class)).isTrue();
    }

    @Test
    void 기기표시에는_허용한_분류만_저장한다() throws Exception {
        mvc.perform(post(ENDPOINT).with(user("101").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(101, null).replace("}", ",\"platform\":\"raw user agent\"}")))
                .andExpect(status().isBadRequest());
    }

    private Cookie register(long userId, MockHttpSession session, Cookie cookie, String version) throws Exception {
        var request = post(ENDPOINT).session(session).with(user(Long.toString(userId)).roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request(userId, version));
        if (cookie != null) {
            request.cookie(cookie);
        }
        var response = mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andReturn().getResponse();
        Cookie issued = response.getCookie("notification-device");
        assertThat(issued).isNotNull();
        assertThat(issued.isHttpOnly()).isTrue();
        assertThat(issued.getAttribute("SameSite")).isEqualTo("Lax");
        return issued;
    }

    private String request(long userId, String version) {
        String expected = "null";
        if (version != null) {
            expected = "\"" + version + "\"";
        }
        return "{\"fid\":\"fixture-installation\",\"expectedUserId\":\"" + userId
                + "\",\"expectedBindingVersion\":" + expected + "}";
    }
}
