package com.toadzip.backend.privacy.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.privacy.domain.PrivacyHash;
import com.toadzip.backend.privacy.dto.PrivacyChoiceRequest;
import com.toadzip.backend.privacy.service.AnalyticsConsentService;
import com.toadzip.backend.privacy.service.PrivacyRetentionService;
import com.toadzip.backend.privacy.repository.ConsentEventRepository;
import com.toadzip.backend.privacy.repository.AnalyticsConsentRepository;
import com.toadzip.backend.privacy.repository.PrivacyRetentionRepository;
import com.toadzip.backend.privacy.repository.PrivacyNoticeCatalog;
import com.toadzip.backend.privacy.exception.PrivacyException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.main.web-application-type=servlet", "privacy.cookie.secure=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PrivacyIntegrationTest.TimeConfiguration.class)
@Transactional
class PrivacyIntegrationTest {

    private static final long MEMBER = 840001L;
    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");
    private static final String NOTICE = "analytics-2026-10-09-v2";
    private static final String SCOPE = "analytics-scope-2";
    private static final String MEMBER_ENDPOINT = "/api/v1/privacy/analytics/me";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AnalyticsConsentService service;
    @Autowired private PrivacyRetentionService retention;
    @Autowired private MutableClock clock;
    @Autowired private MeterRegistry meters;
    @Autowired private PrivacyNoticeCatalog notices;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ConsentEventRepository eventRepository;
    @MockitoSpyBean private PrivacyRetentionRepository retentionRepository;
    @MockitoSpyBean private AnalyticsConsentRepository consentRepository;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        jdbc.update("INSERT INTO users (id, login_identifier, created_at) VALUES (?, ?, ?)", MEMBER,
                "privacy-test-" + UUID.randomUUID(), Timestamp.from(NOW));
    }

    @Test
    void 첫_조회는_선택과_쿠키를_생성하지_않는다() throws Exception {
        long before = count("privacy_analytics_consents");
        mvc.perform(get("/api/v1/privacy/analytics-context")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.subject.kind").value("GUEST"))
                .andExpect(jsonPath("$.consent.effectiveStatus").value("UNSET"))
                .andExpect(jsonPath("$.collectionAllowed").value(false));
        assertEquals(before, count("privacy_analytics_consents"));
    }

    @Test
    void 공개_문서_원문과_해시가_일치한다() throws Exception {
        String body = mvc.perform(get("/api/v1/privacy/notices/ANALYTICS_NOTICE/" + NOTICE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var document = mapper.readTree(body);
        assertEquals(PrivacyHash.sha256(document.get("content").asText()), document.get("contentHash").asText());
        assertEquals("markdown", document.get("format").asText());
        mvc.perform(get("/api/v1/privacy/notices/ANALYTICS_NOTICE/unknown")).andExpect(status().isNotFound());
    }

    @Test
    void 회원_허용_후_철회하고_오래된_허용을_재전송해도_현재는_철회다() throws Exception {
        String first = memberChoice(UUID.randomUUID(), 0, "GRANT", NOTICE, SCOPE);
        submit(first).andExpect(jsonPath("$.receipt.revision").value(1))
                .andExpect(jsonPath("$.current.collectionAllowed").value(true));
        submit(memberChoice(UUID.randomUUID(), 1, "WITHDRAW", null, null))
                .andExpect(jsonPath("$.current.consent.decision").value("WITHDRAWN"));
        submit(first).andExpect(jsonPath("$.receipt.decision").value("GRANTED"))
                .andExpect(jsonPath("$.receipt.revision").value(1))
                .andExpect(jsonPath("$.current.consent.decision").value("WITHDRAWN"))
                .andExpect(jsonPath("$.current.consent.revision").value(2))
                .andExpect(jsonPath("$.current.collectionAllowed").value(false));
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM privacy_analytics_consent_events e JOIN privacy_analytics_consents c ON c.id = e.consent_id
                WHERE c.user_id = ?
                """, Integer.class, MEMBER));
    }

    @Test
    void 같은_명령의_다른_본문과_stale_revision은_각각_충돌한다() throws Exception {
        UUID command = UUID.randomUUID();
        submit(memberChoice(command, 0, "DENY", null, null));
        mvc.perform(post(MEMBER_ENDPOINT).with(user(Long.toString(MEMBER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberChoice(command, 0, "WITHDRAW", null, null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_COMMAND_CONFLICT"));
        mvc.perform(post(MEMBER_ENDPOINT).with(user(Long.toString(MEMBER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberChoice(UUID.randomUUID(), 0, "GRANT", NOTICE, SCOPE)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_REVISION_CONFLICT"));
    }

    @Test
    void 다른_회원과_관리자를_현재_주체로_위장할_수_없다() throws Exception {
        String choice = memberChoice(UUID.randomUUID(), 0, "DENY", null, null);
        mvc.perform(post(MEMBER_ENDPOINT).with(user(Long.toString(MEMBER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(choice.replace("840001", "840002")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_SUBJECT_CHANGED"));
        mvc.perform(post("/api/v1/privacy/analytics/guest-context").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/privacy/analytics/guest-context").with(user(Long.toString(MEMBER))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(MEMBER_ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(choice))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 비회원도_CSRF가_필요하고_쿠키와_context가_일치해야_한다() throws Exception {
        mvc.perform(post("/api/v1/privacy/analytics/guest-context")).andExpect(status().isForbidden());
        var prepared = mvc.perform(post("/api/v1/privacy/analytics/guest-context").with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = prepared.getCookie("toadzip-privacy-local");
        assertNotNull(cookie);
        assertEquals(86400, cookie.getMaxAge());
        assertEquals("/", cookie.getPath());
        assertEquals(true, cookie.isHttpOnly());
        String context = mapper.readTree(prepared.getContentAsString()).get("subject").get("contextId").asText();
        String choice = mapper.writeValueAsString(new PrivacyChoiceRequest(UUID.randomUUID().toString(), null,
                context, 0L, "DENY", null, null, "FIRST_VISIT"));
        mvc.perform(post("/api/v1/privacy/analytics/guest").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content(choice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current.consent.decision").value("DENIED"));
        mvc.perform(post("/api/v1/privacy/analytics/guest").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(choice.replace(context, UUID.randomUUID().toString())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_SUBJECT_CHANGED"));
        mvc.perform(post("/api/v1/privacy/analytics/guest").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(choice))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_CONTEXT_REQUIRED"));
        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM privacy_analytics_consents WHERE guest_token_hash = ?)",
                Boolean.class, cookie.getValue()));
    }

    @Test
    void 허용은_최신_안내가_필요하지만_철회는_안내_없이_가능하다() throws Exception {
        mvc.perform(post(MEMBER_ENDPOINT).with(user(Long.toString(MEMBER))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberChoice(UUID.randomUUID(), 0, "GRANT", "old", SCOPE)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_NOTICE_CHANGED"));
        submit(memberChoice(UUID.randomUUID(), 0, "WITHDRAW", null, null))
                .andExpect(jsonPath("$.current.consent.decision").value("DENIED"));
    }

    @Test
    void 회원의_기간_만료와_90일_파기_후에도_revision은_유지된다() {
        UUID command = UUID.randomUUID();
        service.chooseMember(MEMBER, request(command, 0, "GRANT"));
        clock.set(NOW.plusSeconds(180L * 86400));
        assertEquals("EXPIRED", service.context(MEMBER, null).consent().effectiveStatus());
        clock.set(NOW.plusSeconds(270L * 86400));
        retention.purgeExpiredData();
        assertEquals(1, service.context(MEMBER, null).consent().revision());
        assertEquals(0, count("privacy_analytics_consent_events"));
        assertThrows(RuntimeException.class, () -> service.chooseMember(MEMBER, request(command, 0, "GRANT")));
    }

    @Test
    void 대체된_증빙만_90일후_삭제하고_현재_거부_증빙은_유지한다() {
        service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT"));
        Instant withdrawal = NOW.plusSeconds(3600);
        clock.set(withdrawal);
        service.chooseMember(MEMBER, request(UUID.randomUUID(), 1, "WITHDRAW"));
        clock.set(withdrawal.plusSeconds(90L * 86400));
        retention.purgeExpiredData();
        assertEquals(1, count("privacy_analytics_consent_events"));
        assertEquals("WITHDRAWN", service.context(MEMBER, null).consent().decision());
    }

    @Test
    void 미선택_비회원_context는_24시간_정각에_삭제한다() {
        var prepared = service.prepareGuest(null);
        clock.set(NOW.plusSeconds(86400));
        retention.purgeExpiredData();
        assertEquals("UNSET", service.context(null, prepared.token()).consent().effectiveStatus());
        assertEquals(0, count("privacy_analytics_consents"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 최초_회원_선택의_동시_변경은_하나만_저장한다() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> concurrentChoice(start, "GRANT"));
            var second = executor.submit(() -> concurrentChoice(start, "DENY"));
            start.countDown();
            assertEquals(1, first.get() + second.get());
            assertEquals(1, service.context(MEMBER, null).consent().revision());
            assertEquals(1, count("privacy_analytics_consent_events"));
        } finally {
            deleteMember();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 이력_저장_실패는_현재_상태도_롤백한다() {
        try {
            doThrow(new DataAccessResourceFailureException("test failure")).when(eventRepository)
                    .record(any(), any(), any(), any());
            assertThrows(DataAccessResourceFailureException.class,
                    () -> service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT")));
            assertEquals("UNSET", service.context(MEMBER, null).consent().decision());
            assertEquals(0, count("privacy_analytics_consent_events"));
            reset(eventRepository);
            service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT"));
            assertEquals(1, service.context(MEMBER, null).consent().revision());
        } finally {
            reset(eventRepository);
            deleteMember();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 파기_실패는_청크를_롤백하고_다음_실행에서_정리한다() {
        try {
            service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT"));
            jdbc.update("UPDATE privacy_analytics_consent_events SET scope_version = 'older-scope'");
            Instant before = jdbc.queryForObject("SELECT purge_after FROM privacy_analytics_consent_events",
                    Timestamp.class).toInstant();
            clock.set(NOW.plusSeconds(100L * 86400));
            doThrow(new DataAccessResourceFailureException("test failure")).when(retentionRepository)
                    .deleteExpiredEvents(any(), any(), anyInt());
            assertThrows(DataAccessResourceFailureException.class, retention::purgeExpiredData);
            assertEquals(before, jdbc.queryForObject("SELECT purge_after FROM privacy_analytics_consent_events",
                    Timestamp.class).toInstant());
            reset(retentionRepository);
            retention.purgeExpiredData();
            assertEquals(0, count("privacy_analytics_consent_events"));
            assertEquals(1, service.context(MEMBER, null).consent().revision());
        } finally {
            reset(retentionRepository);
            deleteMember();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 재선택_중인_회원은_파기가_건너뛰고_다음_실행에서_현재_증빙을_보존한다() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT"));
            clock.set(NOW.plusSeconds(271L * 86400));
            CountDownLatch chosen = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var renewal = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                service.chooseMember(MEMBER, request(UUID.randomUUID(), 1, "GRANT"));
                chosen.countDown();
                await(release);
                return null;
            }));
            try {
                chosen.await();
                retention.purgeExpiredData();
                assertEquals(1, count("privacy_analytics_consent_events"));
            } finally {
                release.countDown();
            }
            renewal.get();
            retention.purgeExpiredData();
            assertEquals(1, count("privacy_analytics_consent_events"));
            assertEquals(2, service.context(MEMBER, null).consent().revision());
            assertEquals("GRANTED", service.context(MEMBER, null).consent().effectiveStatus());
        } finally {
            deleteMember();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 두_파기_실행이_겹쳐도_현재_거부와_revision은_남는다() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT"));
            service.chooseMember(MEMBER, request(UUID.randomUUID(), 1, "WITHDRAW"));
            clock.set(NOW.plusSeconds(91L * 86400));
            var first = executor.submit(retention::purgeExpiredData);
            var second = executor.submit(retention::purgeExpiredData);
            first.get();
            second.get();
            assertEquals(1, count("privacy_analytics_consent_events"));
            assertEquals(2, service.context(MEMBER, null).consent().revision());
            assertEquals("WITHDRAWN", service.context(MEMBER, null).consent().decision());
        } finally {
            deleteMember();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 비회원_변경이_잠금을_기다리다_만료되면_이전_시각으로_선택하지_않는다() throws Exception {
        var guest = service.prepareGuest(null);
        UUID contextId = UUID.fromString(guest.context().subject().contextId());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch changing = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM privacy_analytics_consents WHERE id = ? FOR UPDATE", UUID.class, contextId);
                held.countDown();
                await(release);
                return null;
            }));
            await(held);
            clock.set(guest.context().consent().expiresAt().minusNanos(1));
            doAnswer(invocation -> {
                changing.countDown();
                return invocation.callRealMethod();
            }).when(consentRepository).findGuest(guest.token(), true);
            var choice = executor.submit(() -> service.chooseGuest(guest.token(), new PrivacyChoiceRequest(
                    UUID.randomUUID().toString(), null, contextId.toString(), 0L,
                    "GRANT", NOTICE, SCOPE, "FIRST_VISIT")));
            try {
                await(changing);
                clock.set(guest.context().consent().expiresAt());
            } finally {
                release.countDown();
            }
            holder.get();
            ExecutionException failure = assertThrows(ExecutionException.class, choice::get);
            assertEquals("PRIVACY_CONTEXT_REQUIRED", ((PrivacyException) failure.getCause()).getCode());
            assertEquals(0, count("privacy_analytics_consent_events"));
        } finally {
            release.countDown();
            reset(consentRepository);
            jdbc.update("DELETE FROM privacy_analytics_consents WHERE id = ?", contextId);
            deleteMember();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 비회원_준비가_잠금을_기다리다_만료되면_새_context를_발급한다() throws Exception {
        var guest = service.prepareGuest(null);
        UUID contextId = UUID.fromString(guest.context().subject().contextId());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch preparing = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM privacy_analytics_consents WHERE id = ? FOR UPDATE", UUID.class, contextId);
                held.countDown();
                await(release);
                return null;
            }));
            await(held);
            clock.set(guest.context().consent().expiresAt().minusNanos(1));
            doAnswer(invocation -> {
                preparing.countDown();
                return invocation.callRealMethod();
            }).when(consentRepository).findGuest(guest.token(), true);
            var preparation = executor.submit(() -> service.prepareGuest(guest.token()));
            try {
                await(preparing);
                clock.set(guest.context().consent().expiresAt());
            } finally {
                release.countDown();
            }
            holder.get();
            var renewed = preparation.get();
            assertFalse(contextId.toString().equals(renewed.context().subject().contextId()));
            assertEquals(clock.instant().plusSeconds(86400), renewed.context().consent().expiresAt());
            jdbc.update("DELETE FROM privacy_analytics_consents WHERE id = ?",
                    UUID.fromString(renewed.context().subject().contextId()));
        } finally {
            release.countDown();
            reset(consentRepository);
            jdbc.update("DELETE FROM privacy_analytics_consents WHERE id = ?", contextId);
            deleteMember();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 회원_결정과_보유기간은_회원_잠금_획득_이후_시각을_기준으로_기록한다() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch changing = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, MEMBER);
                held.countDown();
                await(release);
                return null;
            }));
            await(held);
            doAnswer(invocation -> {
                changing.countDown();
                return invocation.callRealMethod();
            }).when(consentRepository).lockMember(MEMBER);
            var choice = executor.submit(() -> service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT")));
            Instant acquiredAt = NOW.plusSeconds(30);
            try {
                await(changing);
                clock.set(acquiredAt);
            } finally {
                release.countDown();
            }
            holder.get();
            var result = choice.get();
            assertEquals(acquiredAt, result.receipt().recordedAt());
            assertEquals(acquiredAt.plusSeconds(180L * 86400), result.current().consent().expiresAt());
        } finally {
            release.countDown();
            reset(consentRepository);
            deleteMember();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void scope_기한_갱신이_잠금으로_건너뛰어져도_실효_파기지연을_지표로_노출한다() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT"));
            jdbc.update("UPDATE privacy_analytics_consents SET scope_version = 'older-scope' WHERE user_id = ?", MEMBER);
            jdbc.update("UPDATE privacy_analytics_consent_events SET scope_version = 'older-scope'");
            Instant storedDeadline = jdbc.queryForObject("SELECT purge_after FROM privacy_analytics_consent_events",
                    Timestamp.class).toInstant();
            clock.set(NOW.plusSeconds(100L * 86400));
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, MEMBER);
                held.countDown();
                await(release);
                return null;
            }));
            try {
                await(held);
                retention.purgeExpiredData();
                assertEquals(storedDeadline, jdbc.queryForObject("SELECT purge_after FROM privacy_analytics_consent_events",
                        Timestamp.class).toInstant());
                assertEquals(1.0, meters.get("privacy.retention.overdue.count").tag("job", "consent").gauge().value());
                Instant effectiveDeadline = notices.analyticsScopeEffectiveAt().plusSeconds(90L * 86400);
                assertEquals((double) java.time.Duration.between(effectiveDeadline, clock.instant()).toSeconds(),
                        meters.get("privacy.retention.oldest.overdue.seconds").tag("job", "consent").gauge().value());
                assertEquals((double) clock.instant().getEpochSecond(),
                        meters.get("privacy.retention.last.success.seconds").tag("job", "consent").gauge().value());
            } finally {
                release.countDown();
            }
            holder.get();
            retention.purgeExpiredData();
            assertEquals(0, count("privacy_analytics_consent_events"));
            assertEquals(0.0, meters.get("privacy.retention.overdue.count").tag("job", "consent").gauge().value());
        } finally {
            release.countDown();
            deleteMember();
        }
    }

    private int concurrentChoice(CountDownLatch start, String action) throws InterruptedException {
        start.await();
        try {
            service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, action));
            return 1;
        } catch (PrivacyException exception) {
            assertEquals("PRIVACY_REVISION_CONFLICT", exception.getCode());
            return 0;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void 동의_저장과_파기는_기존_회원_행을_변경하지_않는다() {
        jdbc.update("UPDATE users SET email = 'member@example.test' WHERE id = ?", MEMBER);
        String before = jdbc.queryForObject("SELECT row_to_json(u)::text FROM users u WHERE id = ?",
                String.class, MEMBER);
        service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "GRANT"));
        service.chooseMember(MEMBER, request(UUID.randomUUID(), 1, "WITHDRAW"));
        clock.set(NOW.plusSeconds(90L * 86400));
        retention.purgeExpiredData();
        assertEquals(before, jdbc.queryForObject("SELECT row_to_json(u)::text FROM users u WHERE id = ?",
                String.class, MEMBER));
        assertEquals("WITHDRAWN", service.context(MEMBER, null).consent().decision());
        assertEquals(1, count("privacy_analytics_consent_events"));
    }

    @Test
    void 회원이_사라진_동의만_파기하고_다른_회원은_보존한다() {
        service.chooseMember(MEMBER, request(UUID.randomUUID(), 0, "DENY"));
        jdbc.update("DELETE FROM users WHERE id = ?", MEMBER);
        assertEquals(1, count("privacy_analytics_consents"));
        assertEquals(1, count("privacy_analytics_consent_events"));
        retention.purgeExpiredData();
        assertEquals(0, count("privacy_analytics_consents"));
        assertEquals(0, count("privacy_analytics_consent_events"));
    }

    private void deleteMember() {
        jdbc.update("DELETE FROM privacy_analytics_consents WHERE user_id = ?", MEMBER);
        jdbc.update("DELETE FROM users WHERE id = ?", MEMBER);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private PrivacyChoiceRequest request(UUID command, long revision, String action) {
        return new PrivacyChoiceRequest(command.toString(), Long.toString(MEMBER), null, revision,
                action, NOTICE, SCOPE, "SETTINGS");
    }

    private String memberChoice(UUID command, long revision, String action, String notice, String scope) {
        return mapper.writeValueAsString(new PrivacyChoiceRequest(command.toString(), Long.toString(MEMBER), null,
                revision, action, notice, scope, "FIRST_VISIT"));
    }

    private org.springframework.test.web.servlet.ResultActions submit(String body) throws Exception {
        return mvc.perform(post(MEMBER_ENDPOINT).with(user(Long.toString(MEMBER))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock privacyTestClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(NOW);

        void set(Instant value) {
            time.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return time.get();
        }
    }
}
