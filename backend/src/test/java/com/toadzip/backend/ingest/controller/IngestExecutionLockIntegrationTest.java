package com.toadzip.backend.ingest.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.toadzip.backend.ingest.repository.DataPipelineExecutionLock;
import com.toadzip.backend.ingest.service.MyHomeComplexCollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.main.web-application-type=servlet")
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class IngestExecutionLockIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataPipelineExecutionLock executionLock;

    @MockitoBean
    private MyHomeComplexCollectionService collectionService;

    @Test
    void 파이프라인이_실행_중이면_기존_개별_ingest_API도_거부한다() throws Exception {
        try (DataPipelineExecutionLock.Lease ignored = executionLock.tryAcquire().orElseThrow()) {
            mockMvc.perform(post("/api/admin/ingest/myhome/complexes"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INGEST_ALREADY_RUNNING"));
        }

        verifyNoInteractions(collectionService);
    }
}
