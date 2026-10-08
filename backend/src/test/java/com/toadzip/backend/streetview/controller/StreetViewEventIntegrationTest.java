package com.toadzip.backend.streetview.controller;

import static com.toadzip.backend.streetview.StreetViewFixtures.complex;
import static com.toadzip.backend.streetview.StreetViewFixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.toadzip.backend.streetview.service.StreetViewEventService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.main.web-application-type=servlet", "street-view.events.burst=1000",
        "street-view.events.requests-per-second=2000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StreetViewEventIntegrationTest {
    private static final String ENDPOINT = "/api/v1/street-view/events";
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MeterRegistry meters;
    private long complexId;

    @BeforeEach
    void setUp() {
        policy(jdbc, false);
        complexId = complex(jdbc);
    }

    @Test
    void CSRF_누락과_잘못된_토큰은_권한이_아닌_보안_토큰_오류로_설명한다() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(start()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("요청 보안 토큰이 유효하지 않습니다."));
        mockMvc.perform(post(ENDPOINT).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content(start()))
                .andExpect(status().isForbidden());
    }

    @Test
    void alias는_같은_시도이고_다른_단지와_버전은_충돌한다() throws Exception {
        long aliasId = 900_000_001L;
        jdbc.sql("INSERT INTO housing_complex_aliases (id, housing_complex_id, merge_id) VALUES (:id, :target, :merge)")
                .param("id", aliasId).param("target", complexId).param("merge", UUID.randomUUID()).update();
        String body = start();
        send(body).andExpect(status().isNoContent());
        var event = mapper.readTree(body).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) event).put("complexId", aliasId);
        send(mapper.writeValueAsString(event)).andExpect(status().isNoContent());
        ((tools.jackson.databind.node.ObjectNode) event).put("complexId", complex(jdbc));
        send(mapper.writeValueAsString(event)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STREET_VIEW_EVENT_ATTEMPT_CONFLICT"));
        ((tools.jackson.databind.node.ObjectNode) event).put("complexId", complexId).put("policyRevision", 43);
        send(mapper.writeValueAsString(event)).andExpect(status().isConflict());
    }

    @Test
    void 없는_단지와_삭제한_단지를_보고할_수_없다() throws Exception {
        String body = start();
        var event = (tools.jackson.databind.node.ObjectNode) mapper.readTree(body);
        event.put("complexId", Long.MAX_VALUE);
        send(mapper.writeValueAsString(event)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPLEX_NOT_FOUND"));
        jdbc.sql("UPDATE housing_complexes SET admin_deleted = true WHERE id = :id").param("id", complexId).update();
        send(body).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @CsvSource(value = {"type|0", "type|\"0\"", "phase|0", "phase|\"0\"", "reasonCode|0",
            "reasonCode|\"0\"", "durationMs|1.5", "durationMs|\"0\"", "durationMs|-1", "durationMs|600001",
            "complexId|0", "complexId|1.5", "policyRevision|-1", "policyRevision|1.1", "attemptId|\"invalid\"",
            "type|null", "phase|null", "durationMs|null", "complexId|null", "policyRevision|null",
            "attemptId|null", "rawError|\"private-value\""}, delimiter = '|')
    void 잘못된_타입과_범위_추가_필드를_거부한다(String field, String value) throws Exception {
        var event = (tools.jackson.databind.node.ObjectNode) mapper.readTree(start());
        event.set(field, mapper.readTree(value));
        send(mapper.writeValueAsString(event)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "{}"})
    void 비어_있거나_깨진_본문을_거부한다(String body) throws Exception {
        send(body).andExpect(status().isBadRequest());
    }

    @Test
    void 실제_4096바이트는_허용하고_4097바이트는_거부한다() throws Exception {
        String body = start();
        send(body + " ".repeat(4096 - body.length())).andExpect(status().isNoContent());
        send(body + " ".repeat(4097 - body.length())).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("STREET_VIEW_EVENT_PAYLOAD_TOO_LARGE"));
        mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.TEXT_PLAIN).content(body))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void 첫_실패만_관측하고_원문과_식별자는_메트릭에_넣지_않는다() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(StreetViewEventService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        double before = failureCount();
        long timersBefore = failureTimers();
        try {
            var event = (tools.jackson.databind.node.ObjectNode) mapper.readTree(start());
            event.put("type", "FAILED").put("phase", "SDK").put("reasonCode", "SDK_AUTH_FAILED").put("durationMs", 125);
            String failed = mapper.writeValueAsString(event);
            send(failed).andExpect(status().isNoContent());
            send(failed).andExpect(status().isNoContent());
            event.put("type", "READY").put("phase", "PANORAMA").putNull("reasonCode");
            send(mapper.writeValueAsString(event)).andExpect(status().isNoContent());
            event.put("type", "STARTED").put("phase", "DOCUMENT").put("durationMs", 0);
            send(mapper.writeValueAsString(event)).andExpect(status().isNoContent());
            assertThat(failureCount() - before).isEqualTo(1);
            assertThat(failureTimers() - timersBefore).isEqualTo(1);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("SDK_AUTH_FAILED", "durationMs=125")
                    .doesNotContain("http", "latitude", "private-value");
            assertThat(meters.getMeters().stream().filter(meter -> meter.getId().getName().startsWith("street_view."))
                    .flatMap(meter -> meter.getId().getTags().stream()).map(tag -> tag.getKey()).toList())
                    .doesNotContain("complexId", "attemptId", "policyRevision", "userId", "url");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private double failureCount() {
        return meters.find("street_view.events").tag("type", "FAILED").counters().stream()
                .mapToDouble(counter -> counter.count()).sum();
    }

    private long failureTimers() {
        return meters.find("street_view.initialization.duration").tag("result", "FAILED").timers().stream()
                .mapToLong(timer -> timer.count()).sum();
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return mockMvc.perform(post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String start() {
        return """
                {"attemptId":"%s","complexId":%d,"policyRevision":42,"type":"STARTED",
                "phase":"DOCUMENT","reasonCode":null,"durationMs":0}
                """.formatted(UUID.randomUUID(), complexId);
    }
}
