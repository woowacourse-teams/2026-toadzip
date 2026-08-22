package com.toadzip.backend.ingest.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

class DataGoKrOpenApiClientTest {

    @Test
    @DisplayName("외부 API 데이터를 보존하고 API 응답값 행을 탐색한다")
    void keepsRawResponseAndFindsRows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String payload = "{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"item\":[{\"id\":\"001\"}]}}}";
        server.expect(request -> assertThat(request.getURI()).hasToString(
                "https://example.com/list?serviceKey=key&pageNo=1"))
                .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        DataGoKrOpenApiClient client = new DataGoKrOpenApiClient(
                builder.build(),
                JsonMapper.builder().build(),
                "https://example.com",
                "key",
                "마이홈 단지"
        );
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("pageNo", "1");

        var response = client.get("list", params);

        assertThat(response.apiData()).isEqualTo(payload);
        assertThat(DataGoKrOpenApiClient.findRows(response.responseBody(), "/response/body/item"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("id").asString()).isEqualTo("001"));
        server.verify();
    }

    @Test
    @DisplayName("서비스키는 인코딩 전후 값에서 같은 URI를 만든다")
    void buildsSameUriForDecodedAndEncodedServiceKeys() {
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("pageNo", "1");
        DataGoKrOpenApiClient decoded = new DataGoKrOpenApiClient(
                null,
                JsonMapper.builder().build(),
                "https://example.com",
                "a+b/c==",
                "마이홈 단지"
        );
        DataGoKrOpenApiClient encoded = new DataGoKrOpenApiClient(
                null,
                JsonMapper.builder().build(),
                "https://example.com",
                "a%2Bb%2Fc%3D%3D",
                "마이홈 단지"
        );

        URI decodedUri = decoded.buildUri("list", params);

        assertThat(decodedUri).isEqualTo(encoded.buildUri("list", params))
                .hasToString("https://example.com/list?serviceKey=a%2Bb%2Fc%3D%3D&pageNo=1");
    }

    @Test
    @DisplayName("외부 오류 응답은 안전한 외부 API 오류로 변환한다")
    void rejectsExternalApiErrorResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI()).hasToString(
                "https://example.com/list?serviceKey=key"))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"30\","
                                + "\"resultMsg\":\"등록되지 않은 서비스키\"}}}",
                        MediaType.APPLICATION_JSON
                ));
        DataGoKrOpenApiClient client = new DataGoKrOpenApiClient(
                builder.build(),
                JsonMapper.builder().build(),
                "https://example.com",
                "key",
                "마이홈 단지"
        );

        assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                .isInstanceOf(ExternalApiRequestException.class)
                .hasMessageContaining("resultCode=30")
                .hasMessageContaining("등록되지 않은 서비스키");
        server.verify();
    }
}
