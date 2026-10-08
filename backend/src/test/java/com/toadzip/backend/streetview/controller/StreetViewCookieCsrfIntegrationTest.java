package com.toadzip.backend.streetview.controller;

import static com.toadzip.backend.streetview.StreetViewFixtures.complex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// Separate context: the mock csrf() postprocessor replaces the real token repository in its filter.
@SpringBootTest(properties = {"spring.main.web-application-type=servlet", "street-view.events.burst=101"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StreetViewCookieCsrfIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void 익명_클라이언트는_실제_CSRF_쿠키와_반환된_헤더로_보고한다() throws Exception {
        long complexId = complex(jdbc);
        var tokenResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var token = mapper.readTree(tokenResult.getResponse().getContentAsString());
        String body = """
                {"attemptId":"%s","complexId":%d,"policyRevision":42,"type":"STARTED",
                "phase":"DOCUMENT","reasonCode":null,"durationMs":0}
                """.formatted(UUID.randomUUID(), complexId);
        mockMvc.perform(post("/api/v1/street-view/events").contentType(MediaType.APPLICATION_JSON)
                        .cookie(tokenResult.getResponse().getCookie("XSRF-TOKEN"))
                        .header(token.get("headerName").asText(), token.get("token").asText()).content(body))
                .andExpect(status().isNoContent());
    }
}
