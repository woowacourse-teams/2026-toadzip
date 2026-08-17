package com.toadzip.backend.ingest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(IngestProperties.class)
public class IngestConfiguration {

	@Bean("myHomeComplexOpenApiClient")
	DataGoKrOpenApiClient myHomeComplexOpenApiClient(ObjectMapper objectMapper, IngestProperties properties) {
		String baseUrl = null;
		if (properties.baseUrl() != null) {
			baseUrl = properties.baseUrl().myhomeComplex();
		}
		return new DataGoKrOpenApiClient(RestClient.create(), objectMapper, baseUrl, properties.serviceKey(),
				"마이홈 단지");
	}

	@Bean("myHomeNoticeOpenApiClient")
	DataGoKrOpenApiClient myHomeNoticeOpenApiClient(ObjectMapper objectMapper, IngestProperties properties) {
		String baseUrl = null;
		if (properties.baseUrl() != null) {
			baseUrl = properties.baseUrl().myhomeNotice();
		}
		return new DataGoKrOpenApiClient(RestClient.create(), objectMapper, baseUrl, properties.serviceKey(),
				"마이홈 공고");
	}

}
