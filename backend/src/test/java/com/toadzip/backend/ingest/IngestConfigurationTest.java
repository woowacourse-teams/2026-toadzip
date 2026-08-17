package com.toadzip.backend.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.test.util.ReflectionTestUtils;

import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class IngestConfigurationTest {

	@Test
	@DisplayName("마이홈 공고 전용 base URL로 OpenAPI 클라이언트를 구성한다")
	void configuresMyHomeNoticeOpenApiClient() {
		IngestProperties properties = new IngestProperties("service-key",
				new IngestProperties.BaseUrl("https://complex.example.com", "https://notice.example.com", "https://lh.example.com"));

		DataGoKrOpenApiClient client = new IngestConfiguration().myHomeNoticeOpenApiClient(JsonMapper.builder().build(),
				properties);

		assertThat(ReflectionTestUtils.getField(client, "baseUrl")).isEqualTo("https://notice.example.com");
	}

}
