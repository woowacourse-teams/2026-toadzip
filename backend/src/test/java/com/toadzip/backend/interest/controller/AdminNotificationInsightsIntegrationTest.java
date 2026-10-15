package com.toadzip.backend.interest.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminNotificationInsightsIntegrationTest {

    private static final String ENDPOINT = "/api/admin/notification-subscriptions";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcClient jdbc;
    @MockitoBean private Clock clock;

    @BeforeEach
    void 기준시각을_고정한다() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
    }

    @Test
    void 대상별_활성_신청자를_집계하고_취소와_만료를_제외한다() throws Exception {
        long first = member("google:insights-one");
        long second = member("kakao:insights-two");
        subscription(first, "11", true, "2027-01-01T00:00:00Z");
        subscription(second, "11", true, "2027-01-01T00:00:00Z");
        subscription(first, "26", true, "2027-01-01T00:00:00Z");
        subscription(second, "26", false, "2027-01-01T00:00:00Z");
        subscription(first, "27", true, "2026-10-14T00:00:00Z");
        mvc.perform(get(ENDPOINT + "/targets").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].targetId").value("11"))
                .andExpect(jsonPath("$.data.items[0].activeMembers").value(2));
    }

    @Test
    void 신청일_기록_없는_신청을_별도_필터로_찾는다() throws Exception {
        subscription(member("google:missing-date"), "11", true, "2027-01-01T00:00:00Z");
        mvc.perform(get(ENDPOINT).param("review", "REQUEST_DATE_UNKNOWN")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get(ENDPOINT).param("review", "INVALID").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 집계_시작_전_기간을_없는_신청으로_추정하지_않는다() throws Exception {
        mvc.perform(get(ENDPOINT + "/trend").param("days", "7").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.days.length()").value(7))
                .andExpect(jsonPath("$.data.days[0].activations").isEmpty());
    }

    @Test
    void 대상별_조회도_페이징과_대상_종류_조건을_적용한다() throws Exception {
        long member = member("google:target-pages");
        subscription(member, "11", true, "2027-01-01T00:00:00Z");
        subscription(member, "26", true, "2027-01-01T00:00:00Z");
        mvc.perform(get(ENDPOINT + "/targets").param("size", "1").param("page", "1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items[0].targetId").value("26"));
        mvc.perform(get(ENDPOINT + "/targets").param("targetType", "COMPLEX")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 정상_지역과_이메일_미제공은_대상_오류로_분류하지_않는다() throws Exception {
        long member = member("google:review-region");
        subscription(member, "11", true, "2027-01-01T00:00:00Z");
        subscription(member, "9999999999", true, "2027-01-01T00:00:00Z");
        mvc.perform(get(ENDPOINT).param("review", "TARGET_UNAVAILABLE")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].targetId").value("9999999999"))
                .andExpect(jsonPath("$.data.summary.activeSubscriptions").value(2));
    }

    @Test
    void 신청일과_대상_기록이_있으면_이메일이_없어도_확인필요에서_제외한다() throws Exception {
        long member = member("google:review-notice");
        subscription(member, "11", true, "2027-01-01T00:00:00Z");
        jdbc.sql("""
                INSERT INTO privacy_notification_notices
                    (user_id,target_type,target_id,notice_version,requested_at,expires_at,purge_after)
                VALUES (:member,'REGION','11','fixture',now(),now() + INTERVAL '1 year',now() + INTERVAL '2 years')
                """).param("member", member).update();
        mvc.perform(get(ENDPOINT).param("review", "ANY").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 집계가_시작된_이후의_빈날은_0건이고_시작이전은_미집계다() throws Exception {
        jdbc.sql("DELETE FROM notification_daily_activity").update();
        jdbc.sql("""
                INSERT INTO notification_daily_activity VALUES
                    ('2026-10-13',3,1,'2026-10-13T00:00:00Z')
                """).update();
        mvc.perform(get(ENDPOINT + "/trend").param("days", "3").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.collectionStartedAt").value("2026-10-13T00:00:00Z"))
                .andExpect(jsonPath("$.data.days[0].activations").isEmpty())
                .andExpect(jsonPath("$.data.days[1].activations").value(3))
                .andExpect(jsonPath("$.data.days[1].cancellations").value(1))
                .andExpect(jsonPath("$.data.days[2].activations").value(0));
    }

    @Test
    void 집계_API의_관리자_권한과_입력_범위를_검사한다() throws Exception {
        long ordinaryMember = member("google:insights-permission");
        for (String path : java.util.List.of("/targets", "/trend")) {
            mvc.perform(get(ENDPOINT + path)).andExpect(status().isUnauthorized());
            mvc.perform(get(ENDPOINT + path).with(user(Long.toString(ordinaryMember)).roles("USER")))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(ENDPOINT + "/trend").param("days", "91").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ENDPOINT + "/targets").param("page", "-1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 지역_이전코드를_통합해_회원중복을_제외하고_해당_신청자들을_조회한다() throws Exception {
        long first = member("google:region-group-first");
        long second = member("kakao:region-group-second");
        subscription(first, "12210", true, "2027-01-01T00:00:00Z");
        subscription(first, "29110", true, "2027-01-01T00:00:00Z");
        subscription(second, "29110", true, "2027-01-01T00:00:00Z");
        mvc.perform(get(ENDPOINT + "/targets").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].targetId").value("12210"))
                .andExpect(jsonPath("$.data.items[0].activeMembers").value(2));
        mvc.perform(get(ENDPOINT).param("targetType", "REGION").param("targetId", "29110")
                        .param("groupTarget", "true").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        mvc.perform(get(ENDPOINT).param("targetType", "REGION").param("targetId", "12210")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 단지_ID_선행0도_같은_대상으로_모으고_원문_신청을_보존한다() throws Exception {
        long member = member("google:complex-group");
        jdbc.sql("""
                INSERT INTO notification_subscriptions(user_id,target_type,target_id,active,updated_at,expires_at)
                VALUES (:member,'COMPLEX','1',true,now(),'2027-01-01'),
                    (:member,'COMPLEX','01',true,now(),'2027-01-01')
                """).param("member", member).update();
        mvc.perform(get(ENDPOINT + "/targets").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].activeMembers").value(1));
        mvc.perform(get(ENDPOINT).param("targetType", "COMPLEX").param("targetId", "1")
                        .param("groupTarget", "true").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    private long member(String identifier) {
        return jdbc.sql("INSERT INTO users(login_identifier,created_at) VALUES (:id,now()) RETURNING id")
                .param("id", identifier).query(Long.class).single();
    }

    private void subscription(long userId, String targetId, boolean active, String expiry) {
        jdbc.sql("""
                INSERT INTO notification_subscriptions(user_id,target_type,target_id,active,updated_at,expires_at)
                VALUES (:user,'REGION',:target,:active,now(),CAST(:expiry AS timestamptz))
                """).param("user", userId).param("target", targetId).param("active", active)
                .param("expiry", expiry).update();
    }
}
