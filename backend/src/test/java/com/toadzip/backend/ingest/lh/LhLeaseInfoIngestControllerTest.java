package com.toadzip.backend.ingest.lh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.toadzip.backend.ingest.IngestReport;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LhLeaseInfoIngestControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private LhLeaseInfoIngestService ingestService;

	@Test
	@DisplayName("기본 페이징 조건으로 LH 임대 카탈로그 적재를 실행한다")
	void delegatesLeaseInfoIngestion() throws Exception {
		when(ingestService.ingest(9999, 1)).thenReturn(IngestReport.oneUpdated());

		mockMvc.perform(post("/admin/ingest/lease-infos"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.updated").value(1));

		verify(ingestService).ingest(9999, 1);
	}

	@Test
	@DisplayName("페이지 조건이 범위를 벗어나면 요청을 거절한다")
	void rejectsInvalidPaging() throws Exception {
		mockMvc.perform(post("/admin/ingest/lease-infos").param("pageSize", "0"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/admin/ingest/lease-infos").param("pageSize", "10001"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/admin/ingest/lease-infos").param("maxPages", "10001"))
				.andExpect(status().isBadRequest());
	}

}
