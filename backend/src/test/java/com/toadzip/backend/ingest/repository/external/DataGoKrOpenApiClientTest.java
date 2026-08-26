package com.toadzip.backend.ingest.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

class DataGoKrOpenApiClientTest {

    @Test
    @DisplayName("외부 응답 원문을 보존하고 응답 행을 탐색한다")
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

        assertThat(response.rawPayload()).isEqualTo(payload);
        assertThat(DataGoKrOpenApiClient.findRows(response.body(), "/response/body/item"))
                .singleElement()
                .satisfies(row -> assertThat(row.path("id").asString()).isEqualTo("001"));
        server.verify();
    }

    @Test
    @DisplayName("서비스키는 원문과 인코딩된 값에서 같은 URI를 만든다")
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
    @DisplayName("외부 오류 응답은 안전한 원천 오류로 변환한다")
    void rejectsSourceErrorResponse() {
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
                .isInstanceOf(ExternalDataRequestException.class)
                .hasMessageContaining("resultCode=30")
                .hasMessageContaining("등록되지 않은 서비스키");
        server.verify();
    }

    @Test
    @DisplayName("서버 오류는 재시도 가능한 외부 API 오류로 변환한다")
    void marksServerErrorAsRetryable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI()).hasToString(
                "https://example.com/list?serviceKey=key"))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        DataGoKrOpenApiClient client = client(builder);

        assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                .isInstanceOfSatisfying(
                        ExternalDataRequestException.class,
                        exception -> {
                            assertThat(exception.isRetryable()).isTrue();
                            assertThat(exception).hasMessageContaining("HTTP 504");
                        }
                );
        server.verify();
    }

    @Test
    @DisplayName("HTTP 초당 요청 한도 초과는 재시도 가능한 외부 API 오류로 변환한다")
    void marksTooManyRequestsAsRetryable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI()).hasToString(
                "https://example.com/list?serviceKey=key"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        DataGoKrOpenApiClient client = client(builder);

        assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                .isInstanceOfSatisfying(
                        ExternalDataRequestException.class,
                        exception -> {
                            assertThat(exception.isRetryable()).isTrue();
                            assertThat(exception).hasMessageContaining("HTTP 429");
                        }
                );
        server.verify();
    }

    @Test
    @DisplayName("HTTP 429 본문의 일일 요청 한도 코드는 재시도하지 않는다")
    void doesNotRetryHttpTooManyRequestsForDailyLimit() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI()).hasToString(
                "https://example.com/list?serviceKey=key"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{"
                                + "\"returnReasonCode\":\"22\","
                                + "\"returnAuthMsg\":\"일일 서비스 요청제한 횟수 초과 에러\"}}}"));
        DataGoKrOpenApiClient client = client(builder);

        assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                .isInstanceOfSatisfying(
                        ExternalDataRequestException.class,
                        exception -> {
                            assertThat(exception.isRetryable()).isFalse();
                            assertThat(exception).hasMessageContaining("resultCode=22");
                        }
                );
        server.verify();
    }

    @Test
    @DisplayName("공공데이터 초당 요청 한도 코드는 재시도 가능한 오류로 변환한다")
    void marksPerSecondLimitResultCodeAsRetryable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI()).hasToString(
                "https://example.com/list?serviceKey=key"))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"23\","
                                + "\"resultMsg\":\"초당 호출 한도 초과\"}}}",
                        MediaType.APPLICATION_JSON
                ));
        DataGoKrOpenApiClient client = client(builder);

        assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                .isInstanceOfSatisfying(
                        ExternalDataRequestException.class,
                        exception -> assertThat(exception.isRetryable()).isTrue()
                );
        server.verify();
    }

    @Test
    @DisplayName("공공데이터 일일 요청 한도 코드는 재시도하지 않는 오류로 변환한다")
    void marksDailyLimitResultCodeAsPermanent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI()).hasToString(
                "https://example.com/list?serviceKey=key"))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"22\","
                                + "\"resultMsg\":\"일일 호출 한도 초과\"}}}",
                        MediaType.APPLICATION_JSON
                ));
        DataGoKrOpenApiClient client = client(builder);

        assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                .isInstanceOfSatisfying(
                        ExternalDataRequestException.class,
                        exception -> assertThat(exception.isRetryable()).isFalse()
                );
        server.verify();
    }

    private DataGoKrOpenApiClient client(RestClient.Builder builder) {
        return new DataGoKrOpenApiClient(
                builder.build(),
                JsonMapper.builder().build(),
                "https://example.com",
                "key",
                "마이홈 단지"
        );
    }
}
