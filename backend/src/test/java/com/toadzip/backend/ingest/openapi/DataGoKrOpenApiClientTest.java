package com.toadzip.backend.ingest.openapi;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

	@Test
	@DisplayName("LH 성공 헤더와 배열 데이터셋을 읽는다")
	void readsLhSuccessResponse() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(request -> assertThat(request.getURI()).hasToString("https://example.com/list?serviceKey=key"))
			.andRespond(withSuccess("""
					[{"resHeader":[{"SS_CODE":"Y","RS_MSG":"정상"}]},{"dsList":[{"PAN_ID":"100"}]}]
					""", MediaType.APPLICATION_JSON));
		DataGoKrOpenApiClient client = new DataGoKrOpenApiClient(builder.build(), JsonMapper.builder().build(),
				"https://example.com", "key", "LH");

		var rows = client.getList("list", new LinkedMultiValueMap<>(), "dsList", LhRow.class);

		assertThat(rows).containsExactly(new LhRow("100"));
		server.verify();
	}

	@Test
	@DisplayName("LH 실패 헤더는 원천 오류로 전달한다")
	void rejectsLhErrorResponse() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(request -> assertThat(request.getURI()).hasToString("https://example.com/list?serviceKey=key"))
			.andRespond(withSuccess("""
					[{"resHeader":[{"SS_CODE":"E","RS_MSG":"잘못된 요청"}]}]
					""", MediaType.APPLICATION_JSON));
		DataGoKrOpenApiClient client = new DataGoKrOpenApiClient(builder.build(), JsonMapper.builder().build(),
				"https://example.com", "key", "LH");

		assertThatThrownBy(() -> client.getList("list", new LinkedMultiValueMap<>(), "dsList", LhRow.class))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("SS_CODE=E")
			.hasMessageContaining("잘못된 요청");
		server.verify();
	}

	private record LhRow(String PAN_ID) {
	}

}
