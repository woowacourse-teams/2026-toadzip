package com.toadzip.backend.ingest.myhome;

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
class MyHomeNoticeIngestControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MyHomeNoticeIngestService ingestService;

	@Test
	@DisplayName("기본 페이징 조건으로 마이홈 공고 적재를 실행한다")
	void delegatesDefaultPaging() throws Exception {
		MyHomeNoticeIngestResult result = new MyHomeNoticeIngestResult(IngestReport.oneCreated());
		when(ingestService.ingest(100, 50)).thenReturn(result);

		mockMvc.perform(post("/admin/ingest/notices"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.staging.created").value(1))
			.andExpect(jsonPath("$.projection").doesNotExist());

		verify(ingestService).ingest(100, 50);
	}

	@Test
	@DisplayName("페이징 조건이 범위를 벗어나면 요청을 거절한다")
	void rejectsInvalidPaging() throws Exception {
		mockMvc.perform(post("/admin/ingest/notices").param("pageSize", "0"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/admin/ingest/notices").param("maxPages", "1001"))
			.andExpect(status().isBadRequest());
	}

}
