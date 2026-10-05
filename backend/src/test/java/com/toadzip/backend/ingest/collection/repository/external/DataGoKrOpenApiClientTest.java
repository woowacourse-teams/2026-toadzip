package com.toadzip.backend.ingest.collection.repository.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import com.toadzip.backend.ingest.pipeline.service.IngestExecutionScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class DataGoKrOpenApiClientTest {

    @Test
    void 실행키가_설정키보다_우선하고_실행_종료_후_설정키를_다시_사용한다() {
        DataGoKrOpenApiClient client = new DataGoKrOpenApiClient(null, JsonMapper.builder().build(),
                "https://example.com", "configured-test-key", "마이홈 단지", new MyHomeResponseStatusValidator());
        try (var ignored = IngestExecutionScope.open(null, null, "a+b/c==")) {
            assertThat(client.buildUri("list", new LinkedMultiValueMap<>()))
                    .hasToString("https://example.com/list?serviceKey=a%2Bb%2Fc%3D%3D");
        }
        assertThat(client.buildUri("list", new LinkedMultiValueMap<>()))
                .hasToString("https://example.com/list?serviceKey=configured-test-key");
    }

    @Test
    void 설정키가_없어도_해당_실행의_입력키로_수집한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI())
                        .hasToString("https://example.com/list?serviceKey=runtime-test-key"))
                .andRespond(withSuccess("{\"response\":{\"header\":{\"resultCode\":\"00\"}}}",
                        MediaType.APPLICATION_JSON));
        DataGoKrOpenApiClient client = new DataGoKrOpenApiClient(builder.build(), JsonMapper.builder().build(),
                "https://example.com", "", "마이홈 단지", new MyHomeResponseStatusValidator());
        try (var ignored = IngestExecutionScope.open(null, null, "runtime-test-key")) {
            client.get("list", new LinkedMultiValueMap<>());
        }
        server.verify();
    }

    @Test
    void 외부_실패_메시지와_원인에는_입력키나_URL을_남기지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> { }).andRespond(withSuccess(
                "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"runtime-test-key 인증 실패\"}}}",
                MediaType.APPLICATION_JSON));
        DataGoKrOpenApiClient client = client(builder);
        try (var ignored = IngestExecutionScope.open(null, null, "runtime-test-key")) {
            assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                    .isInstanceOfSatisfying(ExternalDataRequestException.class, exception -> {
                        assertThat(exception.getMessage()).doesNotContain("runtime-test-key");
                        assertThat(exception.getCause()).isNull();
                    });
        }
        server.verify();
    }

    @Test
    void 전송_실패의_원본_예외에_포함된_서비스키를_노출하지_않는다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> { }).andRespond(request -> {
            throw new org.springframework.web.client.ResourceAccessException("failure at " + request.getURI());
        });
        DataGoKrOpenApiClient client = client(builder);
        try (var ignored = IngestExecutionScope.open(null, null, "runtime-test-key")) {
            assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                    .isInstanceOfSatisfying(ExternalDataRequestException.class, exception -> {
                        assertThat(exception.isRetryable()).isTrue();
                        assertThat(exception.toString()).doesNotContain("runtime-test-key");
                        assertThat(exception.getCause()).isNull();
                    });
        }
        server.verify();
    }

    @Test
    void 인코딩된_입력키가_디코딩되어_외부_오류에_나타나도_제거한다() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertThat(request.getURI())
                        .hasToString("https://example.com/list?serviceKey=a%2Bb%2Fc%3D%3D"))
                .andRespond(withSuccess(
                        "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"a+b/c== 인증 실패\"}}}",
                        MediaType.APPLICATION_JSON));
        DataGoKrOpenApiClient client = client(builder);
        try (var ignored = IngestExecutionScope.open(null, null, "a%2Bb%2Fc%3D%3D")) {
            assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                    .hasMessageNotContaining("a+b/c==").hasMessageNotContaining("a%2Bb%2Fc%3D%3D");
        }
        server.verify();
    }

    @Test
    void 인코딩된_키에_포함된_문자를_조회_조건으로_삽입하지_않는다() {
        DataGoKrOpenApiClient client = new DataGoKrOpenApiClient(null, JsonMapper.builder().build(),
                "https://example.com", "configured-test-key", "마이홈 단지", new MyHomeResponseStatusValidator());
        try (var ignored = IngestExecutionScope.open(null, null, "a%2B&PG_SZ=999")) {
            assertThat(client.buildUri("list", new LinkedMultiValueMap<>()))
                    .hasToString("https://example.com/list?serviceKey=a%2B%26PG_SZ%3D999");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"22", "23"})
    void HTTP_200의_게이트웨이_호출_제한도_LH_응답_검증_전에_인식한다(String code) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> { }).andRespond(withSuccess(
                "{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{\"returnReasonCode\":\"" + code + "\"}}}",
                MediaType.APPLICATION_JSON));
        DataGoKrOpenApiClient client = new DataGoKrOpenApiClient(builder.build(), JsonMapper.builder().build(),
                "https://example.com", "key", "LH 공고", new LhResponseStatusValidator());

        assertThatThrownBy(() -> client.get("list", new LinkedMultiValueMap<>()))
                .isInstanceOfSatisfying(ExternalDataRequestException.class, failure -> {
                    assertThat(failure.isRateLimited()).isTrue();
                    assertThat(failure.isRetryable()).isEqualTo("23".equals(code));
                });
        server.verify();
    }

    @Test
    @DisplayName("외부 응답을 파싱한 JSON을 반환한다")
    void returnsParsedJsonResponse() {
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
                "마이홈 단지",
                new MyHomeResponseStatusValidator()
        );
        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("pageNo", "1");

        var response = client.get("list", params);

        assertThat(response.at("/response/body/item/0/id").asString()).isEqualTo("001");
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
                "마이홈 단지",
                new MyHomeResponseStatusValidator()
        );
        DataGoKrOpenApiClient encoded = new DataGoKrOpenApiClient(
                null,
                JsonMapper.builder().build(),
                "https://example.com",
                "a%2Bb%2Fc%3D%3D",
                "마이홈 단지",
                new MyHomeResponseStatusValidator()
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
                "마이홈 단지",
                new MyHomeResponseStatusValidator()
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
                            assertThat(exception.isRateLimited()).isFalse();
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
                            assertThat(exception.isRateLimited()).isTrue();
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
                            assertThat(exception.isRateLimited()).isTrue();
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
                        exception -> {
                            assertThat(exception.isRetryable()).isTrue();
                            assertThat(exception.isRateLimited()).isTrue();
                        }
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
                        exception -> {
                            assertThat(exception.isRetryable()).isFalse();
                            assertThat(exception.isRateLimited()).isTrue();
                        }
                );
        server.verify();
    }

    private DataGoKrOpenApiClient client(RestClient.Builder builder) {
        return new DataGoKrOpenApiClient(
                builder.build(),
                JsonMapper.builder().build(),
                "https://example.com",
                "key",
                "마이홈 단지",
                new MyHomeResponseStatusValidator()
        );
    }
}
