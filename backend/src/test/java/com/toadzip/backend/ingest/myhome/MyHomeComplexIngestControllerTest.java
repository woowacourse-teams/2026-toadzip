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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

@SpringBootTest(properties = { "spring.security.user.name=admin", "spring.security.user.password=secret",
		"spring.security.user.roles=ADMIN" })
@AutoConfigureMockMvc
class MyHomeComplexIngestControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MyHomeComplexIngestService ingestService;

	@Test
	@DisplayName("관리 API는 페이징 조건으로 전국 단지 적재를 실행한다")
	void delegatesNationwideIngestion() throws Exception {
		MyHomeComplexIngestResult result = new MyHomeComplexIngestResult(IngestReport.oneCreated(),
				IngestReport.oneUpdated());
		when(ingestService.ingestNationwide(500, 20)).thenReturn(result);

		mockMvc
			.perform(post("/admin/ingest/complexes").with(httpBasic("admin", "secret"))
				.param("pageSize", "500")
				.param("maxPages", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.staging.created").value(1))
			.andExpect(jsonPath("$.projection.updated").value(1));
		verify(ingestService).ingestNationwide(500, 20);
	}

	@Test
	@DisplayName("페이징 조건이 범위를 벗어나면 요청을 거절한다")
	void rejectsInvalidPageSize() throws Exception {
		mockMvc.perform(post("/admin/ingest/complexes").with(httpBasic("admin", "secret")).param("pageSize", "0"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/admin/ingest/complexes").with(httpBasic("admin", "secret")).param("pageSize", "1001"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/admin/ingest/complexes").with(httpBasic("admin", "secret")).param("maxPages", "1001"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("인증하지 않은 사용자는 단지 적재 API를 호출할 수 없다")
	void rejectsUnauthenticatedRequest() throws Exception {
		mockMvc.perform(post("/admin/ingest/complexes")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("관리자 권한이 없는 사용자는 단지 적재 API를 호출할 수 없다")
	void rejectsNonAdminRequest() throws Exception {
		mockMvc.perform(post("/admin/ingest/complexes").with(user("member").roles("USER")))
			.andExpect(status().isForbidden());
	}

}
