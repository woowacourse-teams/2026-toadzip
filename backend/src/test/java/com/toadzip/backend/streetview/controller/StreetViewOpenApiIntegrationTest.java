package com.toadzip.backend.streetview.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class StreetViewOpenApiIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    void 거리뷰_경로_입력_enum_오류와_응답을_명세한다() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode document = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode paths = document.path("paths");
        JsonNode publicGet = paths.path("/api/v1/complexes/{complexId}/street-view").path("get");
        assertThat(publicGet.path("responses").has("200")).isTrue();
        assertThat(publicGet.path("responses").has("404")).isTrue();
        assertThat(publicGet.path("responses").has("503")).isTrue();
        JsonNode policy = paths.path("/api/admin/street-view-policy");
        assertThat(policy.has("get")).isTrue();
        assertThat(policy.path("put").path("responses").has("409")).isTrue();
        assertThat(paths.path("/api/admin/street-view-policy/changes").has("get")).isTrue();
        JsonNode event = paths.path("/api/v1/street-view/events").path("post");
        for (String code : List.of("204", "400", "403", "404", "409", "413", "415", "429", "503")) {
            assertThat(event.path("responses").has(code)).as(code).isTrue();
        }
        assertThat(event.path("responses").path("204").has("content")).isFalse();
        assertThat(event.path("responses").path("413").path("content").path("application/json").path("schema")
                .path("$ref").asText()).endsWith("/ErrorResponse");
        JsonNode schema = document.path("components").path("schemas").path("StreetViewEventRequest");
        assertThat(StreamSupport.stream(schema.path("required").spliterator(), false).map(JsonNode::asText).toList())
                .contains("attemptId", "complexId", "policyRevision", "type", "phase", "durationMs");
        assertThat(StreamSupport.stream(schema.path("properties").path("type").path("enum").spliterator(), false)
                .map(JsonNode::asText).toList()).containsExactly("STARTED", "READY", "FAILED", "CANCELLED");
        assertThat(schema.path("properties").path("durationMs").path("maximum").asLong()).isEqualTo(600000);
    }
}
