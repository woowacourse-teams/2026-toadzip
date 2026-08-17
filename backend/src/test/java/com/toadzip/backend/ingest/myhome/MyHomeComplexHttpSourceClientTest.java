package com.toadzip.backend.ingest.myhome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.toadzip.backend.ingest.openapi.DataGoKrOpenApiClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MyHomeComplexHttpSourceClientTest {

	private MockRestServiceServer server;

	private MyHomeComplexSourceClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		DataGoKrOpenApiClient openApiClient = new DataGoKrOpenApiClient(builder.build(), JsonMapper.builder().build(),
				"https://example.com", "service-key", "마이홈 단지");
		client = new MyHomeComplexHttpSourceClient(openApiClient);
	}

	@Test
	@DisplayName("지역 코드와 페이지 조건으로 단지 원천 행을 조회한다")
	void fetchesComplexSourcePage() {
		server
			.expect(requestTo("https://example.com/rentalHouseGwList?serviceKey=service-key&brtcCode=11"
					+ "&signguCode=110&pageNo=2&numOfRows=50"))
			.andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

		var rows = client.fetch(new MyHomeComplexPageRequest("11", "110", 2, 50));

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.hsmpSn()).isEqualTo(123L);
			assertThat(row.hsmpNm()).isEqualTo("테스트 단지");
			assertThat(row.suplyTyNm()).isEqualTo("국민임대");
		});
		server.verify();
	}

	@Test
	@DisplayName("자료 없음 응답은 빈 목록으로 처리한다")
	void returnsEmptyListForNoDataResponse() {
		server
			.expect(requestTo("https://example.com/rentalHouseGwList?serviceKey=service-key&brtcCode=11"
					+ "&signguCode=110&pageNo=1&numOfRows=10"))
			.andRespond(withSuccess(noDataResponse(), MediaType.APPLICATION_JSON));

		var rows = client.fetch(new MyHomeComplexPageRequest("11", "110", 1, 10));

		assertThat(rows).isEmpty();
		server.verify();
	}

	@Test
	@DisplayName("원천 오류 코드는 예외로 전달한다")
	void rejectsSourceErrorResponse() {
		server
			.expect(requestTo("https://example.com/rentalHouseGwList?serviceKey=service-key&brtcCode=11"
					+ "&signguCode=110&pageNo=1&numOfRows=10"))
			.andRespond(withSuccess(errorResponse(), MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.fetch(new MyHomeComplexPageRequest("11", "110", 1, 10)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("resultCode=30")
			.hasMessageContaining("등록되지 않은 서비스키");
		server.verify();
	}

	@Test
	@DisplayName("페이지 번호와 크기는 양수여야 한다")
	void rejectsInvalidPageConditions() {
		assertThatThrownBy(() -> new MyHomeComplexPageRequest("11", "110", 0, 10))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MyHomeComplexPageRequest("11", "110", 1, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private String successResponse() {
		return """
				{
				  "response": {
				    "header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE"},
				    "body": {
				      "item": {
				        "hsmpSn": 123,
				        "hsmpNm": "테스트 단지",
				        "insttNm": "LH",
				        "suplyTyNm": "국민임대"
				      }
				    }
				  }
				}
				""";
	}

	private String noDataResponse() {
		return """
				{"response":{"header":{"resultCode":"03","resultMsg":"NO DATA"},"body":{}}}
				""";
	}

	private String errorResponse() {
		return """
				{"response":{"header":{"resultCode":"30","resultMsg":"등록되지 않은 서비스키"}}}
				""";
	}

}
