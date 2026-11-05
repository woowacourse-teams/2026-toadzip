package com.toadzip.backend.eligibility.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.eligibility.domain.RuleSetContent;
import com.toadzip.backend.eligibility.dto.EligibilityRequests.SaveDraft;
import com.toadzip.backend.global.config.PostgresTestDatabases;
import com.toadzip.backend.notification.NotificationFixtures;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Supplier;

@SpringBootTest(
        properties = {
            "spring.main.web-application-type=servlet",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.flyway.enabled=true"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = EligibilityIntegrationSupport.SchemaInitializer.class)
@Import(EligibilityIntegrationSupport.TimeConfiguration.class)
@Transactional
abstract class EligibilityIntegrationSupport {
    static final long USER = 56201;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void member() {
        jdbc.update(
                "INSERT INTO users(id, login_identifier, created_at) VALUES (?, ?, now()) ON"
                        + " CONFLICT DO NOTHING",
                USER,
                "eligibility-fixture-" + USER);
    }

    long announcement() {
        long id = new NotificationFixtures(entities, jdbc).announcement().getId();
        jdbc.update(
                "UPDATE announcements SET application_start_date = '2026-10-26',"
                        + " application_end_date = '2026-10-30' WHERE id = ?",
                id);
        return id;
    }

    JsonNode saveDraft(long announcement, Long draftId, long revision, RuleSetContent content)
            throws Exception {
        return response(
                mvc.perform(
                                put(admin(announcement))
                                        .with(user("admin").roles("ADMIN"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json.writeValueAsString(
                                                        new SaveDraft(draftId, revision, content))))
                        .andExpect(status().isOk()));
    }

    org.springframework.test.web.servlet.ResultActions publish(long announcement, JsonNode draft)
            throws Exception {
        return mvc.perform(
                post(admin(announcement) + "/publish")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "draftId",
                                                draft.path("id").asLong(),
                                                "expectedRevision",
                                                draft.path("revision").asLong()))));
    }

    JsonNode response(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return json.readTree(action.andReturn().getResponse().getContentAsString()).path("data");
    }

    String admin(long announcement) {
        return "/api/admin/announcements/" + announcement + "/eligibility-rule-set/draft";
    }

    <T> T transaction(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    static class SchemaInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "eligibilityTestDatabase",
                                    Map.of(
                                            "spring.datasource.url",
                                            PostgresTestDatabases.migratedPrimaryUrl(
                                                    "eligibility_"))));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        Clock eligibilityTestClock() {
            return Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC);
        }
    }
}
