package com.toadzip.backend.ingest.openapi;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DataGoKrOpenApiClientTest {

	@Test
	@DisplayName("원문 서비스키와 이미 인코딩된 서비스키로 같은 URI를 만든다")
	void buildsSameUriFromDecodedAndEncodedServiceKeys() {
		LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		params.add("pageNo", "1");
		DataGoKrOpenApiClient decoded = new DataGoKrOpenApiClient(null, JsonMapper.builder().build(),
				"https://example.com", "a+b/c==", "마이홈 단지");
		DataGoKrOpenApiClient encoded = new DataGoKrOpenApiClient(null, JsonMapper.builder().build(),
				"https://example.com", "a%2Bb%2Fc%3D%3D", "마이홈 단지");

		URI decodedUri = decoded.buildUri("rentalHouseGwList", params);

		assertThat(decodedUri).isEqualTo(encoded.buildUri("rentalHouseGwList", params))
			.hasToString("https://example.com/rentalHouseGwList?serviceKey=a%2Bb%2Fc%3D%3D&pageNo=1");
	}

}
