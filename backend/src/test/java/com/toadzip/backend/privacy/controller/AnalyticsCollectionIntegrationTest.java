package com.toadzip.backend.privacy.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.interest.domain.NotificationEventSource;
import com.toadzip.backend.interest.domain.NotificationEventType;
import com.toadzip.backend.interest.domain.NotificationInterestOutcome;
import com.toadzip.backend.interest.domain.NotificationTargetType;
import com.toadzip.backend.interest.dto.NotificationInterestRequest;
import com.toadzip.backend.interest.service.NotificationInterestService;
import com.toadzip.backend.privacy.dto.PrivacyChoiceRequest;
import com.toadzip.backend.privacy.exception.PrivacyException;
import com.toadzip.backend.privacy.repository.AnalyticsConsentRepository;
import com.toadzip.backend.privacy.service.AnalyticsConsentService;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.main.web-application-type=servlet", "privacy.cookie.secure=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AnalyticsCollectionIntegrationTest.TimeConfiguration.class)
@Transactional
class AnalyticsCollectionIntegrationTest {

    private static final long MEMBER = 841001L;
    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");
    private static final String NOTICE = "analytics-2026-10-09-v2";
    private static final String SCOPE = "analytics-scope-2";
    private static final String ENDPOINT = "/api/v1/notification-interest-events";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AnalyticsConsentService choices;
    @Autowired private NotificationInterestService observations;
    @Autowired private CollectionClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AnalyticsConsentRepository consents;
    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        jdbc.update("INSERT INTO users(id,login_identifier,created_at) VALUES (?,?,?)", MEMBER,
                "collection-test-" + UUID.randomUUID(), Timestamp.from(NOW));
    }

    @AfterEach
    void cleanUp() {
        for (UUID eventId : eventIds) {
            jdbc.update("DELETE FROM privacy_notification_events WHERE event_id = ?", eventId);
        }
        jdbc.update("DELETE FROM privacy_analytics_consents WHERE user_id = ?", MEMBER);
        jdbc.update("DELETE FROM users WHERE id = ?", MEMBER);
    }

    @Test
    void 회원의_현재_범위_허용은_context와_실제_분석_API에_동일하게_적용된다() throws Exception {
        var granted = choices.chooseMember(MEMBER, memberChoice(0, "GRANT"));
        assertTrue(granted.current().collectionAllowed());
        assertTrue(choices.context(MEMBER, null).collectionAllowed());
        for (NotificationEventType type : List.of(
                NotificationEventType.CLICKED, NotificationEventType.EXPOSED, NotificationEventType.DECLINED)) {
            NotificationInterestRequest request = observation(type);
            mvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("OBSERVED"));
            assertEquals(1, stored(request));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notification_subscriptions WHERE user_id = ?",
                Integer.class, MEMBER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "UNSET", "DENIED", "WITHDRAWN", "EXPIRED", "OLD_SCOPE"})
    void 허용하지_않았거나_효력을_잃은_선택은_context와_분석_API_모두_차단한다(String state) throws Exception {
        arrangeBlockedChoice(state);
        assertFalse(choices.context(MEMBER, null).collectionAllowed());
        NotificationInterestRequest request = observation(NotificationEventType.CLICKED);
        mvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ANALYTICS_CONSENT_REQUIRED"));
        assertEquals(0, stored(request));
    }

    @Test
    void 비회원의_허용과_철회도_적용하고_그_허용을_로그인_계정에_전용하지_않는다() throws Exception {
        var guest = choices.prepareGuest(null);
        String contextId = guest.context().subject().contextId();
        var grant = choices.chooseGuest(guest.token(), new PrivacyChoiceRequest(UUID.randomUUID().toString(),
                null, contextId, 0L, "GRANT", NOTICE, SCOPE, "FIRST_VISIT"));
        assertTrue(grant.current().collectionAllowed());
        Cookie cookie = new Cookie("toadzip-privacy-local", guest.token());
        NotificationInterestRequest request = observation(NotificationEventType.CLICKED);
        mvc.perform(post(ENDPOINT).with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        assertEquals(1, stored(request));

        mvc.perform(post(ENDPOINT).with(user(Long.toString(MEMBER)).roles("USER")).with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(observation(NotificationEventType.CLICKED))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ANALYTICS_CONSENT_REQUIRED"));
        choices.chooseGuest(guest.token(), new PrivacyChoiceRequest(UUID.randomUUID().toString(), null, contextId,
                1L, "WITHDRAW", null, null, "SETTINGS"));
        assertFalse(choices.context(null, guest.token()).collectionAllowed());
        NotificationInterestRequest withdrawnRequest = observation(NotificationEventType.CLICKED);
        mvc.perform(post(ENDPOINT).with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(withdrawnRequest)))
                .andExpect(status().isForbidden());
        assertEquals(0, stored(withdrawnRequest));
    }

    @Test
    void 과거_익명_안내의_허용은_최신_안내에_다시_동의해야_수집할_수_있다() throws Exception {
        seedOldScope();
        assertEquals("RECONSENT_REQUIRED", choices.context(MEMBER, null).consent().effectiveStatus());
        assertFalse(choices.context(MEMBER, null).collectionAllowed());
        PrivacyChoiceRequest stale = new PrivacyChoiceRequest(UUID.randomUUID().toString(), Long.toString(MEMBER),
                null, 1L, "GRANT", "analytics-2026-10-09-v1", "analytics-scope-1", "SETTINGS");
        mvc.perform(post("/api/v1/privacy/analytics/me").with(user(Long.toString(MEMBER)).roles("USER"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(stale)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_NOTICE_CHANGED"));
        assertFalse(choices.context(MEMBER, null).collectionAllowed());

        var renewed = choices.chooseMember(MEMBER, memberChoice(1, "GRANT"));
        assertTrue(renewed.current().collectionAllowed());
        assertEquals(SCOPE, renewed.current().consent().scopeVersion());
        assertEquals(NOTICE, renewed.current().consent().noticeVersion());
        NotificationInterestRequest request = observation(NotificationEventType.CLICKED);
        assertEquals(NotificationInterestOutcome.OBSERVED, observations.record(request, MEMBER, null).outcome());
        assertEquals(1, stored(request));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 철회_트랜잭션이_먼저_잠그면_대기하던_분석은_저장하지_않는다() throws Exception {
        choices.chooseMember(MEMBER, memberChoice(0, "GRANT"));
        NotificationInterestRequest request = observation(NotificationEventType.CLICKED);
        CountDownLatch withdrawn = new CountDownLatch(1);
        CountDownLatch collecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var withdrawal = executor.submit(() -> transaction().execute(status -> {
                choices.chooseMember(MEMBER, memberChoice(1, "WITHDRAW"));
                withdrawn.countDown();
                await(release);
                return null;
            }));
            try {
                await(withdrawn);
                doAnswer(invocation -> {
                    collecting.countDown();
                    return invocation.callRealMethod();
                }).when(consents).lockForCollection(MEMBER, null);
                var collection = executor.submit(() -> observations.record(request, MEMBER, null));
                await(collecting);
                release.countDown();
                withdrawal.get(10, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> collection.get(10, TimeUnit.SECONDS));
                assertEquals("ANALYTICS_CONSENT_REQUIRED", ((PrivacyException) failure.getCause()).getCode());
                assertEquals(0, stored(request));
                assertFalse(choices.context(MEMBER, null).collectionAllowed());
            } finally {
                release.countDown();
            }
        } finally {
            reset(consents);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 수집이_먼저_잠그면_해당_저장까지만_완료하고_철회_이후_수집을_차단한다() throws Exception {
        choices.chooseMember(MEMBER, memberChoice(0, "GRANT"));
        NotificationInterestRequest first = observation(NotificationEventType.CLICKED);
        NotificationInterestRequest later = observation(NotificationEventType.CLICKED);
        CountDownLatch collected = new CountDownLatch(1);
        CountDownLatch withdrawing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var collection = executor.submit(() -> transaction().execute(status -> {
                observations.record(first, MEMBER, null);
                collected.countDown();
                await(release);
                return null;
            }));
            try {
                await(collected);
                doAnswer(invocation -> {
                    withdrawing.countDown();
                    return invocation.callRealMethod();
                }).when(consents).lockMember(MEMBER);
                var withdrawal = executor.submit(() -> choices.chooseMember(MEMBER, memberChoice(1, "WITHDRAW")));
                await(withdrawing);
                release.countDown();
                collection.get(10, TimeUnit.SECONDS);
                withdrawal.get(10, TimeUnit.SECONDS);
                assertEquals(1, stored(first));
                PrivacyException failure = assertThrows(PrivacyException.class,
                        () -> observations.record(later, MEMBER, null));
                assertEquals("ANALYTICS_CONSENT_REQUIRED", failure.getCode());
                assertEquals(0, stored(later));
                assertFalse(choices.context(MEMBER, null).collectionAllowed());
            } finally {
                release.countDown();
            }
        } finally {
            reset(consents);
        }
    }

    private void arrangeBlockedChoice(String state) {
        switch (state) {
            case "MISSING" -> { }
            case "UNSET" -> consents.createMember(MEMBER, NOW);
            case "DENIED" -> choices.chooseMember(MEMBER, memberChoice(0, "DENY"));
            case "WITHDRAWN" -> {
                choices.chooseMember(MEMBER, memberChoice(0, "GRANT"));
                choices.chooseMember(MEMBER, memberChoice(1, "WITHDRAW"));
            }
            case "EXPIRED" -> {
                choices.chooseMember(MEMBER, memberChoice(0, "GRANT"));
                clock.set(NOW.plus(Duration.ofDays(180)));
            }
            case "OLD_SCOPE" -> seedOldScope();
            default -> throw new IllegalArgumentException("Unexpected consent fixture");
        }
    }

    private void seedOldScope() {
        jdbc.update("""
                INSERT INTO privacy_analytics_consents(id,user_id,decision,revision,notice_version,scope_version,
                    decided_at,expires_at,created_at,updated_at)
                VALUES (?,?,'GRANTED',1,'analytics-2026-10-09-v1','analytics-scope-1',?,?,?,?)
                """, UUID.randomUUID(), MEMBER, Timestamp.from(NOW), Timestamp.from(NOW.plus(Duration.ofDays(180))),
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private PrivacyChoiceRequest memberChoice(long revision, String action) {
        return new PrivacyChoiceRequest(UUID.randomUUID().toString(), Long.toString(MEMBER), null, revision,
                action, NOTICE, SCOPE, "SETTINGS");
    }

    private NotificationInterestRequest observation(NotificationEventType type) {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        return new NotificationInterestRequest(eventId, UUID.randomUUID(), type, NotificationEventSource.REGION_SEARCH,
                NotificationTargetType.REGION, "11", null, null);
    }

    private int stored(NotificationInterestRequest request) {
        return jdbc.queryForObject("SELECT count(*) FROM privacy_notification_events WHERE event_id = ?",
                Integer.class, request.eventId());
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "동시 작업의 잠금 지점에 도달하지 못했습니다.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        @Primary
        CollectionClock collectionTestClock() {
            return new CollectionClock();
        }
    }

    static class CollectionClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(NOW);

        void set(Instant value) {
            instant.set(value);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
