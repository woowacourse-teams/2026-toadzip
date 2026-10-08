package com.toadzip.backend.streetview.controller;

import static com.toadzip.backend.streetview.StreetViewFixtures.complex;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.streetview.exception.StreetViewCollectionException.Reason;
import com.toadzip.backend.streetview.exception.StreetViewCollectionException;
import com.toadzip.backend.streetview.service.StreetViewAttemptTracker;
import com.toadzip.backend.streetview.service.StreetViewEventAdmission;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StreetViewCollectionRejectionIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcClient jdbc;
    @MockitoSpyBean private StreetViewEventAdmission admission;
    @MockitoSpyBean private StreetViewAttemptTracker attempts;

    @Test
    void 요청_제한은_본문과_DB조회_이전에_429로_응답하고_다른_API에_영향을_주지_않는다() throws Exception {
        doThrow(new StreetViewCollectionException(Reason.RATE_LIMIT)).when(admission).enter();
        mockMvc.perform(post("/api/v1/street-view/events").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("malformed json"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("STREET_VIEW_EVENT_RATE_LIMITED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
    }

    @Test
    void 보관량_상한이면_재시도_정보와_503을_반환한다() throws Exception {
        long complexId = complex(jdbc);
        doThrow(new StreetViewCollectionException(Reason.CAPACITY)).when(attempts).record(any());
        mockMvc.perform(post("/api/v1/street-view/events").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"attemptId":"%s","complexId":%d,"policyRevision":0,"type":"STARTED",
                                "phase":"DOCUMENT","durationMs":0}
                                """.formatted(UUID.randomUUID(), complexId)))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("STREET_VIEW_EVENT_COLLECTION_UNAVAILABLE"));
    }
}
